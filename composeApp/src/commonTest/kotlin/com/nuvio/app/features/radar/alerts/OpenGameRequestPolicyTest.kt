package com.nuvio.app.features.radar.alerts

import kotlin.test.Test
import kotlin.test.assertEquals

/** A banner's "Open game" request: honoured only while fresh and only until the hub's load settles. */
class OpenGameRequestPolicyTest {
    private val t0 = 1_800_000_000_000L

    @Test
    fun `a request older than 15 s is ignored`() {
        assertEquals(true, OpenGameRequestPolicy.isFresh(atMs = t0, nowMs = t0 + 15_000), "at 15 s")
        assertEquals(false, OpenGameRequestPolicy.isFresh(atMs = t0, nowMs = t0 + 15_001), "past 15 s")
        assertEquals(t0 + 15_000, OpenGameRequestPolicy.deadlineMs(t0), "the deadline")
    }

    @Test
    fun `the wait ends on the fixture or on a finished load not on an idle instant`() {
        val w = OpenGameRequestPolicy.Waiter()
        assertEquals(OpenGameRequestPolicy.Outcome.Wait, w.observe(found = false, loading = false), "not loading yet is not a finished load")
        assertEquals(OpenGameRequestPolicy.Outcome.Wait, w.observe(found = false, loading = true), "loading")
        assertEquals(OpenGameRequestPolicy.Outcome.Drop, w.observe(found = false, loading = false), "the load finished without it")
        assertEquals(OpenGameRequestPolicy.Outcome.Open, OpenGameRequestPolicy.Waiter().observe(found = true, loading = true), "found while loading")
    }
}
