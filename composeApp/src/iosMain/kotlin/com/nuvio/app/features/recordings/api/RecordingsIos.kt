package com.nuvio.app.features.recordings.api

import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.foregroundEvents
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
        // Lifecycle-bound, no timer: one sweep per return to the foreground (the monitor also reports the current state on subscribe).
        // The monitor reads UIApplication state, so it is collected on Main; the sweep itself runs off it.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            AppForegroundMonitor.events().foregroundEvents().collect { scope.launch { sweepNow() } }
        }
    }
}

private suspend fun sweepNow() {
    val port = RecordingsAccess.current() ?: return
    val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
    port.start(now)
    port.sweepOverdue(now)
}

/** Runs the foreground sweep now (loads state on first use, recovers a dead run, runs overdue schedules). Idempotent. */
fun recordingsSweepOnForeground() {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { sweepNow() }
}
