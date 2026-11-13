package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * commonTest (kotlin.test) - `assertEquals(expected, actual, message)`.
 *
 * B259: the docked Live screen re-minted a single-use Stalker link the instant the previous one failed, inside
 * the provider's one-connection window (an immediate second request got an empty 200).
 */
class LiveRemintSpacingPolicyTest {
    private val gap = LiveRemintSpacingPolicy.MIN_GAP_MS

    @Test
    fun `an automatic re mint right after a failure waits the whole gap`() {
        assertEquals(gap, LiveRemintSpacingPolicy.automaticDelayMs(singleUseLink = true, connectionEndedAtMs = 10_000L, nowMs = 10_000L))
    }

    @Test
    fun `the wait counts down from when the connection ended`() {
        assertEquals(gap - 2_500L, LiveRemintSpacingPolicy.automaticDelayMs(true, connectionEndedAtMs = 10_000L, nowMs = 12_500L))
    }

    @Test
    fun `no wait once the window has passed`() {
        assertEquals(0L, LiveRemintSpacingPolicy.automaticDelayMs(true, connectionEndedAtMs = 10_000L, nowMs = 10_000L + gap))
        assertEquals(0L, LiveRemintSpacingPolicy.automaticDelayMs(true, connectionEndedAtMs = 10_000L, nowMs = 90_000L))
    }

    @Test
    fun `nothing ended means no wait`() {
        assertEquals(0L, LiveRemintSpacingPolicy.automaticDelayMs(true, connectionEndedAtMs = null, nowMs = 10_000L))
    }

    @Test
    fun `a reusable link is never spaced`() {
        assertEquals(0L, LiveRemintSpacingPolicy.automaticDelayMs(singleUseLink = false, connectionEndedAtMs = 10_000L, nowMs = 10_000L))
    }

    @Test
    fun `a clock that ran backwards waits the gap and never longer`() {
        assertEquals(gap, LiveRemintSpacingPolicy.automaticDelayMs(true, connectionEndedAtMs = 10_000L, nowMs = 4_000L))
    }

    @Test
    fun `the gap is a few seconds - past the portal link life and well under the startup deadline`() {
        assertTrue(gap in 5_001L..10_000L, "gap $gap must outlast Infomir's 5 s link life and stay a few seconds")
    }

    @Test
    fun `a user retry inside the window is flagged but only for single use links`() {
        assertTrue(LiveRemintSpacingPolicy.userRetryInsideWindow(true, connectionEndedAtMs = 10_000L, nowMs = 11_000L), "inside")
        assertFalse(LiveRemintSpacingPolicy.userRetryInsideWindow(true, connectionEndedAtMs = 10_000L, nowMs = 10_000L + gap), "after")
        assertFalse(LiveRemintSpacingPolicy.userRetryInsideWindow(false, connectionEndedAtMs = 10_000L, nowMs = 11_000L), "reusable")
        assertFalse(LiveRemintSpacingPolicy.userRetryInsideWindow(true, connectionEndedAtMs = null, nowMs = 11_000L), "nothing ended")
    }
}
