package com.nuvio.app.features.player

import androidx.media3.common.PlaybackException

/**
 * ExoPlayer decisions for a LOCAL growing HLS playlist (the live pause buffer, `startAtLiveWindowStart`).
 *
 * Media3 live docs: `seekTo(0)` is the start of the live window, and falling behind it raises
 * ERROR_CODE_BEHIND_LIVE_WINDOW. For a normal live source the recovery is "rejoin the live edge"; the viewer of a
 * time-shifted buffer is NOT at the edge, so there it is "back to the start of the window" and prepare again.
 * Pure, so the rules are tested without a player.
 */
internal object LocalLiveWindowPolicy {
    /** Where a local window source starts: the start of its window. Any other source keeps the caller's position. */
    fun startPositionMs(startAtLiveWindowStart: Boolean, otherwise: Long?): Long? =
        if (startAtLiveWindowStart) 0L else otherwise

    /** True when the error should be answered by `seekTo(0)` + `prepare()` instead of the live-edge recovery. */
    fun restartsAtWindowStart(startAtLiveWindowStart: Boolean, errorCode: Int): Boolean =
        startAtLiveWindowStart && errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW

    /** The window source is prepared paused; the controller starts it when the viewer asked to watch. */
    fun playWhenReady(startAtLiveWindowStart: Boolean, requested: Boolean): Boolean =
        requested && !startAtLiveWindowStart
}
