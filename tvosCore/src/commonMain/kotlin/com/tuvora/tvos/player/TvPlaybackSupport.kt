package com.tuvora.tvos.player

import com.nuvio.app.core.analytics.LivePlaybackFreezePolicy
import com.nuvio.app.core.analytics.LivePlaybackRecoveryPolicy
import com.nuvio.app.features.player.CompletedPlaybackSavePolicy
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.watchprogress.isWatchProgressComplete

/** Request headers handed to a player. Same rules as the phone's sanitizePlaybackHeaders (PlayerEngine.kt, excluded here). */
object TvPlaybackHeaders {
    fun sanitize(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        val sanitized = LinkedHashMap<String, String>(headers.size)
        headers.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty() || key.equals("Range", ignoreCase = true)) return@forEach
            sanitized[key] = value
        }
        return sanitized
    }
}

/**
 * Gate for every progress save, as the phone's admitProgressSave (PlayerScreenRuntimePlaybackActions.kt):
 * once this playback saved a video as completed, a later non-completed save of it is dropped.
 */
class TvProgressGate {
    private var completionRecordedForVideoId: String? = null

    fun admit(videoId: String?, snapshot: PlayerPlaybackSnapshot): Boolean {
        val isCompleted = isWatchProgressComplete(
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = snapshot.durationMs.coerceAtLeast(0L),
            isEnded = snapshot.isEnded,
        )
        if (CompletedPlaybackSavePolicy.shouldSkip(isCompleted, completionRecordedForVideoId == videoId)) return false
        if (isCompleted) completionRecordedForVideoId = videoId
        return true
    }
}

enum class TvLiveAction { None, Reconnect, GiveUp }

/**
 * Live-channel watchdog: feeds player samples to the shared [LivePlaybackFreezePolicy] and
 * [LivePlaybackRecoveryPolicy], the same decisions every other Tuvora player runs.
 *
 * The Apple TV player bridge has no video-only reset (mpv `video-reload`), so the recovery's
 * video-reset rung is disabled here and every attempt is a reconnect.
 */
class TvLiveMonitor {
    private var lastPosition = -1L
    private var lastPositionAt = 0L
    private var lastBuffered = -1L
    private var lastBufferedAt = 0L
    private var lastTicks = -1L
    private var lastTicksAt = 0L
    private var playbackStartAt = -1L
    private var freezeKind: LivePlaybackFreezePolicy.Kind? = null
    private var attempts = 0
    private var lastAttemptAt = Long.MIN_VALUE / 2

    private companion object {
        const val STABLE_RESET_MS = 30_000L
    }

    /**
     * The viewer pressed Retry: one fresh, still bounded, ladder. Never called by the automatic
     * reconnect, so a dead provider still ends in GiveUp (Failed + Retry). A zap opens a new session,
     * and with it a new monitor.
     */
    fun onUserRetry() {
        attempts = 0
        lastAttemptAt = Long.MIN_VALUE / 2
    }

    fun sample(nowMs: Long, snapshot: PlayerPlaybackSnapshot, wantsToPlay: Boolean): TvLiveAction {
        if (lastPosition < 0 || kotlin.math.abs(snapshot.positionMs - lastPosition) > LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS) {
            lastPosition = snapshot.positionMs; lastPositionAt = nowMs
        }
        if (lastBuffered < 0 || snapshot.bufferedPositionMs != lastBuffered) {
            lastBuffered = snapshot.bufferedPositionMs; lastBufferedAt = nowMs
        }
        if (lastTicks < 0 || snapshot.videoProgressTicks != lastTicks) {
            lastTicks = snapshot.videoProgressTicks; lastTicksAt = nowMs
        }
        if (playbackStartAt < 0 && snapshot.isPlaying) playbackStartAt = nowMs

        val state = when {
            snapshot.isEnded -> LivePlaybackFreezePolicy.PlaybackState.ENDED
            snapshot.isLoading -> LivePlaybackFreezePolicy.PlaybackState.BUFFERING
            snapshot.isPlaying -> LivePlaybackFreezePolicy.PlaybackState.READY
            else -> LivePlaybackFreezePolicy.PlaybackState.IDLE
        }
        val decision = LivePlaybackFreezePolicy.evaluate(
            LivePlaybackFreezePolicy.Input(
                state = state,
                wantsToPlay = wantsToPlay,
                positionMs = snapshot.positionMs,
                lastAdvancedPositionMs = lastPosition,
                sinceLastAdvanceMs = nowMs - lastPositionAt,
                bufferedPositionMs = snapshot.bufferedPositionMs,
                lastAdvancedBufferedPositionMs = lastBuffered,
                sinceBufferedAdvanceMs = nowMs - lastBufferedAt,
                freezeActive = freezeKind != null,
                activeKind = freezeKind,
                hasVideoTrack = snapshot.hasVideoTrack,
                videoProgressTicks = snapshot.videoProgressTicks,
                lastAdvancedVideoTicks = lastTicks,
                sinceVideoAdvanceMs = nowMs - lastTicksAt,
                sincePlaybackStartMs = if (playbackStartAt < 0) 0L else nowMs - playbackStartAt,
            ),
        )
        when (decision) {
            LivePlaybackFreezePolicy.Decision.Idle -> {
                // Stable playback since the last attempt clears the reconnect pressure, so separate
                // outages over a long evening never add up to a give-up (Android TV has the same rule).
                if (attempts > 0 && snapshot.isPlaying && nowMs - lastAttemptAt >= STABLE_RESET_MS) attempts = 0
                return TvLiveAction.None
            }
            LivePlaybackFreezePolicy.Decision.Recover -> { freezeKind = null; attempts = 0; return TvLiveAction.None }
            is LivePlaybackFreezePolicy.Decision.Start -> freezeKind = decision.kind
            is LivePlaybackFreezePolicy.Decision.Continue -> freezeKind = decision.kind
        }
        val recovery = LivePlaybackRecoveryPolicy.evaluate(
            LivePlaybackRecoveryPolicy.Input(
                attempts = attempts,
                sinceLastAttemptMs = nowMs - lastAttemptAt,
                kind = freezeKind ?: LivePlaybackFreezePolicy.Kind.STALLED,
                videoResetAttempts = 0,
            ),
        )
        return when (recovery) {
            LivePlaybackRecoveryPolicy.Decision.Wait -> TvLiveAction.None
            LivePlaybackRecoveryPolicy.Decision.GiveUp -> TvLiveAction.GiveUp
            LivePlaybackRecoveryPolicy.Decision.ResetVideo, LivePlaybackRecoveryPolicy.Decision.Reconnect -> {
                attempts++
                lastAttemptAt = nowMs
                // A reconnect restarts the stream: close the old freeze and measure the new one from scratch.
                freezeKind = null
                lastPosition = -1; lastBuffered = -1; lastTicks = -1; playbackStartAt = -1
                TvLiveAction.Reconnect
            }
        }
    }
}
