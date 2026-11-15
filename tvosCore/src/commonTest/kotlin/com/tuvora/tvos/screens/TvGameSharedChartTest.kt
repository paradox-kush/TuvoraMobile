package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Apple TV draws core's ONE chart rule (WinChanceSeries): the leader's line and the reference line, same as Mobile. */
class TvGameSharedChartTest {
    private fun card(w: WinProbabilityBlock, sport: String = "Ice Hockey"): TvWinCard {
        val d = GameDetail(
            gameId = "g", sport = sport, leagueId = "1", leagueName = "L", state = GameState.LIVE,
            header = TeamsHeader(Side("Home Club", short = "HOM"), Side("Away Club", short = "AWY")), blocks = listOf(w),
        )
        return TvGameScreenModel.from(d, 1_000_000L, false).winCard!!
    }

    @Test fun `an away leader's line is the away share and ends at the big number`() {
        val c = card(WinProbabilityBlock(homePercent = 35, history = listOf(WinPoint(1, 30), WinPoint(2, 35))))
        assertEquals("away", c.leaderSide)
        assertEquals(listOf(0.7f, 0.65f), c.chart, "not home's 0.30 / 0.35")
        assertEquals(0.5f, c.reference)
    }

    @Test fun `a three-way card plots the full chance against an even third`() {
        val c = card(
            WinProbabilityBlock(homePercent = 45, drawPercent = 30, awayPercent = 25, history = listOf(WinPoint(1, 50, draw = 30), WinPoint(2, 64, draw = 30))),
            sport = "Soccer",
        )
        assertEquals("home", c.leaderSide)
        assertEquals(1f / 3f, c.reference, 0.0001f)
        assertEquals(0.35f, c.chart[0], 0.0001f)
        assertEquals(0.448f, c.chart[1], 0.0001f)
        assertTrue(c.chart.last() > c.reference)
    }

    @Test fun `the chart keeps up to 120 points`() {
        val many = (1L..500L).map { WinPoint(it, (it % 101).toInt()) }
        assertEquals(120, card(WinProbabilityBlock(homePercent = 60, history = many)).chart.size)
    }
}
