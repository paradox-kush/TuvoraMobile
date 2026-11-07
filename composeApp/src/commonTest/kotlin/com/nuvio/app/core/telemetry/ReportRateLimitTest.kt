package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.ReportRateLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportRateLimitTest {
    @Test
    fun `one report per two minutes`() {
        val limit = ReportRateLimit()
        assertEquals(0L, limit.tryAcquire(0L))
        assertEquals(60_000L, limit.tryAcquire(60_000L))
        assertEquals(1L, limit.tryAcquire(119_999L))
        assertEquals(0L, limit.tryAcquire(120_000L))
    }

    @Test
    fun `a refused attempt does not extend the window`() {
        val limit = ReportRateLimit()
        limit.tryAcquire(0L)
        limit.tryAcquire(100_000L)
        assertEquals(0L, limit.tryAcquire(120_000L))
    }

    @Test
    fun `a failed send gives the window back`() {
        val limit = ReportRateLimit()
        limit.tryAcquire(0L)
        limit.release()
        assertEquals(0L, limit.tryAcquire(1L))
    }

    @Test
    fun `remaining time rounds up to whole seconds and never shows zero`() {
        assertEquals(1, ReportRateLimit.secondsLeft(1L))
        assertEquals(2, ReportRateLimit.secondsLeft(1001L))
        assertTrue(ReportRateLimit.secondsLeft(120_000L) == 120)
    }

    @Test
    fun `a clock that went backwards does not lock reporting out`() {
        val limit = ReportRateLimit()
        limit.tryAcquire(500_000L)
        assertEquals(0L, limit.tryAcquire(1_000L))
    }
}
