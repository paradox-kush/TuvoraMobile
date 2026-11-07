package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.EspnHttpSource
import com.nuvio.app.features.sportsdetail.internal.HttpReply
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import com.nuvio.app.features.sportsdetail.internal.SportsDetailEndpoints
import com.nuvio.app.features.sportsdetail.internal.SportsHttp
import com.nuvio.app.features.sportsdetail.internal.GameDetailBackendSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SportsAdaptersTest {
    private class ScriptedHttp(private val handler: suspend (String) -> HttpReply) : SportsHttp {
        val urls = mutableListOf<String>()
        val deadlines = mutableListOf<Long>()
        override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpReply {
            urls += url; deadlines += deadlineMs
            return handler(url)
        }
    }

    private val mapping = bundledMapping()

    @Test fun `ESPN scoreboard and summary URLs come from the mapping templates`() = runBlocking<Unit> {
        val http = ScriptedHttp { HttpReply(200, "{\"events\":[]}", null) }
        val espn = EspnHttpSource(http) { mapping }
        espn.scoreboard("basketball/nba", "20261107")
        espn.summary("basketball/nba", "401909287")
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=20261107", http.urls[0])
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/basketball/nba/summary?event=401909287", http.urls[1])
        assertEquals(listOf(8_000L, 8_000L), http.deadlines)
    }

    @Test fun `ESPN outcomes - ok and 404 empty and 500 failed and bad json failed`() = runBlocking<Unit> {
        val replies = ArrayDeque(listOf(HttpReply(200, "{\"a\":1}", null), HttpReply(404, null, null), HttpReply(500, "x", null), HttpReply(200, "<html>", null), HttpReply(200, "", null)))
        val espn = EspnHttpSource(ScriptedHttp { replies.removeFirst() }) { mapping }
        assertIs<SourceResult.Ok<*>>(espn.summary("p", "1"))
        assertIs<SourceResult.Empty>(espn.summary("p", "1"))
        assertEquals(SourceResult.Failed(timedOut = false), espn.summary("p", "1"))
        assertEquals(SourceResult.Failed(timedOut = false), espn.summary("p", "1"))
        assertIs<SourceResult.Failed>(espn.summary("p", "1")) // empty body is not "no data"
    }

    @Test fun `a stalled provider ends at the per-call deadline as a timed-out failure never empty`() = runBlocking<Unit> {
        val espn = EspnHttpSource(ScriptedHttp { delay(10_000); HttpReply(200, "{}", null) }, deadlineOverrideMs = 60L) { mapping }
        assertEquals(SourceResult.Failed(timedOut = true), espn.scoreboard("p", "20261107"))
    }

    @Test fun `a thrown transport error is a failure not an exception`() = runBlocking<Unit> {
        val espn = EspnHttpSource(ScriptedHttp { throw IllegalStateException("boom https://secret.example/x") }) { mapping }
        assertEquals(SourceResult.Failed(timedOut = false), espn.summary("p", "1"))
    }

    private val backendBody = """{"gameId":"tsdb:2494097","sport":"Soccer","leagueId":"4328","leagueName":"EPL","round":10,"startsAt":"2026-11-07T12:30:00Z",
        "state":"final","header":{"kind":"teams","home":{"name":"Leeds United","score":"1"},"away":{"name":"Tottenham Hotspur","score":"0"}},
        "blocks":[{"type":"broadcast","source":"tsdb","regions":[{"country":"United Kingdom","channels":["TNT Sports"]}]},{"type":"hologram","source":"x"}],
        "sources":{"broadcast":"tsdb"},"asOf":{"tsdb":1},"mappingVersion":"v"}"""

    @Test fun `game-detail backend call builds the URL and decodes the normalised detail`() = runBlocking<Unit> {
        val http = ScriptedHttp { HttpReply(200, backendBody, null) }
        val src = GameDetailBackendSource(http, "https://backend.invalid/functions/v1/game-detail")
        val r = src.detail("2494097", "4328", setOf("stats", "timeline"))
        assertEquals("https://backend.invalid/functions/v1/game-detail?event_id=2494097&league_id=4328&blocks=stats,timeline", http.urls.single())
        val d = assertIs<SourceResult.Ok<com.nuvio.app.features.sportsdetail.api.GameDetail>>(r).value
        assertEquals("Leeds United", assertIs<com.nuvio.app.features.sportsdetail.api.TeamsHeader>(d.header).home.name)
        assertEquals("10", d.round)
        assertEquals(listOf("TNT Sports"), assertIs<com.nuvio.app.features.sportsdetail.api.BroadcastBlock>(d.blocks.single()).byRegion["United Kingdom"])
    }

    @Test fun `game-detail disabled 404 400 502 and garbage`() = runBlocking<Unit> {
        val replies = ArrayDeque(listOf(
            HttpReply(200, """{"gameId":"tsdb:1","disabled":true,"blocks":[]}""", null), HttpReply(404, null, null),
            HttpReply(400, null, null), HttpReply(502, "x", null), HttpReply(200, """{"nope":1}""", null),
        ))
        val src = GameDetailBackendSource(ScriptedHttp { replies.removeFirst() }, "https://b.invalid/g")
        assertEquals(SourceResult.Disabled, src.detail("1", null, null))
        assertEquals(SourceResult.Empty, src.detail("1", null, null))
        assertEquals(SourceResult.Empty, src.detail("1", null, null))
        assertEquals(SourceResult.Failed(timedOut = false), src.detail("1", null, null))
        assertEquals(SourceResult.Failed(timedOut = false), src.detail("1", null, null))
    }

    @Test fun `endpoints derive both function URLs from one base`() {
        val e = SportsDetailEndpoints.fromSupabaseUrl("https://abc.supabase.co/")
        assertEquals("https://abc.supabase.co/functions/v1/game-detail", e.gameDetailUrl)
        assertEquals("https://abc.supabase.co/functions/v1/sports-mapping", e.mappingUrl)
    }
}
