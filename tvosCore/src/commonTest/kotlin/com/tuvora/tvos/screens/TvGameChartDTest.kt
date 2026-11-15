package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TvGameChartDTest {
    private val now = 10_000_000L
    private val dal = Side("Dallas Stars", short = "DAL", color = "006847", score = "2")
    private val buf = Side("Buffalo Sabres", short = "BUF", color = "003087", score = "1")
    private val t0 = 1_700_000_000_000L

    /** A 10-point series, one point per minute, home share rising from 40 to 85. */
    private val series = (0..9).map { WinPoint(t = t0 + it * 60_000L, home = 40 + it * 5) }

    private fun goal(id: String, minute: Int, side: String = "home", kind: String = "goal", text: String = "Goal") =
        TimelineItem(time = "$minute:00", period = "1", side = side, text = text, kind = kind, id = id, wallclockMs = t0 + minute * 60_000L)

    private fun start(n: Int, minute: Int) = TimelineItem(time = "0:00", period = "$n", side = null, text = "Start of period $n", kind = "periodStart", id = "ps$n", wallclockMs = t0 + minute * 60_000L)

    private fun game(sport: String, chartItems: List<TimelineItem>, switches: Set<String> = setOf("chartMarkers"), history: List<WinPoint> = series,
                     vararg extra: GameBlock) =
        GameDetail(gameId = "espn:d", sport = sport, leagueId = "1", leagueName = "L", state = GameState.LIVE, header = TeamsHeader(dal, buf), uiSwitches = switches,
            blocks = listOf<GameBlock>(WinProbabilityBlock(homePercent = 85, source = "polymarket", history = history, asOfMs = now), PlaysBlock(chartItems.takeLast(60), chartItems = chartItems)) + extra.toList())

    private fun model(d: GameDetail, prev: GameDetail? = null) = TvGameScreenModel.from(d, now, false, prev, false)

    @Test fun `a goal becomes a marker on the line in the scoring team colour`() {
        val c = model(game("Ice Hockey", listOf(goal("g1", 5)))).winCard
        val m = c?.markers?.single()
        assertNotNull(m)
        assertEquals("G", m.label)
        assertEquals("g1", m.key)
        assertEquals("home", m.side)
        assertNotNull(m.colorHex)
        assertTrue(m.x in 0f..1f)
        assertEquals(0.65f, m.y, "y is the drawn series value at that moment (40 + 5 x 5 = 65 %)")
    }

    @Test fun `the label sits above the line from 50 percent and below under it`() {
        val high = model(game("Ice Hockey", listOf(goal("g1", 8)))).winCard?.markers?.single()   // 80 %
        val low = model(game("Ice Hockey", listOf(goal("g2", 1)))).winCard?.markers?.single()    // 45 %
        assertTrue(high?.labelAbove ?: false)
        assertFalse(low?.labelAbove ?: true)
    }

    @Test fun `no markers or dividers when the chartMarkers switch is off`() {
        val c = model(game("Ice Hockey", listOf(goal("g1", 5), start(2, 4)), switches = emptySet())).winCard
        assertEquals(emptyList(), c?.markers)
        assertEquals(emptyList(), c?.dividers)
    }

    @Test fun `a play with no series point near it gets no marker`() {
        val far = goal("g9", 30)   // 20 minutes after the last series point
        assertEquals(emptyList(), model(game("Ice Hockey", listOf(far))).winCard?.markers)
    }

    @Test fun `basketball never gets markers`() {
        assertEquals(emptyList(), model(game("Basketball", listOf(goal("b1", 5)))).winCard?.markers)
    }

    @Test fun `TV keeps at most the newest 5 markers`() {
        val many = (1..9).map { goal("g$it", it) }
        val markers = model(game("Ice Hockey", many)).winCard!!.markers
        assertEquals(5, markers.size)
        assertEquals("g9", markers.maxByOrNull { it.x }?.key, "the newest survive")
    }

    @Test fun `period starts become dividers with the sport's own labels`() {
        val d = model(game("Ice Hockey", listOf(start(1, 0), start(2, 4), start(3, 7)))).winCard?.dividers.orEmpty()
        assertEquals(listOf("P2", "P3"), d.map { it.label })
        assertTrue(d[0].x < d[1].x)
    }

    @Test fun `a market less game has no chart so no markers`() {
        val c = model(game("Ice Hockey", listOf(goal("g1", 5)), history = emptyList())).winCard
        assertEquals(emptyList(), c?.chart)
        assertEquals(emptyList(), c?.markers)
    }

    @Test fun `a new marker and a changed stat are moments`() {
        val a = game("Ice Hockey", listOf(goal("g1", 3)), extra = arrayOf(StatsBlock(listOf(StatRow("Shots", "10", "12")))))
        val b = game("Ice Hockey", listOf(goal("g1", 3), goal("g2", 6)), extra = arrayOf(StatsBlock(listOf(StatRow("Shots", "14", "12")))))
        val moments = model(b, a).moments
        assertEquals("G", moments.single { it.kind == TvMomentKind.MARKER_ADDED }.text)
        val stat = moments.single { it.kind == TvMomentKind.STAT_CHANGE }
        assertEquals("Shots", stat.text)
        assertTrue(stat.leaderChanged, "away led 10-12 and home leads 14-12")
        assertEquals(emptyList(), model(b, b).moments)
    }

    @Test fun `dividers take a little of the left column budget`() {
        val without = TvLeftColumnFit.fit(stripDp = 78, scorerLines = 2, hasLine = true, hasChart = true, hasLastPlay = true)
        val with = TvLeftColumnFit.fit(stripDp = 78, scorerLines = 2, hasLine = true, hasChart = true, hasLastPlay = true, hasDividers = true)
        assertTrue(without.showChart)
        assertFalse(with.showChart, "the divider label row tips this case over the budget")
    }
}
