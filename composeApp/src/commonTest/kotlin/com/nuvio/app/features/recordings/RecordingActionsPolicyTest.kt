package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.RecordingAction
import com.nuvio.app.features.recordings.api.RecordingActionsPolicy
import com.nuvio.app.features.recordings.api.RecordingRow
import com.nuvio.app.features.recordings.api.RecordingSection
import com.nuvio.app.features.recordings.api.RecordingStatus
import com.nuvio.app.features.recordings.api.CaptureMode
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingActionsPolicyTest {
    private fun row(section: RecordingSection, status: RecordingStatus, canRetry: Boolean = false) = RecordingRow(
        id = "x", section = section, title = "T", episode = null, channelName = "C", programmeStartMs = 0, programmeEndMs = 1,
        sizeBytes = 0, status = status, mode = CaptureMode.CATCH_UP, problem = null, description = null,
        scheduleId = "s", recordingId = "r", canRetry = canRetry, retryPending = false,
    )

    @Test
    fun `scheduled and recording rows can be cancelled and nothing else`() {
        assertEquals(listOf(RecordingAction.CANCEL), RecordingActionsPolicy.actionsFor(row(RecordingSection.SCHEDULED, RecordingStatus.SCHEDULED), false))
        assertEquals(listOf(RecordingAction.CANCEL), RecordingActionsPolicy.actionsFor(row(RecordingSection.RECORDING, RecordingStatus.RECORDING), false))
    }

    @Test
    fun `a done row plays when its file is there and always deletes`() {
        val r = row(RecordingSection.DONE, RecordingStatus.DONE)
        assertEquals(listOf(RecordingAction.PLAY, RecordingAction.DELETE), RecordingActionsPolicy.actionsFor(r, fileAvailable = true))
        assertEquals(listOf(RecordingAction.DELETE), RecordingActionsPolicy.actionsFor(r, fileAvailable = false))
    }

    @Test
    fun `a failed or cancelled row retries only when it can and always deletes`() {
        listOf(RecordingStatus.FAILED, RecordingStatus.CANCELLED).forEach { status ->
            assertEquals(
                listOf(RecordingAction.RETRY, RecordingAction.DELETE),
                RecordingActionsPolicy.actionsFor(row(RecordingSection.FAILED, status, canRetry = true), false), "$status",
            )
            assertEquals(
                listOf(RecordingAction.DELETE),
                RecordingActionsPolicy.actionsFor(row(RecordingSection.FAILED, status, canRetry = false), false), "$status",
            )
        }
    }
}
