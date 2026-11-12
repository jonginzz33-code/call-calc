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
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class LaunchTest {
    @Test fun launchMain() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        runCatching { FirebaseApp.initializeApp(ctx) }
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }
    @Test fun launchSettings() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        runCatching { FirebaseApp.initializeApp(ctx) }
        Robolectric.buildActivity(SettingsActivity::class.java).setup()
    }
}
