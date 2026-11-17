package com.nuvio.app.features.player

import com.nuvio.app.features.player.StreamHttpStatusHintPolicy.Hint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamHttpStatusHintPolicyTest {

    @Test
    fun `456 is the provider firewall as on Android TV`() {
        assertEquals(Hint.PROVIDER_FIREWALL, StreamHttpStatusHintPolicy.hintFor(456))
    }

    @Test
    fun `458 and 509 are the account connection limit`() {
        assertEquals(Hint.CONNECTION_LIMIT, StreamHttpStatusHintPolicy.hintFor(458))
        assertEquals(Hint.CONNECTION_LIMIT, StreamHttpStatusHintPolicy.hintFor(509), "509 must not read as a plain 5xx")
    }

    @Test
    fun `the common statuses keep their existing meaning`() {
        assertEquals(Hint.EXPIRED, StreamHttpStatusHintPolicy.hintFor(401))
        assertEquals(Hint.EXPIRED, StreamHttpStatusHintPolicy.hintFor(410))
        assertEquals(Hint.BLOCKED, StreamHttpStatusHintPolicy.hintFor(403))
        assertEquals(Hint.REMOVED, StreamHttpStatusHintPolicy.hintFor(404))
        assertEquals(Hint.RATE_LIMITED, StreamHttpStatusHintPolicy.hintFor(429))
        assertEquals(Hint.SERVER_UNAVAILABLE, StreamHttpStatusHintPolicy.hintFor(500))
        assertEquals(Hint.SERVER_UNAVAILABLE, StreamHttpStatusHintPolicy.hintFor(503))
        assertEquals(Hint.BLOCKED, StreamHttpStatusHintPolicy.hintFor(451))
    }

    @Test
    fun `a success or redirect status has no hint`() {
        assertNull(StreamHttpStatusHintPolicy.hintFor(200))
        assertNull(StreamHttpStatusHintPolicy.hintFor(302))
    }
}
