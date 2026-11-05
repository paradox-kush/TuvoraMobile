package com.tuvora.tvos.player

import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.features.livetv.pause.LiveLink
import com.nuvio.app.features.livetv.pause.LiveLinkMinter
import com.nuvio.app.features.livetv.pause.LivePauseCapabilities
import com.nuvio.app.features.livetv.pause.LivePauseChannel
import com.nuvio.app.features.livetv.pause.LivePauseClock
import com.nuvio.app.features.livetv.pause.LivePauseController
import com.nuvio.app.features.livetv.pause.LivePauseDeviceBudget
import com.nuvio.app.features.livetv.pause.LivePauseEnginePort
import com.nuvio.app.features.livetv.pause.LivePauseFailure
import com.nuvio.app.features.livetv.pause.LivePauseHost
import com.nuvio.app.features.livetv.pause.LivePauseScrub
import com.nuvio.app.features.livetv.pause.LivePauseView
import com.nuvio.app.features.livetv.pause.LivePauseViews
import com.nuvio.app.features.player.BridgeLiveCache
import com.nuvio.app.features.player.LiveSeekWindow
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_no_space
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_provider_refused
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_sequence_reset
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_stalled
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_timed_out
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_unsupported
import org.jetbrains.compose.resources.getString
import kotlin.time.TimeSource

/**
 * Live pause / rewind for ONE Apple TV player session: the shared [LivePauseController] driven over libmpv's own demuxer
 * cache (ENGINE_BACK_BUFFER), exposed to the SwiftUI player as a flat [LivePauseView]. The controller, the cache plan
 * and the window arithmetic are the phone's; only the engine port (the session's bridge) and the published view are
 * Apple TV's. The Compose binding is excluded from :tvosCore, which is why this class exists.
 *
 * Mechanism: no disk capture on tvOS (the controller gets none), so a channel is paused/rewound only while the active
 * engine is libmpv with the live-cache bridge. AVPlayer plays no live TV here, so it never reports a back buffer.
 * Provider-archive resume is a named gap: [hasProviderArchive] is false, so an archived channel uses the same in-memory
 * window as any other instead of opening catch-up from the pause instant.
 *
 * Confinement: every call is made on the session's scope dispatcher (Main).
 */
internal class TvLivePause(
    scope: CoroutineScope,
    private val bridge: () -> NuvioPlayerBridge?,
    /** Sets the viewer's play intent AND drives the engine (the session's live freeze watch reads the intent). */
    private val playIntent: (Boolean) -> Unit,
    private val retune: () -> Unit,
    mint: suspend () -> TvResolvedSource?,
    private val onView: (LivePauseView) -> Unit,
) {
    private val clock = object : LivePauseClock {
        private val origin = TimeSource.Monotonic.markNow()
        override fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds
        override fun wallMs(): Long = TraktPlatformClock.nowEpochMs()
    }

    private var failureTexts: Map<LivePauseFailure, String> = emptyMap()
    private var cache: BridgeLiveCache? = null

    private val controller = LivePauseController(
        instanceId = INSTANCE_ID,
        scope = scope,
        clock = clock,
        capture = null,
        minter = LiveLinkMinter {
            val fresh = mint() ?: error("no live link")
            LiveLink(fresh.url, fresh.headers)
        },
        host = object : LivePauseHost {
            override fun retune() = this@TvLivePause.retune()
            // Provider-archive resume is a named gap on Apple TV (hasProviderArchive stays false), so this is never asked.
            override fun openCatchUp(startMs: Long, endMs: Long) = Unit
        },
        // The honest platform signal: the tier AppMemory was seeded with from ProcessInfo.physicalMemory at launch.
        // The session applies no custom live buffer length, so the engine's normal plan is the bridge's own 64+32 MiB.
        config = LivePauseDeviceBudget.configFor(MemoryPortAccess.current().baseTier(), liveBufferSeconds = null),
    )

    /** The engine slice the controller drives: the session's play intent, the bridge's seek, the shared cache glue. */
    private val port = object : LivePauseEnginePort {
        override fun play() = playIntent(true)
        override fun pause() = playIntent(false)
        override fun seekTo(positionMs: Long) { bridge()?.seekTo(positionMs) }
        override fun liveWindow(): LiveSeekWindow? = cache?.window()
        override fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) {
            cache?.setBudget(maxBytes, maxBackBytes, cacheSecs)
        }
    }

    init {
        scope.launch {
            failureTexts = mapOf(
                LivePauseFailure.NO_SPACE to getString(Res.string.compose_livetv_pause_failed_no_space),
                LivePauseFailure.PROVIDER_REFUSED to getString(Res.string.compose_livetv_pause_failed_provider_refused),
                LivePauseFailure.STALLED to getString(Res.string.compose_livetv_pause_failed_stalled),
                LivePauseFailure.SEQUENCE_RESET to getString(Res.string.compose_livetv_pause_failed_sequence_reset),
                LivePauseFailure.UNSUPPORTED_STREAM to getString(Res.string.compose_livetv_pause_failed_unsupported),
                LivePauseFailure.TIMED_OUT to getString(Res.string.compose_livetv_pause_failed_timed_out),
            )
            controller.state.collect { publish() }
        }
    }

    /** True while this controller drives play/pause for the channel on screen. */
    val handles: Boolean get() = controller.handlesPlayPause

    /** A live source was handed to the engine (a tune, a re-mint, a lane switch): the buffer belongs to the old one. */
    fun sourceOpened(contentId: String, link: LiveLink, singleUseLink: Boolean) {
        controller.onLiveSourceOpened(
            contentId = contentId,
            channel = LivePauseChannel(isLive = true, isCatchUpPlayback = false, hasProviderArchive = false),
            link = link,
            singleUseLink = singleUseLink,
        )
        publish()
    }

    /** The engine that plays the source is ready (again). Null when it went away. */
    fun attach(engine: NuvioPlayerBridge?) {
        cache = engine?.let(::BridgeLiveCache)
        controller.attachEngine(
            engine = engine?.let { port },
            capabilities = LivePauseCapabilities(diskBuffer = false, engineBackBuffer = cache?.supported == true),
        )
        publish()
    }

    fun onSnapshot(positionMs: Long, isPlaying: Boolean) = controller.onSnapshot(positionMs, isPlaying)

    fun togglePlayPause(isPlaying: Boolean) = controller.togglePlayPause(isPlaying)

    /** One arrow press: [deltaMs] through the window ([LivePauseScrub] decides what that means from here). */
    fun step(deltaMs: Long, wasPlaying: Boolean) {
        if (!handles) return
        when (val step = LivePauseScrub.step(controller.state.value, port.liveWindow(), deltaMs, wasPlaying)) {
            LivePauseScrub.Step.Ignore -> Unit
            LivePauseScrub.Step.GoLive -> controller.goLive()
            is LivePauseScrub.Step.Seek -> {
                if (step.pauseFirst) controller.onPause()
                controller.seekInWindow(step.targetMs)
                if (step.resume) controller.onPlay()
            }
        }
    }

    fun goLive() = controller.goLive()

    fun retry() = controller.retry()

    fun dispose() = controller.dispose()

    private fun publish() {
        onView(LivePauseViews.of(controller.state.value, handles) { failureTexts[it].orEmpty() })
    }

    private companion object {
        const val INSTANCE_ID = "tv-live"
    }
}
