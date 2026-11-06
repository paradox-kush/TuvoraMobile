package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayRetryPolicyTest {
    private val spent = ReplayRetryPolicy.Session(singleUse = true)
    private val reusable = ReplayRetryPolicy.Session(singleUse = false)

    @Test
    fun `a pending reopen wins`() {
        assertEquals(ReplayRetryPolicy.Step.REOPEN_PENDING, ReplayRetryPolicy.stepFor(true, spent))
        assertEquals(ReplayRetryPolicy.Step.REOPEN_PENDING, ReplayRetryPolicy.stepFor(true, null))
    }

    @Test
    fun `retry on a single-use replay mints a fresh link instead of re-opening the spent one`() {
        assertEquals(ReplayRetryPolicy.Step.MINT_FRESH, ReplayRetryPolicy.stepFor(false, spent))
    }

    @Test
    fun `retry on a reusable replay re-resolves it and on live re-resolves live`() {
        assertEquals(ReplayRetryPolicy.Step.RE_RESOLVE_REPLAY, ReplayRetryPolicy.stepFor(false, reusable))
        assertEquals(ReplayRetryPolicy.Step.RE_RESOLVE_LIVE, ReplayRetryPolicy.stepFor(false, null))
    }
}
