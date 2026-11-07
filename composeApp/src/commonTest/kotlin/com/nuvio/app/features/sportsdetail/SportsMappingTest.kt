package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.MappingStorage
import com.nuvio.app.features.sportsdetail.api.StoredMapping
import com.nuvio.app.features.sportsdetail.internal.AdoptResult
import com.nuvio.app.features.sportsdetail.internal.BundledSportsMapping
import com.nuvio.app.features.sportsdetail.internal.HttpReply
import com.nuvio.app.features.sportsdetail.internal.RemoteMappingSync
import com.nuvio.app.features.sportsdetail.internal.SportsHttp
import com.nuvio.app.features.sportsdetail.internal.SportsMappingParser
import com.nuvio.app.features.sportsdetail.internal.SportsMappingStore
import com.nuvio.app.features.sportsdetail.internal.SportsMappingValidator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsMappingTest {
    private class MemStorage(var stored: StoredMapping? = null) : MappingStorage {
        override fun load() = stored
        override fun save(value: StoredMapping) { stored = value }
    }

    @Test fun `bundled default parses and validates`() {
        val m = bundledMapping()
        assertEquals(1, m.schemaVersion)
        assertEquals("2026-11-07.1", m.version)
        assertEquals(9, m.profiles.size)
        assertEquals("soccer/eng.1", m.leagueByTsdb("4328")?.espn)
        assertEquals(15_000L, m.espn.liveMs)
        assertEquals(8_000L, m.espn.deadlineMs)
        assertNull(SportsMappingValidator.validate(m))
    }

    @Test fun `unknown fields and unknown profiles are tolerated`() {
        val r = SportsMappingParser.parse(editedMappingJson("\"killSwitch\"", "\"futureThing\": {\"x\":1}, \"killSwitch\""))
        assertIs<SportsMappingParser.ParseResult.Ok>(r)
    }

    @Test fun `garbage is invalid not an exception`() {
        assertIs<SportsMappingParser.ParseResult.Invalid>(SportsMappingParser.parse("not json"))
        assertIs<SportsMappingParser.ParseResult.Invalid>(SportsMappingParser.parse("[]"))
        assertIs<SportsMappingParser.ParseResult.Invalid>(SportsMappingParser.parse("{}"))
    }

    @Test fun `validator rejects an unsupported schemaVersion`() {
        val m = mappingOf(editedMappingJson("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        assertNotNull(SportsMappingValidator.validate(m))
        val zero = mappingOf(editedMappingJson("\"schemaVersion\": 1", "\"schemaVersion\": 0"))
        assertNotNull(SportsMappingValidator.validate(zero))
    }

    @Test fun `smoke parse of the bundled fixture rejects a mapping whose header paths are broken`() {
        val broken = mappingOf(editedMappingJson("\"home\": \"competitions[0].competitors[homeAway=home]\"", "\"home\": \"competitionz[0].competitors[homeAway=home]\""))
        val reason = SportsMappingValidator.validate(broken)
        assertNotNull(reason)
        assertTrue(reason.contains("smoke"), reason)
    }

    @Test fun `validator rejects a mapping without profiles or without the smoke profile`() {
        val noBasketball = mappingOf(editedMappingJson("\"basketball\": {", "\"basketballx\": {"))
        assertNotNull(SportsMappingValidator.validate(noBasketball))
    }

    @Test fun `store keeps the last good mapping when a candidate is invalid`() {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        val bad = editedMappingJson("\"schemaVersion\": 1", "\"schemaVersion\": 9")
        assertIs<AdoptResult.Rejected>(store.offer(bad, "e1"))
        assertEquals("2026-11-07.1", store.current.version)
        val good = editedMappingJson("\"version\": \"2026-11-07.1\"", "\"version\": \"2026-11-08.1\"")
        assertIs<AdoptResult.Adopted>(store.offer(good, "e2"))
        assertEquals("2026-11-08.1", store.current.version)
        assertIs<AdoptResult.Rejected>(store.offer("{ not json", "e3"))
        assertEquals("2026-11-08.1", store.current.version)
    }

    @Test fun `store restores a persisted mapping and falls back to bundled if it no longer validates`() {
        val storage = MemStorage()
        val first = SportsMappingStore(storage, bundledMapping())
        first.offer(editedMappingJson("\"version\": \"2026-11-07.1\"", "\"version\": \"2026-11-09.1\""), "etag-9")
        assertEquals("etag-9", storage.stored?.etag)
        assertEquals("2026-11-09.1", SportsMappingStore(storage, bundledMapping()).current.version)
        storage.stored = StoredMapping("{\"schemaVersion\":7}", "x")
        assertEquals("2026-11-07.1", SportsMappingStore(storage, bundledMapping()).current.version)
    }

    @Test fun `kill switches cover ESPN as a whole and a sport and a league`() {
        assertTrue(bundledMapping().espnEnabledFor(leagueTsdbId = "4387"))
        val all = mappingOf(editedMappingJson("\"espn\": false, \"sports\": []", "\"espn\": true, \"sports\": []"))
        assertFalse(all.espnEnabledFor("4387"))
        val sport = mappingOf(editedMappingJson("\"sports\": []", "\"sports\": [\"Basketball\", \"hockey\"]"))
        assertFalse(sport.espnEnabledFor("4387"))   // league sport "Basketball"
        assertFalse(sport.espnEnabledFor("4380"))   // profile "hockey" (case-insensitive, matches sport or profile)
        assertTrue(sport.espnEnabledFor("4391"))
        val league = mappingOf(editedMappingJson("\"leagues\": []", "\"leagues\": [\"4328\"]"))
        assertFalse(league.espnEnabledFor("4328"))
        assertTrue(league.espnEnabledFor("4387"))
        assertFalse(bundledMapping().espnEnabledFor("9999")) // unknown league has no ESPN path
        assertFalse(bundledMapping().espnEnabledFor("4460")) // cricket entry without an ESPN path yet
    }

    @Test fun `ESPN templates must be https on the ESPN host with their placeholders`() {
        fun reason(from: String, to: String) = SportsMappingValidator.validate(mappingOf(editedMappingJson(from, to)))
        assertNotNull(reason("https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard", "http://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard"))
        assertNotNull(reason("https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard", "https://evil.example/apis/site/v2/sports/{path}/scoreboard"))
        assertNotNull(reason("sports/{path}/scoreboard?dates={yyyymmdd}", "sports/{path}/scoreboard?dates=today"))
        assertNotNull(reason("sports/{path}/summary?event={id}", "sports/{path}/summary?event=1"))
    }

    @Test fun `chain overrides are validated`() {
        val withChain = { v: String -> mappingOf(editedMappingJson("\"eventOverrides\": {}", "\"eventOverrides\": {}, \"chain\": $v")) }
        assertNull(SportsMappingValidator.validate(withChain("{\"stats\": [\"espn\", \"tsdb\"]}")))
        assertNotNull(SportsMappingValidator.validate(withChain("{\"stats\": [\"bing\"]}")))
        assertNotNull(SportsMappingValidator.validate(withChain("{\"nonsense\": [\"espn\"]}")))
    }

    @Test fun `every profile is smoke-parsed - a broken hockey path is caught`() {
        val m = mappingOf(editedMappingJson(
            "\"periodLabel\": \"P\",\n      \"linescore\": { \"from\": \"scoreboard\", \"path\": \"competitions[0].competitors[*].linescores[*].displayValue\" }",
            "\"periodLabel\": \"P\",\n      \"linescore\": { \"from\": \"scoreboard\", \"path\": \"competitionz[0].competitors[*].linescores[*].displayValue\" }",
        ))
        val reason = SportsMappingValidator.validate(m)
        assertNotNull(reason)
        assertTrue(reason.contains("hockey"), reason)
    }

    @Test fun `a rejected ETag is not downloaded again and the rejection is reported by code only`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        val events = mutableListOf<Map<String, Any>>()
        val http = FakeHttp(mutableListOf(HttpReply(200, "{\"schemaVersion\": 99}", "\"bad\""), HttpReply(304, null, "\"bad\"")))
        val sync = RemoteMappingSync(store, http, url = "https://x.invalid/m", report = { events += it })
        assertIs<RemoteMappingSync.Outcome.Rejected>(sync.sync())
        assertEquals(1, events.size)
        assertEquals("rejected", events[0]["outcome"])
        assertFalse(events[0].values.any { it.toString().contains("99") || it.toString().contains("http") })
        assertIs<RemoteMappingSync.Outcome.NotModified>(sync.sync())
        assertEquals("\"bad\"", http.requests[1].second["If-None-Match"])
    }

    private class FakeHttp(val replies: MutableList<HttpReply>) : SportsHttp {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpReply {
            requests += url to headers
            return replies.removeAt(0)
        }
    }

    @Test fun `remote sync sends the stored ETag and treats 304 as unchanged`() = runBlocking<Unit> {
        val storage = MemStorage()
        val store = SportsMappingStore(storage, bundledMapping())
        val body = editedMappingJson("\"version\": \"2026-11-07.1\"", "\"version\": \"2026-12-01.1\"")
        val http = FakeHttp(mutableListOf(HttpReply(200, body, "\"v2\""), HttpReply(304, null, "\"v2\""), HttpReply(500, null, null)))
        val sync = RemoteMappingSync(store, http, url = "https://example.invalid/mapping.json")
        assertIs<RemoteMappingSync.Outcome.Updated>(sync.sync())
        assertEquals("2026-12-01.1", store.current.version)
        assertIs<RemoteMappingSync.Outcome.NotModified>(sync.sync())
        assertEquals("\"v2\"", http.requests[1].second["If-None-Match"])
        assertIs<RemoteMappingSync.Outcome.Failed>(sync.sync())
        assertEquals("2026-12-01.1", store.current.version, "a failed poll never loses the mapping")
        assertEquals("https://example.invalid/mapping.json", http.requests[0].first)
    }

    @Test fun `remote sync with an invalid body keeps the last good mapping and does not store its ETag`() = runBlocking<Unit> {
        val storage = MemStorage()
        val store = SportsMappingStore(storage, bundledMapping())
        val http = FakeHttp(mutableListOf(HttpReply(200, "{\"schemaVersion\": 99}", "\"bad\"")))
        val out = RemoteMappingSync(store, http, url = "u").sync()
        assertIs<RemoteMappingSync.Outcome.Rejected>(out)
        assertEquals("2026-11-07.1", store.current.version)
        assertNull(store.etag)
    }

    @Test fun `bundled JSON constant is the v1 mapping`() {
        assertTrue(BundledSportsMapping.JSON.contains("\"schemaVersion\": 1"))
    }
}
