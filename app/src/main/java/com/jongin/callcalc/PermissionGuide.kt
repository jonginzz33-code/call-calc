package com.jongin.callcalc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 권한 안내. 꺼진 권한이 있으면 앱을 켤 때 바로 안내 창을 띄우고,
 * "켜러 가기" 한 번으로 해당 설정 화면을 연다.
 * 새 권한이 필요해지면 (v0.2 접근성, v0.3 다른 앱 위에 표시) all() 목록에만 추가하면 된다.
 */
object PermissionGuide {
    data class Perm(
        val title: String,
        val why: String,
        val steps: List<String>,
        val granted: (Activity) -> Boolean,
        val open: (Activity) -> Unit,
        val note: String? = null,                          // 막혔을 때 안내
        val hint: String? = null,                          // 설정 화면이 열릴 때 아래쪽에 잠깐 띄우는 안내
        val extra: Pair<String, (Activity) -> Unit>? = null, // 보조 버튼
    )

    private val accessibility = Perm(
        title = "접근성 (콜 계산기)",
        why = "콜 앱 화면의 글자를 읽고, 콜 옆에 계산값을 띄워요.",
        steps = listOf(
            "아래 [켜러 가기]를 누르면 콜 계산기 스위치 화면이 바로 열려요",
            "스위치를 켜고 [허용]을 눌러요",
            "켜지면 자동으로 이 앱으로 돌아와요",
        ),
        hint = "콜 계산기 스위치를 켜고 [허용]을 눌러 주세요\n(목록이 나오면 '설치된 앱' → 콜 계산기)",
        note = "스위치가 회색으로 잠겨 안 켜지면: [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' → 다시 [켜러 가기]",
        extra = "앱 정보 열기" to { a ->
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        },
        granted = { isAccessibilityOn(it) },
        open = { a ->
            // 안드로이드 13+: 콜 계산기 스위치 화면으로 바로. 안 되면 접근성 첫 화면
            val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(a, CallReaderService::class.java).flattenToString())
            if (android.os.Build.VERSION.SDK_INT < 33 || runCatching { a.startActivity(direct) }.isFailure) {
                a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        },
    )

    fun isAccessibilityOn(a: Activity): Boolean {
        val enabled = Settings.Secure.getString(a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = android.content.ComponentName(a, CallReaderService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) || it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    private val installApps = Perm(
        title = "이 출처의 앱 허용",
        why = "새 버전이 나오면 앱이 스스로 받아서 설치할 수 있게 해요.",
        steps = listOf(
            "아래 [켜러 가기]를 눌러요",
            "'이 출처 허용' 스위치를 켜요",
            "켜지면 자동으로 이 앱으로 돌아와요",
        ),
        hint = "'이 출처 허용' 스위치를 켜 주세요",
        granted = { it.packageManager.canRequestPackageInstalls() },
        open = { it.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${it.packageName}"))) },
    )

    fun all(): List<Perm> = listOf(accessibility, installApps)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var watch: Runnable? = null

    /**
     * 설정 화면 열기 + 아래쪽 안내 문구 + 켜지면 자동으로 콜 계산기로 돌아오기.
     * (켜졌는지 0.5초마다 확인, 3분까지)
     */
    fun go(a: Activity, p: Perm) {
        runCatching { p.open(a) }
        p.hint?.let { android.widget.Toast.makeText(a.applicationContext, it, android.widget.Toast.LENGTH_LONG).show() }
        watch?.let { main.removeCallbacks(it) }
        val until = android.os.SystemClock.uptimeMillis() + 180_000
        val r = object : Runnable {
            override fun run() {
                if (runCatching { p.granted(a) }.getOrDefault(false)) {
                    watch = null
                    android.widget.Toast.makeText(a.applicationContext, "✅ ${p.title} 켜졌어요", android.widget.Toast.LENGTH_SHORT).show()
                    runCatching {
                        a.startActivity(Intent(a, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ))
                    }
                    return
                }
                if (android.os.SystemClock.uptimeMillis() < until) main.postDelayed(this, 500) else watch = null
            }
        }
        watch = r
        main.postDelayed(r, 800)
    }

    fun missing(a: Activity): List<Perm> = all().filter { p -> runCatching { !p.granted(a) }.getOrDefault(false) }

    /** 안내 창. later 는 [나중에]를 눌렀을 때 */
    fun show(a: Activity, missing: List<Perm>, later: () -> Unit): AlertDialog {
        val box = a.vbox(20)
        box.addView(a.text("버튼을 누르면 설정 화면이 바로 열려요.", 17f, C.SUB))
        for (p in missing) box.addView(card(a, p))
        return MaterialAlertDialogBuilder(a)
            .setTitle("권한을 켜 주세요" + if (missing.size > 1) " (${missing.size}개)" else "")
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .setCancelable(false)
            .setNegativeButton("나중에") { _, _ -> later() }
            .show()
    }

    private fun card(a: Activity, p: Perm): View {
        val c = a.vbox().apply {
            background = a.roundBg(android.graphics.Color.parseColor("#2A1F0A"), C.WARN, 2, 14)
            setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14))
            layoutParams = lp().also { it.setMargins(0, a.dp(14), 0, 0) }
        }
        c.addView(a.text("❌ " + p.title, 20f, C.WARN, bold = true))
        c.addView(a.text(p.why, 16f, C.FG).margins(a, top = 4))
        c.addView(a.text(p.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"), 17f, C.SUB).apply {
            setLineSpacing(0f, 1.3f)
        }.margins(a, top = 8))
        c.addView(a.button("켜러 가기 →", big = true) { go(a, p) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = a.dp(12) }
        })
        p.note?.let { c.addView(a.text(it, 15f, C.DIM).margins(a, top = 10)) }
        p.extra?.let { (label, act) ->
            c.addView(a.button(label, outlined = true) { act(a) }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = a.dp(6) }
            })
        }
        return c
    }

    /** 설정 화면용: 권한마다 켜짐/꺼짐과 [켜러 가기] */
    fun statusRows(a: Activity): View {
        val box = a.vbox()
        for (p in all()) {
            val on = runCatching { p.granted(a) }.getOrDefault(false)
            val row = a.hbox().apply {
                background = a.roundBg(C.PANEL, if (on) C.LINE else C.WARN, 2, 12)
                setPadding(a.dp(14), a.dp(12), a.dp(10), a.dp(12))
                layoutParams = lp().also { it.setMargins(0, a.dp(4), 0, a.dp(4)) }
            }
            row.addView(a.text((if (on) "✅ " else "❌ ") + p.title, 18f, if (on) C.FG else C.WARN, bold = !on), lp(0, weight = 1f))
            if (!on) row.addView(a.button("켜러 가기", outlined = true) { go(a, p) })
            box.addView(row)
        }
        return box
    }
}
