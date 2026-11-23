package com.tuvora.tvos.screens

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spoiler redraw wake must reach EVERY loop waiting on it: a conflated channel hands one wake to one receiver, so a
 * second loop (Swift's screen-long task beside the poll's) slept through a new due time. kotlin.test → (expected, actual, message).
 */
class TvRedrawSignalTest {

    @Test
    fun `one wake reaches every waiting loop`() = runTest {
        val signal = TvRedrawSignal()
        val waiters = List(2) {
            async { withTimeoutOrNull(1_000L) { signal.awaitAfter(signal.mark()); true } ?: false }
        }
        yield()
        signal.wake()
        assertEquals(listOf(true, true), waiters.map { it.await() }, "both loops re-plan after one wake")
    }

    @Test
    fun `a wake between planning and waiting is not lost`() = runTest {
        val signal = TvRedrawSignal()
        val mark = signal.mark()
        signal.wake()                 // lands while the loop is still computing its next due time
        val woke = withTimeoutOrNull(1_000L) { signal.awaitAfter(mark); true } ?: false
        assertEquals(true, woke, "the loop sees the wake it missed")
    }
}
