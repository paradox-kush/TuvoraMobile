package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.ChartMarker
import com.nuvio.app.features.sportsdetail.internal.ChartMarkers
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.WinChanceSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ONE series/axis rule for the win-chance chart, consumed by Mobile, Desktop, TV and Apple TV (review 2026-11-15). */
class GameScreenChartSeriesTest {
    private fun two(home: Int, history: List<WinPoint> = emptyList()) = WinProbabilityBlock(homePercent = home, history = history)
    private fun three(home: Int, draw: Int, away: Int, history: List<WinPoint> = emptyList()) =
        WinProbabilityBlock(homePercent = home, drawPercent = draw, awayPercent = away, history = history)

    @Test fun `the drawn series is capped at 120 points`() {
        val many = (1L..500L).map { WinPoint(it, (it % 101).toInt()) }
        assertEquals(120, GameDetailUiPolicy.chartSeries(many)!!.size)
    }

    @Test fun `two-way - the line is the leaders share and the reference is 50 percent`() {
        val w = two(35, listOf(WinPoint(1, 30), WinPoint(2, 35)))
        val spec = WinChanceSeries.specOf(w)
        assertEquals("away", spec.leaderSide)
        assertFalse(spec.threeWay)
        assertEquals(0.5f, spec.reference)
        assertEquals(listOf(0.7f, 0.65f), WinChanceSeries.plot(w.history, spec))
    }

    @Test fun `three-way - the line is the leaders FULL chance so it ends at the big number`() {
        val w = three(45, 30, 25, listOf(WinPoint(1, 50, draw = 30), WinPoint(2, 64, draw = 30)))
        val spec = WinChanceSeries.specOf(w)
        assertEquals("home", spec.leaderSide)
        assertTrue(spec.threeWay)
        val p = WinChanceSeries.plot(w.history, spec)
        assertEquals(0.35f, p[0], 0.0001f)
        assertEquals(0.448f, p[1], 0.0001f)
    }

    @Test fun `three-way - the reference is an even three-way split not 50 percent so a 45 percent leader does not read as losing`() {
        val w = three(45, 30, 25, listOf(WinPoint(1, 64, draw = 30), WinPoint(2, 64, draw = 30)))
        val spec = WinChanceSeries.specOf(w)
        assertEquals(1f / 3f, spec.reference, 0.0001f)
        assertTrue(WinChanceSeries.plot(w.history, spec).last() > spec.reference, "a 45% leader is above the reference line")
    }

    @Test fun `three-way - a point without a draw uses the last known draw then the first known then the headline`() {
        val w = three(40, 20, 40, listOf(WinPoint(1, 50), WinPoint(2, 50, draw = 30), WinPoint(3, 50), WinPoint(4, 50, draw = 10), WinPoint(5, 50)))
        val d = WinChanceSeries.withDrawFilled(w.history, WinChanceSeries.specOf(w)).map { it.draw }
        assertEquals(listOf(30, 30, 30, 10, 10), d, "leading gap takes the first known draw; gaps carry the previous one")
        val none = three(40, 20, 40, listOf(WinPoint(1, 50), WinPoint(2, 50)))
        assertEquals(listOf(20, 20), WinChanceSeries.withDrawFilled(none.history, WinChanceSeries.specOf(none)).map { it.draw }, "no point has a draw: the headline's")
    }

    @Test fun `a draw that appears only in the history makes the chart three-way`() {
        val w = two(50, listOf(WinPoint(1, 50, draw = 25), WinPoint(2, 50, draw = 25)))
        assertTrue(WinChanceSeries.specOf(w).threeWay)
    }

    @Test fun `two-way ignores a stray draw of zero and never divides by it`() {
        val w = WinProbabilityBlock(homePercent = 60, drawPercent = 0, history = listOf(WinPoint(1, 60, draw = 0), WinPoint(2, 62, draw = 0)))
        val spec = WinChanceSeries.specOf(w)
        assertFalse(spec.threeWay)
        assertEquals(listOf(0.6f, 0.62f), WinChanceSeries.plot(w.history, spec))
    }

    @Test fun `a tie leads home like the headline model`() {
        assertEquals("home", WinChanceSeries.specOf(two(50)).leaderSide)
    }

    @Test fun `markers sit on the plotted line and flip with the leader`() {
        val t0 = 1_000_000L
        val h = (0 until 20).map { WinPoint(t0 + it * 30_000L, 30) }
        val w = two(30, h)
        val play = TimelineItem(time = "", id = "g", text = "Goal", kind = "goal", wallclockMs = t0 + 5 * 30_000L)
        val m = ChartMarkers.markers(h, listOf(play), "Ice Hockey", spec = WinChanceSeries.specOf(w)).single()
        assertEquals(0.7f, m.y, 0.0001f)
        val home = ChartMarkers.markers(h, listOf(play), "Ice Hockey").single()
        assertEquals(0.3f, home.y, 0.0001f, "the default spec is the two-way home line")
    }

    @Test fun `marker label side follows the reference of its own chart`() {
        assertTrue(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.40f, "G", "home", reference = 1f / 3f)))
        assertFalse(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.30f, "G", "home", reference = 1f / 3f)))
    }

    @Test fun `short series and empty history give no plot`() {
        val spec = WinChanceSeries.specOf(two(50))
        assertNull(WinChanceSeries.chart(listOf(WinPoint(1, 50)), spec))
        assertNull(WinChanceSeries.chart(emptyList(), spec))
    }

    @Test fun `chart returns the drawn series values capped at 120`() {
        val h = (1L..400L).map { WinPoint(it, 40) }
        val c = WinChanceSeries.chart(h, WinChanceSeries.specOf(two(40, h)))!!
        assertEquals(120, c.size)
        assertEquals(0.6f, c.first(), 0.0001f)
    }
}
