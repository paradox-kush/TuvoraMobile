package com.nuvio.app.features.player

/**
 * Pure decisions for iOS Picture-in-Picture that differ for a LIVE stream. The Swift bridge asks
 * these instead of branching inline, so the live rules are tested without a device or the player.
 *
 * Rules:
 *  - A live stream has no usable duration or seek range, even when the container reports one (an
 *    HLS DVR window): AVKit gets an indefinite range and its skip buttons do nothing.
 *  - Backgrounding WITHOUT a PiP handoff pauses the stream and suspends the video track. That
 *    leaves the live socket and buffer stale, so coming back must re-open the stream at the live
 *    edge rather than resume the leftover buffer (slow and choppy, then stalls — sports 2026-10-25).
 *  - Entering PiP never pauses and never touches the stream, so returning from a PiP that stayed
 *    up needs no rejoin. A PiP closed while still in the background pauses the stream (the
 *    pause-in-background path), so that return rejoins the live edge too.
 */
object LivePictureInPicturePolicy {

    /** What the player does when the app returns to the foreground. */
    data class ForegroundPlan(
        /** Re-attach the video layer and switch the video track back on. */
        val restoreVideo: Boolean,
        /** Re-open the current stream at the live edge. */
        val rejoinLiveEdge: Boolean,
        /** Start playing. False keeps the user's paused state (a VOD PiP that was paused). */
        val resumePlayback: Boolean,
    )

    fun foregroundPlan(
        isLive: Boolean,
        pausedForBackground: Boolean,
        pictureInPictureKeptPlayback: Boolean,
    ): ForegroundPlan {
        val rejoin = isLive && pausedForBackground
        return if (pictureInPictureKeptPlayback) {
            // AVKit owns the restore. Only a stream this player paused in the background needs help.
            ForegroundPlan(restoreVideo = false, rejoinLiveEdge = rejoin, resumePlayback = rejoin)
        } else {
            ForegroundPlan(restoreVideo = true, rejoinLiveEdge = rejoin, resumePlayback = true)
        }
    }

    /** True when AVKit must be told the playback range is unbounded (no seek, no duration). */
    fun isIndefinitePlaybackRange(isLive: Boolean, durationMs: Long): Boolean = isLive || durationMs <= 0L

    /** PiP skip buttons only seek a bounded, non-live stream. */
    fun canSkip(isLive: Boolean, durationMs: Long): Boolean = !isIndefinitePlaybackRange(isLive, durationMs)
}
