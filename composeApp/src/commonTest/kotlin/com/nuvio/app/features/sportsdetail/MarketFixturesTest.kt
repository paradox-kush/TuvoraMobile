package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.HttpReply
import com.nuvio.app.features.sportsdetail.internal.MarketRequest
import com.nuvio.app.features.sportsdetail.internal.MarketWinChance
import com.nuvio.app.features.sportsdetail.internal.MarketWinChanceHttpSource
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import com.nuvio.app.features.sportsdetail.internal.SportsHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every response shape the backend's `market-winchance` function produces (research/sports-mapping/fixtures/market-winchance-*.json,
 * copied in by the generator), parsed by the real adapter and rendered by the real policy.
 */
class MarketFixturesTest {
    private val req = MarketRequest("tsdb:1", "4387", "Denver Nuggets", "Indiana Pacers", "2026-11-07T17:30:00Z")
    private val den = TeamsHeader(Side("Denver Nuggets", short = "DEN"), Side("Indiana Pacers", short = "IND"))

    private fun parse(body: String, status: Int = 200): SourceResult<MarketWinChance> = runBlocking {
        val http = object : SportsHttp {
            override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long) = HttpReply(status, body, null)
        }
        MarketWinChanceHttpSource(http, "https://b.invalid/market-winchance").winChance(req, history = true)
    }

    private fun ok(body: String) = assertIs<SourceResult.Ok<MarketWinChance>>(parse(body)).value

    @Test fun `price two-way`() {
        val r = ok(SportsFixtures.market_winchance_price_two_way)
        assertEquals(Triple(60, null, 40), Triple(r.home, r.draw, r.away))
        assertEquals("polymarket", r.source); assertEquals("via Polymarket", r.attribution)
        assertEquals(1794073800000L, r.asOfMs)
        assertEquals(emptyList(), r.storylines); assertEquals(emptyList(), r.history)
    }

    @Test fun `price three-way`() {
        val r = ok(SportsFixtures.market_winchance_price_three_way)
        assertEquals(Triple(16, 22, 62), Triple(r.home, r.draw, r.away))
    }

    @Test fun `history two-way carries the series`() {
        val r = ok(SportsFixtures.market_winchance_history_two_way)
        assertEquals(60, r.home)
        assertEquals(3, r.history.size)
        assertEquals(listOf(60, 60, 60).size, r.history.map { it.home }.size)
        assertEquals(1794000000000L, r.history.first().t)
    }

    @Test fun `history three-way carries per-point home and draw`() {
        val r = ok(SportsFixtures.market_winchance_history_three_way)
        assertEquals(Triple(16, 22, 62), Triple(r.home, r.draw, r.away))
        assertEquals(3, r.history.size)
        assertEquals(21, r.history.first().home, "21.2 rounds to 21: home is the per-point non-draw share, plotted as given")
        assertEquals(22, r.history.first().draw, "and each point carries its draw (21.6 rounds to 22)")
    }

    @Test fun `three-way storylines render`() {
        val r = ok(SportsFixtures.market_winchance_price_three_way_storylines)
        val lines = GameDetailUiPolicy.storylineLines(GameDetailUiPolicy.visibleStorylines(r.storylines, GameState.LIVE, LiveInfo(period = 1), "Soccer"), den)
        assertEquals(listOf("Both teams to score · 58%"), lines)
    }

    @Test fun `two-way storylines render with the team tag and follow the game state`() {
        val r = ok(SportsFixtures.market_winchance_price_two_way_storylines)
        fun lines(state: GameState, live: LiveInfo?) = GameDetailUiPolicy.storylineLines(GameDetailUiPolicy.visibleStorylines(r.storylines, state, live, "Basketball"), den)
        assertEquals(listOf("Leading at half-time: DEN · 62%", "Goes to overtime · 8%"), lines(GameState.UPCOMING, null))
        assertEquals(listOf("Leading at half-time: DEN · 62%", "Goes to overtime · 8%"), lines(GameState.LIVE, LiveInfo(period = 2)))
        assertEquals(listOf("Goes to overtime · 8%"), lines(GameState.LIVE, LiveInfo(period = 3)), "half-time has passed")
        assertEquals(emptyList(), lines(GameState.LIVE, LiveInfo(period = 5)).filter { it.contains("overtime") }, "overtime has started")
        assertEquals(emptyList(), lines(GameState.FINAL, null))
    }

    @Test fun `disabled and no-market`() {
        assertEquals(SourceResult.Disabled, parse(SportsFixtures.market_winchance_disabled))
        assertEquals(SourceResult.Empty, parse(SportsFixtures.market_winchance_no_market, status = 404))
        assertIs<SourceResult.Failed>(parse(SportsFixtures.market_winchance_no_market), "an error body with a 200 is a failure, never a value")
    }
}
