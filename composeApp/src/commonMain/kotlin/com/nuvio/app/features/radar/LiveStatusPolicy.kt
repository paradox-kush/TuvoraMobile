package com.nuvio.app.features.radar

/**
 * The freshest known live state of one fixture, reported by the game screen (ESPN-backed, ~15 s old) while it is open.
 * [live] false means the game is over (sticky); [periodLabel] is "3rd", "2nd half"… for the card badge.
 */
data class FreshFixtureState(val live: Boolean, val periodLabel: String?, val atMs: Long)

/**
 * Pure rules for what a hub card says about a live game. The radar feed (TheSportsDB) lags by minutes and its progress is
 * minutes elapsed, which means nothing for period sports ("LIVE 20" in the 3rd period). So: a fresh state from the game
 * screen wins; otherwise the feed, but never past the sport's maximum duration.
 */
internal object LiveStatusPolicy {
    /** A LIVE report older than this is not trusted (the game screen refreshes every ~15 s while open). */
    const val FRESH_LIVE_TTL_MS = 3L * 60 * 1000

    private const val HOUR = 3_600_000L

    private val PERIOD_SPORTS = setOf("ice hockey", "basketball", "american football")

    /** Longest a game of this sport plausibly stays live (overtime, delays included) — the backstop for a lagging feed. */
    fun displayMaxLiveMs(sport: String?): Long = when (sport?.lowercase()) {
        "ice hockey" -> (3.75 * HOUR).toLong()
        "basketball" -> (3.5 * HOUR).toLong()
        "american football" -> (4.25 * HOUR).toLong()
        "soccer" -> (3.25 * HOUR).toLong()
        "baseball" -> 5 * HOUR
        else -> Long.MAX_VALUE
    }

    /** true/false when a fresh state decides; null when there is none (or a LIVE one went stale). A final state never expires. */
    fun override(fresh: FreshFixtureState?, nowMs: Long): Boolean? = when {
        fresh == null -> null
        !fresh.live -> false
        nowMs - fresh.atMs < FRESH_LIVE_TTL_MS -> true
        else -> null
    }

    /** Text after "LIVE": the period for period sports (only when freshly known), else the feed minute. */
    fun badgeProgress(sport: String?, rawProgress: String?, fresh: FreshFixtureState?, nowMs: Long): String? {
        if (override(fresh, nowMs) == true && !fresh?.periodLabel.isNullOrBlank()) return fresh!!.periodLabel
        if (sport?.lowercase() in PERIOD_SPORTS) return null
        return rawProgress?.takeIf { it.isNotBlank() }
    }
}
