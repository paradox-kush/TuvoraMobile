package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.CatchUpStartupPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A replay link that accepts the connection and never answers used to hold "Starting stream" for
 * minutes: the link walks only advance on an error. Every link now has a first-frame deadline, the
 * walk advances on it, and the whole walk is bounded.
 */
class CatchUpStartupPolicyTest {
    private val s = 1_000L

    private class FakeClock(var nowMs: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    @Test
    fun `each link gets the per-link deadline`() {
        assertEquals(CatchUpStartupPolicy.LINK_DEADLINE_MS, CatchUpStartupPolicy.deadlineMs(elapsedMs = 0L), "first link")
    }

    @Test
    fun `the last deadline is trimmed so the total never overruns`() {
        val nearEnd = CatchUpStartupPolicy.TOTAL_BUDGET_MS - 4 * s
        assertEquals(4 * s, CatchUpStartupPolicy.deadlineMs(nearEnd), "only the remaining budget is waited")
        assertTrue(
            CatchUpStartupPolicy.deadlineMs(CatchUpStartupPolicy.TOTAL_BUDGET_MS + 1) > 0L,
            "a spent budget still yields a positive wait so the timer fires",
        )
    }

    @Test
    fun `a timeout advances the walk while budget and attempts remain`() {
        assertEquals(Decision.ADVANCE, CatchUpStartupPolicy.onTimeout(timeoutCount = 1, elapsedMs = 15 * s))
    }

    @Test
    fun `the walk ends after the maximum number of timeouts`() {
        assertEquals(
            Decision.GIVE_UP,
            CatchUpStartupPolicy.onTimeout(CatchUpStartupPolicy.MAX_TIMEOUTS, elapsedMs = 20 * s),
        )
    }

    @Test
    fun `the walk ends when the total budget is spent however few timeouts fired`() {
        assertEquals(
            Decision.GIVE_UP,
            CatchUpStartupPolicy.onTimeout(timeoutCount = 1, elapsedMs = CatchUpStartupPolicy.TOTAL_BUDGET_MS),
        )
    }

    @Test
    fun `budget - an ever-changing url cannot reset the attempt count`() {
        val clock = FakeClock()
        val budget = CatchUpStartupBudget(clock)
        assertEquals(CatchUpStartupPolicy.LINK_DEADLINE_MS, budget.arm())
        val decisions = mutableListOf<Decision>()
        repeat(CatchUpStartupPolicy.MAX_TIMEOUTS) {
            clock.nowMs += CatchUpStartupPolicy.LINK_DEADLINE_MS
            decisions += budget.onTimeout()
            budget.arm() // re-armed for the next url
        }
        assertEquals(Decision.GIVE_UP, decisions.last())
        assertTrue(decisions.dropLast(1).all { it == Decision.ADVANCE }, "advances before the last")
    }

    @Test
    fun `budget - total time is bounded across re-arms`() {
        val clock = FakeClock()
        val budget = CatchUpStartupBudget(clock)
        budget.arm()
        clock.nowMs += CatchUpStartupPolicy.TOTAL_BUDGET_MS - 2 * s
        assertEquals(2 * s, budget.arm(), "re-arming later must not restart the clock")
    }

    @Test
    fun `budget - giving up latches and no deadline is armed afterwards`() {
        val clock = FakeClock()
        val budget = CatchUpStartupBudget(clock)
        budget.arm()
        clock.nowMs += CatchUpStartupPolicy.TOTAL_BUDGET_MS
        assertEquals(Decision.GIVE_UP, budget.onTimeout())
        assertNull(budget.arm(), "a screen that re-arms after a pause must not restart a finished walk")
    }

    @Test
    fun `budget - no deadline once the replay has played`() {
        val budget = CatchUpStartupBudget(FakeClock())
        budget.markPlayed()
        assertNull(budget.arm())
    }

    @Test
    fun `budget - a user retry grants a fresh budget`() {
        val clock = FakeClock()
        val budget = CatchUpStartupBudget(clock)
        budget.arm()
        clock.nowMs += CatchUpStartupPolicy.TOTAL_BUDGET_MS
        repeat(CatchUpStartupPolicy.MAX_TIMEOUTS) { budget.onTimeout() }
        budget.reset()
        clock.nowMs += 9_000_000L
        assertEquals(CatchUpStartupPolicy.LINK_DEADLINE_MS, budget.arm())
        clock.nowMs += CatchUpStartupPolicy.LINK_DEADLINE_MS
        assertEquals(Decision.ADVANCE, budget.onTimeout())
    }
}
