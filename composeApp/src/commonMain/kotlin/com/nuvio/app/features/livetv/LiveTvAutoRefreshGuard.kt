package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PlayerCredentialRefreshPolicy

/**
 * The docked Live TV screen's ONE automatic fresh re-resolve after a mid-watch playback error (a
 * Stalker token expired, or another device rotated the portal session).
 *
 * It used to be keyed on the failed URL. Every Stalker `create_link` is a new URL, so each failure of
 * a freshly minted link was "a new URL" and minted again: no cap, no backoff, as fast as the portal
 * answered. That is the re-mint pattern that got a viewer's IP banned (skill 02 2.1 and 2.8). Now it
 * is one attempt per tune, re-armed only by a zap or the viewer's Retry ([reset]) or by sustained
 * playback past the refresh ([PlayerCredentialRefreshPolicy.HEALTHY_PLAYBACK_RESET_MS]) — never by
 * the URL changing.
 */
class LiveTvAutoRefreshGuard {
    private var used = false
    private var baselinePositionMs = 0L

    /** True when this error may trigger the automatic re-resolve; consumes the attempt. */
    fun admit(positionMs: Long): Boolean {
        if (used) return false
        used = true
        baselinePositionMs = positionMs.coerceAtLeast(0L)
        return true
    }

    /** Fed from playback snapshots: a stream that kept playing past the threshold has recovered. */
    fun onPlayback(positionMs: Long) {
        if (used && PlayerCredentialRefreshPolicy.hasRecovered(positionMs, baselinePositionMs)) used = false
    }

    /** A zap or the viewer's Retry: a fresh tune gets its own attempt. */
    fun reset() {
        used = false
        baselinePositionMs = 0L
    }
}
