package com.jongin.callcalc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class LaunchTest {
    private fun prep(): Context {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        runCatching { FirebaseApp.initializeApp(ctx) }
        // v0.7.0 에서 쓰던 사람처럼
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("paused", false).putBoolean("alwaysOn", true).putString("tmapKey", "x").apply()
        return ctx
    }
    @Test fun mainFresh() { prep(); Robolectric.buildActivity(MainActivity::class.java).setup().pause().resume() }
    @Test fun serviceThenMain() {
        prep()
        val svc = Robolectric.buildService(CallReaderService::class.java).create().get()
        val m = CallReaderService::class.java.getDeclaredMethod("onServiceConnected")
        m.isAccessible = true
        m.invoke(svc)
        Robolectric.buildActivity(MainActivity::class.java).setup()
        svc.settingsChanged()
    }
    @Test fun mainModeB() {
        val ctx = prep()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("mode", "B").apply()
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }
    @Test fun settings() { prep(); Robolectric.buildActivity(SettingsActivity::class.java).setup() }
    @Test fun team() { prep(); Robolectric.buildActivity(TeamActivity::class.java).setup() }
    @Test fun test() { prep(); Robolectric.buildActivity(TestActivity::class.java).setup() }
}
