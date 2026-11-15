package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.Side
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
import com.nuvio.app.features.sportsdetail.internal.blockTypeOf
import com.nuvio.app.features.sportsdetail.internal.TsdbSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
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

    private class FakeTsdb(var answer: SourceResult<GameDetail>) : TsdbSource {
        val requests = mutableListOf<Set<String>?>()
        override suspend fun detail(eventId: String, leagueId: String?, blocks: Set<String>?): SourceResult<GameDetail> {
            requests += blocks
            return answer
        }
    }

    private fun scoreboardOf(vararg events: String): SourceResult<JsonElement> =
        SourceResult.Ok(parse("""{"events":[${events.joinToString(",")}]}"""))

    private fun tsdbEvent(
        home: String = "Atlanta Hawks", away: String = "Washington Wizards", hs: String? = "139", aws: String? = "132",
        state: GameState = GameState.FINAL, blocks: List<GameBlock> = emptyList(),
    ) = SourceResult.Ok(
        GameDetail(
            gameId = "tsdb:2601", sport = "Basketball", leagueId = "4387", leagueName = "NBA", venue = "State Farm Arena",
            startsAt = "2026-11-07T00:00:00Z", state = state, header = TeamsHeader(Side(home, score = hs, badgeUrl = "https://b/h.png"), Side(away, score = aws)),
            blocks = blocks, sources = blocks.associate { (blockTypeOf(it)?.id ?: "?") to "tsdb" },
        ),
    )

    private val ref = GameRef(
        gameId = "tsdb:2601", leagueId = "4387", tsdbEventId = "2601", espnEventId = null,
        homeName = "Atlanta Hawks", awayName = "Washington Wizards", eventName = null, startsAtMs = 1_794_009_600_000L,
    )

    private val telemetry = mutableListOf<Map<String, Any>>()
    private var now = 1_794_100_000_000L

    private fun session(
        tsdb: TsdbSource?, espn: EspnSource?, mapping: SportsMappingStore = SportsMappingStore(MemStorage(), bundledMapping()), r: GameRef = ref,
        market: com.nuvio.app.features.sportsdetail.internal.MarketWinChanceSource? = null,
    ) = GameDetailSession(r, { mapping.current }, tsdb, espn, nowMs = { now }, report = { telemetry += it }, market = market)

    private class MemStorage : MappingStorage {
        var stored: StoredMapping? = null
        override fun load() = stored
        override fun save(value: StoredMapping) { stored = value }
    }

    private fun loaded(l: DetailLoad): GameDetail = assertIs<DetailLoad.Loaded>(l).detail

    /** A mapping without `sources.espn.event` (an old remote mapping): the live tick is the summary, exactly as before the game-screen redesign. */
    private fun legacyStore() = SportsMappingStore(MemStorage(), mappingOf(editedMappingJson("\"event\": \"https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}\",", "")))

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

    @Test fun `a stale backend score yields to the fresh ESPN score when the team names agree`() = runBlocking<Unit> {
        // backend lags: 1-2 and still LIVE; ESPN (same two teams) says 139-132, final.
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent(hs = "1", aws = "2", state = GameState.LIVE)), espn).tick())
        val h = assertIs<TeamsHeader>(d.header)
        assertEquals("139", h.home.score)
        assertEquals("132", h.away.score)
        assertEquals("Atlanta Hawks", h.home.name, "names, badges and venue stay the primary's")
        assertEquals("https://b/h.png", h.home.badgeUrl)
        assertEquals("ATL", h.home.short, "a missing short name is filled from ESPN so rows can carry a team tag")
        assertEquals(GameState.FINAL, d.state, "state comes from the fresher ESPN too")
        assertEquals("Final", d.live?.detail)
        assertEquals("tsdb", d.sources["header"])
        assertEquals("State Farm Arena", d.venue)
    }

    @Test fun `when the ESPN event is for different teams the primary header stays untouched`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val pinned = ref.copy(espnEventId = "401909287")      // a bad pin: ESPN's Hawks game for a Celtics game
        val d = loaded(session(FakeTsdb(tsdbEvent(home = "Boston Celtics", away = "Miami Heat", hs = "50", aws = "40", state = GameState.LIVE)), espn, r = pinned).tick())
        val h = assertIs<TeamsHeader>(d.header)
        assertEquals("50", h.home.score)
        assertEquals("40", h.away.score)
        assertEquals(GameState.LIVE, d.state)
        assertNull(h.home.short)
    }

    @Test fun `each tick leaves a debug line with the cumulative request counts - no URLs`() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val tsdb = FakeTsdb(tsdbEvent(state = GameState.LIVE))
        val legacy = legacyStore()
        val s = GameDetailSession(ref, { legacy.current }, tsdb, espn, nowMs = { now }, report = {}, debug = { lines += it })
        s.tick()
        s.tick(fetchEvent = true, fetchSummary = false)
        s.tick(fetchEvent = true, fetchSummary = false)
        s.tick(fetchEvent = true, fetchSummary = true)
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("backend=1") && lines[0].contains("summary=1") && lines[0].contains("scoreboard="), lines[0])
        assertTrue(lines[2].contains("backend=1") && lines[2].contains("summary=3"), "live ticks hit the summary only: ${lines[2]}")
        assertTrue(lines[3].contains("backend=2") && lines[3].contains("summary=4"), lines[3])
        assertTrue(lines.none { it.contains("http") })
    }

    @Test fun `TheSportsDB blocks win the chain and ESPN fills the rest from one backend call per slow tick`() = runBlocking<Unit> {
        val hl = com.nuvio.app.features.sportsdetail.api.HighlightsBlock("https://youtu.be/x", source = "tsdb")
        val tsdb = FakeTsdb(tsdbEvent(blocks = listOf(hl)))
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val s = session(tsdb, espn)
        val d = loaded(s.tick())
        assertEquals("tsdb", d.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.HighlightsBlock>().single().source)
        assertEquals("tsdb", d.sources["highlights"])
        assertEquals("espn", d.sources["stats"], "the backend had no stats for basketball -> ESPN")
        val ask = setOf("stats", "timeline", "highlights", "next")
        assertEquals(listOf<Set<String>?>(ask), tsdb.requests, "only the blocks the profile shows and the backend may supply are requested - never broadcast")
        s.tick(fetchEvent = true, fetchSummary = false)
        assertEquals(1, tsdb.requests.size, "a live tick never calls the backend")
        assertTrue(loaded(s.tick(fetchEvent = false, fetchSummary = false)).blocks.any { it is com.nuvio.app.features.sportsdetail.api.HighlightsBlock }, "slow blocks survive fast ticks")
        s.tick(fetchEvent = true, fetchSummary = true)
        assertEquals(listOf<Set<String>?>(ask, ask), tsdb.requests, "the slow cadence refreshes them")
    }

    @Test fun `a broadcast block from the backend or an old mapping is never shown`() = runBlocking<Unit> {
        val tv = com.nuvio.app.features.sportsdetail.api.BroadcastBlock(mapOf("UK" to listOf("TNT")), source = "tsdb")
        assertTrue(loaded(session(FakeTsdb(tsdbEvent(blocks = listOf(tv))), null).tick()).blocks.none { it is com.nuvio.app.features.sportsdetail.api.BroadcastBlock })
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        store.offer(editedMappingJson("\"blocks\": [\"header\", \"situation\", \"watch\", \"linescore\"", "\"blocks\": [\"header\", \"situation\", \"watch\", \"broadcast\", \"linescore\""), "x")
        val d = loaded(session(FakeTsdb(tsdbEvent(blocks = listOf(tv))), null, store).tick())
        assertTrue(d.blocks.none { it is com.nuvio.app.features.sportsdetail.api.BroadcastBlock })
        assertNull(d.sources["broadcast"])
    }

    @Test fun `backend kill switch falls back to an ESPN-primary game`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(SourceResult.Disabled), espn).tick())
        assertEquals("espn", d.sources["header"])
        assertEquals("Atlanta Hawks", assertIs<TeamsHeader>(d.header).home.name)
    }

    @Test fun `a pinned ESPN id missing from the scoreboard is looked up once then remembered`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_epl_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val s = session(FakeTsdb(tsdbEvent()), espn, r = ref.copy(espnEventId = "does-not-exist"))
        loaded(s.tick())
        val afterFirst = espn.scoreboardCalls
        assertTrue(afterFirst in 1..2)
        repeat(4) { loaded(s.tick()) }
        assertEquals(afterFirst, espn.scoreboardCalls, "a wrong id must not re-fetch whole-day scoreboards every tick")
        assertEquals(0, espn.summaryCalls)
    }

    @Test fun `leagues without a summary endpoint refresh the scoreboard only on the slow cadence`() = runBlocking<Unit> {
        val f1 = ref.copy(gameId = "tsdb:9", leagueId = "4370", tsdbEventId = "9", homeName = "MSC Cruises Sao Paulo Grand Prix", awayName = null, startsAtMs = 1_794_000_000_000L)
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_f1_event), SourceResult.Empty)
        val s = session(FakeTsdb(SourceResult.Empty), espn, r = f1)
        loaded(s.tick())
        val first = espn.scoreboardCalls
        repeat(3) { loaded(s.tick(fetchEvent = true, fetchSummary = false)) }
        assertEquals(first, espn.scoreboardCalls, "fast ticks must not re-read the whole-day scoreboard")
        loaded(s.tick(fetchEvent = true, fetchSummary = true))
        assertEquals(first + 1, espn.scoreboardCalls)
        assertEquals(0, espn.summaryCalls, "no summary endpoint for F1")
    }

    @Test fun `backend standings are shown - the default chain does not drop them`() = runBlocking<Unit> {
        val standings = com.nuvio.app.features.sportsdetail.api.StandingsBlock(listOf(com.nuvio.app.features.sportsdetail.api.StandingRow("1", "Arsenal", "10", "25")), source = "tsdb")
        val soccer = ref.copy(leagueId = "4328")
        val d = loaded(session(FakeTsdb(tsdbEvent(blocks = listOf(standings))), null, r = soccer).tick())
        assertTrue(d.blocks.any { it is com.nuvio.app.features.sportsdetail.api.StandingsBlock })
        assertEquals("tsdb", d.sources["standings"])
    }

    @Test fun `a failed or empty backend falls back to the ESPN header when ESPN resolved the event`() = runBlocking<Unit> {
        for (answer in listOf<SourceResult<GameDetail>>(SourceResult.Failed(true), SourceResult.Empty, SourceResult.Disabled)) {
            val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
            val d = loaded(session(FakeTsdb(answer), espn).tick())
            assertEquals("espn", d.sources["header"], "$answer")
            assertEquals("Atlanta Hawks", assertIs<TeamsHeader>(d.header).home.name)
        }
    }

    @Test fun `a persistently failing ESPN backs off about 30 s while the backend stays healthy`() = runBlocking<Unit> {
        val espn = FakeEspn(SourceResult.Failed(false), SourceResult.Failed(false))
        val s = session(FakeTsdb(tsdbEvent()), espn)
        repeat(5) { loaded(s.tick()) }
        assertEquals(1, espn.scoreboardCalls)
        now += 31_000
        loaded(s.tick())
        assertEquals(2, espn.scoreboardCalls)
    }

    @Test fun `the legacy tsdb-prefixed override key still works as a fallback`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        store.offer(editedMappingJson("\"eventOverrides\": {}", "\"eventOverrides\": {\"tsdb:2601\": \"401909287\"}"), null)
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        assertTrue(loaded(session(FakeTsdb(tsdbEvent(home = "ATL", away = "WAS")), espn, store).tick()).blocks.any { it is LinescoreBlock })
    }

    @Test fun `last-good backend blocks survive a refresh that omitted them`() = runBlocking<Unit> {
        val stats = StatsBlock(listOf(com.nuvio.app.features.sportsdetail.api.StatRow("Shots", "3", "2")), source = "tsdb")
        val tsdb = FakeTsdb(tsdbEvent(blocks = listOf(stats)))
        val s = session(tsdb, null, r = ref.copy(leagueId = "4328"))
        assertTrue(loaded(s.tick()).blocks.any { it is StatsBlock })
        tsdb.answer = tsdbEvent(blocks = emptyList())   // the backend's stats sub-call failed this time
        assertTrue(loaded(s.tick(fetchEvent = true, fetchSummary = true)).blocks.any { it is StatsBlock }, "stats must not vanish")
        tsdb.answer = tsdbEvent(blocks = listOf(StatsBlock(listOf(com.nuvio.app.features.sportsdetail.api.StatRow("Shots", "9", "9")), source = "tsdb")))
        val fresh = loaded(s.tick(fetchEvent = true, fetchSummary = true)).blocks.filterIsInstance<StatsBlock>().single()
        assertEquals("9", fresh.rows[0].home, "a block the backend does return is replaced")
    }

    @Test fun `ESPN failing never renders as empty and is retried`() = runBlocking<Unit> {
        val espn = FakeEspn(SourceResult.Failed(timedOut = true), SourceResult.Failed(timedOut = false))
        val s = session(FakeTsdb(tsdbEvent()), espn)
        val d = loaded(s.tick())
        assertTrue(d.blocks.none { it is LinescoreBlock || it is StatsBlock })
        assertIs<TeamsHeader>(d.header)
        espn.scoreboard = scoreboardOf(SportsFixtures.espn_nba_event)
        espn.summary = SourceResult.Ok(parse(SportsFixtures.espn_nba_summary))
        loaded(s.tick())
        assertEquals(1, espn.scoreboardCalls, "ESPN just failed: no retry inside the backoff window")
        now += 31_000
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
        store.offer(editedMappingJson("\"espn\": false", "\"espn\": true"), null)
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent()), espn, store).tick())
        assertEquals(0, espn.scoreboardCalls + espn.summaryCalls)
        assertTrue(d.blocks.none { it is LinescoreBlock })
    }

    @Test fun `a live tick requests only the summary of the resolved event - never the whole-day scoreboard`() = runBlocking<Unit> {
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val tsdb = FakeTsdb(tsdbEvent(state = GameState.LIVE))
        val s = session(tsdb, espn, legacyStore())   // pins the legacy pattern; the new one is in GameScreenRefreshTest
        s.tick()
        val scoreboards = espn.scoreboardCalls
        val summaries = espn.summaryCalls
        val backend = tsdb.requests.size
        repeat(3) { assertTrue(loaded(s.tick(fetchEvent = true, fetchSummary = false)).blocks.any { it is StatsBlock }) }
        assertEquals(backend, tsdb.requests.size, "a live tick is ESPN summary only - no backend call")
        assertEquals(scoreboards, espn.scoreboardCalls, "scoreboard is read once to resolve the event id")
        assertEquals(summaries + 3, espn.summaryCalls, "each live tick is exactly one summary request")
        loaded(s.tick(fetchEvent = false, fetchSummary = false))
        assertEquals(summaries + 3, espn.summaryCalls, "idle tick makes no request")
    }

    @Test fun `the live overlay brings the summary score into an ESPN-primary header`() = runBlocking<Unit> {
        val live = SportsFixtures.espn_nba_summary
            .replace("\"score\":\"139\"", "\"score\":\"77\"").replace("\"state\":\"post\"", "\"state\":\"in\"")
        val r = ref.copy(gameId = "espn:401909287", tsdbEventId = null, espnEventId = "401909287")
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(live)))
        val d = loaded(session(null, espn, r = r).tick())
        assertEquals("77", assertIs<TeamsHeader>(d.header).home.score)
        assertEquals(GameState.LIVE, d.state)
    }

    @Test fun `live game shows situation and win probability and the live clock`() = runBlocking<Unit> {
        val liveEvent = SportsFixtures.espn_nba_event
            .replace("\"state\":\"post\"", "\"state\":\"in\"")
        val liveSummary = SportsFixtures.espn_nba_summary.replace("\"state\":\"post\"", "\"state\":\"in\"")
        val espn = FakeEspn(scoreboardOf(liveEvent), SourceResult.Ok(parse(liveSummary)))
        val d = loaded(session(FakeTsdb(tsdbEvent(state = GameState.LIVE)), espn).tick())
        assertEquals(GameState.LIVE, d.state)
        assertTrue(d.blocks.any { it is WinProbabilityBlock })
    }

    @Test fun `upcoming game hides score blocks`() = runBlocking<Unit> {
        val upcoming = SportsFixtures.espn_nba_event.replace("\"state\":\"post\"", "\"state\":\"pre\"")
        val espn = FakeEspn(scoreboardOf(upcoming), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(tsdbEvent(state = GameState.UPCOMING, hs = null, aws = null)), espn).tick())
        assertEquals(GameState.UPCOMING, d.state)
        assertTrue(d.blocks.none { it is LinescoreBlock || it is StatsBlock || it is TimelineBlock })
    }

    @Test fun `event override pins the ESPN event regardless of names`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        store.offer(editedMappingJson("\"eventOverrides\": {}", "\"eventOverrides\": {\"2601\": \"401909287\"}"), null)
        val misnamed = tsdbEvent(home = "ATL", away = "WAS")
        val espn = FakeEspn(scoreboardOf(SportsFixtures.espn_nba_event), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)))
        val d = loaded(session(FakeTsdb(misnamed), espn, store).tick())
        assertTrue(d.blocks.any { it is LinescoreBlock })
    }

    @Test fun `a mapping edit that repairs a broken path shows the block on the next tick`() = runBlocking<Unit> {
        val store = SportsMappingStore(MemStorage(), bundledMapping())
        val broken = editedMappingJson("\"path\": \"boxscore.teams[*].statistics[*]\", \"label\": \"label\", \"value\": \"displayValue\",\n        \"allow\": [\"Field Goal %\"",
            "\"path\": \"boxscore.teamz[*].statistics[*]\", \"label\": \"label\", \"value\": \"displayValue\",\n        \"allow\": [\"Field Goal %\"")
            .replace("\"${bundledMapping().version}\"", "\"2026-11-07.2\"")
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

    // ---- Polymarket win chance (behind killSwitch.market) --------------------------------------------------------

    private class FakeMarket(var answer: SourceResult<com.nuvio.app.features.sportsdetail.internal.MarketWinChance>) : com.nuvio.app.features.sportsdetail.internal.MarketWinChanceSource {
        val requests = mutableListOf<Pair<com.nuvio.app.features.sportsdetail.internal.MarketRequest, Boolean>>()
        override suspend fun winChance(req: com.nuvio.app.features.sportsdetail.internal.MarketRequest, history: Boolean): SourceResult<com.nuvio.app.features.sportsdetail.internal.MarketWinChance> {
            requests += req to history
            return answer
        }
    }

    private fun mc(home: Int = 62, history: List<com.nuvio.app.features.sportsdetail.api.WinPoint> = emptyList(), draw: Int? = null, asOf: Long? = 1L,
        storylines: List<com.nuvio.app.features.sportsdetail.api.Storyline> = emptyList()) =
        SourceResult.Ok(com.nuvio.app.features.sportsdetail.internal.MarketWinChance(home, 100 - home - (draw ?: 0), draw, asOf, "polymarket", "via Polymarket", history, storylines))

    private fun marketOn(): SportsMappingStore =
        SportsMappingStore(MemStorage(), bundledMapping()).also { it.offer(editedMappingJson("\"market\": {\"all\": true", "\"market\": {\"all\": false"), null) }

    private val nhlRef get() = ref.copy(gameId = "tsdb:2701", leagueId = "4380", tsdbEventId = "2701", espnEventId = "401892659", homeName = "Buffalo Sabres", awayName = "Columbus Blue Jackets")

    private fun nhl(from: String, to: String): FakeEspn {
        val ev = SportsFixtures.espn_nhl_event.replace("\"state\":\"post\"", "\"state\":\"$to\"")
        val sm = SportsFixtures.espn_nhl_summary.replace("\"state\":\"post\"", "\"state\":\"$to\"")
        return FakeEspn(scoreboardOf(ev), SourceResult.Ok(parse(sm)))
    }

    private fun nhlTsdb(state: GameState) = FakeTsdb(tsdbEvent(home = "Buffalo Sabres", away = "Columbus Blue Jackets", state = state))

    private fun winBlock(d: GameDetail) = d.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock>().singleOrNull()

    @Test fun `ESPN stays the headline but the market is always asked for storylines and the chart`() = runBlocking<Unit> {
        val liveEvent = SportsFixtures.espn_nba_event.replace("\"state\":\"post\"", "\"state\":\"in\"")
        val liveSummary = SportsFixtures.espn_nba_summary.replace("\"state\":\"post\"", "\"state\":\"in\"")
        val hist = listOf(com.nuvio.app.features.sportsdetail.api.WinPoint(1L, 40), com.nuvio.app.features.sportsdetail.api.WinPoint(2L, 44))
        val stories = listOf(com.nuvio.app.features.sportsdetail.api.Storyline("ot", "Goes to overtime", 8, null))
        val market = FakeMarket(mc(44, hist, storylines = stories, asOf = 1_000L))
        val s = session(FakeTsdb(tsdbEvent(state = GameState.LIVE)), FakeEspn(scoreboardOf(liveEvent), SourceResult.Ok(parse(liveSummary))), marketOn(), market = market)
        val d = loaded(s.tick())
        val w = assertNotNull(winBlock(d))
        assertEquals("espn", w.source, "the headline is ESPN's")
        assertEquals("via ESPN", w.attribution)
        assertEquals(100, w.homePercent, "ESPN's number, not the market's 44")
        assertEquals(stories, w.storylines)
        assertEquals(hist, w.history, "ESPN has no series, so the chart is the market's")
        assertEquals("via Polymarket", w.extrasAttribution)
        assertEquals(1_000L, w.asOfMs, "the market block carries its own timestamp")
        assertEquals("espn", d.sources["winProbability"])
        assertEquals(1, market.requests.size)
        s.tick(fetchEvent = true, fetchSummary = false)
        assertEquals(1, market.requests.size, "the market rides the slow cadence only")
        s.tick(fetchEvent = true, fetchSummary = true)
        assertEquals(2, market.requests.size)
    }

    @Test fun `with the market off ESPN's win probability has no extras and the market is never asked`() = runBlocking<Unit> {
        val liveEvent = SportsFixtures.espn_nba_event.replace("\"state\":\"post\"", "\"state\":\"in\"")
        val liveSummary = SportsFixtures.espn_nba_summary.replace("\"state\":\"post\"", "\"state\":\"in\"")
        val market = FakeMarket(mc())
        val w = assertNotNull(winBlock(loaded(session(FakeTsdb(tsdbEvent(state = GameState.LIVE)), FakeEspn(scoreboardOf(liveEvent), SourceResult.Ok(parse(liveSummary))), market = market).tick())))
        assertEquals(emptyList(), w.storylines)
        assertNull(w.extrasAttribution)
        assertTrue(market.requests.isEmpty())
    }

    @Test fun `where ESPN has none the market fills the win chance with its attribution and history`() = runBlocking<Unit> {
        val hist = listOf(com.nuvio.app.features.sportsdetail.api.WinPoint(1L, 50), com.nuvio.app.features.sportsdetail.api.WinPoint(2L, 62))
        val market = FakeMarket(mc(62, hist))
        val d = loaded(session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef, market = market).tick())
        val w = assertNotNull(winBlock(d))
        assertEquals(62, w.homePercent)
        assertEquals("polymarket", w.source)
        assertEquals("via Polymarket", w.attribution)
        assertEquals(hist, w.history)
        assertEquals("polymarket", d.sources["winProbability"])
        assertEquals(1L, d.asOf["polymarket"], "the server's asOf, so a stale served value shows 'Updated Xm ago'")
        val (req, wantHistory) = market.requests.single()
        assertEquals("tsdb:2701", req.gameId); assertEquals("4380", req.leagueId)
        assertEquals("Buffalo Sabres", req.home); assertEquals("Columbus Blue Jackets", req.away)
        assertTrue(wantHistory)
        assertTrue(telemetry.any { it["block"] == "winProbability" && it["source"] == "polymarket" && it["outcome"] == "loaded" && it["sport"] == "hockey" })
    }

    @Test fun `a soccer game carries the honest three-way split and the server timestamp`() = runBlocking<Unit> {
        val market = FakeMarket(mc(45, draw = 25, asOf = 1_700_000_000_000L))
        val d = loaded(session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef, market = market).tick())
        val w = assertNotNull(winBlock(d))
        assertEquals(Triple(45, 25, 30), Triple(w.homePercent, w.drawPercent, w.awayPercent))
        assertEquals(1_700_000_000_000L, d.asOf["polymarket"])
    }

    @Test fun `storylines ride on the win chance block and vanish with the kill switch`() = runBlocking<Unit> {
        val stories = listOf(com.nuvio.app.features.sportsdetail.api.Storyline("ot", "Goes to overtime", 23, null))
        val on = loaded(session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef, market = FakeMarket(mc(storylines = stories))).tick())
        assertEquals(stories, winBlock(on)?.storylines)
        val off = loaded(session(nhlTsdb(GameState.LIVE), nhl("post", "in"), r = nhlRef, market = FakeMarket(mc(storylines = stories))).tick())
        assertNull(winBlock(off), "kill switch on (default): no block, so no storylines either")
    }

    @Test fun `with the kill switch on - the default - the market is never called`() = runBlocking<Unit> {
        val market = FakeMarket(mc())
        val d = loaded(session(nhlTsdb(GameState.LIVE), nhl("post", "in"), r = nhlRef, market = market).tick())
        assertNull(winBlock(d))
        assertTrue(market.requests.isEmpty())
    }

    @Test fun `disabled and 404 hide the block and are remembered - not re-asked every slow tick`() = runBlocking<Unit> {
        for (answer in listOf<SourceResult<com.nuvio.app.features.sportsdetail.internal.MarketWinChance>>(SourceResult.Disabled, SourceResult.Empty)) {
            val market = FakeMarket(answer)
            val s = session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef, market = market)
            assertNull(winBlock(loaded(s.tick())))
            repeat(3) { s.tick(fetchEvent = true, fetchSummary = true) }
            assertEquals(1, market.requests.size, "$answer")
            assertTrue(telemetry.any { it["block"] == "winProbability" && it["source"] == "polymarket" && it["outcome"] == "empty" })
        }
    }

    @Test fun `a failing market backs off about 30 s and never blocks the screen`() = runBlocking<Unit> {
        val market = FakeMarket(SourceResult.Failed(true))
        val s = session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef, market = market)
        assertNull(winBlock(loaded(s.tick())))
        s.tick(fetchEvent = true, fetchSummary = true)
        assertEquals(1, market.requests.size)
        now += 31_000
        market.answer = mc()
        assertNotNull(winBlock(loaded(s.tick(fetchEvent = true, fetchSummary = true))))
        assertEquals(2, market.requests.size)
    }

    @Test fun `live ticks ride the summary cadence - the market is fetched on the slow tick only`() = runBlocking<Unit> {
        val market = FakeMarket(mc())
        val s = session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef, market = market)
        s.tick()
        repeat(3) { assertNotNull(winBlock(loaded(s.tick(fetchEvent = true, fetchSummary = false))), "the last good value stays on screen") }
        assertEquals(1, market.requests.size)
        market.answer = mc(70)
        assertEquals(70, winBlock(loaded(s.tick(fetchEvent = true, fetchSummary = true)))!!.homePercent)
        assertEquals(2, market.requests.size)
    }

    @Test fun `a final game fetches once and shows only a real momentum history`() = runBlocking<Unit> {
        val two = listOf(com.nuvio.app.features.sportsdetail.api.WinPoint(1L, 40), com.nuvio.app.features.sportsdetail.api.WinPoint(2L, 100))
        val withHistory = FakeMarket(mc(100, two))
        val s = session(nhlTsdb(GameState.FINAL), nhl("post", "post"), marketOn(), r = nhlRef, market = withHistory)
        assertNotNull(winBlock(loaded(s.tick())))
        s.tick(fetchEvent = true, fetchSummary = true); s.tick(fetchEvent = true, fetchSummary = true)
        assertEquals(1, withHistory.requests.size, "final games fetch once")
        val noHistory = FakeMarket(mc(100))
        assertNull(winBlock(loaded(session(nhlTsdb(GameState.FINAL), nhl("post", "post"), marketOn(), r = nhlRef, market = noHistory).tick())), "a 100/0 bar after the whistle says nothing")
    }

    @Test fun `an upcoming game shows the pre-game win chance`() = runBlocking<Unit> {
        val market = FakeMarket(mc(55))
        val d = loaded(session(FakeTsdb(tsdbEvent(home = "Buffalo Sabres", away = "Columbus Blue Jackets", state = GameState.UPCOMING, hs = null, aws = null)), nhl("post", "pre"), marketOn(), r = nhlRef, market = market).tick())
        assertEquals(55, winBlock(d)?.homePercent)
    }

    @Test fun `a single-entity event has no two sides so the market is not asked`() = runBlocking<Unit> {
        val market = FakeMarket(mc())
        loaded(session(nhlTsdb(GameState.LIVE), nhl("post", "in"), marketOn(), r = nhlRef.copy(awayName = null), market = market).tick())
        assertTrue(market.requests.isEmpty())
    }
}
