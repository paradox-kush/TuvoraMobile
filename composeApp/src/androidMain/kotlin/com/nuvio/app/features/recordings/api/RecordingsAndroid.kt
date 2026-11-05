package com.nuvio.app.features.recordings.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.nuvio.app.features.recordings.internal.AndroidRecordingAlarms
import com.nuvio.app.features.recordings.internal.AndroidRecordingFiles
import com.nuvio.app.features.recordings.internal.AndroidRecordingSaver
import com.nuvio.app.features.recordings.internal.RecordingNotifications
import com.nuvio.app.features.recordings.internal.RecordingService
import com.nuvio.app.features.recordings.internal.RecordingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Android capabilities. One constant per platform, so wave 4 (live capture) flips it in one place. */
private object AndroidRecordingCapabilities {
    /** TODO(live capture pending live-pause branch): wave 4 sets this true. */
    const val CAN_RECORD_LIVE = false
    val value = RecordingCapabilities(canRecordLive = CAN_RECORD_LIVE, canSaveCatchUp = true)
}

/**
 * The Android composition of the recordings feature. [install] builds the one repository for the
 * process and publishes it (alarm receivers and the service have no Compose tree); it is cheap (no
 * I/O) and safe to call from every process start, including a headless alarm start.
 */
object RecordingsAndroid {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun install(context: Context) {
        if (RecordingsAccess.current() != null) return
        val app = context.applicationContext
        val files = AndroidRecordingFiles(
            stateDir = File(app.filesDir, "recordings-state"),
            recordingsDir = File(app.filesDir, "recordings"),
        )
        val saver = AndroidRecordingSaver(isOnline = { isOnline(app) })
        RecordingsAccess.register(
            RecordingsRepository(
                files = files,
                alarms = AndroidRecordingAlarms(app),
                saver = saver,
                capabilities = AndroidRecordingCapabilities.value,
                lowSpacePolicy = { LowSpacePolicy.STOP }, // TODO(wave 3): read the user's setting
            ),
        )
    }

    /**
     * App start (an Activity is visible, so a foreground service may start): load, recover what a dead
     * process left running, then hand each overdue schedule to the service. Off the main thread.
     */
    fun onAppStart(context: Context) {
        install(context)
        val app = context.applicationContext
        val port = RecordingsAccess.current() ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            port.start(now)
            for (id in port.overdueIds(now)) {
                try {
                    ContextCompat.startForegroundService(app, RecordingService.fireIntent(app, id))
                } catch (e: IllegalStateException) {
                    RecordingNotifications.couldNotStart(app)
                    break
                }
            }
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
