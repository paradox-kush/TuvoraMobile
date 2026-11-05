package com.nuvio.app.features.livetv.pause

import com.nuvio.app.core.contracts.MemoryTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePauseDeviceBudgetTest {
    @Test
    fun `a low tier device gets no raised mpv budget and the low capture cap`() {
        val c = LivePauseDeviceBudget.configFor(MemoryTier.LOW, null)
        assertTrue(c.lowMemoryTier)
        assertEquals(0L, c.engineMemoryBudgetBytes)
    }

    @Test
    fun `mid and high tiers budget covers the normal plan with room to raise`() {
        for (tier in listOf(MemoryTier.MID, MemoryTier.HIGH)) {
            val c = LivePauseDeviceBudget.configFor(tier, null)
            assertFalse(c.lowMemoryTier)
            assertTrue(c.engineMemoryBudgetBytes > c.engineNormalPlan.maxBytes + c.engineNormalPlan.maxBackBytes, "$tier")
        }
    }

    @Test
    fun `a chosen live buffer length becomes the normal read ahead and auto leaves the engine default`() {
        assertEquals(20L, LivePauseDeviceBudget.configFor(MemoryTier.MID, 20).engineNormalPlan.cacheSecs)
        assertEquals(LivePauseDeviceBudget.UNBOUNDED_CACHE_SECS, LivePauseDeviceBudget.configFor(MemoryTier.MID, 0).engineNormalPlan.cacheSecs)
        assertEquals(LivePauseDeviceBudget.UNBOUNDED_CACHE_SECS, LivePauseDeviceBudget.configFor(MemoryTier.MID, null).engineNormalPlan.cacheSecs)
    }

    @Test
    fun `clock and behind labels`() {
        assertEquals("0:00", LivePauseUiText.clock(-5))
        assertEquals("0:09", LivePauseUiText.clock(9_999))
        assertEquals("1:05", LivePauseUiText.clock(65_000))
        assertEquals("10:00", LivePauseUiText.clock(600_000))
        assertEquals("1:02:05", LivePauseUiText.clock(3_725_000))
        assertEquals("−1:05", LivePauseUiText.behind(65_000))
    }
}
