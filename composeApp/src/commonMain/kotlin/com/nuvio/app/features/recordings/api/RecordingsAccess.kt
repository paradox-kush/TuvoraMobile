package com.nuvio.app.features.recordings.api

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.atomicfu.atomic

/**
 * Where the platform composition root publishes the one [RecordingsPort]. Alarm receivers and the
 * foreground service have no Compose tree, so they read it here; screens use [LocalRecordings].
 */
object RecordingsAccess {
    private val port = atomic<RecordingsPort?>(null)

    fun register(value: RecordingsPort) {
        port.value = value
    }

    fun current(): RecordingsPort? = port.value
}

val LocalRecordings = staticCompositionLocalOf<RecordingsPort?> { null }
