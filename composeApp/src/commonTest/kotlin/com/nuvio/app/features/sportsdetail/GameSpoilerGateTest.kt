package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameSpoilerGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The game page under Hide scores / score delay: hidden strips the scoreboard numbers and every spoiler-bearing part
 * of a live or final game but keeps teams, status, standings and line-ups; a delay renders the snapshot from N s ago
 * (pending -> "–" and no details). kotlin.test → assertEquals(expected, actual, message).
 */
class GameSpoilerGateTest {

    private fun game(home: String?, away: String?, state: GameState = GameState.LIVE, clock: String = "67'") = GameDetail(
        gameId = "tsdb:1",
        sport = "soccer",
        leagueId = "4328",
        leagueName = "Premier League",
        state = state,
        header = TeamsHeader(Side("Arsenal", score = home), Side("Chelsea", score = away)),
        live = LiveInfo(clock = clock),
        blocks = listOf(
            LinescoreBlock(listOf("1", "2", "T"), listOf(listOf("1", "0", "1"), listOf("0", "0", "0"))),
            StatsBlock(listOf(StatRow("Shots", "9", "4"))),
            LeadersBlock(listOf(LeaderItem("Goals", "Saka", "1"))),
            PlaysBlock(listOf(TimelineItem(time = "23'", text = "Goal Saka", kind = "goal"))),
            WinProbabilityBlock(homePercent = 71),
            SoccerStripBlock(lastEvent = SoccerEvent("goal", "home", "23'", "Goal"), homeScorers = listOf("Saka 23'")),
            StandingsTableBlock(emptyList()),
            LineupBlock(listOf(LineupSide("home", players = emptyList()))),
            FormBlock(listOf("W"), listOf("L")),
        ),
    )

    private fun scores(d: GameDetail): Pair<String?, String?> = (d.header as TeamsHeader).let { it.home.score to it.away.score }

    @Test
    fun `shown with no delay renders the newest game untouched`() {
        val gate = GameSpoilerGate()
        val newest = game("1", "0")
        gate.record(newest, 0L)
        val view = gate.view(newest, ScoreSpoilerSettings(), 0L)
        assertEquals(newest, view.detail, "today's behaviour")
        assertFalse(view.hidden, "not hidden")
        assertFalse(view.pending, "not pending")
    }

    @Test
    fun `hidden removes scores and spoiler details but keeps teams status standings and lineups`() {
        val gate = GameSpoilerGate()
        val newest = game("1", "0")
        gate.record(newest, 0L)
        val view = gate.view(newest, ScoreSpoilerSettings(shown = false), 0L)
        assertTrue(view.hidden, "hidden")
        assertEquals(null to null, scores(view.detail), "no scoreboard numbers")
        val kinds = view.detail.blocks.map { it::class.simpleName }
        assertFalse(view.detail.blocks.any { it is LinescoreBlock || it is StatsBlock || it is LeadersBlock || it is PlaysBlock || it is WinProbabilityBlock }, "box score, stats, plays and win chance gone: $kinds")
        val strip = view.detail.blocks.filterIsInstance<SoccerStripBlock>().single()
        assertNull(strip.lastEvent, "no last play")
        assertTrue(strip.homeScorers.isEmpty() && strip.awayScorers.isEmpty(), "no scorers")
        assertTrue(view.detail.blocks.any { it is StandingsTableBlock } && view.detail.blocks.any { it is LineupBlock } && view.detail.blocks.any { it is FormBlock }, "standings, line-ups and form kept: $kinds")
        assertEquals("Arsenal", (view.detail.header as TeamsHeader).home.name, "teams kept")
        assertEquals("67'", view.detail.live?.clock, "the live clock is kept")
        assertEquals(GameState.LIVE, view.detail.state, "LIVE kept")
    }

    @Test
    fun `hidden keeps the pre-game win chance because a prediction spoils nothing`() {
        val gate = GameSpoilerGate()
        val upcoming = game(null, null, state = GameState.UPCOMING)
        gate.record(upcoming, 0L)
        val view = gate.view(upcoming, ScoreSpoilerSettings(shown = false), 0L)
        assertTrue(view.detail.blocks.any { it is WinProbabilityBlock }, "pre-game win chance kept (TV twin keeps it too)")
    }

    @Test
    fun `hidden hides a final result too`() {
        val gate = GameSpoilerGate()
        val final = game("2", "1", state = GameState.FINAL)
        gate.record(final, 0L)
        val view = gate.view(final, ScoreSpoilerSettings(shown = false), 0L)
        assertEquals(null to null, scores(view.detail), "final score hidden")
        assertEquals(GameState.FINAL, view.detail.state, "it still says it ended")
    }

    @Test
    fun `delay renders the older snapshot not the newer`() {
        val gate = GameSpoilerGate()
        gate.record(game("0", "0", clock = "20'"), 0L)
        val newest = game("1", "0", clock = "23'")
        gate.record(newest, 40_000L)
        val view = gate.view(newest, ScoreSpoilerSettings(delaySeconds = 30), 50_000L)
        assertEquals("0" to "0", scores(view.detail), "the 30 s-old snapshot is on screen")
        assertEquals("20'", view.detail.live?.clock, "the whole live model is delayed, clock included")
        assertEquals(70_000L, gate.nextChangeAtMs(ScoreSpoilerSettings(delaySeconds = 30), 50_000L), "re-render when 1-0 is due")
    }

    @Test
    fun `delay with nothing old enough is pending with dashes and no details`() {
        val gate = GameSpoilerGate()
        val newest = game("1", "0")
        gate.record(newest, 100_000L)
        val view = gate.view(newest, ScoreSpoilerSettings(delaySeconds = 60), 110_000L)
        assertTrue(view.pending, "pending")
        assertEquals("–" to "–", scores(view.detail), "scoreboard shows dashes")
        assertFalse(view.detail.blocks.any { it is PlaysBlock || it is WinProbabilityBlock }, "details hidden until a snapshot is due")
    }

    @Test
    fun `a game already final when the page opened is not delayed`() {
        val gate = GameSpoilerGate()
        val final = game("2", "1", state = GameState.FINAL)
        gate.record(final, 100_000L)
        val view = gate.view(final, ScoreSpoilerSettings(delaySeconds = 60), 100_000L)
        assertEquals(final, view.detail, "old news shows at once")
    }
}
