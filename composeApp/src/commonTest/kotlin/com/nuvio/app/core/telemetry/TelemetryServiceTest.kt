package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.internal.SupportCodeGenerator
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryServiceTest {

    private fun service(host: FakeTelemetryHost = FakeTelemetryHost()) = TelemetryService(host) to host

    @Test
    fun `the support code is generated once and then stable across starts`() {
        val (s1, host) = service()
        assertTrue(SupportCodeGenerator.isValid(s1.supportCode))
        assertEquals(s1.supportCode, host.storedCode)
        val s2 = TelemetryService(host)
        assertEquals(s1.supportCode, s2.supportCode)
    }

    @Test
    fun `sdk ready registers the support code as a super property`() {
        val (s, host) = service()
        s.onSdkReady()
        assertEquals(s.supportCode, host.superProps.first()["support_code"])
    }

    // --- error-only sending ---

    @Test
    fun `counted events send nothing and the summary carries them on background`() {
        val (s, host) = service()
        s.emit("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "loaded", "duration_ms" to 800L))
        s.emit("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        assertTrue(host.sent.isEmpty())
        s.onBackground()
        val health = host.sent.single { it.event == "session_health" }
        assertEquals(1, health.props["n_iptv_load_hub_categories_loaded"])
        assertEquals(1, health.props["n_playback_started_exo_live"])
    }

    @Test
    fun `an idle session sends no session_health`() {
        val (s, host) = service()
        s.onBackground()
        assertTrue(host.sent.none { it.event == "session_health" })
    }

    @Test
    fun `a failure is sent immediately with the trail of what led up to it`() {
        val (s, host) = service()
        s.record(DiagEntry(host.now - 5000, DiagArea.PLAYBACK, "prepare", mapOf("engine" to "exo")))
        s.emit("iptv_load", mapOf("surface" to "hub_categories", "outcome" to "timeout", "duration_ms" to 25_000L))
        val sent = host.sent.single()
        assertEquals("iptv_load", sent.event)
        val trail = sent.props["diag_trail"] as String
        assertTrue("prepare" in trail && "iptv_load" in trail, trail)
        assertEquals(1, sent.props["schema_version"])
    }

    @Test
    fun `screens go to the recorder and never to the wire`() {
        val (s, host) = service()
        s.emit("\$screen", mapOf("\$screen_name" to "Home"))
        assertTrue(host.sent.isEmpty())
        assertTrue(s.recorder.snapshot().any { it.area == DiagArea.SCREEN && it.fields["screen"] == "home" })
    }

    @Test
    fun `consent off sends nothing at all but the recorder keeps working`() {
        val (s, host) = service(FakeTelemetryHost(consent = false))
        s.emit("iptv_load", mapOf("outcome" to "failed"))
        s.reportError("live_playback_freeze", mapOf("engine" to "exo"))
        s.onBackground()
        assertTrue(host.sent.isEmpty())
        assertTrue(s.recorder.snapshot().isNotEmpty())
    }

    @Test
    fun `reportError sends the event with its trail`() {
        val (s, host) = service()
        s.reportError("live_playback_freeze", mapOf("engine" to "exo", "url" to "http://x"))
        val sent = host.sent.single()
        assertTrue("diag_trail" in sent.props)
    }

    // --- Send report ---

    @Test
    fun `send report with consent off says so and sends nothing`() = runTest {
        val (s, host) = service(FakeTelemetryHost(consent = false))
        val r = s.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS)
        assertEquals(ReportResult.ConsentOff, r)
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `send report sends one user_issue_report with trail memory and hardware and and returns the display code`() = runTest {
        val (s, host) = service()
        s.record(DiagEntry(host.now - 1000, DiagArea.PLAYBACK, "stall", mapOf("engine" to "mpv")))
        // let the background hardware probe land
        val r = s.sendUserReport(ReportCategory.FREEZES, ReportSurface.LIVE, ReportContext(failureCode = "freeze", engine = "mpv"))
        assertIs<ReportResult.Sent>(r)
        assertEquals(SupportCodeGenerator.display(s.supportCode), r.supportCode)
        val sent = host.sent.single { it.event == "user_issue_report" }
        assertEquals("freezes", sent.props["category"])
        assertEquals("live", sent.props["surface"])
        assertEquals("freeze", sent.props["failure_code"])
        assertEquals("mpv", sent.props["engine"])
        assertNotNull(sent.props["report_id"])
        assertEquals(120L, sent.props["mem_java_mb"])
        assertTrue("stall" in (sent.props["diag_trail"] as String))
        assertTrue(host.flushes >= 1)
    }

    @Test
    fun `a second report inside two minutes is rate limited`() = runTest {
        val (s, host) = service()
        assertIs<ReportResult.Sent>(s.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS))
        host.now += 30_000L
        val r = s.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS)
        assertIs<ReportResult.RateLimited>(r)
        assertEquals(90, r.retryInSec)
        assertEquals(1, host.sent.count { it.event == "user_issue_report" })
        host.now += 90_000L
        assertIs<ReportResult.Sent>(s.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS))
    }

    @Test
    fun `a failing send is Failed not Sent and does not burn the rate window`() = runTest {
        val (s, host) = service()
        host.failCapture = true
        assertEquals(ReportResult.Failed, s.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS))
        host.failCapture = false
        assertIs<ReportResult.Sent>(s.sendUserReport(ReportCategory.OTHER, ReportSurface.SETTINGS))
    }

    // --- crash path (the regression for "Java crashes arrive with no stack") ---

    @Test
    fun `REGRESSION - an uncaught exception yields exactly one app_crash_report with a stack on the next start`() = runTest {
        val store = FakeFileStore()
        // Run 1: the process dies.
        val host1 = FakeTelemetryHost(now = 5_000_000L, store = store)
        val s1 = TelemetryService(host1)
        s1.record(DiagEntry(host1.now - 2000, DiagArea.PLAYBACK, "decoder_init", mapOf("engine" to "exo")))
        s1.emit("\$screen", mapOf("\$screen_name" to "LiveTv"))
        val error = runCatching { check(false) { "boom from http://prov.example.tv:8080/x" } }.exceptionOrNull()!!
        s1.handleCrash("main", error)
        assertTrue(store.files.containsKey("pending_crash.json"), "the record is on disk before the process is gone")
        assertTrue(host1.sent.none { it.event == "app_crash_report" }, "nothing is sent from the dying process")

        // Run 2: next launch, 3 minutes later.
        val host2 = FakeTelemetryHost(now = 5_180_000L, store = store).also { it.storedCode = host1.storedCode }
        val s2 = TelemetryService(host2)
        s2.sendPendingCrash()
        s2.sendPendingCrash() // a second call (e.g. a restart of startup) must not send again
        val crashes = host2.sent.filter { it.event == "app_crash_report" }
        assertEquals(1, crashes.size)
        val c = crashes.single()
        assertEquals(5_000_000L, c.atMs, "event time is the crash time, not the send time")
        assertEquals(5_000_000L, c.props["crashed_at_ms"])
        assertEquals(s1.supportCode, c.props["support_code"])
        // "java.lang.IllegalStateException" on the JVM, "kotlin.IllegalStateException" on Native.
        assertTrue((c.props["exception_type"] as String).endsWith("IllegalStateException"), c.props["exception_type"].toString())
        val stack = c.props["stack"] as String
        assertTrue(stack.lines().size > 1 && "IllegalStateException" in stack, stack)
        assertFalse("prov.example" in stack || "prov.example" in (c.props["message"] as String))
        assertTrue("decoder_init" in (c.props["diag_trail"] as String))
        assertEquals("LiveTv", c.props["screen"])
        assertNotNull(c.props["crash_id"])
        assertTrue(store.files.keys.none { it.endsWith("crash.json") || it == "sending.json" }, "crash files deleted after send: ${store.files.keys}")
        assertTrue(host2.flushes >= 1)
    }

    @Test
    fun `a record left in sending json is re-sent with the same crash_id`() = runTest {
        val store = FakeFileStore()
        val h1 = FakeTelemetryHost(store = store)
        TelemetryService(h1).handleCrash("main", IllegalStateException("x"))
        store.rename("pending_crash.json", "sending.json")
        val first = store.files["sending.json"]!!
        val h2 = FakeTelemetryHost(store = store)
        TelemetryService(h2).sendPendingCrash()
        val id = Regex("\"crash_id\":\"([^\"]+)\"").find(first)!!.groupValues[1]
        assertEquals(id, h2.sent.single().props["crash_id"])
    }

    @Test
    fun `a crash with consent off is deleted and never sent`() = runTest {
        val store = FakeFileStore()
        TelemetryService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val h = FakeTelemetryHost(store = store, consent = false)
        TelemetryService(h).sendPendingCrash()
        assertTrue(h.sent.isEmpty())
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `a corrupt crash file is deleted not retried forever`() = runTest {
        val store = FakeFileStore().also { it.files["pending_crash.json"] = "{{{{ half a file" }
        val h = FakeTelemetryHost(store = store)
        TelemetryService(h).sendPendingCrash()
        assertTrue(h.sent.isEmpty())
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `a failing sdk leaves the record for the next launch and never throws into startup`() = runTest {
        val store = FakeFileStore()
        TelemetryService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val h = FakeTelemetryHost(store = store).also { it.failCapture = true }
        TelemetryService(h).sendPendingCrash()
        assertEquals(setOf("sending.json"), store.files.keys)
    }

    @Test
    fun `the crash handler never throws even when the disk write fails`() {
        val store = FakeFileStore().also { it.failWrites = true }
        val s = TelemetryService(FakeTelemetryHost(store = store))
        s.handleCrash("main", IllegalStateException("x"))
        assertNull(store.files["pending_crash.json"])
    }

    // --- ring persistence ---

    @Test
    fun `the ring snapshot is written on errors and the previous run's trail is readable at the next start`() {
        val store = FakeFileStore()
        val h1 = FakeTelemetryHost(store = store)
        val s1 = TelemetryService(h1)
        s1.record(DiagEntry(h1.now - 100, DiagArea.PLAYBACK, "before_kill", mapOf("engine" to "exo")))
        s1.reportError("live_playback_freeze", emptyMap())
        assertTrue("before_kill" in store.files["ring.json"]!!)
        val s2 = TelemetryService(FakeTelemetryHost(store = store))
        assertTrue("before_kill" in s2.previousSessionTrail()!!)
    }
}
