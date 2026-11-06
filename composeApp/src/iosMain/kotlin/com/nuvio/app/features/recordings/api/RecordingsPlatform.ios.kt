package com.nuvio.app.features.recordings.api

/** iOS has no exact-alarm permission: a schedule waits for the next foreground sweep, and the confirmation says so. */
actual object RecordingsPlatform {
    actual fun takeExactAlarmPromptTurn(): Boolean = false
    actual fun openExactAlarmSettings(): Boolean = false
}
