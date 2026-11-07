package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.internal.FlightRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlightRecorderTest {
    private fun e(i: Int) = DiagEntry(i.toLong(), DiagArea.PLAYBACK, "e$i")

    @Test
    fun `the ring keeps the newest 200 entries`() {
        val r = FlightRecorder()
        (1..350).forEach { r.record(e(it)) }
        val snap = r.snapshot()
        assertEquals(200, snap.size)
        assertEquals("e151", snap.first().code)
        assertEquals("e350", snap.last().code)
    }

    @Test
    fun `entries that fail the allow-list never enter the ring`() {
        val r = FlightRecorder()
        r.record(DiagEntry(1, DiagArea.PLAYBACK, "http://bad.example/x"))
        r.record(DiagEntry(2, DiagArea.PLAYBACK, "ok", mapOf("channel_name" to "bbc", "engine" to "exo")))
        val snap = r.snapshot()
        assertEquals(1, snap.size)
        assertEquals(setOf("engine"), snap.single().fields.keys)
    }

    @Test
    fun `tail returns the newest n`() {
        val r = FlightRecorder()
        (1..10).forEach { r.record(e(it)) }
        assertEquals(listOf("e8", "e9", "e10"), r.tail(3).map { it.code })
    }

    @Test
    fun `persisting is delta-shaped - nothing when clean and at most every 30 seconds and always on force`() {
        val r = FlightRecorder()
        assertFalse(r.shouldPersist(0L))
        r.record(e(1))
        assertTrue(r.shouldPersist(0L))
        assertFalse(r.shouldPersist(1L), "clean again")
        r.record(e(2))
        assertFalse(r.shouldPersist(10_000L), "dirty but inside 30 s")
        assertTrue(r.shouldPersist(30_000L))
        r.record(e(3))
        assertTrue(r.shouldPersist(30_001L, force = true), "an error forces it")
    }

    @Test
    fun `a failed write re-arms the dirty flag`() {
        val r = FlightRecorder()
        r.record(e(1))
        assertTrue(r.shouldPersist(0L))
        r.markDirty()
        assertTrue(r.shouldPersist(31_000L))
    }

    @Test
    fun `concurrent writers and readers never corrupt the ring`() = runTest {
        val r = FlightRecorder()
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(8) { w -> launch { repeat(2_000) { r.record(e(w * 10_000 + it)); if (it % 50 == 0) r.snapshot() } } }
                launch { repeat(500) { assertTrue(r.tail(40).size <= 40) } }
            }
        }
        assertEquals(200, r.snapshot().size)
    }
}
