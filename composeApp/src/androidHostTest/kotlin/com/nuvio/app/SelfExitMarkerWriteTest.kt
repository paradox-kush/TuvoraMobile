package com.nuvio.app

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import kotlin.test.assertEquals

/**
 * platformExitApp() marks with a null Context. The fallback context must exist without reportPendingExits having run
 * (it only runs when consent was on at launch), or a mid-session opt-in followed by Exit leaves no marker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfExitMarkerWriteTest {
    @Test
    fun `a context-less mark writes the marker once the context is attached`() {
        val app = RuntimeEnvironment.getApplication()
        ShadowProcess.setPid(4242)
        AppExitReporter.attachContext(app)

        AppExitReporter.markSelfExit(null, SelfExitMarker.USER_EXIT)

        val stored = app.getSharedPreferences("app_exit_reporter", Context.MODE_PRIVATE)
            .getString("self_exit_marker_v1", null)
        assertEquals("user_exit", SelfExitMarker.decode(stored)?.tag, "Exit must leave a marker without reportPendingExits")
        assertEquals(4242, SelfExitMarker.decode(stored)?.pid)
    }
}
