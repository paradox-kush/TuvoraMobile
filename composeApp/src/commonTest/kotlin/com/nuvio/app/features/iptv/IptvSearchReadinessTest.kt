package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F48 — a playlist with no catalog index is a failure the viewer can retry, never "no matches". */
class IptvSearchReadinessTest {
    @Test
    fun `a missing or zero build time is not ready`() {
        assertFalse(IptvSearchReadiness.isReady(null))
        assertFalse(IptvSearchReadiness.isReady(0L))
        assertFalse(IptvSearchReadiness.isReady(-5L))
        assertTrue(IptvSearchReadiness.isReady(1_700_000_000_000L))
    }

    @Test
    fun `requiring a missing index throws so the search ends Failed`() {
        val error = assertFailsWith<IptvSearchNotReadyException> { IptvSearchReadiness.require(null, "Movies index") }
        assertEquals("Movies index is not available yet", error.message)
        IptvSearchReadiness.require(1L, "Movies index") // ready: no throw
    }
}
