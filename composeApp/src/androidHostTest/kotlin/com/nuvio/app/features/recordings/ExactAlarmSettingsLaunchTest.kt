package com.nuvio.app.features.recordings

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nuvio.app.features.recordings.internal.AndroidRecordingAlarms
import com.nuvio.app.features.recordings.internal.ExactAlarmSettingsLaunch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back from "Alarms & reminders" landed on the LAUNCHER: the intent was started from the application context
 * with NEW_TASK, so it opened in a new task. From a foreground Activity it must join the app's own task (no NEW_TASK).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExactAlarmSettingsLaunchTest {
    @Test
    fun `with a foreground activity there is no new task`() {
        assertEquals(false, ExactAlarmSettingsLaunch.needsNewTask(hasForegroundActivity = true))
    }

    @Test
    fun `without an activity the application context needs a new task`() {
        assertEquals(true, ExactAlarmSettingsLaunch.needsNewTask(hasForegroundActivity = false))
    }

    @Test
    fun `the settings intent itself carries no new-task flag`() {
        val intent = assertNotNull(AndroidRecordingAlarms.exactAlarmSettingsIntent(ApplicationProvider.getApplicationContext()))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0, "NEW_TASK is added only when no Activity exists")
    }
}
