package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.PlaybackSignal
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys
import com.nuvio.app.core.telemetry.api.QaMirror
import com.nuvio.app.core.telemetry.internal.MemoryContext
import com.nuvio.app.core.telemetry.internal.MetricKitEvent
import com.nuvio.app.core.telemetry.internal.PlaybackTelemetryPrivacy
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin half of the Apple lane: memory-warning counts, the MetricKit event shape, Apple's memory names. */
class AppleTelemetryTest {
    private fun service(host: FakeTelemetryHost) = TelemetryService(host, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `memory warnings are counted by where the app was and send no event`() {
        val host = FakeTelemetryHost()
        val s = service(host)
        s.recordPlayback(PlaybackSignal.MemoryWarning(foreground = true))
        s.recordPlayback(PlaybackSignal.MemoryWarning(foreground = true))
        s.recordPlayback(PlaybackSignal.MemoryWarning(foreground = false))
        assertTrue(host.sent.isEmpty())
        s.onBackground()
        val p = host.sent.single { it.event == "session_health" }.props
        assertEquals(2, p["n_memwarn:foreground"])
        assertEquals(1, p["n_memwarn:background"])
        assertTrue(PlaybackTelemetryPrivacy.violations(p).isEmpty(), PlaybackTelemetryPrivacy.violations(p).toString())
    }

    @Test
    fun `memwarn keys accept only the two places`() {
        assertTrue(PlaybackTelemetryKeys.isValidKey("memwarn:foreground"))
        assertTrue(PlaybackTelemetryKeys.isValidKey("memwarn:background"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("memwarn:somewhere"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("memwarn"))
    }

    @Test
    fun `memory warnings with the switch off count nothing`() {
        val host = FakeTelemetryHost(consent = false)
        val s = service(host)
        s.recordPlayback(PlaybackSignal.MemoryWarning(foreground = true))
        s.onBackground()
        assertTrue(host.sent.none { it.event == "session_health" })
    }

    @Test
    fun `memory warning is mirrored to the QA log with exact fields only`() {
        val host = FakeTelemetryHost().apply { qaMirrorEnabled = true }
        service(host).recordPlayback(PlaybackSignal.MemoryWarning(foreground = false))
        assertEquals(listOf("""{"ev":"memwarn","where":"background"}"""), host.qaLines)
        assertEquals("memwarn", QaMirror.fieldsFor(PlaybackSignal.MemoryWarning(true)).first)
    }

    @Test
    fun `unclean exit carries the memory warning count and passes the privacy validator`() {
        val host = FakeTelemetryHost()
        host.memory = mapOf("footprint_mb" to 1_650L, "avail_mb" to 90L)
        service(host).sampleMemoryOnce()
        service(host).recordUncleanExit(wasPlaying = true, kind = "live", engine = "avplayer", memWarnings = 3)
        val sent = host.sent.single { it.event == "app_exit" }.props
        assertEquals(3, sent["mem_warnings"])
        assertEquals(1_650L, sent["mem_footprint_mb"])
        assertEquals(90L, sent["mem_avail_mb"])
        val checked = sent - "diag_trail"
        assertTrue(PlaybackTelemetryPrivacy.violations(checked).isEmpty(), PlaybackTelemetryPrivacy.violations(checked).toString())
    }

    @Test
    fun `no warnings leaves the property out`() {
        val host = FakeTelemetryHost()
        service(host).recordUncleanExit(wasPlaying = false)
        assertNull(host.sent.single { it.event == "app_exit" }.props["mem_warnings"])
    }

    @Test
    fun `apple memory names are event properties`() {
        assertEquals(
            mapOf("mem_footprint_mb" to 5L, "mem_avail_mb" to 6L),
            MemoryContext.asProperties(mapOf("footprint_mb" to 5L, "avail_mb" to 6L, "ram_total_mb" to 9L)),
        )
    }

    @Test
    fun `metrickit event carries counts under closed keys only`() {
        val props = MetricKitEvent.build(
            "metrics",
            mapOf("fg_watchdog" to 2, "bg_memory_pressure" to 1, "fg_normal" to 40, "bogus" to 9, "bg_cpu_limit" to 0),
            windowHours = 24,
        )!!
        assertEquals(
            mapOf(
                "metrickit_source" to "metrics", "mk_fg_normal" to 40, "mk_fg_watchdog" to 2,
                "mk_bg_memory_pressure" to 1, "mk_window_h" to 24,
            ),
            props,
        )
        assertTrue(PlaybackTelemetryPrivacy.violations(props).isEmpty(), PlaybackTelemetryPrivacy.violations(props).toString())
    }

    @Test
    fun `a routine only payload is not an event`() {
        assertNull(MetricKitEvent.build("metrics", mapOf("fg_normal" to 12, "bg_normal" to 30), 24))
        assertNull(MetricKitEvent.build("diagnostics", emptyMap(), 24))
    }

    @Test
    fun `metrickit values are clamped and an unknown source is dropped`() {
        val props = MetricKitEvent.build("diagnostics", mapOf("crash" to 5_000_000, "hang" to -4), windowHours = 9_999)!!
        assertEquals(MetricKitEvent.MAX_COUNT, props["mk_crash"])
        assertNull(props["mk_hang"])
        assertEquals(MetricKitEvent.MAX_WINDOW_HOURS, props["mk_window_h"])
        assertNull(MetricKitEvent.build("payload text", mapOf("crash" to 1), 1))
    }

    @Test
    fun `metrickit payload sends through the port and nothing with the switch off`() {
        val host = FakeTelemetryHost()
        val s = service(host)
        s.emit(MetricKitEvent.EVENT, MetricKitEvent.build("diagnostics", mapOf("crash" to 1, "hang" to 2), 24)!!)
        assertEquals(1, host.sent.count { it.event == MetricKitEvent.EVENT })
        val off = FakeTelemetryHost(consent = false)
        service(off).emit(MetricKitEvent.EVENT, MetricKitEvent.build("diagnostics", mapOf("crash" to 1), 24)!!)
        assertTrue(off.sent.isEmpty())
    }
}
