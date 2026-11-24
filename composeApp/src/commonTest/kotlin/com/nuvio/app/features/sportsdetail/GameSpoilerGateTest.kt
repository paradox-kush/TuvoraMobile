package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.NextBlock
import com.nuvio.app.features.sportsdetail.api.NextItem
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StandingsBlock
import com.nuvio.app.features.sportsdetail.api.UnknownBlock
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WatchBlock
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
import com.nuvio.app.features.sportsdetail.api.StandingRow
import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableRow
import com.nuvio.app.features.sportsdetail.internal.GameRevealChip
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.internal.GameSectionsPolicy
import com.nuvio.app.features.sportsdetail.internal.GameSpoilerGate
import com.nuvio.app.features.sportsdetail.internal.revealChip
import com.nuvio.app.features.sportsdetail.internal.showsHiddenCard
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
        assertFalse(view.detail.blocks.any { it is SoccerStripBlock }, "the soccer strip (last play, scorers, cards, corners, possession) is gone: $kinds")
        assertTrue(view.detail.blocks.any { it is LineupBlock }, "line-ups kept: $kinds")
        assertFalse(view.detail.blocks.any { it is StandingsTableBlock || it is FormBlock }, "standings and form give the result away: $kinds")
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

    // --- started games: an ALLOW-list, never a deny-list -----------------------------------------------------------

    private fun liveWith(vararg blocks: GameBlock, state: GameState = GameState.LIVE) =
        game("1", "0", state = state).copy(blocks = blocks.toList())

    private fun hiddenView(detail: GameDetail) = GameSpoilerGate().let { gate ->
        gate.record(detail, 0L)
        gate.view(detail, ScoreSpoilerSettings(shown = false), 0L)
    }

    @Test
    fun `hidden drops the situation line of a live game`() {
        val view = hiddenView(liveWith(SituationBlock("2nd & 5 at LSU 33 - LSU leads by 7")))
        assertFalse(view.detail.blocks.any { it is SituationBlock }, "situation can name the leader: ${view.detail.blocks}")
    }

    @Test
    fun `hidden drops the football field strip`() {
        val view = hiddenView(liveWith(FootballFieldBlock(yardLine = 95, possession = "home", line = "1st & Goal", redZone = true)))
        assertFalse(view.detail.blocks.any { it is FootballFieldBlock }, "field and drive give the game away: ${view.detail.blocks}")
    }

    @Test
    fun `hidden drops the hockey strip`() {
        val view = hiddenView(liveWith(HockeyStripBlock(powerPlaySide = "home", homePowerPlay = "2/3", homeShots = 31)))
        assertFalse(view.detail.blocks.any { it is HockeyStripBlock }, "power-play goals are goals: ${view.detail.blocks}")
    }

    @Test
    fun `hidden drops the basketball strip and unknown blocks`() {
        val view = hiddenView(liveWith(BasketballStripBlock(homeFouls = 4, homeTimeouts = 2), UnknownBlock()))
        assertTrue(view.detail.blocks.isEmpty(), "fouls, timeouts and anything unrecognised are dropped: ${view.detail.blocks}")
    }

    @Test
    fun `hidden keeps only watch broadcast lineup and next for a started game`() {
        val kept = listOf(
            WatchBlock(entry = "browsing"), BroadcastBlock(mapOf("UK" to listOf("Sky"))), LineupBlock(emptyList()),
            NextBlock(listOf(NextItem("Sat", "vs Spurs"))),
        )
        val view = hiddenView(liveWith(*kept.toTypedArray(), state = GameState.FINAL))
        assertEquals(kept, view.detail.blocks, "the allow-list passes through untouched")
    }

    @Test
    fun `hidden redacts the versus header scores and records`() {
        val tennis = game(null, null).copy(
            sport = "tennis",
            header = VersusHeader(Side("Sinner", score = "6 3", record = "41-4"), Side("Alcaraz", score = "4 6", record = "38-6")),
        )
        val view = hiddenView(tennis)
        val h = view.detail.header as VersusHeader
        assertEquals(null to null, h.a.score to h.b.score, "set scores hidden")
        assertEquals(null to null, h.a.record to h.b.record, "a record that moved with this result is hidden")
        assertEquals("Sinner", h.a.name, "players kept")
    }

    @Test
    fun `hidden redacts the teams header records of a started game`() {
        val final = game("2", "1", state = GameState.FINAL).let {
            it.copy(header = TeamsHeader(Side("Arsenal", score = "2", record = "10-2-1"), Side("Chelsea", score = "1", record = "7-4-2")))
        }
        val h = hiddenView(final).detail.header as TeamsHeader
        assertEquals(null to null, h.home.record to h.away.record, "W-D-L moves with the result")
    }

    @Test
    fun `upcoming games keep every block while hidden`() {
        val upcoming = liveWith(SituationBlock("Preview"), HockeyStripBlock(), state = GameState.UPCOMING)
        assertEquals(upcoming.blocks, hiddenView(upcoming).detail.blocks, "nothing to spoil before the start")
    }

    @Test
    fun `the reveal chip shows only for a started game`() {
        assertTrue(hiddenView(game("1", "0")).showsRevealChip, "live")
        assertTrue(hiddenView(game("1", "0", state = GameState.FINAL)).showsRevealChip, "final")
        assertFalse(hiddenView(game(null, null, state = GameState.UPCOMING)).showsRevealChip, "upcoming has no score to reveal")
        val shown = GameSpoilerGate().let { g -> val d = game("1", "0"); g.record(d, 0L); g.view(d, ScoreSpoilerSettings(), 0L) }
        assertFalse(shown.showsRevealChip, "nothing hidden, no chip")
    }

    // --- one game revealed; the empty-overview card ------------------------------------------------------------------

    @Test
    fun `a hidden started game offers Show this game's score`() {
        assertEquals(GameRevealChip.SHOW_THIS_GAME, hiddenView(game("1", "0")).revealChip(revealedHere = false, globalShown = false), "reveal one game")
    }

    @Test
    fun `a game revealed while scores are hidden offers Hide again`() {
        val gate = GameSpoilerGate()
        val live = game("1", "0")
        gate.record(live, 0L)
        val shownHere = gate.view(live, ScoreSpoilerSettings(), 0L)    // the page gets shown settings for a revealed game
        assertEquals(GameRevealChip.HIDE_AGAIN, shownHere.revealChip(revealedHere = true, globalShown = false), "hide it again")
        assertEquals(GameRevealChip.NONE, shownHere.revealChip(revealedHere = true, globalShown = true), "scores shown everywhere: nothing to undo here")
        assertEquals(GameRevealChip.NONE, shownHere.revealChip(revealedHere = false, globalShown = true), "plain shown game: no chip")
    }

    @Test
    fun `an upcoming game offers neither chip`() {
        val upcoming = game(null, null, state = GameState.UPCOMING)
        assertEquals(GameRevealChip.NONE, hiddenView(upcoming).revealChip(revealedHere = false, globalShown = false), "nothing to reveal")
        val gate = GameSpoilerGate().also { it.record(upcoming, 0L) }
        assertEquals(GameRevealChip.NONE, gate.view(upcoming, ScoreSpoilerSettings(), 0L).revealChip(revealedHere = true, globalShown = false), "nothing to hide")
    }

    @Test
    fun `a final game whose overview redaction emptied shows the Scores are hidden card`() {
        val final = liveWith(StandingsTableBlock(emptyList()), WatchBlock(entry = "browsing"), state = GameState.FINAL)
        assertTrue(hiddenView(final).showsHiddenCard, "standings and Watch are not Overview: the Overview would be blank")
    }

    @Test
    fun `no hidden card while the overview still has content or scores are shown`() {
        val withNext = liveWith(NextBlock(listOf(NextItem("Sat", "vs Spurs"))), state = GameState.FINAL)
        assertFalse(hiddenView(withNext).showsHiddenCard, "next games still fill the Overview")
        val gate = GameSpoilerGate()
        val shownEmpty = liveWith(state = GameState.FINAL).also { gate.record(it, 0L) }
        assertFalse(gate.view(shownEmpty, ScoreSpoilerSettings(), 0L).showsHiddenCard, "not hidden: no card")
    }

    // --- standings and form give the result away (W4 / L4 streaks, W-L moving) -----------------------------------

    private val tableAndForm = listOf(
        StandingsTableBlock(listOf(StandingsGroup("West", listOf("W", "L", "STRK"), listOf(StandingsTableRow("1", "Suns", listOf("40", "12", "W4"), isGameTeam = true))))),
        StandingsBlock(listOf(StandingRow("1", "Suns", "52", "80", form = "WWWW"))),
        FormBlock(listOf("W", "W"), listOf("L", "L")),
    )

    private fun withHeaderForm(d: GameDetail) = d.copy(
        header = TeamsHeader(Side("Suns", score = "112", form = listOf("W", "W")), Side("Pelicans", score = "99", form = listOf("L", "L"))),
    )

    @Test
    fun `hidden final drops standings standings table form and header form`() {
        val final = withHeaderForm(liveWith(*(tableAndForm + LineupBlock(emptyList())).toTypedArray(), state = GameState.FINAL))
        val view = hiddenView(final)
        assertFalse(view.detail.blocks.any { it is StandingsTableBlock || it is StandingsBlock || it is FormBlock }, "a streak names the winner: ${view.detail.blocks}")
        assertTrue(view.detail.blocks.any { it is LineupBlock }, "line-ups stay")
        val h = view.detail.header as TeamsHeader
        assertEquals(null to null, h.home.form to h.away.form, "header form hidden too")
    }

    @Test
    fun `a game revealed on its own keeps standings and form`() {
        val gate = GameSpoilerGate()
        val final = withHeaderForm(liveWith(*tableAndForm.toTypedArray(), state = GameState.FINAL)).also { gate.record(it, 0L) }
        val view = gate.view(final, ScoreSpoilerSettings(), 0L)  // revealed -> the page gets shown settings
        assertEquals(final, view.detail, "everything back")
    }

    @Test
    fun `upcoming keeps standings and form while hidden`() {
        val upcoming = withHeaderForm(liveWith(*tableAndForm.toTypedArray(), state = GameState.UPCOMING))
        val view = hiddenView(upcoming)
        assertEquals(upcoming.blocks, view.detail.blocks, "pre-game form spoils nothing")
        assertEquals(listOf("W", "W"), (view.detail.header as TeamsHeader).home.form, "header form kept")
    }

    @Test
    fun `no standings tab while hidden`() {
        val view = hiddenView(liveWith(*tableAndForm.toTypedArray(), state = GameState.FINAL))
        assertFalse(GameSectionsPolicy.sections(view.detail).contains(GameSection.STANDINGS), "the Standings tab disappears")
        assertTrue(view.showsHiddenCard, "and the empty Overview says why")
    }
}
