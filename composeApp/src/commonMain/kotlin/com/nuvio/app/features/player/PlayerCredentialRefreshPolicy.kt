package com.nuvio.app.features.player

/**
 * Bounds the player's credential-refresh (fresh IPTV/Stalker link re-mint) so an expired short-TTL
 * Stalker `create_link` token cannot drive an INFINITE re-mint loop on a live channel.
 *
 * The failure this fixes (phone-only; NuvioTV already guards the same path with a URL-independent
 * one-shot): a Stalker `create_link` mints a NEW, unique, ~5s-TTL URL on every call. The old guard
 * keyed on the exact failed URL string, so every re-mint produced a different string the guard never
 * matched — and the guard was additionally reset on the refresh's OWN source swap. With no global
 * attempt cap, an expired live link re-minted forever, hammering the portal (which can get the user's
 * IP Cloudflare-banned) and never recovering.
 *
 * The decision is pure so it is unit-tested without the player, the network, or Android.
 */
internal object PlayerCredentialRefreshPolicy {
    /** Max consecutive credential refreshes before the error is surfaced instead of re-minting. */
    const val MAX_CONSECUTIVE_REFRESHES = 3

    /**
     * Continuous playback (ms advanced past the position captured when a refresh was kicked off) that
     * proves the stream genuinely recovered, re-arming the counter. Deliberately larger than a
     * short-TTL token's death window: a link that dies ~5s after minting never reaches this threshold,
     * so it can never re-arm — only a stream that actually keeps playing does.
     */
    const val HEALTHY_PLAYBACK_RESET_MS = 30_000L

    enum class Decision {
        /** Not an IPTV/expiring-credential source — do not intercept the error. */
        NOT_ELIGIBLE,

        /** A refresh is already running; swallow this error rather than starting another. */
        IN_FLIGHT,

        /** The bounded cap is reached — stop re-minting and surface the error (this breaks the loop). */
        EXHAUSTED,

        /** Attempt a fresh credential refresh. */
        ATTEMPT,
    }

    fun decide(
        isEligible: Boolean,
        refreshInFlight: Boolean,
        consecutiveRefreshes: Int,
        maxConsecutiveRefreshes: Int = MAX_CONSECUTIVE_REFRESHES,
    ): Decision = when {
        !isEligible -> Decision.NOT_ELIGIBLE
        refreshInFlight -> Decision.IN_FLIGHT
        consecutiveRefreshes >= maxConsecutiveRefreshes -> Decision.EXHAUSTED
        else -> Decision.ATTEMPT
    }

    /**
     * True once the stream has played continuously past the refresh baseline long enough to be
     * considered recovered, so the consecutive-refresh counter may be re-armed. A token that dies
     * right after minting never advances this far, so it cannot re-arm the loop.
     */
    fun hasRecovered(
        positionMs: Long,
        refreshBaselineMs: Long,
        thresholdMs: Long = HEALTHY_PLAYBACK_RESET_MS,
    ): Boolean = positionMs - refreshBaselineMs >= thresholdMs
}
