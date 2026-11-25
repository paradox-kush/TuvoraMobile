package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.SendDecision
import com.nuvio.app.core.telemetry.internal.SessionHealth
import com.nuvio.app.core.telemetry.internal.TelemetrySendPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertTelemetryPolicyTest {
    private fun decide(event: String, vararg p: Pair<String, Any>) = TelemetrySendPolicy.decide(event, mapOf(*p))

    @Test
    fun `alert choices permission results and aftermath are listed as SEND with no trail`() {
        val events = mapOf(
            "alert_setting_changed" to mapOf<String, Any>("setting" to "all_paused", "value" to "on"),
            "alert_permission_result" to mapOf("result" to "denied", "entry" to "chip"),
            "alert_aftermath" to mapOf("delivered" to 2, "acted" to 1, "backoff_tier" to 0),
        )
        for ((event, props) in events) {
            assertEquals(SendDecision.SEND, TelemetrySendPolicy.decide(event, props), event)
            assertTrue(event in TelemetrySendPolicy.explicitSendEvents, "$event is listed explicitly")
            assertFalse(TelemetrySendPolicy.attachesTrail(event, props), "$event attaches no trail")
        }
    }

    @Test
    fun `alert planning counters are counted never sent`() {
        for (event in listOf("alerts_planned", "alerts_skipped_opened_today", "alerts_delayed_quiet", "alerts_capped", "alert_meta_fetches")) {
            assertEquals(SendDecision.COUNT, decide(event, "n" to 3), event)
            assertTrue(event in TelemetrySendPolicy.alertCounterEvents)
        }
    }

    @Test
    fun `counters fold into session health by their number`() {
        val health = SessionHealth(0L)
        health.countEvent("alert_meta_fetches", mapOf("n" to 4))
        health.countEvent("alert_meta_fetches", mapOf("n" to 2))
        health.countEvent("alerts_planned", emptyMap())
        val payload = health.snapshot(1L)!!.payload
        assertEquals(6, payload["n_alert_meta_fetches"])
        assertEquals(1, payload["n_alerts_planned"])
    }

    @Test
    fun `the notifications feature and the send policy agree on the event names`() {
        assertEquals(
            com.nuvio.app.features.notifications.AlertTelemetry.sentEvents,
            TelemetrySendPolicy.explicitSendEvents - "first_run_step",
        )
        assertEquals(com.nuvio.app.features.notifications.AlertTelemetry.counterEvents, TelemetrySendPolicy.alertCounterEvents)
    }
}
