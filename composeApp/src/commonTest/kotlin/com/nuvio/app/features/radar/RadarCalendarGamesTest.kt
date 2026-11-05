package com.nuvio.app.features.radar

import com.nuvio.app.features.calendar.api.CalendarGameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadarCalendarGamesTest {
    private val kickoff = radarTimestampToEpochMs("2026-07-02T14:00:00")!!
    private val hour = 3_600_000L

    private fun fixture(
        id: String? = "e1",
        ts: String = "2026-07-02T14:00:00",
        leagueId: String? = "l1",
        status: String? = null,
        postponed: String? = null,
        homeScore: String? = null,
        awayScore: String? = null,
    ) = RadarFixture(
        id = id, leagueId = leagueId, league = "Premier League", sport = "Soccer", home = "Arsenal", away = "Chelsea",
        homeBadge = "h.png", awayBadge = "a.png", ts = ts, status = status, postponed = postponed,
        homeScore = homeScore, awayScore = awayScore,
    )

    private fun state(
        fixtures: Map<String, List<RadarFixture>> = emptyMap(),
        teamFixtures: Map<String, List<RadarFixture>> = emptyMap(),
        follows: List<String> = listOf("l1"),
        teams: List<String> = emptyList(),
        live: Map<String, RadarLiveScore> = emptyMap(),
    ) = RadarUiState(
        follows = follows.map { RadarFollow(leagueId = it) },
        teamFollows = teams.map { RadarTeamFollow(teamId = it, name = it) },
        fixturesByLeague = fixtures,
        fixturesByTeam = teamFixtures,
        liveScores = live,
        liveEventIds = live.keys,
        livescoreSports = if (live.isEmpty()) emptySet() else setOf("soccer"),
    )

    @Test fun aFollowedLeaguesFixtureBecomesAScheduledGameAtItsUtcKickoff() {
        val games = RadarCalendarGames.games(state(fixtures = mapOf("l1" to listOf(fixture()))), kickoff - 5 * hour)
        val g = games.single()
        assertEquals("e1", g.id)
        assertEquals(kickoff, g.kickoffMs)
        assertEquals(CalendarGameState.SCHEDULED, g.state)
        assertEquals("Arsenal", g.home)
        assertEquals("Premier League", g.league)
        assertNull(g.homeScore)
    }

    @Test fun aLeagueThatIsNotFollowedContributesNothing() {
        val s = state(fixtures = mapOf("l1" to listOf(fixture()), "l2" to listOf(fixture(id = "other", leagueId = "l2"))))
        assertEquals(listOf("e1"), RadarCalendarGames.games(s, kickoff - hour).map { it.id })
    }

    @Test fun aFollowedTeamsFixtureComesInEvenWhenItsLeagueIsNotFollowed() {
        val s = state(follows = emptyList(), teams = listOf("t1"), teamFixtures = mapOf("t1" to listOf(fixture(id = "team-game"))))
        assertEquals(listOf("team-game"), RadarCalendarGames.games(s, kickoff - hour).map { it.id })
    }

    @Test fun theSameEventFromTheLeagueAndTeamLanesCountsOnce() {
        val s = state(teams = listOf("t1"), fixtures = mapOf("l1" to listOf(fixture())), teamFixtures = mapOf("t1" to listOf(fixture())))
        assertEquals(1, RadarCalendarGames.games(s, kickoff - hour).size)
    }

    @Test fun theLiveFeedMakesItLiveWithScoreAndMinute() {
        val live = mapOf("e1" to RadarLiveScore(eventId = "e1", status = "2H", progress = "67'", homeScore = "2", awayScore = "1"))
        val g = RadarCalendarGames.games(state(fixtures = mapOf("l1" to listOf(fixture())), live = live), kickoff + hour).single()
        assertEquals(CalendarGameState.LIVE, g.state)
        assertEquals("2", g.homeScore)
        assertEquals("1", g.awayScore)
        assertEquals("67'", g.progress)
    }

    @Test fun aFinishedFixtureIsFinalWithItsScoreEvenIfTheLiveSetStillListsIt() {
        val f = fixture(status = "Match Finished", homeScore = "3", awayScore = "0")
        val live = mapOf("e1" to RadarLiveScore(eventId = "e1", homeScore = "3", awayScore = "0"))
        val g = RadarCalendarGames.games(state(fixtures = mapOf("l1" to listOf(f)), live = live), kickoff + 3 * hour).single()
        assertEquals(CalendarGameState.FINAL, g.state)
        assertEquals("3", g.homeScore)
        assertNull(g.progress)
    }

    @Test fun aFixtureLongPastKickoffWithNoFeedWordIsFinalNotScheduled() {
        val g = RadarCalendarGames.games(state(fixtures = mapOf("l1" to listOf(fixture()))), kickoff + 24 * hour).single()
        assertEquals(CalendarGameState.FINAL, g.state)
    }

    @Test fun postponedAndCancelledFixturesArePostponed() {
        assertEquals(CalendarGameState.POSTPONED, RadarCalendarGames.games(state(fixtures = mapOf("l1" to listOf(fixture(postponed = "yes")))), kickoff - hour).single().state)
        assertEquals(CalendarGameState.POSTPONED, RadarCalendarGames.games(state(fixtures = mapOf("l1" to listOf(fixture(status = "Cancelled")))), kickoff - hour).single().state)
    }

    @Test fun aFixtureWithoutATimestampIsLeftOutAndOneWithoutAnIdGetsAStableKey() {
        val s = state(fixtures = mapOf("l1" to listOf(fixture(id = "x", ts = ""), fixture(id = null))))
        val g = RadarCalendarGames.games(s, kickoff - hour).single()
        assertTrue(g.id.contains("2026-07-02T14:00:00"))
        assertEquals(g.id, radarFixtureKey(fixture(id = null)))
    }

    @Test fun theMatchSheetCanFindTheFixtureAGameIdNames() {
        val s = state(fixtures = mapOf("l1" to listOf(fixture())), teams = listOf("t1"), teamFixtures = mapOf("t1" to listOf(fixture(id = "tg"))))
        assertEquals("e1", s.fixtureByKey("e1")?.id)
        assertEquals("tg", s.fixtureByKey("tg")?.id)
        assertNull(s.fixtureByKey("nope"))
    }
}
