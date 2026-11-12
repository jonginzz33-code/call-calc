package com.jongin.callcalc

import android.app.Application
import java.io.File

/** 앱이 갑자기 꺼지면 이유를 파일에 남겨 두고, 다음에 열 때 첫 화면에서 보여 준다 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val text = "v" + BuildConfig.VERSION_NAME + " · " + java.time.LocalDateTime.now().withNano(0) + "\n" +
                    android.util.Log.getStackTraceString(e).lines().take(40).joinToString("\n")
                crashFile(this).writeText(text)
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
    }
}
