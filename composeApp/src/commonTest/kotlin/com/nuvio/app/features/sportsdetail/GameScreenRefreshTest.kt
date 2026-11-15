package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.EspnLiveOverlay
import com.nuvio.app.features.sportsdetail.internal.EspnSource
import com.nuvio.app.features.sportsdetail.internal.GameDetailSession
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import com.nuvio.app.features.sportsdetail.internal.SportsPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase A refresh redesign: the 15 s live tick is the single-event scoreboard, the summary rides the 60 s cadence. */
class GameScreenRefreshTest {
    private class FakeEspn(var scoreboard: SourceResult<JsonElement>, var summary: SourceResult<JsonElement>, var event: SourceResult<JsonElement>) : EspnSource {
        var scoreboardCalls = 0
        var summaryCalls = 0
        var eventCalls = 0
        override suspend fun scoreboard(espnPath: String, yyyymmdd: String): SourceResult<JsonElement> { scoreboardCalls++; return scoreboard }
        override suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement> { summaryCalls++; return summary }
        override suspend fun event(espnPath: String, eventId: String): SourceResult<JsonElement> { eventCalls++; return event }
    }

    private val startsAt = 1_794_009_600_000L
    private var now = startsAt + 3_600_000L
    private val ref = GameRef(gameId = "espn:401909287", leagueId = "4387", tsdbEventId = null, espnEventId = "401909287",
        homeName = "Atlanta Hawks", awayName = "Washington Wizards", eventName = null, startsAtMs = startsAt)

    private fun liveEvent(homeScore: String = "139", awayScore: String = "132", clock: String? = null): String =
        SportsFixtures.espn_nba_event.replace("\"state\":\"post\"", "\"state\":\"in\"")
            .replace("\"score\":\"139\"", "\"score\":\"$homeScore\"").replace("\"score\":\"132\"", "\"score\":\"$awayScore\"")
            .let { if (clock != null) it.replace("\"displayClock\":\"0.0\"", "\"displayClock\":\"$clock\"") else it }

    private fun liveSummary(homeScore: String = "50", awayScore: String = "48"): String =
        SportsFixtures.espn_nba_summary.replace("\"state\":\"post\"", "\"state\":\"in\"")
            .replace("\"score\":\"139\"", "\"score\":\"$homeScore\"").replace("\"score\":\"132\"", "\"score\":\"$awayScore\"")

    private fun espn(event: SourceResult<JsonElement> = SourceResult.Ok(parse(liveEvent()))) = FakeEspn(
        SourceResult.Ok(parse("""{"events":[${liveEvent()}]}""")), SourceResult.Ok(parse(liveSummary())), event,
    )

    private fun session(espn: EspnSource, mapping: com.nuvio.app.features.sportsdetail.internal.SportsMapping = bundledMapping(), lines: MutableList<String>? = null) =
        GameDetailSession(ref, { mapping }, null, espn, nowMs = { now }, report = {}, debug = { lines?.add(it) })

    private fun loaded(l: DetailLoad): GameDetail = assertIs<DetailLoad.Loaded>(l).detail
    private fun home(d: GameDetail) = assertIs<TeamsHeader>(d.header).home.score

    /** The legacy mapping: no `sources.espn.event`, exactly what installed apps get from an old remote mapping. */
    private fun legacyMapping() = mappingOf(editedMappingJson("\"event\": \"https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}\",", ""))

    /** Drives the session the way GameDetailController does: policy plan -> tick, remembering when each part last ran. */
    private class Driver(val s: GameDetailSession, val startsAt: Long, val clock: () -> Long) {
        var lastEvent: Long? = null
        var lastSummary: Long? = null
        var last: GameDetail? = null
        suspend fun first() { last = (s.tick(true, true) as DetailLoad.Loaded).detail; lastEvent = clock(); lastSummary = clock() }
        /** Returns the plan that was followed. */
        suspend fun step(): GameDetailRefreshPolicy.Plan {
            val d = last!!
            val plan = GameDetailRefreshPolicy.plan(d.state, startsAt, clock(), lastEvent, lastSummary)
            if (plan.fetchEvent || plan.fetchSummary) {
                val t = clock()
                (s.tick(plan.fetchEvent, plan.fetchSummary) as? DetailLoad.Loaded)?.let { last = it.detail }
                if (plan.fetchEvent) lastEvent = t
                if (plan.fetchSummary) lastSummary = t
            }
            return plan
        }
    }

