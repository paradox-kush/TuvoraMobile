package com.nuvio.app.core.rec

import android.app.Application
import android.content.Context
import com.nuvio.app.NuvioApplication
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecConsentStartupTest {
    @Test
    fun savedOptOutIsLoadedBeforeFeaturesWithoutStartingAnActivity() {
        val app = NuvioApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attach",
            ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
        val prefs = app.getSharedPreferences("nuvio_rec_events", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("logging_enabled", false).commit()

        app.onCreate()

        assertFalse(RecEventSettings.enabled.value)
        assertFalse(RecEventSettings.isActive(Long.MAX_VALUE))
        RecEventSettings.setEnabled(true)
        assertTrue(prefs.getBoolean("logging_enabled", false))
        RecEventSettings.setEnabled(false)
        assertFalse(prefs.getBoolean("logging_enabled", true))
        assertFalse(RecEventSettings.isActive(Long.MAX_VALUE))
    }
}
