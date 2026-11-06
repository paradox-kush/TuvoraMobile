package com.nuvio.app.features.iptv

import com.nuvio.app.core.analytics.AnalyticsSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedLoadTest {
    private val events = mutableListOf<Pair<String, Map<String, Any>>>()

    @BeforeTest
    fun setUp() {
        BoundedLoad.stallOverrideMsForTests = 150L
        BoundedLoad.resetReportsForTests()
        AnalyticsSink.register { name, props -> events += name to props }
    }

    @AfterTest
    fun tearDown() {
        BoundedLoad.stallOverrideMsForTests = null
        AnalyticsSink.register { _, _ -> }
    }

    @Test
    fun `a provider that never answers ends as a timed-out failure`(): Unit = runBlocking {
        val outcome = BoundedLoad.run<List<String>>(LoadSurface.HUB_CATEGORIES) { awaitCancellation() }

        assertIs<LoadOutcome.Failed>(outcome)
        assertTrue(outcome.timedOut)
        assertEquals(LoadStatus.Failed(timedOut = true), outcome.status)
    }

    @Test
    fun `a failure is never reported as empty`(): Unit = runBlocking {
        val outcome = BoundedLoad.run<List<String>>(LoadSurface.HUB_ROW, isEmpty = { it.isEmpty() }) {
            throw IllegalStateException("HTTP 503")
        }

        assertIs<LoadOutcome.Failed>(outcome)
        assertEquals(false, outcome.timedOut)
    }

    @Test
    fun `an empty answer is empty and a full one is loaded`(): Unit = runBlocking {
        assertIs<LoadOutcome.Empty<*>>(BoundedLoad.run(LoadSurface.HUB_ROW, isEmpty = { it: List<Int> -> it.isEmpty() }) { emptyList() })
        assertIs<LoadOutcome.Loaded<*>>(BoundedLoad.run(LoadSurface.HUB_ROW, isEmpty = { it: List<Int> -> it.isEmpty() }) { listOf(1) })
    }

    @Test
    fun `progress keeps a slow but healthy import alive past the stall deadline`(): Unit = runBlocking {
        // 6 x 60 ms = 360 ms in total, more than twice the 150 ms stall, but never 150 ms without progress.
        val ticks = flow { repeat(6) { delay(60); emit(it) } }
        var extended = 0
        val outcome = BoundedLoad.run(LoadSurface.HUB_CATEGORIES, progress = ticks, onProgress = { extended++ }) {
            delay(360); "imported"
        }

        assertEquals(LoadOutcome.Loaded("imported"), outcome)
        assertTrue(extended >= 4, "each progress tick should push the deadline out (got $extended)")
    }

    @Test
    fun `an import the deadline gave up on keeps running when asked to`(): Unit = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val outcome = BoundedLoad.run(LoadSurface.HUB_CATEGORIES, cancelOnTimeout = false) {
            delay(400); finished.complete(Unit); "late"
        }

        assertIs<LoadOutcome.Failed>(outcome)
        finished.await() // completes: the import was not cancelled by the deadline
    }

    @Test
    fun `the wait ends at the deadline even when the work ignores cancellation`(): Unit = runBlocking(Dispatchers.Default) {
        // A multi-threaded dispatcher, as in the app: the busy work holds one thread, the deadline another.
        val started = BoundedLoad.clock()
        val outcome = BoundedLoad.run(LoadSurface.HUB_ROW) {
            // A blocking busy-wait that never checks for cancellation.
            val end = BoundedLoad.clock() + 1_000
            while (BoundedLoad.clock() < end) { /* spin */ }
            "too late"
        }
        val waited = BoundedLoad.clock() - started

        assertIs<LoadOutcome.Failed>(outcome)
        assertTrue(waited < 900, "the caller must be released at the deadline, not when the work returns (waited ${waited}ms)")
    }

    @Test
    fun `live pause surface has its 20 second deadline and reports every outcome`() {
        assertEquals("live_pause", LoadSurface.LIVE_PAUSE.key)
        assertEquals(20_000L, LoadSurface.LIVE_PAUSE.stallMs)
        assertTrue(LoadSurface.LIVE_PAUSE.reportsEveryOutcome)
    }

    @Test
    fun `a loading status past its deadline reads as a timed-out failure`() {
        val loading = BoundedLoad.begin(LoadSurface.HUB_ROW, nowMs = 1_000)

        assertEquals(loading, BoundedLoad.effectiveAt(loading, nowMs = 1_100))
        assertEquals(LoadStatus.Failed(timedOut = true), BoundedLoad.effectiveAt(loading, nowMs = 1_150))
        assertEquals(LoadStatus.Loaded, BoundedLoad.effectiveAt(LoadStatus.Loaded, nowMs = 99_999))
    }

    @Test
    fun `progress pushes the deadline out`() {
        val loading = BoundedLoad.begin(LoadSurface.HUB_CATEGORIES, nowMs = 1_000)
        val later = BoundedLoad.progressed(loading, LoadSurface.HUB_CATEGORIES, nowMs = 1_100)

        assertIs<LoadStatus.Loading>(later)
        assertEquals(1_250, later.deadlineAtMs)
        assertEquals(LoadStatus.Empty, BoundedLoad.progressed(LoadStatus.Empty, LoadSurface.HUB_CATEGORIES))
    }

    @Test
    fun `page loads report every outcome and rows report only failures without the error message`(): Unit = runBlocking {
        BoundedLoad.run(LoadSurface.HUB_CATEGORIES, report = mapOf("source_type" to "xtream")) { "ok" }
        BoundedLoad.run(LoadSurface.HUB_ROW) { "ok" }
        BoundedLoad.run<String>(LoadSurface.HUB_ROW) { throw IllegalStateException("http://user:pass@host") }

        val loads = events.filter { it.first == "iptv_load" }.map { it.second }
        assertEquals(listOf("hub_categories" to "loaded", "hub_row" to "failed"), loads.map { it["surface"] to it["outcome"] })
        assertEquals("xtream", loads.first()["source_type"])
        assertEquals("IllegalStateException", loads.last()["error_type"])
        assertTrue(loads.none { props -> props.values.any { it.toString().contains("pass") } }, "no message, host or credential")
    }
}
