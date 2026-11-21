package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** kotlin.test argument order: assertEquals(expected, actual, message). */
class GuideClockPolicyTest {
    @Test
    fun `mid tick waits only for the remainder to the next boundary`() {
        assertEquals(18_000L, GuideClockPolicy.delayToNextTickMs(120_000L + 12_000L), "12 s into a tick")
    }

    @Test
    fun `exactly on a boundary waits a full tick`() {
        assertEquals(30_000L, GuideClockPolicy.delayToNextTickMs(180_000L), "on the boundary")
    }

    @Test
    fun `one millisecond past a boundary waits just under a tick`() {
        assertEquals(29_999L, GuideClockPolicy.delayToNextTickMs(180_001L), "1 ms past")
    }

    @Test
    fun `delay is always between 1 ms and one tick and lands on a boundary`() {
        for (now in listOf(0L, 1L, 29_999L, 30_000L, 1_700_000_000_123L, -1L)) {
            val d = GuideClockPolicy.delayToNextTickMs(now)
            assertTrue(d in 1L..GuideClockPolicy.TICK_MS, "delay $d for $now")
            assertEquals(0L, (((now + d) % GuideClockPolicy.TICK_MS) + GuideClockPolicy.TICK_MS) % GuideClockPolicy.TICK_MS, "lands on a boundary for $now")
        }
    }
}
