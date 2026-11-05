package com.nuvio.app.features.livetv.pause

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.LiveSeekWindow
import kotlin.time.TimeSource
import com.nuvio.app.features.trakt.TraktPlatformClock

/** Monotonic for durations, wall for "what time was it on the channel". One per controller. */
internal class SystemLivePauseClock : LivePauseClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds
    override fun wallMs(): Long = TraktPlatformClock.nowEpochMs()
}

/** The slice of a [PlayerEngineController] the pause controller drives. */
internal class EnginePort(private val controller: PlayerEngineController) : LivePauseEnginePort {
    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    override fun liveWindow(): LiveSeekWindow? = controller.liveSeekWindow()
    override fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) =
        controller.setLiveCacheBudget(maxBytes, maxBackBytes, cacheSecs)
}

/** What the ACTIVE engine can do for pause: ExoPlayer records to disk, an engine with a back buffer keeps it in memory. */
internal fun PlayerEngineController.livePauseCapabilities(): LivePauseCapabilities = LivePauseCapabilities(
    diskBuffer = playbackEngine == AndroidPlaybackEngine.ExoPlayer,
    engineBackBuffer = supportsLiveBackBuffer,
)

/**
 * One [LivePauseController] per player instance, tied to the composition: disposed (capture cancelled, directory
 * deleted) when the screen leaves. The callbacks are read through [rememberUpdatedState], so the controller never
 * holds a stale screen.
 */
@Composable
internal fun rememberLivePauseController(
    instanceId: String,
    onRetune: () -> Unit,
    onOpenCatchUp: (startMs: Long, endMs: Long) -> Unit,
    mint: suspend () -> LiveLink,
): LivePauseController {
    val scope = rememberCoroutineScope()
    val latestRetune = rememberUpdatedState(onRetune)
    val latestCatchUp = rememberUpdatedState(onOpenCatchUp)
    val latestMint = rememberUpdatedState(mint)
    val controller = remember(instanceId) {
        LivePauseController(
            instanceId = instanceId,
            scope = scope,
            clock = SystemLivePauseClock(),
            capture = LivePauseCaptureAccess.current(),
            minter = LiveLinkMinter { latestMint.value() },
            host = object : LivePauseHost {
                override fun retune() = latestRetune.value()
                override fun openCatchUp(startMs: Long, endMs: Long) = latestCatchUp.value(startMs, endMs)
            },
            config = LivePauseDeviceBudget.configFor(
                tier = MemoryPortAccess.current().baseTier(),
                liveBufferSeconds = PlayerSettingsRepository.uiState.value.liveBufferSeconds,
            ),
        )
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}
