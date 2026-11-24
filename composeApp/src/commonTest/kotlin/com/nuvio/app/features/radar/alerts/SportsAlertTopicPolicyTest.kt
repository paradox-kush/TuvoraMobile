package com.nuvio.app.features.radar.alerts

import com.nuvio.app.features.radar.RadarFollow
import com.nuvio.app.features.radar.RadarTeamFollow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which per-sport topics a device joins. kotlin.test → assertEquals(expected, actual, message). */
class SportsAlertTopicPolicyTest {
    private val soccerA = RadarFollow(leagueId = "4328", sport = "Soccer")
    private val soccerB = RadarFollow(leagueId = "4335", sport = "Soccer")
    private val nba = RadarFollow(leagueId = "4387", sport = "Basketball")
    private val tennis = RadarFollow(leagueId = "4464", sport = "Tennis")
    private val arsenal = RadarTeamFollow(teamId = "133604", name = "Arsenal", sport = "Soccer")
    private val rangers = RadarTeamFollow(teamId = "134830", name = "Rangers", sport = "Ice Hockey")

    private fun topics(
        settings: SportsAlertSettings = SportsAlertSettings(),
        follows: List<RadarFollow> = emptyList(),
        teams: List<RadarTeamFollow> = emptyList(),
        signedIn: Boolean = true,
        leagueSport: (String) -> String? = { null },
    ) = SportsAlertTopicPolicy.topics(settings, follows, teams, signedIn, leagueSport)

    @Test
    fun `follows map to their sports' topics`() {
        assertEquals(
            setOf("sports-alerts:v1:soccer", "sports-alerts:v1:basketball", "sports-alerts:v1:ice_hockey"),
            topics(follows = listOf(soccerA, nba), teams = listOf(rangers)),
            "one topic per sport",
        )
    }

    @Test
    fun `alerts off or no follows or signed out join nothing`() {
        assertEquals(emptySet(), topics(SportsAlertSettings(enabled = false), listOf(soccerA)), "alerts off")
        assertEquals(emptySet(), topics(), "no follows")
        assertEquals(emptySet(), topics(follows = listOf(soccerA), signedIn = false), "signed out or anonymous")
    }

    @Test
    fun `a muted league removes its sport only when nothing else needs it`() {
        val oneMuted = SportsAlertSettings(mutedLeagues = setOf("4328"))
        assertEquals(setOf("sports-alerts:v1:soccer"), topics(oneMuted, listOf(soccerA, soccerB)), "the other soccer league still needs it")
        val bothMuted = SportsAlertSettings(mutedLeagues = setOf("4328", "4335"))
        assertEquals(emptySet(), topics(bothMuted, listOf(soccerA, soccerB)), "nothing needs soccer")
        assertEquals(setOf("sports-alerts:v1:soccer"), topics(bothMuted, listOf(soccerA, soccerB), listOf(arsenal)), "a followed club needs it")
    }

    @Test
    fun `topics ignore levels so a followed sport stays joined`() {
        assertEquals(
            setOf("sports-alerts:v1:soccer", "sports-alerts:v1:ice_hockey"),
            topics(SportsAlertSettings(leagueLevel = AlertLevel.Off, teamLevel = AlertLevel.Off), listOf(soccerA), listOf(rangers)),
            "levels decide what shows, not what is joined",
        )
    }

    @Test
    fun `sports without a live feed get no topic`() {
        assertEquals(emptySet(), topics(follows = listOf(tennis)), "tennis has no livescore")
    }

    @Test
    fun `a league follow without a sport resolves it from the catalog`() {
        val bare = RadarFollow(leagueId = "4328", sport = "")
        assertEquals(setOf("sports-alerts:v1:soccer"), topics(follows = listOf(bare), leagueSport = { "Soccer" }), "catalog sport")
    }

    @Test
    fun `slugs accept the payload and the catalog spellings`() {
        assertEquals("american_football", SportsAlertTopicPolicy.slug("American Football"), "catalog")
        assertEquals("american_football", SportsAlertTopicPolicy.slug("american_football"), "slug")
        assertEquals(null, SportsAlertTopicPolicy.slug("Cricket"), "no feed")
    }
}