    @Test fun `sixty seconds of live ticks make 1 whole-day scoreboard 2 summaries and 4 single-event calls`() = runBlocking<Unit> {
        val e = espn()
        val lines = mutableListOf<String>()
        val d = Driver(session(e, lines = lines), startsAt) { now }
        d.first()
        repeat(4) { now += 15_000L; d.step() }
        assertEquals(1, e.scoreboardCalls, "whole-day scoreboard: first load only")
        assertEquals(2, e.summaryCalls, "summary at 0 s and 60 s")
        assertEquals(4, e.eventCalls, "single-event at 15, 30, 45 and 60 s")
        assertTrue(lines.last().contains("event=4"), "the tick log carries the event count: ${lines.last()}")
    }

    @Test fun `a final game makes no request after the first load`() = runBlocking<Unit> {
        val finalBoard = SportsFixtures.espn_nba_event
        val e = FakeEspn(SourceResult.Ok(parse("""{"events":[$finalBoard]}""")), SourceResult.Ok(parse(SportsFixtures.espn_nba_summary)), SourceResult.Ok(parse(finalBoard)))
        val d = Driver(session(e), startsAt) { now }
        d.first()
        assertEquals(GameState.FINAL, d.last!!.state)
        val before = Triple(e.scoreboardCalls, e.summaryCalls, e.eventCalls)
        repeat(6) { now += 15_000L; assertTrue(d.step().isIdle) }
        assertEquals(before, Triple(e.scoreboardCalls, e.summaryCalls, e.eventCalls))
    }

    @Test fun `an event tick newer than the cached summary shows the event score`() = runBlocking<Unit> {
        val e = espn()
        val s = session(e)
        assertEquals("50", home(loaded(s.tick())), "first load: the summary overlay as today")
        now += 15_000L
        e.event = SourceResult.Ok(parse(liveEvent(homeScore = "58", awayScore = "55", clock = "3:21")))
        val d = loaded(s.tick(fetchEvent = true, fetchSummary = false))
        assertEquals("58", home(d), "the fresher event wins over the 15 s old summary")
        assertEquals("55", assertIs<TeamsHeader>(d.header).away.score)
        assertEquals("3:21", d.live?.clock)
        now += 45_000L
        e.event = SourceResult.Ok(parse(liveEvent(homeScore = "60", awayScore = "55", clock = "1:00")))
        e.summary = SourceResult.Ok(parse(liveSummary("60", "55")))
        assertEquals("60", home(loaded(s.tick(fetchEvent = true, fetchSummary = true))))
    }

    @Test fun `a mapping without the event key behaves exactly like today - the summary is the live tick`() = runBlocking<Unit> {
        val e = espn()
        val s = session(e, legacyMapping())
        s.tick()
        val summaries = e.summaryCalls
        repeat(3) { now += 15_000L; loaded(s.tick(fetchEvent = true, fetchSummary = false)) }
        assertEquals(summaries + 3, e.summaryCalls, "legacy: each live tick is one summary")
        assertEquals(0, e.eventCalls)
        assertEquals(1, e.scoreboardCalls)
    }

