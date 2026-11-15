package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.SingleFlight
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import com.nuvio.app.features.sportsdetail.api.WatchUi
import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.internal.GameDetailController
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The game screen's channel lookup is shared, long-running work (channel guides, TV listings, EPG probes). A caller's timeout or a
 * screen pause must only stop WAITING — never cancel the work — or a cold lookup restarts from zero every time and never finishes.
 */
@OptIn(ExperimentalAtomicApi::class)
class SingleFlightTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val offer = WatchLookup.Found(WatchOffer("c1", "NHL Network"))

    private fun flight(deadlineMs: Long = 5_000, ttlMs: Long = 60_000, nowMs: () -> Long = { 0L }) =
        SingleFlight<String, WatchLookup>(scope, ttlMs, nowMs, deadlineMs, onDeadline = WatchLookup.Failed, shouldCache = { it !is WatchLookup.Failed })

    @Test fun `cancelling the caller mid-load does not cancel the load and the next caller gets the result`() = runBlocking<Unit> {
        val starts = AtomicInt(0)
        val gate = CompletableDeferred<WatchLookup>()
        val f = flight()
        val first = launch { f.get("g") { starts.addAndFetch(1); gate.await() } }
        withTimeout(2_000) { while (starts.load() == 0) delay(5) }
        first.cancel(); first.join()
        gate.complete(offer)                       // the load finishes while nobody is waiting
        val second = withTimeout(2_000) { f.get("g") { error("must not start a second load") } }
        assertEquals(offer, second)
        assertEquals(1, starts.load())
    }

    @Test fun `a caller's timeout only stops waiting`() = runBlocking<Unit> {
        val starts = AtomicInt(0)
        val gate = CompletableDeferred<WatchLookup>()
        val f = flight()
        assertNull(withTimeoutOrNull(60) { f.get("g") { starts.addAndFetch(1); gate.await() } }, "the caller gave up")
        gate.complete(offer)
        assertEquals(offer, withTimeout(2_000) { f.get("g") { error("restarted from zero") } })
        assertEquals(1, starts.load())
    }

    @Test fun `concurrent callers share one load`() = runBlocking<Unit> {
        val starts = AtomicInt(0)
        val gate = CompletableDeferred<WatchLookup>()
        val f = flight()
        val a = async { f.get("g") { starts.addAndFetch(1); gate.await() } }
        val b = async { f.get("g") { starts.addAndFetch(1); gate.await() } }
        delay(50); gate.complete(offer)
        assertEquals(listOf(offer, offer), listOf(withTimeout(2_000) { a.await() }, withTimeout(2_000) { b.await() }))
        assertEquals(1, starts.load())
    }

    @Test fun `the load is bounded overall and the failure is surfaced and not remembered`() = runBlocking<Unit> {
        val starts = AtomicInt(0)
        val f = flight(deadlineMs = 80)
        assertEquals(WatchLookup.Failed, withTimeout(3_000) { f.get("g") { starts.addAndFetch(1); CompletableDeferred<WatchLookup>().await() } })
        assertEquals(offer, withTimeout(2_000) { f.get("g") { starts.addAndFetch(1); offer } }, "a Failed is not cached: the next caller starts fresh")
        assertEquals(2, starts.load())
    }

    @Test fun `a finished result is remembered for its ttl then reloaded`() = runBlocking<Unit> {
        var now = 0L
        val starts = AtomicInt(0)
        val f = flight(ttlMs = 1_000, nowMs = { now })
        assertEquals(offer, f.get("g") { starts.addAndFetch(1); offer })
        now = 500; f.get("g") { starts.addAndFetch(1); offer }
        assertEquals(1, starts.load())
        now = 1_500; f.get("g") { starts.addAndFetch(1); offer }
        assertEquals(2, starts.load())
    }

    @Test fun `a throwing load reaches the caller and does not wedge the key`() = runBlocking<Unit> {
        val f = flight()
        try { f.get("g") { error("boom") }; error("should have thrown") } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
        assertEquals(offer, withTimeout(2_000) { f.get("g") { offer } })
    }

    // ---- through the screen's controller ---------------------------------------------------------------------------

    private class FlightWatch(scope: CoroutineScope) : GameWatchPort {
        val gate = CompletableDeferred<WatchLookup>()
        val starts = AtomicInt(0)
        private val flight = SingleFlight<String, WatchLookup>(scope, 60_000, { 0L }, 5_000, WatchLookup.Failed) { it !is WatchLookup.Failed }
        override val nowWatching: StateFlow<NowWatching?> = MutableStateFlow(null)
        override suspend fun lookup(ref: GameRef): WatchLookup = flight.get(ref.gameId) { starts.addAndFetch(1); gate.await() }
    }

    private class OneShotSource(val detail: GameDetail) : GameDetailSource {
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad = DetailLoad.Loaded(detail)
    }

    @Test fun `the controller's own deadline gives up waiting - and retrying gets the load that kept running`() = runBlocking<Unit> {
        val now = 1_000_000_000L
        val ref = GameRef("tsdb:1", "4387", "1", null, "Home", "Away", null, now)
        val detail = GameDetail(gameId = "tsdb:1", sport = "Basketball", leagueId = "4387", leagueName = "NBA", startsAt = "t",
            state = GameState.LIVE, header = TeamsHeader(Side("Home"), Side("Away")), asOf = mapOf("espn" to now))
        val watch = FlightWatch(scope)
        val c = GameDetailController(ref, OneShotSource(detail), watch, { GameDetailRefreshPolicy.Config() }, { now }, { delay(it) }, 20_000, lookupDeadlineMs = 60)
        c.start()
        assertEquals(WatchUi.LookupFailed, assertIs<GameDetailUiState.Loaded>(c.state.value).watch, "the deadline ended the WAIT")
        watch.gate.complete(WatchLookup.Found(WatchOffer("c1", "NHL Network")))
        withTimeout(2_000) { c.retryWatch() }
        assertEquals(WatchUi.Browsing(WatchOffer("c1", "NHL Network")), assertIs<GameDetailUiState.Loaded>(c.state.value).watch)
        assertEquals(1, watch.starts.load(), "one load served both the abandoned wait and the retry")
    }
}
