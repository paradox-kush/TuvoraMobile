package com.tuvora.tvos.screens

import com.nuvio.app.features.radar.MatchConfidence
import com.nuvio.app.features.radar.RadarCatalog
import com.nuvio.app.features.radar.RadarChannelMatcher
import com.nuvio.app.features.radar.RadarCategory
import com.nuvio.app.features.radar.RadarFixture
import com.nuvio.app.features.radar.RadarFollow
import com.nuvio.app.features.radar.RadarLeague
import com.nuvio.app.features.radar.RadarLiveScore
import com.nuvio.app.features.radar.RadarTeamFollow
import com.nuvio.app.features.radar.RadarUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSportsHubPolicyTest {
    // 2026-09-28T12:00:00Z
    private val now = 1_790_596_800_000L

    private fun fixture(id: String, league: String, ts: String, home: String = "A", away: String = "B") =
        RadarFixture(id = id, leagueId = league, league = league, sport = "Rugby", home = home, away = away, ts = ts)

    private val epl = RadarLeague(id = "4328", name = "Premier League")
    private val nba = RadarLeague(id = "4387", name = "NBA")
    private val catalog = RadarCatalog(categories = listOf(RadarCategory(name = "Football", leagues = listOf(epl)), RadarCategory(name = "Basketball", leagues = listOf(nba))))

    @Test
    fun `followed leagues and teams feed one live and upcoming row soonest first`() {
        val state = RadarUiState(
            catalog = catalog,
            follows = listOf(RadarFollow(leagueId = "4328")),
            teamFollows = listOf(RadarTeamFollow(teamId = "t1", name = "Arsenal")),
            fixturesByLeague = mapOf("4328" to listOf(fixture("m2", "4328", "2026-09-28T18:00:00"), fixture("m1", "4328", "2026-09-28T14:00:00"))),
            fixturesByTeam = mapOf("t1" to listOf(fixture("m1", "4328", "2026-09-28T14:00:00"), fixture("m3", "x", "2026-09-29T14:00:00"))),
        )
        val hub = TvSportsHubPolicy.build(state, now)
        assertEquals(listOf("m1", "m2", "m3"), hub.upcoming.map { it.id })
        assertEquals(listOf("Arsenal"), hub.teamRows.map { it.title })
        assertEquals(listOf("Premier League"), hub.leagueRows.map { it.title })
        assertEquals(epl, hub.leagueRows.single().league)
        assertFalse(hub.followPrompt)
        assertEquals(listOf("1 followed", "1 to track"), hub.categories.map { it.subtitle })
    }

    @Test
    fun `a profile that follows nothing gets the follow prompt and no rails`() {
        val hub = TvSportsHubPolicy.build(RadarUiState(catalog = catalog), now)
        assertTrue(hub.followPrompt)
        assertTrue(hub.upcoming.isEmpty() && hub.teamRows.isEmpty() && hub.leagueRows.isEmpty())
        assertFalse(hub.upcomingLoading)
    }

    @Test
    fun `the skeleton shows only while a follower's fixtures load`() {
        val loading = RadarUiState(catalog = catalog, follows = listOf(RadarFollow(leagueId = "4328")), loadingFixtures = true)
        assertTrue(TvSportsHubPolicy.build(loading, now).upcomingLoading)
        assertFalse(TvSportsHubPolicy.build(loading.copy(loadingFixtures = false), now).upcomingLoading)
    }

    @Test
    fun `status pill carries the live minute`() {
        val fx = fixture("m1", "4328", "2026-09-28T11:30:00")
        assertEquals(TvMatchStatus(TvMatchStatusTone.Live, "LIVE 67'"), TvSportsHubPolicy.status(fx, true, RadarLiveScore(progress = "67'"), TvMatchDay.Today))
        assertEquals(TvMatchStatus(TvMatchStatusTone.Live, "LIVE"), TvSportsHubPolicy.status(fx, true, null, TvMatchDay.Today))
        assertEquals(TvMatchStatus(TvMatchStatusTone.Muted, "POSTPONED"), TvSportsHubPolicy.status(fx.copy(postponed = "yes"), false, null, TvMatchDay.Today))
        assertEquals(TvMatchStatus(TvMatchStatusTone.Muted, "FT"), TvSportsHubPolicy.status(fx.copy(homeScore = "2", awayScore = "1"), false, null, TvMatchDay.Today))
        assertEquals(TvMatchStatus(TvMatchStatusTone.Accent, "", TvMatchDay.Tomorrow), TvSportsHubPolicy.status(fx, false, null, TvMatchDay.Tomorrow))
        assertEquals(TvMatchStatusTone.None, TvSportsHubPolicy.status(fx, false, null, TvMatchDay.Other).tone)
    }

    @Test
    fun `match day is decided by calendar day in the viewer's zone and never by an English label`() {
        val hour = 60 * 60 * 1000L
        // now = 2026-09-28 12:00 UTC
        assertEquals(TvMatchDay.Today, TvSportsHubPolicy.matchDay(now + 11 * hour, now, utcOffsetMs = 0))      // 23:00
        assertEquals(TvMatchDay.Tomorrow, TvSportsHubPolicy.matchDay(now + 13 * hour, now, utcOffsetMs = 0))   // 01:00 next day
        assertEquals(TvMatchDay.Other, TvSportsHubPolicy.matchDay(now + 40 * hour, now, utcOffsetMs = 0))
        assertEquals(TvMatchDay.Other, TvSportsHubPolicy.matchDay(now - 13 * hour, now, utcOffsetMs = 0))      // yesterday
        assertEquals(TvMatchDay.Other, TvSportsHubPolicy.matchDay(null, now, utcOffsetMs = 0))
        // The same instant is "tomorrow" in UTC but still "today" in New York (UTC-4).
        assertEquals(TvMatchDay.Today, TvSportsHubPolicy.matchDay(now + 13 * hour, now, utcOffsetMs = -4 * hour))
        // ...and a UTC+14 viewer is already on the 29th at 12:00 UTC: 23:00 UTC is still their 29th.
        assertEquals(TvMatchDay.Today, TvSportsHubPolicy.matchDay(now + 11 * hour, now, utcOffsetMs = 14 * hour))
    }

    @Test
    fun `scores prefer the live feed and the trailing side dims`() {
        assertEquals("2", TvSportsHubPolicy.score("2", "1"))
        assertEquals("1", TvSportsHubPolicy.score(null, "1"))
        assertEquals(null, TvSportsHubPolicy.score(null, " "))
        assertTrue(TvSportsHubPolicy.scoreTrails("0", "2"))
        assertFalse(TvSportsHubPolicy.scoreTrails("2", "2"))
        assertFalse(TvSportsHubPolicy.scoreTrails("-", "2"))
    }

    @Test
    fun `crest fallback monograms`() {
        assertEquals("MU", TvSportsHubPolicy.teamMonogram("Manchester United"))
        assertEquals("AR", TvSportsHubPolicy.teamMonogram("Arsenal"))
        assertEquals("?", TvSportsHubPolicy.teamMonogram("1860"))
    }

    @Test
    fun `refresh cadence is a live tick every two minutes and a full refresh every fifteen`() {
        var since = 0L
        val fulls = (1..15).map { TvSportsRefreshCadence.next(since).also { since = it.sinceFullMs }.full }
        // 2-min ticks: the 8th tick (16 min) is the first full refresh, then the count restarts.
        assertEquals(listOf(8), fulls.withIndex().filter { it.value }.map { it.index + 1 })
    }

    private fun match(id: String, via: RadarChannelMatcher.MatchVia, confidence: MatchConfidence) = RadarChannelMatcher.ChannelMatch(
        channel = RadarChannelMatcher.CandidateChannel("p", "Playlist", id, id, null, 1),
        programme = null, score = 1, via = via, confidence = confidence,
    )

    @Test
    fun `match sheet always distinguishes evidence even with a single tier`() {
        val epg = match("c1", RadarChannelMatcher.MatchVia.EPG, MatchConfidence.CONFIRMED)
        val listing = match("c2", RadarChannelMatcher.MatchVia.LISTING, MatchConfidence.CONFIRMED)
        val league = match("c3", RadarChannelMatcher.MatchVia.NAME, MatchConfidence.LEAGUE)
        val possible = match("c4", RadarChannelMatcher.MatchVia.NAME, MatchConfidence.POSSIBLE)
        val groups = TvSportsHubPolicy.groupMatches(listOf(league, epg, listing, epg, possible), "Premier League")
        assertEquals(listOf("SCHEDULED FOR THIS EVENT", "LISTED BROADCASTER", "POSSIBLE EVENT FEEDS", "CARRIES PREMIER LEAGUE"), groups.map { it.label })
        assertEquals(listOf(listOf("c1"), listOf("c2"), listOf("c4"), listOf("c3")), groups.map { g -> g.matches.map { it.channel.contentId } })
        val single = TvSportsHubPolicy.groupMatches(listOf(league), null)
        assertEquals(listOf<String?>("CARRIES THIS COMPETITION"), single.map { it.label })
        assertTrue(TvSportsHubPolicy.groupMatches(emptyList(), null).isEmpty())
    }

    @Test
    fun `league page splits upcoming from recent results and tracks follow and loading`() {
        val league = RadarLeague(id = "4328", name = "Premier League", sport = "Soccer")
        val notLoaded = TvSportsHubPolicy.leaguePage(RadarUiState(catalog = catalog), league, now)
        assertEquals("Soccer · Loading…", notLoaded.subtitle)
        assertFalse(notLoaded.loaded || notLoaded.empty || notLoaded.followed)

        val state = RadarUiState(
            catalog = catalog,
            follows = listOf(RadarFollow(leagueId = "4328")),
            fixturesByLeague = mapOf(
                "4328" to listOf(
                    fixture("past", "4328", "2026-09-20T14:00:00").copy(sport = "Soccer", homeScore = "2", awayScore = "0"),
                    fixture("next", "4328", "2026-09-30T14:00:00").copy(sport = "Soccer"),
                ),
            ),
        )
        val page = TvSportsHubPolicy.leaguePage(state, league, now)
        assertEquals(listOf("next"), page.upcoming.map { it.id })
        assertEquals(listOf("past"), page.recent.map { it.id })
        assertTrue(page.followed)
        assertEquals("Soccer · 1 upcoming", page.subtitle)

        val empty = TvSportsHubPolicy.leaguePage(state.copy(fixturesByLeague = mapOf("4328" to emptyList())), league, now)
        assertTrue(empty.empty)
    }
}
