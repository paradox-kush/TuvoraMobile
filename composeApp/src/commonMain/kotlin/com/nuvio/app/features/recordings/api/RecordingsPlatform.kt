package com.nuvio.app.features.recordings.api

/**
 * The few OS questions the recordings screens ask. Android: exact-alarm permission (Settings screen,
 * asked once). iOS: nothing to ask, both answers are false.
 */
expect object RecordingsPlatform {
    /** True once per install, the first time it is asked: the screen may offer "Allow exact alarms". False after, and where there is no such permission. */
    fun takeExactAlarmPromptTurn(): Boolean

    /** Opens the system screen that allows exact alarms. False when this device has none to open. */
    fun openExactAlarmSettings(): Boolean
}
