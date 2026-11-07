package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.internal.HttpReply
import com.nuvio.app.features.sportsdetail.internal.MarketRequest
import com.nuvio.app.features.sportsdetail.internal.MarketWinChance
import com.nuvio.app.features.sportsdetail.internal.MarketWinChanceHttpSource
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import com.nuvio.app.features.sportsdetail.internal.SportsDetailEndpoints
import com.nuvio.app.features.sportsdetail.internal.SportsHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The backend's `market-winchance` contract: devices never call Polymarket, only this endpoint. */
class MarketWinChanceSourceTest {
    private class Scripted(private val reply: HttpReply) : SportsHttp {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpReply { urls += url; return reply }
    }

    private val req = MarketRequest("tsdb:2601", "4380", "Man Utd & Co", "Atlético FC", "2026-11-07T17:30:00Z")
    private val url = "https://backend.invalid/functions/v1/market-winchance"

    private fun src(reply: HttpReply) = Scripted(reply).let { it to MarketWinChanceHttpSource(it, url) }

    @Test fun `request carries the game and is percent-encoded`() = runBlocking<Unit> {
        val (http, s) = src(HttpReply(200, """{"gameId":"tsdb:2601","home":62,"away":38,"asOf":1794000000000,"source":"polymarket","attribution":"via Polymarket"}""", null))
        s.winChance(req, history = true)
        assertEquals(
            "$url?game_id=tsdb%3A2601&league_id=4380&home=Man%20Utd%20%26%20Co&away=Atl%C3%A9tico%20FC&starts_at=2026-11-07T17%3A30%3A00Z&history=1",
            http.urls.single(),
        )
        s.winChance(req, history = false)
        assertTrue(!http.urls.last().contains("history"))
    }

    @Test fun `a normal answer becomes a win chance`() = runBlocking<Unit> {
        val body = """{"gameId":"tsdb:2601","home":62,"away":33,"draw":5,"asOf":1794000000000,"source":"polymarket","attribution":"via Polymarket",
            "series":[{"t":1793990000000,"home":50},{"t":1794000000000,"home":62},{"t":"bad","home":1}],"extra":1}"""
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, body, null)).second.winChance(req, true)).value
        assertEquals(62, r.home); assertEquals(33, r.away); assertEquals(5, r.draw)
        assertEquals("polymarket", r.source); assertEquals("via Polymarket", r.attribution)
        assertEquals(listOf(WinPoint(1793990000000, 50), WinPoint(1794000000000, 62)), r.history, "a malformed point is dropped, the rest kept")
    }

    @Test fun `the backend's history=1 answer carries the current price AND the series - three-way for soccer`() = runBlocking<Unit> {
        val body = """{"gameId":"tsdb:2494097","home":45,"away":30,"draw":25,"asOf":1794054600000,
            "series":[{"t":1794050000000,"home":40},{"t":1794052000000,"home":43},{"t":1794054600000,"home":45}],"source":"polymarket","attribution":"via Polymarket"}"""
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, body, null)).second.winChance(req, true)).value
        assertEquals(Triple(45, 25, 30), Triple(r.home, r.draw, r.away))
        assertEquals(1794054600000L, r.asOfMs)
        assertEquals(listOf(40, 43, 45), r.history.map { it.home })
    }

    @Test fun `storylines are read - at most three - with the side and clamped percentages`() = runBlocking<Unit> {
        val body = """{"home":55,"away":45,"asOf":1,"source":"polymarket","storylines":[
            {"key":"ot","label":"Goes to overtime","yes":23},
            {"key":"half","label":"Leading at half-time: DEN","yes":70,"side":"home"},
            {"key":"btts","label":"Both teams score","yes":155,"side":"nope"},
            {"key":"x","label":"A fourth","yes":10},{"label":"","yes":5},{"key":"bad","label":"No number"}]}"""
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, body, null)).second.winChance(req, false)).value
        assertEquals(listOf("ot", "half", "btts"), r.storylines.map { it.key }, "max 3, blanks and number-less rows dropped first")
        assertEquals(listOf(23, 70, 100), r.storylines.map { it.yes })
        assertEquals(listOf(null, "home", null), r.storylines.map { it.side })
    }

    @Test fun `no storylines key is an empty list`() = runBlocking<Unit> {
        assertEquals(emptyList(), assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, """{"home":50,"away":50,"source":"polymarket"}""", null)).second.winChance(req, false)).value.storylines)
    }

    @Test fun `series points carry an optional draw`() = runBlocking<Unit> {
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, """{"home":40,"away":35,"draw":25,"source":"polymarket","series":[{"t":1,"home":53.4,"draw":24.6},{"t":2,"home":54}]}""", null)).second.winChance(req, true)).value
        assertEquals(listOf(WinPoint(1, 53, 25), WinPoint(2, 54, null)), r.history)
    }

    @Test fun `a series named history from an older backend is still read`() = runBlocking<Unit> {
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, """{"home":50,"away":50,"history":[{"t":1,"home":50},{"t":2,"home":51}],"source":"polymarket"}""", null)).second.winChance(req, true)).value
        assertEquals(2, r.history.size)
    }

    @Test fun `percentages are clamped and a missing home is a failure`() = runBlocking<Unit> {
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, """{"home":140,"away":-5,"source":"polymarket"}""", null)).second.winChance(req, false)).value
        assertEquals(100, r.home); assertEquals(0, r.away)
        assertEquals(SourceResult.Failed(false), src(HttpReply(200, """{"away":40,"source":"polymarket"}""", null)).second.winChance(req, false))
    }

    @Test fun `disabled 404 and failures are told apart`() = runBlocking<Unit> {
        assertEquals(SourceResult.Disabled, src(HttpReply(200, """{"disabled":true}""", null)).second.winChance(req, false))
        assertEquals(SourceResult.Empty, src(HttpReply(404, null, null)).second.winChance(req, false))
        assertEquals(SourceResult.Failed(false), src(HttpReply(502, "x", null)).second.winChance(req, false))
        assertEquals(SourceResult.Failed(false), src(HttpReply(200, "nope", null)).second.winChance(req, false))
    }

    @Test fun `the endpoint URL derives from the same single base`() {
        assertEquals("https://abc.supabase.co/functions/v1/market-winchance", SportsDetailEndpoints.fromSupabaseUrl("https://abc.supabase.co/").marketWinChanceUrl)
    }
}
