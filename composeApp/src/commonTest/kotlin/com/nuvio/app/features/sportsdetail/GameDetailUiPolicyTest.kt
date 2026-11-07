package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameDetailUiPolicyTest {
    private val now = 1_000_000_000L

    @Test fun `no chip while the data is fresh`() {
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 5_000), now))
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 20_000), now))
        assertNull(GameDetailUiPolicy.staleLabel(emptyMap(), now))
    }

    @Test fun `chip tracks the FRESHEST source fetch and shows past 20 s`() {
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 21_000, "tsdb" to now - 2_000), now), "a fresh source keeps the chip away")
        assertEquals("Updated 21s ago", GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 21_000, "tsdb" to now - 90_000), now))
        assertEquals("Updated 2 min ago", GameDetailUiPolicy.staleLabel(mapOf("tsdb" to now - 125_000, "espn" to now - 300_000), now))
        assertEquals("Updated 3 h ago", GameDetailUiPolicy.staleLabel(mapOf("tsdb" to now - 3 * 3_600_000L - 5), now))
    }

    @Test fun `a clock that went backwards never shows a negative age`() {
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now + 60_000), now))
    }

    @Test fun `empty blocks hide`() {
        assertTrue(GameDetailUiPolicy.isEmpty(StatsBlock(emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(LinescoreBlock(emptyList(), emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(LinescoreBlock(listOf("Q1"), emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(TimelineBlock(emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(LeadersBlock(emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(BroadcastBlock(mapOf("US" to listOf("ESPN")))), "broadcast is never shown")
        assertTrue(GameDetailUiPolicy.isEmpty(FormBlock(emptyList(), emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(SituationBlock(" ")))
        assertTrue(GameDetailUiPolicy.isEmpty(HighlightsBlock("")))
    }

    @Test fun `non-empty blocks show`() {
        assertFalse(GameDetailUiPolicy.isEmpty(StatsBlock(listOf(StatRow("Fouls", "1", "2")))))
        assertFalse(GameDetailUiPolicy.isEmpty(LeadersBlock(listOf(LeaderItem("Points", "A", "30")))))
        assertFalse(GameDetailUiPolicy.isEmpty(WinProbabilityBlock(0)))
        assertFalse(GameDetailUiPolicy.isEmpty(FormBlock(listOf("W"), emptyList())))
    }

    @Test fun `wide layout from 768 dp`() {
        assertFalse(GameDetailUiPolicy.isWide(767))
        assertTrue(GameDetailUiPolicy.isWide(768))
    }

    @Test fun `wide layout puts score blocks left and detail blocks right`() {
        assertTrue(GameDetailUiPolicy.isPrimaryColumn(LinescoreBlock(listOf("Q1"), listOf(listOf("1")))))
        assertTrue(GameDetailUiPolicy.isPrimaryColumn(FormBlock(listOf("W"), emptyList())))
        assertFalse(GameDetailUiPolicy.isPrimaryColumn(StatsBlock(listOf(StatRow("a", "1", "2")))))
        assertFalse(GameDetailUiPolicy.isPrimaryColumn(TimelineBlock(emptyList())))
    }

    @Test fun `loading ends at its deadline in the UI even if the controller is stuck`() {
        val loading = com.nuvio.app.features.sportsdetail.internal.GameDetailUiState.Loading(startedAtMs = 0, deadlineAtMs = 20_000)
        assertEquals(loading, GameDetailUiPolicy.effective(loading, 19_999))
        assertEquals(com.nuvio.app.features.sportsdetail.internal.GameDetailUiState.Failed(timedOut = true), GameDetailUiPolicy.effective(loading, 20_000))
        val failed = com.nuvio.app.features.sportsdetail.internal.GameDetailUiState.Failed(false)
        assertEquals(failed, GameDetailUiPolicy.effective(failed, 99_999))
    }

    @Test fun `only http and https links open`() {
        assertTrue(GameDetailUiPolicy.isSafeLink("https://youtu.be/x"))
        assertTrue(GameDetailUiPolicy.isSafeLink("http://example.com/a"))
        assertFalse(GameDetailUiPolicy.isSafeLink("intent://x#Intent;end"))
        assertFalse(GameDetailUiPolicy.isSafeLink("javascript:alert(1)"))
        assertFalse(GameDetailUiPolicy.isSafeLink("file:///etc/passwd"))
        assertFalse(GameDetailUiPolicy.isSafeLink(""))
    }

    // ---- live line, periods, team tags, broadcast ordering -----------------------------------------------------

    @Test fun `live status text is period and clock or the break wording`() {
        fun t(l: LiveInfo?) = GameDetailUiPolicy.liveStatusText(l)
        assertEquals("2nd · 5:12", t(LiveInfo(clock = "5:12", periodLabel = "2nd", detail = "5:12 - 2nd Period")))
        assertEquals("Intermission", t(LiveInfo(clock = "0:00", periodLabel = "2nd", detail = "End of 2nd Period")))
        assertEquals("Halftime", t(LiveInfo(clock = "45'", detail = "Halftime")))
        assertEquals("67'", t(LiveInfo(clock = "67'", detail = "67'")))
        assertEquals("LIVE", t(LiveInfo()))
        assertEquals("LIVE", t(null))
    }

    @Test fun `not-started periods are hidden while live and totals are kept`() {
        val b = LinescoreBlock(listOf("P1", "P2", "P3", "T"), listOf(listOf("1", "2", "0", "3"), listOf("2", "0", "0", "2")), listOf("WSH", "FLA"))
        val trimmed = GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.LIVE, livePeriod = 2)
        assertEquals(listOf("P1", "P2", "T"), trimmed.columns)
        assertEquals(listOf("1", "2", "3"), trimmed.rows[0])
        assertEquals(listOf("2", "0", "2"), trimmed.rows[1])
        assertEquals(b, GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.FINAL, livePeriod = 2), "final games show every period")
        assertEquals(b, GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.LIVE, livePeriod = null))
        val mlb = LinescoreBlock(listOf("1", "2", "3", "R", "H", "E"), listOf(listOf("0", "1", "0", "1", "3", "0"), listOf("0", "0", "0", "0", "1", "1")))
        assertEquals(listOf("1", "2", "R", "H", "E"), GameDetailUiPolicy.trimUnstartedPeriods(mlb, GameState.LIVE, livePeriod = 2).columns)
        assertEquals(b.columns, GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.LIVE, livePeriod = 9).columns)
    }

    @Test fun `rows carry the team short name from the header sides`() {
        val h = TeamsHeader(Side("Washington Capitals", short = "WSH"), Side("Florida Panthers"))
        assertEquals("WSH", GameDetailUiPolicy.teamTag("home", h))
        assertEquals("Florida Panthers", GameDetailUiPolicy.teamTag("away", h), "no short name: the full name")
        assertNull(GameDetailUiPolicy.teamTag(null, h))
        assertNull(GameDetailUiPolicy.teamTag("home", EventHeader("Grand Prix")))
    }


    @Test fun `momentum chart points are time-ordered normalised and capped`() {
        assertNull(GameDetailUiPolicy.chartPoints(emptyList()))
        assertNull(GameDetailUiPolicy.chartPoints(listOf(com.nuvio.app.features.sportsdetail.api.WinPoint(1, 50))), "one point is no line")
        val pts = GameDetailUiPolicy.chartPoints(listOf(
            com.nuvio.app.features.sportsdetail.api.WinPoint(3, 80), com.nuvio.app.features.sportsdetail.api.WinPoint(1, 50), com.nuvio.app.features.sportsdetail.api.WinPoint(2, 100),
        ))!!
        assertEquals(listOf(0.5f, 1.0f, 0.8f), pts, "sorted by time, percent -> 0..1")
        val many = (1L..500L).map { com.nuvio.app.features.sportsdetail.api.WinPoint(it, (it % 101).toInt()) }
        assertEquals(60, GameDetailUiPolicy.chartPoints(many)!!.size)
        assertEquals(0.01f, GameDetailUiPolicy.chartPoints(many)!!.first(), 0.0001f)
    }

    @Test fun `soccer chart is home's share of the non-draw mass`() {
        val h = listOf(com.nuvio.app.features.sportsdetail.api.WinPoint(1, 30), com.nuvio.app.features.sportsdetail.api.WinPoint(2, 45))
        assertEquals(listOf(0.4f, 0.6f), GameDetailUiPolicy.chartPoints(h, drawPercent = 25))
        assertEquals(listOf(0.3f, 0.45f), GameDetailUiPolicy.chartPoints(h, drawPercent = null))
        assertEquals(listOf(1.0f, 1.0f), GameDetailUiPolicy.chartPoints(h, drawPercent = 100), "a degenerate draw never divides by zero")
    }

    @Test fun `win chance caption is the attribution or the source name`() {
        assertEquals("via Polymarket", GameDetailUiPolicy.winChanceCaption(WinProbabilityBlock(60, source = "polymarket", attribution = "via Polymarket")))
        assertEquals("via ESPN", GameDetailUiPolicy.winChanceCaption(WinProbabilityBlock(60, source = "espn")))
        assertNull(GameDetailUiPolicy.winChanceCaption(WinProbabilityBlock(60, source = "")))
    }

    // ---- storylines + comeback watch -----------------------------------------------------------------------------------

    private fun pts(vararg p: Pair<Long, Int>) = p.map { com.nuvio.app.features.sportsdetail.api.WinPoint(it.first, it.second) }
    private val min = 60_000L

    @Test fun `comeback watch fires when the trailing team gained 15 points within the last 10 minutes`() {
        // home trails (40) and was at 22 eight minutes earlier: +18
        val c = GameDetailUiPolicy.comebackWatch(pts(0L to 30, 5 * min to 22, 13 * min to 40), drawPercent = null)
        assertEquals("home", c?.side); assertEquals(18, c?.rise)
        // away trails (home 70 -> away 30) and was at 12 six minutes earlier (home 88): +18 for away
        val a = GameDetailUiPolicy.comebackWatch(pts(0L to 80, 4 * min to 88, 10 * min to 70), drawPercent = null)
        assertEquals("away", a?.side); assertEquals(18, a?.rise)
    }

    @Test fun `no comeback watch for a small move an old move or a team that is no longer trailing`() {
        assertNull(GameDetailUiPolicy.comebackWatch(pts(0L to 30, 8 * min to 40), null), "+10 is not enough")
        assertNull(GameDetailUiPolicy.comebackWatch(pts(0L to 20, 5 * min to 20, 20 * min to 38), null), "the rise happened more than 10 minutes before the end")
        assertNull(GameDetailUiPolicy.comebackWatch(pts(0L to 30, 8 * min to 55), null), "it now leads: that is not a comeback to watch")
        assertNull(GameDetailUiPolicy.comebackWatch(pts(0L to 40), null))
        assertNull(GameDetailUiPolicy.comebackWatch(emptyList(), null))
    }

    @Test fun `comeback watch uses home's non-draw share for soccer`() {
        // 20% home with a 25% draw is a 26.7% share; 36% is 48%: a 21-point rise
        val c = GameDetailUiPolicy.comebackWatch(pts(0L to 20, 6 * min to 36), drawPercent = 25)
        assertEquals("home", c?.side)
        assertEquals(21, c?.rise)
    }

    @Test fun `comeback text names the team`() {
        val h = TeamsHeader(Side("Denver Nuggets", short = "DEN"), Side("Indiana Pacers", short = "IND"))
        assertEquals("Comeback watch · DEN +18 in 10 min", GameDetailUiPolicy.comebackText(GameDetailUiPolicy.Comeback("home", 18), h))
        assertNull(GameDetailUiPolicy.comebackText(GameDetailUiPolicy.Comeback("home", 18), EventHeader("Grand Prix")))
    }

    @Test fun `storylines show as label dot percent - three on phone one on TV`() {
        val s = listOf(
            com.nuvio.app.features.sportsdetail.api.Storyline("a", "Goes to overtime", 23, null),
            com.nuvio.app.features.sportsdetail.api.Storyline("b", "  ", 50, null),
            com.nuvio.app.features.sportsdetail.api.Storyline("c", "Both teams score", 55, null),
            com.nuvio.app.features.sportsdetail.api.Storyline("d", "Leading at half-time: DEN", 70, "home"),
            com.nuvio.app.features.sportsdetail.api.Storyline("e", "Fifth", 1, null),
        )
        assertEquals(listOf("Goes to overtime · 23%", "Both teams score · 55%", "Leading at half-time: DEN · 70%"), GameDetailUiPolicy.storylineLines(s))
        assertEquals(listOf("Goes to overtime · 23%"), GameDetailUiPolicy.storylineLines(s, max = 1))
        assertEquals(emptyList(), GameDetailUiPolicy.storylineLines(emptyList()))
    }
}
