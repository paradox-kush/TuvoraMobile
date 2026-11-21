package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kotlin.test argument order: assertEquals(expected, actual, message). */
class UpdateProgressThrottleTest {

    @Test
    fun `first report publishes`() {
        val t = UpdateProgressThrottle()
        assertEquals(0.01f, t.accept(1_000L, 100_000L, 0L), "first report")
    }

    @Test
    fun `unknown or zero total never publishes`() {
        val t = UpdateProgressThrottle()
        assertNull(t.accept(500L, null, 0L), "null total")
        assertNull(t.accept(500L, 0L, 10L), "zero total")
    }

    @Test
    fun `same percent is skipped however late`() {
        val t = UpdateProgressThrottle()
        t.accept(1_000L, 100_000L, 0L)
        assertNull(t.accept(1_400L, 100_000L, 10_000L), "same whole percent")
    }

    @Test
    fun `new percent inside the interval is skipped`() {
        val t = UpdateProgressThrottle()
        t.accept(1_000L, 100_000L, 1_000L)
        assertNull(t.accept(2_000L, 100_000L, 1_100L), "only 100 ms later")
    }

    @Test
    fun `new percent after the interval publishes`() {
        val t = UpdateProgressThrottle()
        t.accept(1_000L, 100_000L, 1_000L)
        assertEquals(0.02f, t.accept(2_000L, 100_000L, 1_250L), "250 ms later")
    }

    @Test
    fun `final 100 percent publishes immediately`() {
        val t = UpdateProgressThrottle()
        t.accept(98_000L, 100_000L, 1_000L)
        assertEquals(1f, t.accept(100_000L, 100_000L, 1_001L), "100 percent bypasses the interval")
    }

    @Test
    fun `thousands of reads collapse to at most one publish per percent`() {
        val t = UpdateProgressThrottle()
        val total = 60_000_000L
        var published = 0
        var now = 0L
        var downloaded = 0L
        while (downloaded < total) {
            downloaded = minOf(total, downloaded + 8_192L)
            now += 1L
            if (t.accept(downloaded, total, now) != null) published++
        }
        assertTrue(published <= 101, "published $published times for ~7300 reads")
        assertTrue(published >= 2, "at least first and last")
    }
}
