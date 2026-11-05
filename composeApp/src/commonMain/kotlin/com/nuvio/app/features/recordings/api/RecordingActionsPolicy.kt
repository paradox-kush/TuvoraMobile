package com.nuvio.app.features.recordings.api

enum class RecordingAction { PLAY, CANCEL, RETRY, DELETE }

/** Which buttons a row (and its details sheet) offers. Pure. */
object RecordingActionsPolicy {
    /** [fileAvailable]: the file for a DONE row is on disk and can be opened. */
    fun actionsFor(row: RecordingRow, fileAvailable: Boolean): List<RecordingAction> = when (row.status) {
        RecordingStatus.SCHEDULED, RecordingStatus.RECORDING -> listOf(RecordingAction.CANCEL)
        RecordingStatus.DONE -> if (fileAvailable) listOf(RecordingAction.PLAY, RecordingAction.DELETE) else listOf(RecordingAction.DELETE)
        RecordingStatus.FAILED, RecordingStatus.CANCELLED ->
            if (row.canRetry) listOf(RecordingAction.RETRY, RecordingAction.DELETE) else listOf(RecordingAction.DELETE)
    }

    /**
     * Stopping a RUNNING live recording keeps what was recorded (the show so far becomes a finished recording), so
     * its button says so; cancelling a waiting schedule or a catch-up save keeps nothing.
     */
    fun cancelKeepsFootage(row: RecordingRow): Boolean = row.status == RecordingStatus.RECORDING && row.mode == CaptureMode.LIVE
}
