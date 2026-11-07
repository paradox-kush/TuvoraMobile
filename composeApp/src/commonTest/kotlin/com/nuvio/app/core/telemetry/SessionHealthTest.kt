package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.SessionHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionHealthTest {
    @Test
    fun `an idle session has no summary`() = assertNull(SessionHealth(0L).snapshot(10L))

    @Test
    fun `counted events fold into closed-vocabulary keys`() {
        val h = SessionHealth(1_000L)
        h.countEvent("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "loaded", "duration_ms" to 900L))
        h.countEvent("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "loaded"))
        h.countEvent("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        h.countEvent("epg_ingest", mapOf("source" to "mirror", "outcome" to "ok"))
        val s = h.snapshot(61_000L)!!.payload
        assertEquals(2, s["n_iptv_load_hub_categories_loaded"])
        assertEquals(1, s["n_playback_started_exo_live"])
        assertEquals(1, s["n_epg_ingest_mirror_ok"])
        assertEquals(60_000L, s["window_ms"])
        assertEquals(1, s["schema_version"])
    }

    @Test
    fun `peak memory is the maximum seen`() {
        val h = SessionHealth(0L)
        h.count("x"); h.noteMemory(100L, 300L); h.noteMemory(180L, 250L); h.noteMemory(90L, null)
        val s = h.snapshot(1L)!!.payload
        assertEquals(180L, s["peak_java_mb"])
        assertEquals(300L, s["peak_pss_mb"])
    }

    @Test
    fun `the key space is bounded`() {
        val h = SessionHealth(0L, maxKeys = 5)
        repeat(50) { h.count("k$it") }
        assertEquals(5, h.snapshot(1L)!!.payload.keys.count { it.startsWith("n_") })
    }

    @Test
    fun `memory peaks alone make no summary`() {
        val h = SessionHealth(0L)
        h.noteMemory(100L, 200L)
        assertNull(h.snapshot(1L))
    }

    @Test
    fun `commit subtracts exactly what was sent so counts that landed meanwhile survive`() {
        val h = SessionHealth(0L)
        h.count("a", 3)
        val pending = h.snapshot(10L)!!
        h.count("a", 2) // lands while the summary is being sent
        h.count("b")
        h.commit(pending, 20L)
        val next = h.snapshot(30L)!!.payload
        assertEquals(2, next["n_a"])
        assertEquals(1, next["n_b"])
        assertEquals(10L, next["window_ms"])
    }

    @Test
    fun `without a commit the same counts are offered again`() {
        val h = SessionHealth(0L)
        h.count("a", 3)
        h.snapshot(10L)
        assertEquals(3, h.snapshot(20L)!!.payload["n_a"])
    }
}
