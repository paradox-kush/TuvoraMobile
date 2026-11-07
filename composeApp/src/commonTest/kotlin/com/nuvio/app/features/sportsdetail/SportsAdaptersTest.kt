package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.EspnHttpSource
import com.nuvio.app.features.sportsdetail.internal.HttpReply
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import com.nuvio.app.features.sportsdetail.internal.SportsDetailEndpoints
import com.nuvio.app.features.sportsdetail.internal.SportsHttp
import com.nuvio.app.features.sportsdetail.internal.TheSportsDbHttpSource
import com.nuvio.app.features.sportsdetail.internal.TsdbLookup
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun `TheSportsDB source reads the event row and lookups`() = runBlocking<Unit> {
        val tvJson = SportsFixtures.tsdb_tv
        val http = ScriptedHttp { url ->
            when {
                url.endsWith("/lookup/event/2494097") -> HttpReply(200, """{"lookup":[{"idEvent":"2494097","strHomeTeam":"Leeds United"}]}""", null)
                url.endsWith("/lookup/event_tv/2494097") -> HttpReply(200, tvJson, null)
                url.endsWith("/lookup/event_stats/2494097") -> HttpReply(200, """{"lookup":null}""", null)
                else -> HttpReply(404, null, null)
            }
        }
        val tsdb = TheSportsDbHttpSource(http, baseUrl = "https://backend.invalid/sports/tsdb")
        val ev = tsdb.event("2494097")
        assertEquals("Leeds United", assertIs<SourceResult.Ok<kotlinx.serialization.json.JsonObject>>(ev).value["strHomeTeam"]?.jsonPrimitive?.content)
        assertTrue(tsdb.lookup("2494097", TsdbLookup.TV) is SourceResult.Ok<*>)
        assertEquals(SourceResult.Empty, tsdb.lookup("2494097", TsdbLookup.STATS)) // {"lookup":null}
        assertEquals(SourceResult.Empty, tsdb.lookup("2494097", TsdbLookup.TIMELINE)) // 404
        assertTrue(http.urls.all { it.startsWith("https://backend.invalid/sports/tsdb/lookup/") })
    }

    @Test fun `endpoint constants are single-sourced`() {
        assertTrue(SportsDetailEndpoints.MAPPING_URL.startsWith("https://"))
        assertTrue(SportsDetailEndpoints.TSDB_BASE.startsWith("https://"))
    }
}
