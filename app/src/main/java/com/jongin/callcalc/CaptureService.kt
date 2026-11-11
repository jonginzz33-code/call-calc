package com.jongin.callcalc

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.jongin.callcalc.core.TextItem

/**
 * 화면 캡처(MediaProjection) + OCR. 접근성으로 글자가 안 읽히는 콜 앱용 (설명서 4-7).
 * - 화면이 바뀌었을 때만 OCR (작은 지문으로 비교)
 * - 빠르게 읽으려고 가로 720 픽셀로 줄여서 읽고, 좌표는 화면 크기로 되돌린다
 * - 콜 앱이 캡처를 막아 검은 화면이면 알려 주기만 한다 (우회하지 않음)
 */
class CaptureService : Service() {

    sealed class Result {
        data class Items(val items: List<TextItem>, val ms: Long) : Result()
        object Unchanged : Result()
        object Black : Result()
        data class Error(val msg: String) : Result()
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var scale = 1f
    private var capW = 0
    private var capH = 0
    private var lastHash = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundNow()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            start(code, data)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "화면 캡처", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = android.app.PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("콜 계산기: 화면 읽는 중")
            .setContentText("콜 앱 화면을 읽어 계산값을 보여 줘요. 밖으로 보내지 않아요.")
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTI_ID, n)
    }

    private fun start(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        thread = HandlerThread("capture").also { it.start() }
        bg = Handler(thread!!.looper)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val (sw, sh) = screenSize()
        scale = minOf(1f, 720f / sw)
        capW = (sw * scale).toInt()
        capH = (sh * scale).toInt()
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        display = p.createVirtualDisplay(
            "callcalc", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg,
        )
        reader = r
        projection = p
        instance = this
        CallReaderService.instance?.captureStateChanged()
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /**
     * 지금 화면을 한 장 찍어 글자를 읽는다. exclude: 우리 표시가 떠 있는 자리 (화면 좌표) → 그 자리 글자는 버림.
     * 결과는 메인 스레드로 돌려준다.
     */
    fun captureAndRead(exclude: List<Rect>, done: (Result) -> Unit) {
        val h = bg ?: return done(Result.Error("캡처 준비 안 됨"))
        h.post {
            val t0 = SystemClock.elapsedRealtime()
            val bmp = try { grab() } catch (e: Exception) { null }
            if (bmp == null) { main.post { done(Result.Error("화면을 아직 못 받았어요")) }; return@post }
            val (hash, dark) = fingerprint(bmp)
            if (dark) { bmp.recycle(); main.post { done(Result.Black) }; return@post }
            if (hash == lastHash) { bmp.recycle(); main.post { done(Result.Unchanged) }; return@post }
            Ocr.recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    lastHash = hash
                    val inv = 1f / scale
                    val items = text.textBlocks.flatMap { b ->
                        b.lines.mapNotNull { l ->
                            val bb = l.boundingBox ?: return@mapNotNull null
                            val rr = Rect((bb.left * inv).toInt(), (bb.top * inv).toInt(), (bb.right * inv).toInt(), (bb.bottom * inv).toInt())
                            if (exclude.any { Rect.intersects(it, rr) }) null
                            else TextItem(l.text, rr.left, rr.top, rr.right, rr.bottom)
                        }
                    }
                    bmp.recycle()
                    done(Result.Items(items, SystemClock.elapsedRealtime() - t0))
                }
                .addOnFailureListener { bmp.recycle(); done(Result.Error(it.message ?: "OCR 실패")) }
        }
    }

    /** 다음 읽기는 화면이 같아도 OCR 하도록 (표시를 다시 그려야 할 때) */
    fun forgetLast() { lastHash = 0L }

    private fun grab(): Bitmap? {
        val r = reader ?: return null
        var img = r.acquireLatestImage()
        if (img == null) { SystemClock.sleep(80); img = r.acquireLatestImage() }
        img ?: return null
        img.use {
            val plane = it.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * capW
            val full = Bitmap.createBitmap(capW + rowPadding / pixelStride, capH, Bitmap.Config.ARGB_8888)
            val need = full.byteCount
            val src = plane.buffer
            // 마지막 줄에 여백이 없는 기기가 있어서, 모자라면 0으로 채운 버퍼에 옮겨 담는다
            val buf = if (src.remaining() >= need) src else java.nio.ByteBuffer.allocate(need).apply { put(src); rewind() }
            full.copyPixelsFromBuffer(buf)
            if (full.width == capW) return full
            val cropped = Bitmap.createBitmap(full, 0, 0, capW, capH)
            full.recycle()
            return cropped
        }
    }

    /** 작은 지문(격자 샘플)과 "거의 검은 화면인지" */
    private fun fingerprint(b: Bitmap): Pair<Long, Boolean> {
        var hash = 1125899906842597L
        var maxLum = 0
        val stepX = maxOf(1, b.width / 36)
        val stepY = maxOf(1, b.height / 72)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                hash = 31 * hash + (c and 0xF0F0F0)
                val lum = ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)
                if (lum > maxLum) maxLum = lum
                x += stepX
            }
            y += stepY
        }
        return hash to (maxLum < 30)
    }

    override fun onDestroy() {
        instance = null
        display?.release()
        reader?.close()
        runCatching { projection?.stop() }
        thread?.quitSafely()
        display = null; reader = null; projection = null
        CallReaderService.instance?.captureStateChanged()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 앱을 최근 목록에서 지워도 계속 동작 (중지는 알림의 [중지])
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "capture"
        private const val NOTI_ID = 7
        @Volatile var instance: CaptureService? = null

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CaptureService::class.java))
    }
}
