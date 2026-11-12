package com.jongin.callcalc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.jongin.callcalc.core.CallDetail
import com.jongin.callcalc.core.DetailParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs

/**
 * 2인 1조: A가 수락한 콜 → 서버 → B 폰 알림 (화면 사진 + 주소 + [TMAP 길안내])
 * 그리고 내 콜 기록 한 줄.
 */
object Share {
    private const val CHANNEL = "team_share"

    /** 시·구 까지만 (기록용. 정확한 주소는 기록에 안 남김) */
    fun area(place: String): String {
        val w = DetailParser.cleanPlace(place)
        val a = w.filter { Regex("^[가-힣]{1,6}(특별시|광역시|도|시|군|구)$").matches(it) }.take(2)
        return (if (a.isNotEmpty()) a else w.take(1)).joinToString(" ")
    }

    /** 내비 위치가 콜 앱 안내와 많이 다르면 경고 */
    fun warning(d: CallDetail, r: CallCheck.Result?): String? {
        if (r == null || !r.done) return null
        if (r.destPt == null) return "⚠ 내비가 도착지를 못 찾았어요 · 사진의 주소로 검색해 주세요"
        val app = d.rideMin?.toInt() ?: return null
        val nav = r.ride?.takeIf { !it.estimated }?.min ?: return null
        val gap = abs(nav - app)
        return if (gap >= maxOf(10, app / 2)) "⚠ 내비 위치 확인 (콜 앱 ${app}분 / 내비 ${nav}분 · 사진 주소와 비교해 주세요)" else null
    }

    /** 팀에 보낼 내용 */
    fun sharePayload(d: CallDetail, r: CallCheck.Result?, image: String?): Map<String, Any?> = mapOf(
        "origin" to d.origin,
        "destination" to d.destination,
        "price" to d.price,
        "originName" to r?.originPt?.name, "originX" to r?.originPt?.x, "originY" to r?.originPt?.y,
        "destName" to r?.destPt?.name, "destX" to r?.destPt?.x, "destY" to r?.destPt?.y,
        "rideKm" to r?.ride?.km, "rideMin" to (r?.ride?.min ?: d.rideMin?.toInt()),
        "perMinute" to r?.perMinute?.takeIf { r.done },
        "warn" to warning(d, r),
        "image" to image,
    )

    /** 내 콜 기록 (주소는 시·구까지만) */
    fun recordPayload(d: CallDetail, r: CallCheck.Result?, app: String?): Map<String, Any?> = mapOf(
        "price" to d.price,
        "toll" to (r?.toll ?: 0),
        "net" to (r?.net ?: d.price),
        "perMinute" to r?.perMinute?.takeIf { r.done },
        "totalMin" to r?.totalMin,
        "pickupKm" to r?.pickup?.km, "pickupMin" to r?.pickup?.min,
        "rideKm" to r?.ride?.km, "rideMin" to (r?.ride?.min ?: d.rideMin?.toInt()),
        "estimated" to (r == null || !r.done || r.pickup?.estimated == true || r.ride?.estimated == true),
        "originArea" to area(d.origin),
        "destArea" to area(d.destination),
        "car" to (r?.car ?: 0),
        "app" to app,
    )

