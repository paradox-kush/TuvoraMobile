package com.nuvio.app.features.radar.alerts

import com.nuvio.app.features.radar.RadarFollow
import com.nuvio.app.features.radar.RadarTeamFollow

/**
 * Which per-sport alert topics this device listens to (spec section 2): the sports of the user's non-muted follows
 * (whatever their levels),
 * among the five livescore sports. Empty when alerts are off, there are no follows, or the user is signed out or
 * anonymous (Realtime runs only for signed-in users; this feature adds no socket). Pure.
 */
object SportsAlertTopicPolicy {
    const val TOPIC_PREFIX = "sports-alerts:v1:"

    /** TheSportsDB sport (normalised) → topic slug. Other sports have no live feed, so no alerts in v1. */
    private val SLUGS = mapOf(
        "soccer" to "soccer",
        "american football" to "american_football",
        "basketball" to "basketball",
        "baseball" to "baseball",
        "ice hockey" to "ice_hockey",
    )

    fun slug(sport: String?): String? = SLUGS[SportsAlert.normalizeSport(sport)]

    fun topic(slug: String): String = TOPIC_PREFIX + slug

    /**
     * [leagueSport] resolves a league follow whose row carries no sport (a catalog follow made before the sport was
     * stored) from the catalog; it never fetches.
     */
    fun topics(
        settings: SportsAlertSettings,
        follows: List<RadarFollow>,
        teamFollows: List<RadarTeamFollow>,
        signedIn: Boolean,
        leagueSport: (String) -> String? = { null },
    ): Set<String> {
        if (!signedIn || !settings.enabled) return emptySet()
        // Levels decide what SHOWS, not what is joined (review round 1): a per-game All must still be able to reach a
        // game whose league level is Off, and re-joining on every level change would churn channels for nothing.
        val leagueSports = follows
            .filter { it.leagueId !in settings.mutedLeagues }
            .map { follow -> follow.sport.ifBlank { leagueSport(follow.leagueId).orEmpty() } }
        val teamSports = teamFollows
            .filter { it.teamId !in settings.mutedTeams }
            .map { it.sport }
        return (leagueSports + teamSports).mapNotNull(::slug).map(::topic).toSet()
    }
}
