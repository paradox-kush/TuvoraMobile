package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.SendDecision
import com.nuvio.app.core.telemetry.internal.TelemetrySendPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetrySendPolicyTest {
    private fun decide(event: String, vararg p: Pair<String, Any>) = TelemetrySendPolicy.decide(event, mapOf(*p))

    @Test
    fun `screen views stay in the recorder`() = assertEquals(SendDecision.RECORD_ONLY, decide("\$screen"))

    @Test
    fun `playback_started and index builds are counted not sent`() {
        assertEquals(SendDecision.COUNT, decide("playback_started", "engine" to "exo"))
        assertEquals(SendDecision.COUNT, decide("iptv_index_build"))
    }

    @Test
    fun `iptv_load successes are counted and failures are sent`() {
        assertEquals(SendDecision.COUNT, decide("iptv_load", "outcome" to "loaded"))
        assertEquals(SendDecision.COUNT, decide("iptv_load", "outcome" to "empty"))
        assertEquals(SendDecision.SEND, decide("iptv_load", "outcome" to "failed"))
        assertEquals(SendDecision.SEND, decide("iptv_load", "outcome" to "timeout"))
    }

    @Test
    fun `epg ingest counts ok empty skipped and sends errors`() {
        for (ok in listOf("ok", "empty", "skipped")) assertEquals(SendDecision.COUNT, decide("epg_ingest", "outcome" to ok))
        assertEquals(SendDecision.SEND, decide("epg_ingest", "outcome" to "error"))
    }

    @Test
    fun `epg_resolve is a count unless the panel ask failed`() {
        assertEquals(SendDecision.COUNT, decide("epg_resolve", "none" to 3, "unavailable" to 0))
        assertEquals(SendDecision.SEND, decide("epg_resolve", "unavailable" to 5))
    }

    @Test
    fun `our own tagged exit is counted even at foreground importance`() {
        assertEquals(SendDecision.COUNT, decide("app_exit", "reason" to "self_exit", "importance" to 100))
        assertEquals(SendDecision.SEND, decide("app_exit", "reason" to "signaled", "importance" to 100))
    }

    @Test
    fun `app_exit sends crashes anrs and foreground kills and counts background kills`() {
        assertEquals(SendDecision.SEND, decide("app_exit", "reason" to "crash"))
        assertEquals(SendDecision.SEND, decide("app_exit", "reason" to "native_crash"))
        assertEquals(SendDecision.SEND, decide("app_exit", "reason" to "anr"))
        assertEquals(SendDecision.SEND, decide("app_exit", "reason" to "low_memory", "importance" to "foreground"))
        assertEquals(SendDecision.COUNT, decide("app_exit", "reason" to "low_memory", "importance" to "cached"))
        assertEquals(SendDecision.COUNT, decide("app_exit", "reason" to "user_requested", "importance" to "cached"))
        // Android hands the raw ActivityManager importance: 100 foreground .. 270 user-visible, 400 cached.
        assertEquals(SendDecision.SEND, decide("app_exit", "reason" to "low_memory_kill", "importance" to 100))
        assertEquals(SendDecision.COUNT, decide("app_exit", "reason" to "low_memory_kill", "importance" to 400))
    }

    @Test
    fun `unknown events are sent so a new event is never silently swallowed`() {
        assertEquals(SendDecision.SEND, decide("live_playback_freeze"))
        assertEquals(SendDecision.SEND, decide("something_new"))
    }

    @Test
    fun `error-shaped events attach the trail and routine ones do not`() {
        assertTrue(TelemetrySendPolicy.attachesTrail("live_playback_freeze", emptyMap()))
        assertTrue(TelemetrySendPolicy.attachesTrail("iptv_load", mapOf("outcome" to "timeout")))
        assertTrue(TelemetrySendPolicy.attachesTrail("app_uncaught_exception_ios", emptyMap()))
        assertFalse(TelemetrySendPolicy.attachesTrail("simkl_sync", mapOf("outcome" to "ok")))
    }

    @Test
    fun `addon_bridge_state counts the healthy states and sends the failing ones`() {
        assertEquals(SendDecision.COUNT, decide("addon_bridge_state", "state" to "none"))
        assertEquals(SendDecision.COUNT, decide("addon_bridge_state", "state" to "ready"))
        for (bad in listOf("preparing", "failed", "disconnected", "unreachable")) {
            assertEquals(SendDecision.SEND, decide("addon_bridge_state", "state" to bad))
        }
    }

    @Test
    fun `first_run_step is listed as SEND and attaches no trail`() {
        val props = mapOf<String, Any>("step" to "first_launch", "schema_version" to 1, "launch_index" to 1, "secs_since_first_launch" to 0)
        assertEquals(SendDecision.SEND, decide("first_run_step", *props.toList().toTypedArray()))
        assertFalse(TelemetrySendPolicy.attachesTrail("first_run_step", props))
        assertTrue("first_run_step" in TelemetrySendPolicy.explicitSendEvents)
    }

    @Test
    fun `first_leave is sent as first_run_step with no trail`() {
        val props = mapOf<String, Any>(
            "step" to "first_leave", "schema_version" to 1, "launch_index" to 1, "secs_since_first_launch" to 20,
            "last_screen" to "tabs.home", "secs_in_app" to 20, "screens_seen" to 2,
            "opened_source_form" to true, "tried_save" to true, "save_error" to "unreachable",
        )
        assertEquals(SendDecision.SEND, decide("first_run_step", *props.toList().toTypedArray()))
        assertFalse(TelemetrySendPolicy.attachesTrail("first_run_step", props))
    }
}
