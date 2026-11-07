package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.MappingStorage
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.StoredMapping
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.internal.EspnSource
import com.nuvio.app.features.sportsdetail.internal.GameDetailSession
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import com.nuvio.app.features.sportsdetail.internal.SportsDetailTelemetry
import com.nuvio.app.features.sportsdetail.internal.SportsMappingStore
import com.nuvio.app.features.sportsdetail.internal.TheSportsDbHttpSource
import com.nuvio.app.features.sportsdetail.internal.TsdbLookup
import com.nuvio.app.features.sportsdetail.internal.TsdbSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameDetailSessionTest {
    // NBA: Hawks (home) v Wizards, tipped 2026-11-07 00:00Z-ish. The ESPN event date is read from the fixture.
    private val nbaEventDate = 1_794_009_600_000L // 2026-11-07T00:00:00Z

    private class FakeEspn(
        var scoreboard: SourceResult<JsonElement>,
        var summary: SourceResult<JsonElement>,
    ) : EspnSource {
        var scoreboardCalls = 0
        var summaryCalls = 0
        override suspend fun scoreboard(espnPath: String, yyyymmdd: String): SourceResult<JsonElement> { scoreboardCalls++; return scoreboard }
        override suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement> { summaryCalls++; return summary }
    }

    private class FakeTsdb(
        var event: SourceResult<JsonObject>,
        val lookups: MutableMap<TsdbLookup, SourceResult<JsonObject>> = mutableMapOf(),
    ) : TsdbSource {
        var eventCalls = 0
        val lookupCalls = mutableListOf<TsdbLookup>()
        override suspend fun event(idEvent: String): SourceResult<JsonObject> { eventCalls++; return event }
        override suspend fun lookup(idEvent: String, kind: TsdbLookup): SourceResult<JsonObject> {
            lookupCalls += kind
            return lookups[kind] ?: SourceResult.Empty
        }
    }

    private fun scoreboardOf(vararg events: String): SourceResult<JsonElement> =
        SourceResult.Ok(parse("""{"events":[${events.joinToString(",")}]}"""))

    private fun tsdbEvent(home: String = "Atlanta Hawks", away: String = "Washington Wizards", hs: String = "139", aws: String = "132", status: String = "FT") =
        SourceResult.Ok(parse("""{"idEvent":"2601","idLeague":"4387","strSport":"Basketball","strHomeTeam":"$home","strAwayTeam":"$away",
            "intHomeScore":"$hs","intAwayScore":"$aws","strStatus":"$status","strPostponed":"no","strTimestamp":"2026-11-07T00:00:00",
            "strVenue":"State Farm Arena","strHomeTeamBadge":"https://b/h.png","strAwayTeamBadge":"https://b/a.png","intRound":"0"}""").jsonObject)

    private val ref = GameRef(
        gameId = "tsdb:2601", leagueId = "4387", tsdbEventId = "2601", espnEventId = null,
        homeName = "Atlanta Hawks", awayName = "Washington Wizards", eventName = null, startsAtMs = 1_794_009_600_000L,
    )

    private val telemetry = mutableListOf<Map<String, Any>>()
    private var now = 1_794_100_000_000L

    private fun session(
        tsdb: TsdbSource?, espn: EspnSource?, mapping: SportsMappingStore = SportsMappingStore(MemStorage(), bundledMapping()), r: GameRef = ref,
    ) = GameDetailSession(r, { mapping.current }, tsdb, espn, nowMs = { now }, report = { telemetry += it })

    private class MemStorage : MappingStorage {
        var stored: StoredMapping? = null
        override fun load() = stored
        override fun save(value: StoredMapping) { stored = value }
    }

    private fun loaded(l: DetailLoad): GameDetail = assertIs<DetailLoad.Loaded>(l).detail

    @Test fun `NBA final assembles header from TheSportsDB and blocks from ESPN`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent()), espn).tick())
        val teams = assertIs<TeamsHeader>(d.header)
        assertEquals("Atlanta Hawks", teams.home.name)
        assertEquals("139", teams.home.score)
        assertEquals(GameState.FINAL, d.state)
        assertEquals("4387", d.leagueId)
        assertEquals("State Farm Arena", d.venue)
        assertEquals("espn", d.sources["linescore"])
        assertEquals("espn", d.sources["stats"])
        assertTrue(d.blocks.any { it is TimelineBlock })
        assertTrue(d.blocks.any { it is LinescoreBlock })
        assertTrue(d.blocks.none { it is WinProbabilityBlock || it is SituationBlock }, "live-only blocks hidden on a final game")
        assertNotNull(d.asOf["espn"])
        assertNotNull(d.asOf["tsdb"])
    }

    @Test fun `a mislinked ESPN event can never change the score in the header`() = runBlocking<Unit> {
        val wrongScores = SportsFixtures.espn_nba_event.replace("\"score\":\"139\"", "\"score\":\"1\"")
        val espn = FakeEspn(scoreboardOf(wrongScores), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent()), espn).tick())
        assertEquals("139", assertIs<TeamsHeader>(d.header).home.score)
    }

    @Test fun `TheSportsDB blocks win the chain and a TSDB miss is negative-cached per game`() = runBlocking<Unit> {
        val tsdb = FakeTsdb(tsdbEvent(), mutableMapOf(
            TsdbLookup.TV to SourceResult.Ok(parse(SportsFixtures.tsdb_tv).jsonObject),
        ))
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val s = session(tsdb, espn)
        val d = loaded(s.tick())
        val bc = d.blocks.filterIsInstance<BroadcastBlock>().single()
        assertEquals("tsdb", bc.source)
        assertTrue(bc.byRegion.containsKey("United Kingdom"))
        assertEquals("tsdb", d.sources["broadcast"])
        assertEquals("espn", d.sources["stats"], "TSDB had no stats for basketball -> falls through to ESPN")
        repeat(3) { s.tick() }
        assertEquals(1, tsdb.lookupCalls.count { it == TsdbLookup.STATS }, "probed once, then remembered")
        assertEquals(1, tsdb.lookupCalls.count { it == TsdbLookup.TIMELINE })
    }

    @Test fun `ESPN failing never renders as empty and is retried`() = runBlocking<Unit> {
        val espn = FakeEspn(SourceResult.Failed(timedOut = true), SourceResult.Failed(timedOut = false))
        val s = session(FakeTsdb(tsdbEvent()), espn)
        val d = loaded(s.tick())
        assertTrue(d.blocks.none { it is LinescoreBlock || it is StatsBlock })
        assertIs<TeamsHeader>(d.header)
        espn.scoreboard = scoreboardOf(SportsFixtures.espn_nba_event)
        espn.summary = SourceResult.Ok(parse(SportsFixtures.espn_nba_summary))
        val again = loaded(s.tick())
        assertTrue(again.blocks.any { it is LinescoreBlock }, "failure was not negative-cached")
        assertEquals(2, espn.scoreboardCalls)
    }

    @Test fun `both sources failing is Failed not Empty`() = runBlocking<Unit> {
        val r = session(FakeTsdb(SourceResult.Failed(timedOut = true)), FakeEspn(SourceResult.Failed(false), SourceResult.Failed(false))).tick()
        assertEquals(DetailLoad.Failed(timedOut = true), r)
    }

    @Test fun `TheSportsDB has no such event and ESPN cannot match is Empty`() = runBlocking<Unit> {
        val r = session(FakeTsdb(SourceResult.Empty), FakeEspn(scoreboardOf(), SourceResult.Empty)).tick()
        assertEquals(DetailLoad.Empty, r)
    }

    @Test fun `no ESPN match is remembered so the scoreboard is not re-scanned every tick`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_epl_event), SourceResult.Empty)
        val s = session(FakeTsdb(tsdbEvent()), espn)
        loaded(s.tick())
        val afterFirst = espn.scoreboardCalls // up to two dates when kickoff is near midnight UTC
        assertTrue(afterFirst in 1..2)
        repeat(3) { loaded(s.tick()) }
        assertEquals(afterFirst, espn.scoreboardCalls)
        assertEquals(0, espn.summaryCalls)
    }

    @Test fun `ESPN kill switch means ESPN is never called`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        store.offer(editedMappingJson("\"espn\": false, \"sports\": []", "\"espn\": true, \"sports\": []"), null)
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent()), espn, store).tick())
        assertEquals(0, espn.scoreboardCalls + espn.summaryCalls)
        assertTrue(d.blocks.none { it is LinescoreBlock })
    }

    @Test fun `a live-tick fetches only the event and not the summary and and reuses the cached summary`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val tsdb = FakeTsdb(tsdbEvent())
        val s = session(tsdb, espn)
        s.tick()
        val summaryBefore = espn.summaryCalls
        val d = loaded(s.tick(fetchEvent = true, fetchSummary = false))
        assertEquals(summaryBefore, espn.summaryCalls)
        assertTrue(d.blocks.any { it is StatsBlock }, "stats still shown from the cached summary")
        assertEquals(2, tsdb.eventCalls)
        val idle = loaded(s.tick(fetchEvent = false, fetchSummary = false))
        assertEquals(2, tsdb.eventCalls)
        assertTrue(idle.blocks.isNotEmpty())
    }

    @Test fun `live game shows situation and win probability and the live clock`() = runBlocking<Unit> {
        val liveEvent = SportsFixtures.espn_nba_event
            .replace("\"state\":\"post\"", "\"state\":\"in\"")
        val espn = FakeEspn(scoreboardOf(liveEvent), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent(status = "Q3")), espn).tick())
        assertEquals(GameState.LIVE, d.state)
        assertTrue(d.blocks.any { it is WinProbabilityBlock })
    }

    @Test fun `upcoming game hides score blocks`() = runBlocking<Unit> {
        val upcoming = SportsFixtures.espn_nba_event.replace("\"state\":\"post\"", "\"state\":\"pre\"")
        val espn = FakeEspn(scoreboardOf(upcoming), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent(status = "NS", hs = "", aws = "")), espn).tick())
        assertEquals(GameState.UPCOMING, d.state)
        assertTrue(d.blocks.none { it is LinescoreBlock || it is StatsBlock || it is TimelineBlock })
    }

    @Test fun `event override pins the ESPN event regardless of names`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        store.offer(editedMappingJson("\"eventOverrides\": {}", "\"eventOverrides\": {\"tsdb:2601\": \"401909287\"}"), null)
        val misnamed = tsdbEvent(home = "ATL", away = "WAS")
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(misnamed), espn, store).tick())
        assertTrue(d.blocks.any { it is LinescoreBlock })
    }

    @Test fun `a mapping edit that repairs a broken path shows the block on the next tick`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        val broken = editedMappingJson("\"path\": \"boxscore.teams[*].statistics[*]\", \"label\": \"label\", \"value\": \"displayValue\",\n        \"allow\": [\"Field Goal %\"",
            "\"path\": \"boxscore.teamz[*].statistics[*]\", \"label\": \"label\", \"value\": \"displayValue\",\n        \"allow\": [\"Field Goal %\"")
            .replace("\"2026-11-07.1\"", "\"2026-11-07.2\"")
        assertIs<com.nuvio.app.features.sportsdetail.internal.AdoptResult.Adopted>(store.offer(broken, "b"))
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val s = session(FakeTsdb(tsdbEvent()), espn, store)
        assertTrue(loaded(s.tick()).blocks.none { it is StatsBlock })
        store.offer(broken.replace("boxscore.teamz[*]", "boxscore.teams[*]").replace("\"2026-11-07.2\"", "\"2026-11-07.3\""), "c")
        assertTrue(loaded(s.tick(fetchEvent = false, fetchSummary = false)).blocks.any { it is StatsBlock }, "negative cache is per mapping version")
    }

    @Test fun `telemetry reports sport block source outcome and duration once and never urls or teams`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val s = session(FakeTsdb(tsdbEvent()), espn)
        s.tick()
        val first = telemetry.size
        assertTrue(first > 0)
        assertTrue(telemetry.any { it["block"] == "linescore" && it["source"] == "espn" && it["outcome"] == "loaded" && it["sport"] == "basketball" })
        assertTrue(telemetry.any { it["block"] == "stats" && it["source"] == "tsdb" && it["outcome"] == "empty" })
        telemetry.forEach { e ->
            assertEquals(setOf("sport", "block", "source", "outcome", "duration_ms"), e.keys)
            assertFalse(e.values.any { it.toString().contains("http", true) || it.toString().contains("Hawks") })
        }
        s.tick()
        assertEquals(first, telemetry.size, "steady state is silent")
    }

    @Test fun `telemetry event name and property builder`() {
        assertEquals("sports_detail", SportsDetailTelemetry.EVENT)
        val p = SportsDetailTelemetry.properties("hockey", "stats", "espn", "timeout", 8000)
        assertEquals(mapOf<String, Any>("sport" to "hockey", "block" to "stats", "source" to "espn", "outcome" to "timeout", "duration_ms" to 8000L), p)
    }

    @Test fun `ESPN-primary game builds its header from ESPN`() = runBlocking<Unit> {
        val r = ref.copy(gameId = "espn:401909287", tsdbEventId = null, espnEventId = "401909287")
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(null, espn, r = r).tick())
        assertEquals("Atlanta Hawks", assertIs<TeamsHeader>(d.header).home.name)
        assertEquals("espn", d.sources["header"])
    }
}
