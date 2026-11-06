// Ported from the Arc fork's CalendarRateLimiterTest (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRateLimiterTest {

    @Test fun neverMoreThanFifteenCallsInAnyTenSeconds() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        repeat(80) {
            limiter.acquire()
            times += testScheduler.currentTime
        }
        times.forEach { start ->
            val inWindow = times.count { it >= start && it < start + 10_000L }
            assertTrue(inWindow <= 15, "$inWindow calls in the 10 s from $start")
        }
        assertEquals(0L, times[4])
        assertTrue(times[5] > 0L)
        assertTrue(times.last() in 74_000L..76_000L, "80 calls took ${times.last()} ms")
    }

    @Test fun concurrentCallersShareOneBucket() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        val lock = SynchronizedObject()
        repeat(4) {
            launch {
                repeat(10) {
                    limiter.acquire()
                    synchronized(lock) { times += testScheduler.currentTime }
                }
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(40, times.size)
        times.forEach { start -> assertTrue(times.count { it >= start && it < start + 10_000L } <= 15) }
    }

    @Test fun aLookupTakesTwoPermits() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        repeat(2) { limiter.acquire(2) }
        assertEquals(0L, testScheduler.currentTime)
        limiter.acquire(2)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test fun retryAfterHoldsEveryoneBack() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        limiter.acquire()
        limiter.pauseFor(5_000L)
        limiter.acquire()
        assertTrue(testScheduler.currentTime >= 5_000L)
    }

    @Test fun retryAfterHeader() {
        assertEquals(3_000L, parseRetryAfterMs("3"))
        assertEquals(1_000L, parseRetryAfterMs("0"))
        assertEquals(120_000L, parseRetryAfterMs("3600"))
        assertNull(parseRetryAfterMs(null))
        assertNull(parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }
}
