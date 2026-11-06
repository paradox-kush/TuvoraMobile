package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VodStartDeadlinePolicyTest {
    private fun decide(
        elapsedMs: Long,
        started: Boolean = false,
        isLive: Boolean = false,
        isTorrent: Boolean = false,
        suspended: Boolean = false,
        hasError: Boolean = false,
        canFailover: Boolean = false,
    ) = VodStartDeadlinePolicy.decide(
        elapsedMs = elapsedMs,
        started = started,
        isLive = isLive,
        isTorrent = isTorrent,
        suspended = suspended,
        hasError = hasError,
        canFailover = canFailover,
    )

    @Test
    fun waits_while_the_load_is_young() {
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(VodStartDeadlinePolicy.FAILOVER_AFTER_MS - 1, canFailover = true))
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(VodStartDeadlinePolicy.FAIL_AFTER_MS - 1))
    }

    @Test
    fun a_load_with_no_first_frame_always_ends_in_failed() {
        // B214/B217: the old behaviour waited forever (no case here ever ended the spinner).
        assertEquals(VodStartDeadlinePolicy.Action.Fail, decide(VodStartDeadlinePolicy.FAIL_AFTER_MS))
        assertEquals(VodStartDeadlinePolicy.Action.Fail, decide(10 * 60_000L, canFailover = true))
    }

    @Test
    fun an_engine_failover_is_tried_before_failing_when_it_is_available() {
        assertEquals(VodStartDeadlinePolicy.Action.FailOver, decide(VodStartDeadlinePolicy.FAILOVER_AFTER_MS, canFailover = true))
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(VodStartDeadlinePolicy.FAILOVER_AFTER_MS, canFailover = false))
    }

    @Test
    fun never_judges_a_started_live_torrent_suspended_or_already_failed_load() {
        val late = 10 * 60_000L
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(late, started = true))
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(late, isLive = true))
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(late, isTorrent = true))
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(late, suspended = true))
        assertEquals(VodStartDeadlinePolicy.Action.Wait, decide(late, hasError = true))
    }
}
