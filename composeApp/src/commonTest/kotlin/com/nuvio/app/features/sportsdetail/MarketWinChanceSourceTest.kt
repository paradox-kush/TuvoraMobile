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
            "history":[{"t":1793990000000,"home":50},{"t":1794000000000,"home":62},{"t":"bad","home":1}],"extra":1}"""
        val r = assertIs<SourceResult.Ok<MarketWinChance>>(src(HttpReply(200, body, null)).second.winChance(req, true)).value
        assertEquals(62, r.home); assertEquals(33, r.away); assertEquals(5, r.draw)
        assertEquals("polymarket", r.source); assertEquals("via Polymarket", r.attribution)
        assertEquals(listOf(WinPoint(1793990000000, 50), WinPoint(1794000000000, 62)), r.history, "a malformed point is dropped, the rest kept")
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
