package com.nuvio.app.features.recordings.api

import com.nuvio.app.features.recordings.internal.IosRecordingAlarms
import com.nuvio.app.features.recordings.internal.IosRecordingCapabilities
import com.nuvio.app.features.recordings.internal.IosRecordingFiles
import com.nuvio.app.features.recordings.internal.IosRecordingSaver
import com.nuvio.app.features.recordings.internal.RecordingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS composition. No process alarm exists, so the app start (and each foreground, via
 * [recordingsSweepOnForeground]) loads state and runs what is overdue.
 */
object RecordingsIos {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install() {
        if (RecordingsAccess.current() != null) return
        val files = IosRecordingFiles()
        RecordingsAccess.register(
            RecordingsRepository(
                files = files,
                alarms = IosRecordingAlarms(),
                saver = IosRecordingSaver(files),
                capabilities = IosRecordingCapabilities.value,
                lowSpacePolicy = { LowSpacePolicy.STOP }, // TODO(wave 3): read the user's setting
            ),
        )
        recordingsSweepOnForeground()
    }
}

/** Call when the app becomes active: loads state on first use, recovers a dead run, runs overdue schedules. */
fun recordingsSweepOnForeground() {
    val port = RecordingsAccess.current() ?: return
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
        port.start(now)
        port.sweepOverdue(now)
    }
}
