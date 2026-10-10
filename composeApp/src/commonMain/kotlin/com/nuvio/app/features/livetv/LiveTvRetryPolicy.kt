package com.nuvio.app.features.livetv

/**
 * What the Live TV screen's Retry does (the error pill's button and the freeze watcher's reconnect).
 *
 * Live and replay refill the player through different paths: a live channel re-resolves its source
 * (a fresh Stalker create_link, so the dead token is never replayed), while a catch-up replay owns
 * its own source — the live resolve stands down for it. Tearing the surface down is only honest when
 * the matching path will fill it again; a replay Retry that cleared the source and bumped the LIVE
 * resolve left the player blank with nothing coming.
 */
internal object LiveTvRetryPolicy {
    data class Plan(
        /** Drop the dead source/controller so the surface re-initialises (and the deadline re-arms). */
        val tearDownSurface: Boolean,
        /** Bump the live resolve — a forced re-mint of the channel's live URL. */
        val reResolveLive: Boolean,
        /** Re-open the replay's current dialect attempt (re-resolving its archive URL). */
        val reopenReplay: Boolean,
    )

    /**
     * [replayActive] must be read from the catch-up STATE at tap time (not a composition snapshot),
     * so a handler captured before the replay started still answers for the replay on screen.
     */
    fun onRetry(replayActive: Boolean): Plan =
        if (replayActive) {
            // The recording is what the viewer asked for: re-open its current attempt. Never the
            // live re-mint — it stands down during a replay (nothing would refill the surface), and
            // if it ran it would swap the recording for the live channel.
            Plan(tearDownSurface = true, reResolveLive = false, reopenReplay = true)
        } else {
            Plan(tearDownSurface = true, reResolveLive = true, reopenReplay = false)
        }
}
