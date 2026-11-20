package com.nuvio.app.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecodeHealthBridgeWireTest {

    @Test
    fun `a full line parses to signals`() {
        val s = DecodeHealthBridgeWire.parse("v1,6200,120,2,1,1,0,0,5000")!!
        assertEquals(6_200L, s.avSyncMs)
        assertEquals(120L, s.droppedFrames)
        assertEquals(2, s.audioDecoderErrors)
        assertEquals(1, s.videoDecoderErrors)
        assertEquals(true, s.audioSelected)
        assertEquals(false, s.audioAdvancing)
        assertEquals(false, s.audioMuted)
        assertEquals(50f, s.videoFps)
    }

    @Test
    fun `dashes are unknown`() {
        val s = DecodeHealthBridgeWire.parse("v1,-,0,0,0,0,-,1,-")!!
        assertNull(s.avSyncMs)
        assertNull(s.audioAdvancing)
        assertEquals(false, s.audioSelected)
        assertEquals(true, s.audioMuted)
        assertEquals(0f, s.videoFps)
    }

    @Test
    fun `a negative offset keeps its sign`() {
        assertEquals(-7_000L, DecodeHealthBridgeWire.parse("v1,-7000,0,0,0,1,1,0,-")!!.avSyncMs)
    }

    @Test
    fun `empty other versions and malformed lines parse to null`() {
        assertNull(DecodeHealthBridgeWire.parse(null))
        assertNull(DecodeHealthBridgeWire.parse(""))
        assertNull(DecodeHealthBridgeWire.parse("   "))
        assertNull(DecodeHealthBridgeWire.parse("v2,0,0,0,0,0,0,0,0"))
        assertNull(DecodeHealthBridgeWire.parse("v1,0,0,0"))
        assertNull(DecodeHealthBridgeWire.parse("v1,x,0,0,0,0,0,0,0"))
        assertNull(DecodeHealthBridgeWire.parse("v1,0,abc,0,0,0,0,0,0"))
        assertNull(DecodeHealthBridgeWire.parse("v1,0,0,0,0,2,0,0,0"))
    }

    @Test
    fun `negative counters are clamped`() {
        val s = DecodeHealthBridgeWire.parse("v1,0,-5,-1,-1,1,1,0,0")!!
        assertEquals(0L, s.droppedFrames)
        assertEquals(0, s.audioDecoderErrors)
        assertEquals(0, s.videoDecoderErrors)
    }

    @Test
    fun `the Swift writer format round trips`() {
        // What DecodeHealthShadow.wireLine produces for avsync 6.2 s, 120 dropped, 2 and 1 warns,
        // audio selected and flat, not muted, 50 fps.
        val expected = DecodeHealthPolicy.Signals(
            audioDecoderErrors = 2, videoDecoderErrors = 1, droppedFrames = 120L, audioAdvancing = false,
            audioSelected = true, audioMuted = false, avSyncMs = 6_200L, videoFps = 50f,
        )
        assertEquals(expected, DecodeHealthBridgeWire.parse("v1,6200,120,2,1,1,0,0,5000"))
    }
}
