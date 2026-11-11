package com.jongin.callcalc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 새 버전 확인과 설치.
 * 인터넷은 여기서만 쓴다: GitHub 릴리스에서 최신 버전 번호를 보고, 새 APK를 받는 것뿐.
 * 화면에서 읽은 콜 내용은 절대 보내지 않는다.
 */
object Updater {
    private const val REPO = "jonginzz33-code/call-calc"
    private const val FIXED_APK = "https://github.com/$REPO/releases/latest/download/call-calc.apk"

    data class Info(val versionCode: Long, val name: String, val apkUrl: String)

    private val main = Handler(Looper.getMainLooper())

    fun currentVersionCode(a: Activity): Long {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    /** 최신 릴리스 정보. 못 가져오면 null */
    private fun latest(): Info? = try {
        val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "CallCalcApp")
        con.connectTimeout = 10000
        con.readTimeout = 15000
        val body = con.inputStream.bufferedReader().readText()
        con.disconnect()
        val o = JSONObject(body)
        val vc = Regex("versionCode=(\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toLongOrNull()
            ?: o.optString("tag_name").removePrefix("build-").toLongOrNull() ?: 0L
        var apk = FIXED_APK
        val assets = o.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) apk = asset.optString("browser_download_url")
        }
        Info(vc, o.optString("name"), apk)
    } catch (e: Exception) {
        null
    }

    /** '이 출처 허용' 켜러 갔다 돌아오면 이어서 설치할 업데이트 */
    private var pending: Info? = null

    fun versionText(a: Activity): String {
        val name = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
        return "v$name (build ${currentVersionCode(a)})"
    }

    /** 앱으로 돌아왔을 때: 허용을 켜고 왔으면 바로 이어서 설치 */
    fun resumePending(a: Activity) {
        val info = pending ?: return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return
        pending = null
        install(a, info)
    }

    /** 브라우저로 최신 APK 받기 (앱 안 설치가 안 될 때) */
    fun openInBrowser(a: Activity) {
        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FIXED_APK))) }
    }

    /**
     * 새 버전이 있으면 물어보고 설치한다.
     * quiet = true 면 (앱 켤 때 자동 확인) 새 버전이 없거나 실패해도 아무 말 안 함.
     * quiet = false 면 (버튼으로 확인) 결과를 창으로 보여 준다.
     */
    fun check(a: Activity, quiet: Boolean) {
        val progress = if (quiet) null else MaterialAlertDialogBuilder(a)
            .setTitle("업데이트 확인")
            .setMessage("새 버전을 확인하는 중…")
            .show()
        Thread {
            val info = latest()
            main.post {
                progress?.dismiss()
                if (a.isFinishing || a.isDestroyed) return@post
                val cur = currentVersionCode(a)
                val now = versionText(a)
                when {
                    info == null -> if (!quiet) MaterialAlertDialogBuilder(a)
                        .setTitle("확인하지 못했어요")
                        .setMessage("지금 버전: $now\n\n인터넷 연결을 확인해 주세요. 안 되면 브라우저로 최신 버전을 받을 수 있어요.")
                        .setPositiveButton("브라우저로 받기") { _, _ -> openInBrowser(a) }
                        .setNegativeButton("닫기", null)
                        .show()
                    info.versionCode <= cur -> if (!quiet) MaterialAlertDialogBuilder(a)
                        .setTitle("최신 버전이에요 ✅")
                        .setMessage("지금 버전: $now\n최신 버전: ${info.name}")
                        .setPositiveButton("확인", null)
                        .show()
                    else -> MaterialAlertDialogBuilder(a)
                        .setTitle("새 버전이 있어요")
                        .setMessage("지금 버전: $now\n새 버전: ${info.name}\n\n지금 업데이트할까요? 설정은 그대로 남아요.")
                        .setPositiveButton("업데이트") { _, _ -> install(a, info) }
                        .setNeutralButton("브라우저로 받기") { _, _ -> openInBrowser(a) }
                        .setNegativeButton("나중에", null)
                        .show()
                }
            }
        }.start()
    }

    private fun install(a: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            // 켜고 돌아오면 resumePending()이 이어서 설치
            pending = info
            Toast.makeText(a, "'이 출처 허용'을 켜고 뒤로 가기를 누르면 바로 이어서 받아요", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        val dir = File(a.getExternalFilesDir(null) ?: a.filesDir, "updates").apply { mkdirs() }
        val file = File(dir, "call-calc.apk")
        val part = File(dir, "call-calc.apk.part")
        file.delete()

        val bar = LinearProgressIndicator(a).apply { isIndeterminate = true; max = 100 }
        val txt = a.text("연결하는 중…", 14f, C.SUB)
        val box = a.vbox(20).apply { addView(bar); addView(txt.margins(a, top = 10)) }
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle("새 버전 받는 중")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { _, _ -> cancelFlag.set(true) }
            .show()

        Thread {
            val result = runCatching {
                download(info.apkUrl, part, { cancelFlag.get() }) { done, total ->
                    main.post {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (done * 100 / total).toInt()
                            txt.text = "받는 중 ${done * 100 / total}% (${mb(done)} / ${mb(total)})"
                        } else txt.text = "받는 중 ${mb(done)}"
                    }
                }
            }
            main.post {
                if (cancelFlag.get()) return@post
                dialog.dismiss()
                result.onFailure {
                    MaterialAlertDialogBuilder(a)
                        .setTitle("받지 못했어요")
                        .setMessage("${it.message ?: "인터넷 연결 확인"}\n\n브라우저로 받아서 설치할 수도 있어요.")
                        .setPositiveButton("브라우저로 받기") { _, _ -> openInBrowser(a) }
                        .setNegativeButton("닫기", null)
                        .show()
                }.onSuccess {
                    part.renameTo(file)
                    val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
                    a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        }.start()
    }

    private fun mb(b: Long) = String.format(java.util.Locale.US, "%.1fMB", b / 1048576.0)

    /** 받기. 끊기면 최대 4번 처음부터 다시 시도 */
    private fun download(url: String, out: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        var lastErr: Exception? = null
        for (attempt in 0 until 4) {
            if (cancelled()) throw IllegalStateException("취소됨")
            try {
                out.delete()
                val con = open(url)
                val code = con.responseCode
                if (code != 200) throw IllegalStateException("HTTP $code")
                val total = con.contentLengthLong
                con.inputStream.use { inp ->
                    FileOutputStream(out).use { fo ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            if (cancelled()) throw IllegalStateException("취소됨")
                            val n = inp.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 200) { last = now; progress(done, total) }
                        }
                    }
                }
                con.disconnect()
                if (total <= 0 || out.length() >= total) return
                throw IllegalStateException("받다가 끊겼어요")
            } catch (e: Exception) {
                if (cancelled()) throw e
                lastErr = e
            }
            Thread.sleep(1500L * (attempt + 1))
        }
        throw lastErr ?: IllegalStateException("다운로드 실패")
    }

    /** GitHub → 파일 서버로 넘겨주는 주소 이동을 직접 따라간다 */
    private fun open(start: String): HttpURLConnection {
        var u = start
        repeat(6) {
            val con = URL(u).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "CallCalcApp")
            val code = con.responseCode
            if (code in 300..399) {
                val loc = con.getHeaderField("Location") ?: throw IllegalStateException("주소 이동 실패")
                con.disconnect()
                u = URL(URL(u), loc).toString()
            } else return con
        }
        throw IllegalStateException("주소 이동이 너무 많아요")
    }
}
