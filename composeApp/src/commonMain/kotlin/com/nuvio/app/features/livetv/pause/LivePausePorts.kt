package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.player.LiveSeekWindow
import kotlinx.coroutines.flow.Flow

/** A resolved, playable live URL plus the headers it needs (post DoH / User-Agent policy). */
data class LiveLink(val url: String, val headers: Map<String, String> = emptyMap())

/**
 * Mints a fresh playable link for the channel on screen, through the screen's own live-resolve path
 * (Stalker create_link and friends). A single-use link is NEVER re-opened: the capture asks this for every
 * open of one. Throws when the provider refuses; never returns a stale link.
 */
fun interface LiveLinkMinter {
    suspend fun mint(): LiveLink
}

/** Two clocks, never mixed: monotonic for durations and stalls, wall for "what time was it on the channel". */
interface LivePauseClock {
    fun monotonicMs(): Long
    fun wallMs(): Long
}

/** What a capture needs to start. Plain values, so a fake in commonTest and a file-backed one on Android agree. */
data class LiveCaptureRequest(
    val instanceId: String,
    /** The link the player was playing. Ignored when [singleUseLink]: that one is spent, so the capture mints. */
    val firstLink: LiveLink,
    val singleUseLink: Boolean,
    val minter: LiveLinkMinter,
    val keepMs: Long,
    val bitrateBps: Long?,
    val lowMemoryTier: Boolean,
    /**
     * THE attempt counter of this channel's capture, shared with every earlier capture of it (Retry included), so
     * a viewer pressing Retry cannot make the provider hand out a fresh single-use link per press.
     */
    val reconnect: LiveCaptureReconnect = LiveCaptureReconnect(singleUseLink),
)

sealed interface LiveCaptureEvent {
    /** The first piece is playable: [playlistUrl] is the local `index.m3u8`. */
    data class Ready(val playlistUrl: String, val bufferedMs: Long) : LiveCaptureEvent

    /** A piece landed (or the front was trimmed). [full] = the keep window is full and the oldest is being dropped. */
    data class Buffered(val bufferedMs: Long, val full: Boolean) : LiveCaptureEvent

    /** The capture ended and will not recover on its own. */
    data class Failed(val reason: LivePauseFailure) : LiveCaptureEvent
}

/** One running capture. [dispose] cancels it and deletes its directory; safe to call twice. */
interface LiveCaptureHandle {
    val events: Flow<LiveCaptureEvent>

    /**
     * Stops recording but KEEPS what is on disk, finished as an ended playlist, so a player still watching it can
     * play to the end. [dispose] still deletes it later. Default: ports that cannot keep anything dispose.
     */
    fun stop() = dispose()

    fun dispose()
}

/** Platform disk buffer (Android). Null where there is none. One capture per player instance. */
interface LivePauseCapturePort {
    fun start(request: LiveCaptureRequest): LiveCaptureHandle
}

/** The slice of the player engine the controller drives. Adapted from `PlayerEngineController` by the screen. */
interface LivePauseEnginePort {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun liveWindow(): LiveSeekWindow?
    fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long)
}

/** What the controller asks of the screen that hosts it. */
interface LivePauseHost {
    /** Leave the buffer: open the channel the ordinary way (re-mints single-use links). */
    fun retune()

    /** Open the provider's archive at [startMs] (wall clock) through the existing catch-up path. */
    fun openCatchUp(startMs: Long, endMs: Long)
}

/** Which source the player surface should hold. */
sealed interface LivePauseSurface {
    /** The ordinary live source. */
    data object Live : LivePauseSurface

    /** No source: the provider connection is released while the buffer starts (or after it failed). */
    data object Released : LivePauseSurface

    /** The local growing playlist, parked at the start of its window. */
    data class Local(val playlistUrl: String) : LivePauseSurface
}

data class LivePauseConfig(
    val keepMs: Long = LivePausePolicy.DEFAULT_KEEP_MS,
    val lowMemoryTier: Boolean = false,
    /** What the platform lets mpv hold in RAM for this one player; 0 = unknown (never raise the caches). */
    val engineMemoryBudgetBytes: Long = 0L,
    /** The engine's ordinary live cache plan (the floor mpv is restored to). */
    val engineNormalPlan: LivePausePolicy.EngineCachePlan = LivePausePolicy.EngineCachePlan(0L, 0L, 0L),
    /** The provider connection is released this long before the capture opens (a max_connections=1 account). */
    val captureStartDelayMs: Long = 1_500L,
    /** Resuming sooner than this after a PROVIDER_CATCHUP pause just plays on; later opens the archive. */
    val catchUpResumeThresholdMs: Long = 20_000L,
    /** Playing this close to the end of the engine's window counts as live again. */
    val edgeSlackMs: Long = 2_000L,
)

/**
 * Process-global registration point for the platform disk buffer, registered once at app init (Android:
 * `AndroidLivePauseWiring.install`). Null where the platform has none (iOS, tvOS, Desktop): the controller then
 * offers only the mechanisms the active engine can do. Holds the PORT, never capture state: every capture is
 * per player instance and owned by its [LivePauseController].
 */
object LivePauseCaptureAccess {
    private var port: LivePauseCapturePort? = null
    fun register(port: LivePauseCapturePort?) { this.port = port }
    fun current(): LivePauseCapturePort? = port
}
