package com.nuvio.app.features.player

/**
 * B259: how long an AUTOMATIC re-mint of a single-use (Stalker create_link) live link waits after the previous
 * connection failed or closed.
 *
 * The probe of a failing portal saw the provider's stream origin answer an IMMEDIATE second request with an empty
 * 200 (consistent with a one-connection limit still held by the first, dead connection), while a request 75 s later
 * got the normal redirect chain. The docked Live screen re-minted the instant a channel failed (the one-shot
 * auto re-resolve, the freeze reconnect), which lands exactly in that window: it spends a create_link and a
 * connection attempt on an answer that cannot succeed, then reports the channel dead.
 *
 * [MIN_GAP_MS] = 6 s: Infomir's own middleware gives a temporary link and a nginx secure link a 5 s life
 * (`tv_tmp_link_ttl` / `nginx_secure_link_ttl` default 5 in `itv.class.php` / `config.ini`), so after ~5 s the
 * portal-side state of the previous link has lapsed; one more second covers the server noticing our socket
 * closed. It is short next to the 15 s+ startup deadline, so a recovery that works still lands well inside it.
 * It only SPACES attempts: the reconnect budgets ([com.nuvio.app.core.analytics.LivePlaybackRecoveryPolicy],
 * [com.nuvio.app.features.livetv.LiveTvAutoRefreshGuard]) still decide whether there is an attempt at all.
 *
 * The viewer's own Retry is never delayed - they asked for it now. When it lands inside the window the screen may
 * say the provider could still be holding the previous connection ([userRetryInsideWindow]).
 *
 * Reusable links (Xtream, M3U) are not spaced: the engine itself reconnects those.
 *
 * Pure: times in ms on one clock the caller owns.
 */
object LiveRemintSpacingPolicy {
    const val MIN_GAP_MS: Long = 6_000L

    /**
     * Wait this long before an automatic re-mint. [connectionEndedAtMs] = when the previous connection failed or
     * was torn down (null = nothing ended yet: no wait). A clock that ran backwards waits the whole gap.
     */
    fun automaticDelayMs(singleUseLink: Boolean, connectionEndedAtMs: Long?, nowMs: Long): Long {
        if (!singleUseLink || connectionEndedAtMs == null) return 0L
        val elapsed = (nowMs - connectionEndedAtMs).coerceAtLeast(0L)
        return (MIN_GAP_MS - elapsed).coerceIn(0L, MIN_GAP_MS)
    }

    /** True when a Retry now would land inside the window an automatic re-mint waits out. */
    fun userRetryInsideWindow(singleUseLink: Boolean, connectionEndedAtMs: Long?, nowMs: Long): Boolean =
        automaticDelayMs(singleUseLink, connectionEndedAtMs, nowMs) > 0L
}
