package com.nuvio.app.features.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpgFetchBoundsTest {
    @Test
    fun `joins chunks within the cap`() {
        assertEquals("abcdefg", boundedJoin(10) { emit -> emit("abc"); emit("defg") })
    }

    @Test
    fun `content exactly at the cap is kept`() {
        assertEquals("abcdef", boundedJoin(6) { emit -> emit("abc"); emit("def") })
    }

    @Test
    fun `rejects when the accumulation exceeds the cap`() {
        assertNull(boundedJoin(5) { emit -> emit("abc"); emit("def") }, "over the cap → reject, keep prior generation")
    }

    @Test
    fun `stays bounded even if the producer swallows the abort and keeps emitting`() {
        // Models a streamer that catches the abort throw and keeps feeding: memory must stay bounded
        // and the result must still be a rejection.
        val out = boundedJoin(4) { emit ->
            repeat(100) {
                try {
                    emit("xx")
                } catch (_: EpgResponseTooLargeException) {
                    // swallow, like some line streamers might
                }
            }
        }
        assertNull(out)
    }

    @Test
    fun `empty producer yields empty string`() {
        assertEquals("", boundedJoin(10) { })
    }
}
