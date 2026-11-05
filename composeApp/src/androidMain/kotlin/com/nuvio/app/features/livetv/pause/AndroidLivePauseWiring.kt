package com.nuvio.app.features.livetv.pause

import android.content.Context
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Process-init wiring of the Android disk buffer (called from `NuvioApplication.onCreate`, next to the feature registration). */
object AndroidLivePauseWiring {
    fun install(context: Context) {
        val root = File(context.cacheDir, "live-pause")
        LivePauseCaptureAccess.register(
            AndroidLivePauseCapture(
                rootDir = root,
                callFactoryFor = { singleUse, link ->
                    PlayerPlaybackNetworking.captureCallFactory(link.url, link.headers, retryConnectionFailures = !singleUse)
                },
                headerPolicy = PlayerPlaybackNetworking::captureHeaders,
            ),
        )
        // A crash or a kill while paused leaves a directory behind: sweep what is older than six hours.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LivePauseStorage.sweepStale(root, System.currentTimeMillis())
        }
    }
}
