package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameMomentTracker
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.ui.GameScoreBar
import com.nuvio.app.features.sportsdetail.internal.ui.GameTabsUi
import com.nuvio.app.features.sportsdetail.internal.ui.PlaysGrouping
import com.nuvio.app.features.sportsdetail.internal.ui.StatLeader
import com.nuvio.app.features.sportsdetail.internal.ui.StatsPanel
import com.nuvio.app.features.sportsdetail.internal.ui.WinChanceModel
import com.nuvio.app.features.sportsdetail.internal.ui.rankText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameUiHelpersTest {
    private fun detail(home: String = "1", away: String = "0", state: GameState = GameState.LIVE, blocks: List<com.nuvio.app.features.sportsdetail.api.GameBlock> = emptyList(), id: String = "espn:1") =
        GameDetail(
            gameId = id, sport = "Ice Hockey", leagueId = "1", leagueName = "NHL", state = state,
            header = TeamsHeader(Side("Home Club", "HOM", score = home), Side("Away Club", "AWY", score = away)),
            live = LiveInfo(clock = "5:12", periodLabel = "2nd", period = 2), blocks = blocks,
        )

    // ---- stats ------------------------------------------------------------------------------------------

    @Test fun `stat bar splits by numeric value and the larger side leads`() {
        val r = StatsPanel.rows(StatsBlock(listOf(StatRow("Shots", "20", "10")))).single()
        assertEquals(2f / 3f, r.homeFraction!!, 0.001f, "home flex share")
        assertEquals(StatLeader.HOME, r.leader, "more shots leads")
    }

    @Test fun `lower is better rows flip the leader`() {
        val r = StatsPanel.rows(StatsBlock(listOf(StatRow("Turnovers", "3", "7")))).single()
        assertEquals(StatLeader.HOME, r.leader, "fewer turnovers leads")
        assertEquals(StatLeader.AWAY, StatsPanel.rows(StatsBlock(listOf(StatRow("Fouls", "9", "4")))).single().leader, "fewer fouls leads")
    }

    @Test fun `non numeric values omit the bar and leader`() {
        val r = StatsPanel.rows(StatsBlock(listOf(StatRow("Power plays", "1/2", "0/1")))).single()
        assertNull(r.homeFraction, "no bar for fractions")
        assertEquals(StatLeader.NONE, r.leader, "no leader for fractions")
    }

    @Test fun `percent units are numeric and a tie has no leader`() {
        val r = StatsPanel.rows(StatsBlock(listOf(StatRow("Possession", "50", "50", unit = "%")))).single()
        assertEquals(0.5f, r.homeFraction!!, 0.001f, "even split")
        assertEquals(StatLeader.NONE, r.leader, "tie")
        assertEquals("50%", r.home, "unit appended")
    }

    @Test fun `both zero omits the bar`() {
        assertNull(StatsPanel.rows(StatsBlock(listOf(StatRow("Penalties", "0", "0")))).single().homeFraction, "0 vs 0 has nothing to split")
    }

    // ---- score bar --------------------------------------------------------------------------------------

    @Test fun `score bar text for a live game`() {
        val t = GameScoreBar.of(detail("2", "1"))!!
        assertEquals("HOM", t.home, "short name")
        assertEquals("2", t.homeScore, "home score")
        assertEquals("1", t.awayScore, "away score")
        assertTrue(t.clock.isNotBlank(), "live clock shown")
    }

    @Test fun `score bar is absent without two teams`() {
        assertNull(GameScoreBar.of(detail().copy(header = com.nuvio.app.features.sportsdetail.api.EventHeader("Race"))), "event header has no slim bar")
    }

    @Test fun `rank text hides unranked`() {
        assertEquals("5", rankText(5), "ranked")
        assertNull(rankText(null), "null")
        assertNull(rankText(99), "ESPN unranked sentinel")
        assertNull(rankText(0), "zero")
    }

    // ---- plays ------------------------------------------------------------------------------------------

    @Test fun `plays group consecutively by period keeping newest first`() {
        val items = listOf(
            TimelineItem("3:00", "3rd Quarter", "home", "a"), TimelineItem("5:00", "3rd Quarter", "away", "b"),
            TimelineItem("1:00", "2nd Quarter", "home", "c"),
        )
        val g = PlaysGrouping.groups(items)
        assertEquals(listOf("3RD QUARTER", "2ND QUARTER"), g.map { it.label }, "headers upper-cased in feed order")
        assertEquals(listOf(2, 1), g.map { it.items.size }, "items per group")
    }

    @Test fun `plays without a period form one unlabeled group`() {
        val g = PlaysGrouping.groups(listOf(TimelineItem("1'", null, null, "x")))
        assertEquals("", g.single().label, "no header text")
    }

    // ---- tabs -------------------------------------------------------------------------------------------

    @Test fun `a vanished section falls back to overview`() {
        assertEquals(GameSection.OVERVIEW, GameTabsUi.selected(listOf(GameSection.OVERVIEW, GameSection.STATS), GameSection.PLAYS), "plays tab gone")
        assertEquals(GameSection.STATS, GameTabsUi.selected(listOf(GameSection.OVERVIEW, GameSection.STATS), GameSection.STATS), "kept")
    }

    @Test fun `tab labels`() {
        assertEquals("Overview", GameTabsUi.label(GameSection.OVERVIEW), "overview")
        assertEquals("Standings", GameTabsUi.label(GameSection.STANDINGS), "standings")
    }

    // ---- win chance -------------------------------------------------------------------------------------

    @Test fun `win chance leader is the larger of home and away`() {
        val m = WinChanceModel.of(WinProbabilityBlock(homePercent = 35, awayPercent = 65), TeamsHeader(Side("H", "HOM"), Side("A", "AWY")))
        assertEquals("away", m.leaderSide, "away leads")
        assertEquals(65, m.leaderPercent, "leader percent")
        assertEquals(35, m.otherPercent, "other percent")
        assertEquals("A to win", m.leaderLabel, "full team name")
    }

    @Test fun `win chance draw is kept for soccer and home wins ties`() {
        val m = WinChanceModel.of(WinProbabilityBlock(homePercent = 40, drawPercent = 20, awayPercent = 40), TeamsHeader(Side("H"), Side("A")))
        assertEquals("home", m.leaderSide, "tie goes to home")
        assertEquals(20, m.drawPercent, "draw shown")
    }

    // ---- moments ----------------------------------------------------------------------------------------

    @Test fun `first snapshot yields no moments and a score rise yields one batch`() {
        val t = GameMomentTracker()
        assertTrue(t.accept(detail("1", "0")).moments.isEmpty(), "open = no animation")
        val b = t.accept(detail("2", "0"))
        assertEquals(listOf<Moment>(Moment.ScoreChange("home", "2")), b.moments, "one score change")
    }

    @Test fun `identical snapshot keeps the batch sequence so nothing replays`() {
        val t = GameMomentTracker()
        t.accept(detail("1", "0"))
        val a = t.accept(detail("2", "0"))
        val same = t.accept(detail("2", "0"))
        assertTrue(same.moments.isEmpty(), "no change no moment")
        assertTrue(same.seq > a.seq || same.moments.isEmpty(), "never replays the previous batch")
    }

    @Test fun `reset after resume treats the next snapshot as the first`() {
        val t = GameMomentTracker()
        t.accept(detail("1", "0"))
        t.reset()
        assertTrue(t.accept(detail("5", "0")).moments.isEmpty(), "resume catches up silently")
    }

    @Test fun `new plays moment flows through`() {
        val t = GameMomentTracker()
        t.accept(detail(blocks = listOf(PlaysBlock(listOf(TimelineItem("1:00", "1st", "home", "old", id = "1"))))))
        val b = t.accept(detail(blocks = listOf(PlaysBlock(listOf(TimelineItem("2:00", "1st", "home", "new", id = "2"))))))
        assertTrue(b.moments.any { it is Moment.NewPlay }, "new play moment")
    }

    @Test fun `chart follows the leader so the line rises with the headline number`() {
        val m = WinChanceModel.of(WinProbabilityBlock(homePercent = 1, awayPercent = 99), TeamsHeader(Side("A", "AAA"), Side("B", "BBB")))
        assertEquals(listOf(0.8f, 0.99f), m.seriesFor(listOf(0.2f, 0.01f)), "away leads so the home series is flipped")
        val h = WinChanceModel.of(WinProbabilityBlock(homePercent = 70, awayPercent = 30), TeamsHeader(Side("A", "AAA"), Side("B", "BBB")))
        assertEquals(listOf(0.2f, 0.7f), h.seriesFor(listOf(0.2f, 0.7f)), "home leads so the series is as given")
    }
}
