package com.jongin.callcalc

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 좋은 콜 알림: 진동 두 번 + 짧은 알림음 */
object Alert {
    fun play(ctx: Context) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(ctx, uri)?.play()
        }
    }
}