    /** 화면 사진 → 작게 줄인 JPEG (base64). 서버 한 칸에 들어가게 */
    fun encode(b: Bitmap, top: Int): String {
        val h = (b.height - top).coerceAtLeast(1)
        val cut = Bitmap.createBitmap(b, 0, top.coerceIn(0, b.height - 1), b.width, h)
        val w = 540
        val small = if (cut.width > w) Bitmap.createScaledBitmap(cut, w, cut.height * w / cut.width, true) else cut
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 55, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ───────── B 폰: 알림 ─────────

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "팀 콜 알림 (2인 1조)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "팀원이 콜을 수락하면 주소와 화면 사진을 알려 줘요"
                enableVibration(true)
            })
        }
    }

    /** 받은 사진을 파일로 보관 (알림·뒷방 화면에서 보여 주기). 하루 지난 건 지움 */
    private fun saveImage(ctx: Context, id: String, data: Map<String, Any?>): File? {
        val img = data["image"] as? String ?: return null
        return runCatching {
            val dir = File(ctx.filesDir, "shares").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
            File(dir, "$id.jpg").apply { writeBytes(Base64.decode(img, Base64.DEFAULT)) }
        }.getOrNull()
    }

    /** 받은 콜 한 건 (사진은 파일 경로로) */
    fun record(ctx: Context, id: String, data: Map<String, Any?>): org.json.JSONObject {
        val file = saveImage(ctx, id, data)
        val o = org.json.JSONObject()
        o.put("id", id)
        o.put("at", (data["at"] as? Number)?.toLong() ?: System.currentTimeMillis())
        for (k in listOf("fromName", "origin", "destination", "destName", "originName", "warn")) (data[k] as? String)?.let { o.put(k, it) }
        for (k in listOf("destX", "destY", "originX", "originY", "price", "perMinute", "rideMin")) (data[k] as? Number)?.let { o.put(k, it.toDouble()) }
        if (data["test"] == true) o.put("test", true)
        file?.let { o.put("image", it.absolutePath) }
        return o
    }

    // 뒷방: 받은 콜 목록 (이 폰에만, 최근 50건)
    private fun recvPrefs(ctx: Context) = ctx.getSharedPreferences("received", Context.MODE_PRIVATE)

    fun saveReceived(ctx: Context, o: org.json.JSONObject) {
        val arr = runCatching { org.json.JSONArray(recvPrefs(ctx).getString("list", "[]")) }.getOrDefault(org.json.JSONArray())
        val out = org.json.JSONArray().put(o)
        for (i in 0 until minOf(arr.length(), 49)) out.put(arr.get(i))
        recvPrefs(ctx).edit().putString("list", out.toString()).apply()
    }

    fun received(ctx: Context): List<org.json.JSONObject> {
        val arr = runCatching { org.json.JSONArray(recvPrefs(ctx).getString("list", "[]")) }.getOrDefault(org.json.JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** 받은 콜 화면 열기 */
    fun openIntent(ctx: Context, o: org.json.JSONObject) = Intent(ctx, ShareActivity::class.java).apply {
        putExtra("json", o.toString())
    }

    fun navFor(ctx: Context, o: org.json.JSONObject, dest: Boolean = true): Intent {
        val p = if (dest) "dest" else "origin"
        val addr = o.optString(if (dest) "destination" else "origin")
        return nav(ctx, o.optString("${p}Name").ifEmpty { null },
            if (o.has("${p}X")) o.getDouble("${p}X") else null, if (o.has("${p}Y")) o.getDouble("${p}Y") else null, addr)
    }

    /** 팀원이 수락한 콜 알림. loud = 뒷방: 화면 켜기 + 전체 화면 알림 */
    fun notify(ctx: Context, o: org.json.JSONObject, loud: Boolean) {
        channel(ctx)
        val from = o.optString("fromName", "팀원")
        val dest = o.optString("destination")
        val warn = o.optString("warn").ifEmpty { null }
        val code = o.optString("id").hashCode()
        val pi = PendingIntent.getActivity(ctx, code, openIntent(ctx, o).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val navPi = PendingIntent.getActivity(ctx, code + 1, navFor(ctx, o).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = listOfNotNull("도착: $dest", warn).joinToString("\n")
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle((if (o.optBoolean("test")) "🧪 시험 · " else "🚗 ") + "$from 콜 수락")
            .setContentText("도착: $dest")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "TMAP 길안내 (도착)", navPi)
        if (loud) b.setFullScreenIntent(pi, true)
        val bmp = o.optString("image").ifEmpty { null }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (bmp != null) b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).setSummaryText(text))
        else b.setStyle(NotificationCompat.BigTextStyle().bigText("출발: ${o.optString("origin")}\n$text"))
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(code, b.build()) }
    }

    // ───────── 길안내 ─────────

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** TMAP 길안내 (위치를 알면 바로 경로, 모르면 주소로 검색). TMAP 이 없으면 다른 지도 앱 */
    fun nav(ctx: Context, name: String?, x: Double?, y: Double?, address: String): Intent {
        val pm = ctx.packageManager
        val tmap = if (x != null && y != null)
            Intent(Intent.ACTION_VIEW, Uri.parse("tmap://route?goalname=${enc(name ?: address)}&goalx=$x&goaly=$y"))
        else Intent(Intent.ACTION_VIEW, Uri.parse("tmap://search?name=${enc(DetailParser.searchQueries(address).firstOrNull() ?: address)}"))
        if (tmap.resolveActivity(pm) != null) return tmap
        return if (x != null && y != null) Intent(Intent.ACTION_VIEW, Uri.parse("geo:$y,$x?q=$y,$x(${enc(name ?: address)})"))
        else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${enc(address)}"))
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
