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

    @Test fun `the chart plots the series as given - the server sends the per-point non-draw share`() {
        val h = listOf(com.nuvio.app.features.sportsdetail.api.WinPoint(1, 30), com.nuvio.app.features.sportsdetail.api.WinPoint(2, 45))
        assertEquals(listOf(0.3f, 0.45f), GameDetailUiPolicy.chartPoints(h), "no division by today's draw")
    }

    @Test fun `win chance caption is the attribution or the source name`() {
        assertEquals("via Polymarket", GameDetailUiPolicy.winChanceCaption(WinProbabilityBlock(60, source = "polymarket", attribution = "via Polymarket")))
        assertEquals("via ESPN", GameDetailUiPolicy.winChanceCaption(WinProbabilityBlock(60, source = "espn")))
        assertNull(GameDetailUiPolicy.winChanceCaption(WinProbabilityBlock(60, source = "")))
    }

    // ---- storylines + comeback watch -----------------------------------------------------------------------------------

    private fun pts(vararg p: Pair<Long, Int>) = p.map { com.nuvio.app.features.sportsdetail.api.WinPoint(it.first, it.second) }
    private val min = 60_000L

    private val live = GameState.LIVE
    private fun cw(history: List<com.nuvio.app.features.sportsdetail.api.WinPoint>, headlineHome: Int, state: GameState = live, asOf: Long? = history.lastOrNull()?.t, headlineAway: Int = 100 - headlineHome) =
        GameDetailUiPolicy.comebackWatch(history, headlineHome, headlineAway, state, asOf)

    @Test fun `comeback watch fires when the trailing team gained 15 points from the start of the last 10 minutes`() {
        // home trails (headline 40); the window (last 10 min) starts at 22 and ends at 40: +18
        val c = cw(pts(0L to 30, 4 * min to 22, 8 * min to 30, 13 * min to 40), headlineHome = 40)
        assertEquals("home", c?.side); assertEquals(18, c?.rise)
        // away trails (headline home 70): home fell 88 -> 70 across the window
        val a = cw(pts(0L to 60, 1 * min to 88, 5 * min to 80, 11 * min to 70), headlineHome = 70)
        assertEquals("away", a?.side); assertEquals(18, a?.rise)
    }

    @Test fun `a spike that came back down is not a comeback`() {
        assertNull(cw(pts(0L to 40, 3 * min to 22, 6 * min to 30, 9 * min to 40), headlineHome = 40), "40 -> 22 -> 40 ended where it began")
    }

    @Test fun `comeback watch needs a live game a fresh series and enough points`() {
        val rising = pts(0L to 22, 4 * min to 30, 8 * min to 40)
        assertEquals("home", cw(rising, 40)?.side)
        assertNull(cw(rising, 40, state = GameState.UPCOMING), "pre-game")
        assertNull(cw(rising, 40, state = GameState.FINAL), "post-game")
        assertNull(cw(rising, 40, asOf = 8 * min + 5 * min), "the series' last point is 5 min older than the server's asOf: stale")
        assertEquals("home", cw(rising, 40, asOf = 8 * min + 90_000)?.side, "within ~2 min of asOf is fresh")
        assertNull(cw(pts(0L to 22, 8 * min to 40), 40), "two points in the window are noise")
        assertNull(cw(rising, 40, asOf = null), "no asOf, no trust")
        assertNull(cw(emptyList(), 40))
    }

    @Test fun `trailing comes from the headline price not the series end`() {
        // the series ended at 45 (still < 50) but the headline says home leads 52: nobody is mounting a comeback
        assertNull(cw(pts(0L to 22, 4 * min to 33, 8 * min to 45), headlineHome = 52))
        assertNull(cw(pts(0L to 22, 4 * min to 33, 8 * min to 45), headlineHome = 50))
    }

    @Test fun `soccer - trailing is home vs away and not vs 50 and the rise is on the non-draw share`() {
        // 30 / 25 draw / 45: home trails although 45 would look like "the leader" against 50 in a 3-way market
        val homeTrails = cw(pts(0L to 20, 4 * min to 28, 8 * min to 38), headlineHome = 30, headlineAway = 45)
        assertEquals("home", homeTrails?.side); assertEquals(18, homeTrails?.rise)
        // 45 / 25 draw / 30: AWAY trails (30 < 45); the old '< 50' rule named home. Home's non-draw share fell 60 -> 44
        val awayTrails = cw(pts(0L to 60, 4 * min to 52, 8 * min to 44), headlineHome = 45, headlineAway = 30)
        assertEquals("away", awayTrails?.side); assertEquals(16, awayTrails?.rise)
        assertNull(cw(pts(0L to 60, 4 * min to 52, 8 * min to 44), headlineHome = 35, headlineAway = 35), "level headline: nobody trails")
    }

    @Test fun `comeback is measured on the series as given`() {
        // soccer: points are already non-draw shares
        val c = cw(pts(0L to 20, 4 * min to 28, 8 * min to 36), headlineHome = 36)
        assertEquals(16, c?.rise)
    }

    @Test fun `comeback text names the team`() {
        val h = TeamsHeader(Side("Denver Nuggets", short = "DEN"), Side("Indiana Pacers", short = "IND"))
        assertEquals("Comeback watch · DEN +18 in 10 min", GameDetailUiPolicy.comebackText(GameDetailUiPolicy.Comeback("home", 18), h))
        assertNull(GameDetailUiPolicy.comebackText(GameDetailUiPolicy.Comeback("home", 18), EventHeader("Grand Prix")))
    }

    private val denHeader = TeamsHeader(Side("Denver Nuggets", short = "DEN"), Side("Indiana Pacers", short = "IND"))

    @Test fun `storylines show as label dot percent with the team tag when they are about one side`() {
        val s = listOf(
            com.nuvio.app.features.sportsdetail.api.Storyline("a", "Goes to overtime", 23, null),
            com.nuvio.app.features.sportsdetail.api.Storyline("b", "  ", 50, null),
            com.nuvio.app.features.sportsdetail.api.Storyline("c", "Both teams to score", 55, null),
            com.nuvio.app.features.sportsdetail.api.Storyline("d", "Leading at half-time", 62, "home"),
            com.nuvio.app.features.sportsdetail.api.Storyline("e", "Fifth", 1, null),
        )
        assertEquals(listOf("Goes to overtime · 23%", "Both teams to score · 55%", "Leading at half-time: DEN · 62%"), GameDetailUiPolicy.storylineLines(s, denHeader))
        assertEquals(listOf("Goes to overtime · 23%"), GameDetailUiPolicy.storylineLines(s, denHeader, max = 1))
        assertEquals(emptyList(), GameDetailUiPolicy.storylineLines(emptyList(), denHeader))
        assertEquals("Leading at half-time: IND · 62%", GameDetailUiPolicy.storylineLines(listOf(com.nuvio.app.features.sportsdetail.api.Storyline("d", "Leading at half-time", 62, "away")), denHeader).single())
        assertEquals("Leading at half-time: DEN · 62%", GameDetailUiPolicy.storylineLines(listOf(com.nuvio.app.features.sportsdetail.api.Storyline("d", "Leading at half-time: DEN", 62, "home")), denHeader).single(), "a label that already names the team is not doubled")
    }

    private fun story(key: String, label: String) = com.nuvio.app.features.sportsdetail.api.Storyline(key, label, 40, null)

    @Test fun `storylines show only before and during the game`() {
        val all = listOf(story("both_teams_to_score", "Both teams to score"))
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(all, GameState.UPCOMING, null, "Soccer").size)
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(all, GameState.LIVE, LiveInfo(period = 1), "Soccer").size)
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(all, GameState.FINAL, null, "Soccer").size)
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(all, GameState.POSTPONED, null, "Soccer").size)
    }

    @Test fun `first-half storylines vanish once half-time has passed`() {
        val l = listOf(story("first_half_moneyline", "Leading at half-time"))
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 1), "Soccer").size)
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 2), "Soccer").size, "soccer: second half started")
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 2), "Basketball").size, "basketball: Q2 is still the first half")
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 3), "Basketball").size)
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(l, GameState.UPCOMING, null, "Basketball").size)
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, null, "Basketball").size, "period unknown: keep")
    }

    @Test fun `the overtime storyline vanishes once overtime has started`() {
        val l = listOf(story("basketball_game_overtime", "Goes to overtime"))
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 4), "Basketball").size)
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 5), "Basketball").size)
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(periodLabel = "OT", period = 4), "Basketball").size)
        assertEquals(1, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 3), "Ice Hockey").size, "hockey regulation is 3 periods")
        assertEquals(0, GameDetailUiPolicy.visibleStorylines(l, GameState.LIVE, LiveInfo(period = 4), "Ice Hockey").size)
    }

    @Test fun `the win chance card has its own staleness`() {
        val now = 10_000_000L
        assertNull(GameDetailUiPolicy.blockStaleLabel(now - 60_000, now), "a server-cached minute is normal")
        assertEquals("Updated 3 min ago", GameDetailUiPolicy.blockStaleLabel(now - 190_000, now))
        assertNull(GameDetailUiPolicy.blockStaleLabel(null, now))
    }

    @Test fun `the card chip is only for a live market headline`() {
        val now = 10_000_000L
        val stale = now - 190_000
        val market = WinProbabilityBlock(60, source = "polymarket", asOfMs = stale)
        val espnWithStaleExtras = WinProbabilityBlock(60, source = "espn", extrasAttribution = "via Polymarket", asOfMs = stale)
        assertEquals("Updated 3 min ago", GameDetailUiPolicy.winChanceStaleLabel(market, GameState.LIVE, now))
        assertNull(GameDetailUiPolicy.winChanceStaleLabel(market, GameState.FINAL, now), "never on a settled game")
        assertNull(GameDetailUiPolicy.winChanceStaleLabel(market, GameState.UPCOMING, now))
        assertNull(GameDetailUiPolicy.winChanceStaleLabel(espnWithStaleExtras, GameState.LIVE, now), "an ESPN headline is not marked stale because only the market extras are")
    }
}