    @Test fun `a failed event tick keeps the last good data stays loaded and backs off`() = runBlocking<Unit> {
        val e = espn()
        val s = session(e)
        assertEquals("50", home(loaded(s.tick())))
        now += 15_000L
        e.event = SourceResult.Failed(timedOut = true)
        val d = loaded(s.tick(fetchEvent = true, fetchSummary = false))
        assertEquals("50", home(d), "last good data stays")
        assertEquals(1, e.eventCalls)
        now += 15_000L
        loaded(s.tick(fetchEvent = true, fetchSummary = false))
        assertEquals(1, e.eventCalls, "inside the 30 s backoff the event is not asked again")
        now += 20_000L
        e.event = SourceResult.Ok(parse(liveEvent(homeScore = "62")))
        assertEquals("62", home(loaded(s.tick(fetchEvent = true, fetchSummary = false))), "recovers after the backoff")
    }

    @Test fun `the event is not asked on the tick that just resolved the whole-day scoreboard`() = runBlocking<Unit> {
        val e = espn()
        session(e).tick(fetchEvent = true, fetchSummary = true)
        assertEquals(0, e.eventCalls)
    }

    @Test fun `an event answer is merged so a block the single event lacks keeps its last value`() = runBlocking<Unit> {
        val e = espn()
        val s = session(e)
        val first = loaded(s.tick())
        val leadersBefore = first.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.LeadersBlock>().size
        now += 15_000L
        e.event = SourceResult.Ok(parse("""{"id":"401909287","status":{"displayClock":"2:00","period":2,"type":{"state":"in","shortDetail":"2:00 - 2nd"}},"competitions":[{"competitors":[{"homeAway":"home","score":"70"},{"homeAway":"away","score":"66"}]}]}"""))
        val d = loaded(s.tick(fetchEvent = true, fetchSummary = false))
        assertEquals("70", home(d))
        assertEquals("Atlanta Hawks", assertIs<TeamsHeader>(d.header).home.name, "names survive a thin event")
        assertEquals(leadersBefore, d.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.LeadersBlock>().size)
    }

    @Test fun `a TheSportsDB header takes the ESPN colours and rank only when both team names agree`() = runBlocking<Unit> {
        fun tsdb(home: String, away: String) = object : com.nuvio.app.features.sportsdetail.internal.TsdbSource {
            override suspend fun detail(eventId: String, leagueId: String?, blocks: Set<String>?) = SourceResult.Ok(
                GameDetail(gameId = "tsdb:2601", sport = "Basketball", leagueId = "4387", leagueName = "NBA", startsAt = "2026-11-07T00:00:00Z", state = GameState.LIVE,
                    header = TeamsHeader(com.nuvio.app.features.sportsdetail.api.Side(home), com.nuvio.app.features.sportsdetail.api.Side(away))),
            )
        }
        val r = ref.copy(gameId = "tsdb:2601", tsdbEventId = "2601")
        val ranked = liveEvent().replace("\"team\":{\"id\":\"1\"", "\"curatedRank\":{\"current\":7},\"team\":{\"id\":\"1\"")
        suspend fun go(home: String, away: String): TeamsHeader {
            val e = FakeEspn(SourceResult.Ok(parse("""{"events":[$ranked]}""")), SourceResult.Ok(parse(liveSummary())), SourceResult.Empty)
            return assertIs<TeamsHeader>(loaded(GameDetailSession(r, { bundledMapping() }, tsdb(home, away), e, nowMs = { now }, report = {}).tick()).header)
        }
        val ok = go("Atlanta Hawks", "Washington Wizards")
        assertNotNull(ok.home.color, "colour from ESPN")
        assertNotNull(ok.away.color)
        val bad = go("Boston Celtics", "Miami Heat")
        assertNull(bad.home.color, "a mislinked event never leaks colours")
        assertNull(bad.away.color)
    }

    // ---- EspnLiveOverlay.apply(base, summary, baseIsFresher) -------------------------------------------------------

    private val baseEvent = parse("""{"id":"1","status":{"displayClock":"5:00","period":2,"type":{"state":"in"}},
        "competitions":[{"competitors":[{"homeAway":"home","score":"60","linescores":[{"displayValue":"30"}]},{"homeAway":"away","score":"55"}]}]}""")
    private val summary = parse("""{"header":{"competitions":[{"status":{"displayClock":"9:00","period":1,"type":{"state":"in"}},
        "competitors":[{"homeAway":"home","score":"50","linescores":[{"displayValue":"25"}]},{"homeAway":"away","score":"48"}]}]},
        "situation":{"downDistanceText":"1st & 10"}}""")

