package com.nuvio.app.features.player

import com.nuvio.app.core.analytics.LivePlaybackFreezePolicy
import com.nuvio.app.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.app.core.analytics.LivePlaybackReconnector
import com.nuvio.app.features.trakt.TraktPlatformClock

/** The docked Live TV screen. */
const val LIVE_FREEZE_SURFACE_DOCKED = "livetv_docked"

/** A live channel opened through the full player rather than the docked screen. */
const val LIVE_FREEZE_SURFACE_PLAYER = "player"

/**
 * Adapts [PlayerPlaybackSnapshot] — the one shape every mobile engine already reports — into
 * freeze detection, reconnect, and reporting.
 *
 * Nothing in the player layer re-prepares a live source, so a clean upstream close or a dead
 * socket leaves a frozen picture with no error. [reconnect] is the automated form of the
 * channel-change-and-back viewers do by hand; it should re-prepare the current source.
 *
 * Arming is deferred until the channel has actually produced a frame: a slow channel open is a
 * different problem, and reporting or reconnecting it would bury the real signal.
 */
fun LivePlaybackFreezeReporter.onLiveSnapshot(
    snapshot: PlayerPlaybackSnapshot,
    engine: () -> String?,
    streamUrl: String?,
    contentId: String?,
    surface: String,
    reconnector: LivePlaybackReconnector,
    reconnect: () -> Unit,
    /**
     * Reinitialise the video pipeline without touching the connection. Tried first for a
     * video-only freeze, where audio proves the stream is still arriving and a re-resolve would
     * risk a live link the provider has already invalidated.
     */
    resetVideo: (() -> Boolean)? = null,
    /**
     * Called once when the reconnect ladder is exhausted (several reconnects in a row that never
     * rendered a frame). The surface must show a terminal error with Retry — never a black screen.
     */
    onGaveUp: () -> Unit = {},
) {
    val nowMs = TraktPlatformClock.nowEpochMs()
    val started = LivePlaybackStartupPolicy.hasStarted(snapshot)

    if (!isArmed && started) {
        onLivePlaybackStarted(
            profile = LivePlaybackFreezeReporter.Profile(
                engine = engine()?.lowercase() ?: LivePlaybackFreezeReporter.ENGINE_UNKNOWN,
                streamContainer = LivePlaybackFreezeReporter.streamContainerOf(streamUrl),
                iptvKind = LivePlaybackFreezeReporter.iptvKindOf(contentId),
                surface = surface,
            ),
            nowMs = nowMs,
            positionMs = snapshot.positionMs,
            videoProgressTicks = snapshot.videoProgressTicks,
        )
        // Keyed on the channel, not the URL: the full player re-arms on every URL change, and a
        // Stalker re-mint changes it every time, so a URL-keyed reset refilled the ladder (skill 02 2.1).
        reconnector.onPlaybackArmed(contentId)
    }

    // The controller lands in its own effect and can lose the race against the first playing
    // snapshot, so keep asking until the engine is named — otherwise every event from the
    // losing side reports "unknown", which is exactly what the Android fleet data showed.
    if (needsEngine) {
        engine()?.takeIf { it.isNotBlank() }?.let { onEngineKnown(it.lowercase()) }
    }

    val decision = onSample(
        nowMs = nowMs,
        positionMs = snapshot.positionMs,
        bufferedPositionMs = snapshot.bufferedPositionMs,
        state = snapshot.toFreezeState(),
        // The snapshot has no "user paused" flag: a viewer pause reports isPlaying=false with
        // isLoading=false, which maps to IDLE and is never treated as wanting to play.
        wantsToPlay = snapshot.isPlaying || snapshot.isLoading || snapshot.isEnded,
        videoProgressTicks = snapshot.videoProgressTicks,
        hasVideoTrack = snapshot.hasVideoTrack,
    )

    if (decision is LivePlaybackFreezePolicy.Decision.Recover ||
        (decision is LivePlaybackFreezePolicy.Decision.Idle && started && snapshot.isPlaying && !snapshot.isEnded)
    ) {
        reconnector.onPlaybackConfirmed()
    }

    val kind = when (decision) {
        is LivePlaybackFreezePolicy.Decision.Start -> decision.kind
        is LivePlaybackFreezePolicy.Decision.Continue -> decision.kind
        else -> null
    }
    if (kind != null) {
        val hadGivenUp = reconnector.hasGivenUp
        reconnector.onFrozen(
            nowMs = nowMs,
            kind = kind,
            resetVideo = resetVideo,
            reconnect = reconnect,
        )
        if (!hadGivenUp && reconnector.hasGivenUp) onGaveUp()
    }
}

/** Flushes an unresolved freeze — leaving the screen while frozen is the reported workaround. */
fun LivePlaybackFreezeReporter.onLiveSnapshotStopped(snapshot: PlayerPlaybackSnapshot) {
    onLivePlaybackStopped(
        nowMs = TraktPlatformClock.nowEpochMs(),
        positionMs = snapshot.positionMs,
        bufferedPositionMs = snapshot.bufferedPositionMs,
    )
}

private fun PlayerPlaybackSnapshot.toFreezeState(): LivePlaybackFreezePolicy.PlaybackState = when {
    // A live channel has no end, so ENDED is always the fault this exists to catch.
    isEnded -> LivePlaybackFreezePolicy.PlaybackState.ENDED
    isLoading -> LivePlaybackFreezePolicy.PlaybackState.BUFFERING
    isPlaying -> LivePlaybackFreezePolicy.PlaybackState.READY
    else -> LivePlaybackFreezePolicy.PlaybackState.IDLE
}
