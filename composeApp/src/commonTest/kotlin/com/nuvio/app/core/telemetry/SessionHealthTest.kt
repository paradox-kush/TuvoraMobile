package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.SessionHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionHealthTest {
    @Test
    fun `an idle session has no summary`() = assertNull(SessionHealth(0L).summary(10L))

    @Test
    fun `counted events fold into closed-vocabulary keys`() {
        val h = SessionHealth(1_000L)
        h.countEvent("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "loaded", "duration_ms" to 900L))
        h.countEvent("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "loaded"))
        h.countEvent("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        h.countEvent("epg_ingest", mapOf("source" to "mirror", "outcome" to "ok"))
        val s = h.summary(61_000L)!!
        assertEquals(2, s["n_iptv_load_hub_categories_loaded"])
        assertEquals(1, s["n_playback_started_exo_live"])
        assertEquals(1, s["n_epg_ingest_mirror_ok"])
        assertEquals(60_000L, s["session_ms"])
        assertEquals(1, s["schema_version"])
    }

    @Test
    fun `peak memory is the maximum seen`() {
        val h = SessionHealth(0L)
        h.count("x"); h.noteMemory(100L, 300L); h.noteMemory(180L, 250L); h.noteMemory(90L, null)
        val s = h.summary(1L)!!
        assertEquals(180L, s["peak_java_mb"])
        assertEquals(300L, s["peak_pss_mb"])
    }

    @Test
    fun `the key space is bounded`() {
        val h = SessionHealth(0L, maxKeys = 5)
        repeat(50) { h.count("k$it") }
        assertEquals(5, h.summary(1L)!!.keys.count { it.startsWith("n_") })
    }
}