    @Test fun `overlay - an older summary no longer overwrites a fresher event`() {
        val r = EspnLiveOverlay.apply(baseEvent, summary, baseIsFresher = true)
        assertEquals("60", SportsPath.text(r, "competitions[0].competitors[homeAway=home].score"))
        assertEquals("55", SportsPath.text(r, "competitions[0].competitors[homeAway=away].score"))
        assertEquals("30", SportsPath.text(r, "competitions[0].competitors[homeAway=home].linescores[0].displayValue"))
        assertEquals("5:00", SportsPath.text(r, "status.displayClock"))
        assertEquals("2", SportsPath.text(r, "status.period"))
    }

    @Test fun `overlay - a fresher event still takes the summary situation when it has none`() {
        val r = EspnLiveOverlay.apply(baseEvent, summary, baseIsFresher = true)
        assertEquals("1st & 10", SportsPath.text(r, "competitions[0].situation.downDistanceText"))
        val own = parse("""{"id":"1","status":{"displayClock":"5:00"},"competitions":[{"situation":{"downDistanceText":"3rd & 4"},"competitors":[]}]}""")
        assertEquals("3rd & 4", SportsPath.text(EspnLiveOverlay.apply(own, summary, baseIsFresher = true), "competitions[0].situation.downDistanceText"), "the event's own situation wins")
    }

    @Test fun `overlay - the default is today's behaviour and the summary overwrites`() {
        val r = EspnLiveOverlay.apply(baseEvent, summary)
        assertEquals("50", SportsPath.text(r, "competitions[0].competitors[homeAway=home].score"))
        assertEquals("9:00", SportsPath.text(r, "status.displayClock"))
    }

    // ---- GameDetailRefreshPolicy.wantsUpcomingSummary ---------------------------------------------------------------

    private val t0 = 10_000_000L
    private fun plan(lastEvent: Long?, lastSummary: Long?, wants: Boolean, untilKickoffMs: Long = 20 * 60_000L) =
        GameDetailRefreshPolicy.plan(GameState.UPCOMING, t0 + untilKickoffMs, t0, lastEvent, lastSummary, 0, GameDetailRefreshPolicy.Config(), wantsUpcomingSummary = wants)

    @Test fun `upcoming soccer in the lead window refreshes the summary on the 60 s cadence`() {
        assertTrue(plan(t0 - 15_000L, null, wants = true).fetchSummary, "first lineup fetch")
        assertFalse(plan(t0 - 15_000L, t0 - 30_000L, wants = true).fetchSummary)
        assertTrue(plan(t0 - 15_000L, t0 - 60_000L, wants = true).fetchSummary)
        val p = plan(t0 - 10_000L, t0 - 30_000L, wants = true)
        assertEquals(5_000L, p.nextCheckInMs, "min(eventWait, summaryWait)")
        assertEquals(2_000L, plan(t0 - 10_000L, t0 - 58_000L, wants = true).nextCheckInMs)
    }

    @Test fun `upcoming without lineups never fetches a summary`() {
        val p = plan(t0 - 15_000L, null, wants = false)
        assertFalse(p.fetchSummary)
        assertEquals(0L, p.nextCheckInMs, "event wait only")
        assertEquals(5_000L, plan(t0 - 10_000L, null, wants = false).nextCheckInMs)
    }

    @Test fun `only soccer wants the upcoming summary`() {
        assertTrue(GameDetailRefreshPolicy.wantsUpcomingSummary("Soccer"))
        assertTrue(GameDetailRefreshPolicy.wantsUpcomingSummary("soccer"))
        for (s in listOf("Basketball", "American Football", "Ice Hockey", "Baseball", "Motorsport", "")) assertFalse(GameDetailRefreshPolicy.wantsUpcomingSummary(s), s)
    }
}
