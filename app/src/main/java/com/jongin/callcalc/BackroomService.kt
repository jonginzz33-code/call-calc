package com.jongin.callcalc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.firebase.firestore.ListenerRegistration

/**
 * B 뒷방: 접근성 없이, 팀원이 수락한 콜 알림만 기다린다.
 * 알림줄에 "🚗 뒷방 대기 중"이 떠 있는 동안 동작. 콜이 오면 화면 켜기 + 큰 소리(무음이어도 알람 소리) + 진동.
 */
class BackroomService : Service() {
    private var reg: ListenerRegistration? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(WAIT) == null) {
            nm.createNotificationChannel(NotificationChannel(WAIT, "뒷방 대기 중", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, WAIT)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("🚗 뒷방 대기 중")
            .setContentText("팀원이 콜을 수락하면 바로 알려 드려요")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, 41, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        relisten()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    fun relisten() {
        reg?.remove(); reg = null
        reg = runCatching { Cloud.listen(this) { id, data -> received(id, data) } }.getOrNull()
        listeners.forEach { it() }
    }

    val listening: Boolean get() = reg != null

    private fun received(id: String, data: Map<String, Any?>) {
        val o = Share.record(this, id, data)
        Share.saveReceived(this, o)
        Share.notify(this, o, loud = true)
        wakeScreen()
        ring()
        listeners.forEach { it() }
    }

    private fun wakeScreen() {
        runCatching {
            @Suppress("DEPRECATION")
            val wl = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "calc:backroom")
            wl.acquire(10_000)
        }
    }

    /** 알람 소리 (무음 모드여도 울림) 약 6초 + 진동 */
    private fun ring() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(this, uri)
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r.play()
            main.postDelayed({ runCatching { r.stop() } }, 6000)
        }
        runCatching {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 250, 600, 250, 900), -1))
        }
    }

    override fun onDestroy() {
        reg?.remove(); reg = null
        instance = null
        listeners.forEach { it() }
        super.onDestroy()
    }

    companion object {
        private const val WAIT = "backroom_wait"
        @Volatile var instance: BackroomService? = null
        /** 뒷방 화면 다시 그리기용 */
        val listeners = mutableListOf<() -> Unit>()

        fun start(ctx: Context) {
            val i = Intent(ctx, BackroomService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BackroomService::class.java)) }
    }
}
