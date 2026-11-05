package com.nuvio.app.features.recordings.api

actual object RecordingsPlatform {
    actual fun takeExactAlarmPromptTurn(): Boolean = RecordingsAndroid.takeExactAlarmPromptTurn()
    actual fun openExactAlarmSettings(): Boolean = RecordingsAndroid.openExactAlarmSettings()
}
