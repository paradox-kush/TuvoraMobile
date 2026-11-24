package com.nuvio.app

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The first-run funnel decides new-vs-existing in NuvioApplication.onCreate, right after
 * initializeAndroidProcessStorage. The probe's four stores must already be readable then (before MainActivity's
 * startup tasks), or every updating Android user is counted as a new install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstRunProbeAndroidInitTest {
    @Test
    fun `a saved playlist is seen by the existing-install probe after process storage init`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nuvio_iptv", Context.MODE_PRIVATE).edit()
            .putString("xtream_accounts_1", """[{"id":"a"}]""").commit()

        initializeAndroidProcessStorage(app)

        assertTrue(FirstRunExistingInstallProbe.hasUserState(), "an install with a saved playlist is an existing install")
    }

    @Test
    fun `saved watch history is seen by the existing-install probe after process storage init`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nuvio_watched", Context.MODE_PRIVATE).edit()
            .putString("watched_payload_1", """{"items":[{"id":"tt1"}]}""").commit()

        initializeAndroidProcessStorage(app)

        assertTrue(FirstRunExistingInstallProbe.hasUserState(), "an install with watch history is an existing install")
    }
}
