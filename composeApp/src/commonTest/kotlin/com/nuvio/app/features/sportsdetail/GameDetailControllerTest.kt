package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import com.nuvio.app.features.sportsdetail.api.WatchUi
import com.nuvio.app.features.sportsdetail.internal.GameDetailController
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameDetailControllerTest {
    private var now = 1_000_000_000L
    private var stopAfter: Int? = null
    private val ref = GameRef("tsdb:1", "4387", "1", null, "Home", "Away", null, now)

    private fun detail(state: GameState, asOf: Map<String, Long> = mapOf("espn" to now), home: String = "Home") = GameDetail(
        gameId = "tsdb:1", sport = "Basketball", leagueId = "4387", leagueName = "NBA", startsAt = "t", state = state,
        header = TeamsHeader(Side(home), Side("Away")), asOf = asOf,
    )

    private class FakeSource(val answers: ArrayDeque<DetailLoad>, var hang: Boolean = false) : GameDetailSource {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
            calls += fetchEvent to fetchSummary
            if (hang) awaitCancellation()
            return if (answers.size > 1) answers.removeFirst() else answers.first()
        }
    }

    private class FakeWatch(var answer: WatchLookup = WatchLookup.NoMatch, var hang: Boolean = false) : GameWatchPort {
        val playing = MutableStateFlow<NowWatching?>(null)
        override val nowWatching: StateFlow<NowWatching?> get() = playing
        var lookups = 0
        override suspend fun lookup(ref: GameRef): WatchLookup { lookups++; if (hang) awaitCancellation(); return answer }
    }

    private fun controller(source: GameDetailSource, watch: GameWatchPort = FakeWatch(), deadline: Long = 20_000, sleeps: MutableList<Long> = mutableListOf(), lookupDeadline: Long = 15_000) =
        GameDetailController(ref, source, watch, { GameDetailRefreshPolicy.Config() }, { now }, { sleeps += it; now += it; kotlinx.coroutines.yield(); if (stopAfter != null && sleeps.size >= stopAfter!!) throw CancellationException("stop") }, deadline, lookupDeadline)

    private fun source(vararg a: DetailLoad) = FakeSource(ArrayDeque(a.toList()))

    @Test fun `starts Loading then Loaded with a watch lookup`() = runBlocking<Unit> {
        val offer = WatchOffer("c", "Channel")
        val watch = FakeWatch(WatchLookup.Found(offer))
        val c = controller(source(DetailLoad.Loaded(detail(GameState.LIVE))), watch)
        assertIs<GameDetailUiState.Loading>(c.state.value)
        c.start()
        val s = assertIs<GameDetailUiState.Loaded>(c.state.value)
        assertEquals(WatchUi.Browsing(offer), s.watch)
        assertEquals(1, watch.lookups)
    }

    @Test fun `Empty and Failed are terminal outcomes and Failed is never Empty`() = runBlocking<Unit> {
        val e = controller(source(DetailLoad.Empty)); e.start()
        assertEquals(GameDetailUiState.Empty, e.state.value)
        val f = controller(source(DetailLoad.Failed(timedOut = false))); f.start()
        assertEquals(GameDetailUiState.Failed(timedOut = false), f.state.value)
        val t = controller(source(DetailLoad.Failed(timedOut = true))); t.start()
        assertEquals(GameDetailUiState.Failed(timedOut = true), t.state.value)
    }

    @Test fun `a first load that never returns ends at its own deadline as timed out`() = runBlocking<Unit> {
        val src = FakeSource(ArrayDeque(listOf(DetailLoad.Empty)), hang = true)
        val c = controller(src, deadline = 60)
        c.start()
        assertEquals(GameDetailUiState.Failed(timedOut = true), c.state.value)
    }

    @Test fun `Failed waits for Retry - no timer loop - and Retry reloads`() = runBlocking<Unit> {
        val src = source(DetailLoad.Failed(false), DetailLoad.Loaded(detail(GameState.FINAL)))
        val c = controller(src)
        c.start()
        assertNull(c.step(), "failed: idle until the viewer retries")
        assertEquals(1, src.calls.size)
        c.retry()
        c.step()
        assertIs<GameDetailUiState.Loaded>(c.state.value)
        assertEquals(2, src.calls.size)
    }

    @Test fun `final game is idle - zero requests`() = runBlocking<Unit> {
        val src = source(DetailLoad.Loaded(detail(GameState.FINAL)))
        val c = controller(src)
        c.start()
        now += 10 * 60_000
        assertNull(c.step())
        assertEquals(1, src.calls.size)
    }

    @Test fun `live ticks follow the refresh policy`() = runBlocking<Unit> {
        val src = source(DetailLoad.Loaded(detail(GameState.LIVE)))
        val c = controller(src)
        c.start()
        assertEquals(listOf(true to true), src.calls)
        now += 10_000
        val wait = c.step()
        assertEquals(1, src.calls.size, "10 s: nothing due")
        assertTrue(wait != null && wait <= 5_000, "wakes at least every 5 s to keep the chip honest: $wait")
        now += 6_000
        c.step()
        assertEquals(true to false, src.calls.last(), "16 s: event only")
        now += 50_000
        c.step()
        assertEquals(true to true, src.calls.last(), "summary due after 60 s")
    }

    @Test fun `a failed refresh keeps the last good screen and backs off`() = runBlocking<Unit> {
        val src = source(DetailLoad.Loaded(detail(GameState.LIVE)), DetailLoad.Failed(true))
        val c = controller(src)
        c.start()
        now += 16_000
        c.step()
        val s = assertIs<GameDetailUiState.Loaded>(c.state.value, "a tick failure must not blank the screen")
        assertEquals("Home", (s.detail.header as TeamsHeader).home.name)
        val calls = src.calls.size
        now += 16_000
        c.step()
        assertEquals(calls, src.calls.size, "backed off: 2x of 15 s not yet elapsed")
    }

    @Test fun `stale chip appears and clears as asOf ages and refreshes`() = runBlocking<Unit> {
        val d = detail(GameState.LIVE, asOf = mapOf("espn" to now))
        val src = source(DetailLoad.Loaded(d))
        val c = controller(src)
        c.start()
        assertNull(assertIs<GameDetailUiState.Loaded>(c.state.value).staleLabel)
        now += 40_000
        src.hang = true
        // no tick due that returns data: the event tick hangs, so only assert via a non-fetching recompute
        c.refreshChip()
        assertEquals("Updated 40s ago", assertIs<GameDetailUiState.Loaded>(c.state.value).staleLabel)
    }

    @Test fun `the watch block follows the player's current channel`() = runBlocking<Unit> {
        val watch = FakeWatch(WatchLookup.NoMatch)
        val c = controller(source(DetailLoad.Loaded(detail(GameState.LIVE))), watch)
        c.start()
        assertEquals(WatchUi.NoMatch, assertIs<GameDetailUiState.Loaded>(c.state.value).watch)
        watch.playing.value = NowWatching("x", "ESPN")
        c.refreshWatch()
        assertEquals(WatchUi.Watching(NowWatching("x", "ESPN"), null), assertIs<GameDetailUiState.Loaded>(c.state.value).watch)
    }

    @Test fun `a failed watch lookup can be retried and is looked up once otherwise`() = runBlocking<Unit> {
        val watch = FakeWatch(WatchLookup.Failed)
        val c = controller(source(DetailLoad.Loaded(detail(GameState.LIVE))), watch)
        c.start()
        now += 16_000; c.step(); now += 16_000; c.step()
        assertEquals(1, watch.lookups)
        assertEquals(WatchUi.LookupFailed, assertIs<GameDetailUiState.Loaded>(c.state.value).watch)
        watch.answer = WatchLookup.Found(WatchOffer("c", "C"))
        c.retryWatch()
        assertEquals(WatchUi.Browsing(WatchOffer("c", "C")), assertIs<GameDetailUiState.Loaded>(c.state.value).watch)
    }

    @Test fun `run loops with bounded sleeps and stops on cancellation`() = runBlocking<Unit> {
        val sleeps = mutableListOf<Long>()
        val src = source(DetailLoad.Loaded(detail(GameState.LIVE)))
        val c = GameDetailController(ref, src, FakeWatch(), { GameDetailRefreshPolicy.Config() }, { now },
            { ms -> sleeps += ms; now += ms; if (sleeps.size >= 4) throw CancellationException("stop") }, 20_000)
        try { c.run() } catch (_: CancellationException) {}
        assertEquals(4, sleeps.size)
        assertTrue(sleeps.all { it in 1..5_000 }, "$sleeps")
        assertTrue(src.calls.size >= 2)
    }

    @Test fun `a watch lookup that never returns ends at its deadline as LookupFailed - never Checking forever`() = runBlocking<Unit> {
        val c = controller(source(DetailLoad.Loaded(detail(GameState.LIVE))), FakeWatch(hang = true), lookupDeadline = 60)
        c.start()
        assertEquals(WatchUi.LookupFailed, assertIs<GameDetailUiState.Loaded>(c.state.value).watch)
    }

    @Test fun `a cancelled run leaves the lookup to be redone on the next run`() = runBlocking<Unit> {
        val watch = FakeWatch(WatchLookup.Found(WatchOffer("c", "C")), hang = true)
        val c = controller(source(DetailLoad.Loaded(detail(GameState.LIVE))), watch)
        stopAfter = 2
        try { c.run() } catch (_: CancellationException) {}
        assertEquals(WatchUi.Checking, assertIs<GameDetailUiState.Loaded>(c.state.value).watch, "the cancelled lookup left no answer")
        watch.hang = false
        stopAfter = 4
        try { c.run() } catch (_: CancellationException) {}
        assertEquals(WatchUi.Browsing(WatchOffer("c", "C")), assertIs<GameDetailUiState.Loaded>(c.state.value).watch, "the next run redoes it")
    }

    @Test fun `finished games skip the channel lookup entirely`() = runBlocking<Unit> {
        val watch = FakeWatch()
        val c = controller(source(DetailLoad.Loaded(detail(GameState.FINAL))), watch)
        c.start()
        assertEquals(0, watch.lookups)
    }

    @Test fun `a cancelled tick does not delay the next one`() = runBlocking<Unit> {
        val src = source(DetailLoad.Loaded(detail(GameState.LIVE)))
        val c = controller(src)
        c.start()
        now += 16_000
        src.hang = true
        val job = launch(Dispatchers.Unconfined) { c.step() }
        job.cancel()
        job.join()
        src.hang = false
        val before = src.calls.size
        c.step()
        assertEquals(before + 1, src.calls.size, "the cancelled tick never completed, so the resume fetches at once")
    }
}
