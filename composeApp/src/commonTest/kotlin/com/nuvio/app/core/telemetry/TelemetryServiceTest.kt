package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.internal.CrashRecordCodec
import com.nuvio.app.core.telemetry.internal.SupportCodeGenerator
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryServiceTest {

    // Unconfined: the service's background work (persist worker, onBackground) runs inline, so tests are deterministic.
    private fun newService(host: FakeTelemetryHost) = TelemetryService(host, scope = CoroutineScope(Dispatchers.Unconfined))

    private fun service(host: FakeTelemetryHost = FakeTelemetryHost()) = newService(host) to host

    @Test
    fun `the support code is generated once and then stable across starts`() {
        val (s1, host) = service()
        assertTrue(SupportCodeGenerator.isValid(s1.supportCode))
        assertEquals(s1.supportCode, host.storedCode)
        val s2 = newService(host)
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
        val s1 = newService(host1)
        s1.record(DiagEntry(host1.now - 2000, DiagArea.PLAYBACK, "decoder_init", mapOf("engine" to "exo")))
        s1.emit("\$screen", mapOf("\$screen_name" to "LiveTv"))
        val error = runCatching { check(false) { "boom from http://prov.example.tv:8080/x" } }.exceptionOrNull()!!
        s1.handleCrash("main", error)
        assertTrue(store.files.containsKey("pending_crash.json"), "the record is on disk before the process is gone")
        assertTrue(host1.sent.none { it.event == "app_crash_report" }, "nothing is sent from the dying process")

        // Run 2: next launch, 3 minutes later.
        val host2 = FakeTelemetryHost(now = 5_180_000L, store = store).also { it.storedCode = host1.storedCode }
        val s2 = newService(host2)
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
        assertFalse("prov.example" in stack, stack)
        assertFalse("message" in c.props, "exception text never leaves the device")
        assertTrue("decoder_init" in (c.props["diag_trail"] as String))
        assertEquals("LiveTv", c.props["screen"])
        assertNotNull(c.props["crash_id"])
        assertTrue(host2.flushes >= 1)
    }

    @Test
    fun `a record left in sending json is re-sent with the same crash_id`() = runTest {
        val store = FakeFileStore()
        val h1 = FakeTelemetryHost(store = store)
        newService(h1).handleCrash("main", IllegalStateException("x"))
        store.rename("pending_crash.json", "sending.json")
        val first = store.files["sending.json"]!!
        val h2 = FakeTelemetryHost(store = store)
        newService(h2).sendPendingCrash()
        val id = Regex("\"crash_id\":\"([^\"]+)\"").find(first)!!.groupValues[1]
        assertEquals(id, h2.sent.single().props["crash_id"])
    }

    @Test
    fun `a crash with consent off is deleted and never sent`() = runTest {
        val store = FakeFileStore()
        newService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val h = FakeTelemetryHost(store = store, consent = false)
        newService(h).sendPendingCrash()
        assertTrue(h.sent.isEmpty())
        assertTrue(store.files.keys.none { it.endsWith("crash.json") || it == "sending.json" })
    }

    @Test
    fun `a corrupt crash file is deleted not retried forever`() = runTest {
        val store = FakeFileStore().also { it.files["pending_crash.json"] = "{{{{ half a file" }
        val h = FakeTelemetryHost(store = store)
        newService(h).sendPendingCrash()
        assertTrue(h.sent.isEmpty())
        assertTrue(store.files.keys.none { it.endsWith("crash.json") || it == "sending.json" })
    }

    @Test
    fun `a failing sdk leaves the record for the next launch and never throws into startup`() = runTest {
        val store = FakeFileStore()
        newService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val h = FakeTelemetryHost(store = store).also { it.failCapture = true }
        newService(h).sendPendingCrash()
        assertTrue("pending_crash.json" in store.files.keys, store.files.keys.toString())
    }

    @Test
    fun `the crash handler never throws even when the disk write fails`() {
        val store = FakeFileStore().also { it.failWrites = true }
        val s = newService(FakeTelemetryHost(store = store))
        s.handleCrash("main", IllegalStateException("x"))
        assertNull(store.files["pending_crash.json"])
    }

    // --- ring persistence ---

    @Test
    fun `the ring snapshot is written on errors and the previous run's trail is readable at the next start`() {
        val store = FakeFileStore()
        val h1 = FakeTelemetryHost(store = store)
        val s1 = newService(h1)
        s1.record(DiagEntry(h1.now - 100, DiagArea.PLAYBACK, "before_kill", mapOf("engine" to "exo")))
        s1.reportError("live_playback_freeze", emptyMap())
        assertTrue("before_kill" in store.files["ring.json"]!!)
        val s2 = newService(FakeTelemetryHost(store = store))
        assertTrue("before_kill" in s2.previousSessionTrail()!!)
    }

    // ---- session_health: counts are never lost without being sent ----

    @Test
    fun `foreground without a background in between keeps the counts`() {
        val (s, host) = service()
        s.emit("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        s.onForeground() // iOS inactive -> active, an Android config change
        s.onForeground()
        s.onBackground()
        assertEquals(1, host.sent.single { it.event == "session_health" }.props["n_playback_started_exo_live"])
    }

    @Test
    fun `a failed send keeps the counts for the next background`() {
        val (s, host) = service()
        s.emit("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        host.failCapture = true
        s.onBackground()
        host.failCapture = false
        s.emit("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        s.onBackground()
        assertEquals(2, host.sent.single { it.event == "session_health" }.props["n_playback_started_exo_live"])
    }

    @Test
    fun `after a successful send the counters start over and an empty summary is never sent`() {
        val (s, host) = service()
        s.emit("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        s.onBackground()
        s.onBackground()
        assertEquals(1, host.sent.count { it.event == "session_health" })
    }

    @Test
    fun `counts recorded from another thread before the first foreground are not wiped`() {
        val (s, host) = service()
        s.emit("app_exit", mapOf("reason" to "low_memory_kill", "importance" to 400)) // AppExitReporter thread
        s.onForeground() // first onActivityStarted
        s.onBackground()
        assertEquals(1, host.sent.single { it.event == "session_health" }.props["n_app_exit_low_memory_kill"])
    }

    @Test
    fun `consent off discards the counts instead of hoarding them`() {
        val (s, host) = service()
        s.emit("playback_started", mapOf("engine" to "exo", "kind" to "live"))
        host.consent = false
        s.onBackground()
        host.consent = true
        s.onBackground()
        assertTrue(host.sent.none { it.event == "session_health" })
    }

    // ---- consent is checked BEFORE anything is written ----

    @Test
    fun `a crash while reports are off writes no record`() {
        val store = FakeFileStore()
        val host = FakeTelemetryHost(store = store, consent = false)
        newService(host).handleCrash("main", IllegalStateException("x"))
        assertFalse("pending_crash.json" in store.files)
    }

    @Test
    fun `a crash from while reports were off is not sent after they are turned on`() = runTest {
        val store = FakeFileStore()
        val off = FakeTelemetryHost(store = store, consent = false)
        newService(off).handleCrash("main", IllegalStateException("x"))
        val on = FakeTelemetryHost(store = store, consent = true)
        newService(on).sendPendingCrash()
        assertTrue(on.sent.isEmpty())
    }

    @Test
    fun `the ring is not persisted while reports are off and an old snapshot is removed`() {
        val store = FakeFileStore().also { it.files["ring.json"] = "[]" }
        val host = FakeTelemetryHost(store = store, consent = false)
        val s = newService(host)
        s.record(DiagEntry(host.now, DiagArea.SCREEN, "a"))
        s.reportError("live_playback_freeze", emptyMap())
        assertFalse("ring.json" in store.files)
    }

    // ---- privacy of the error path ----

    @Test
    fun `reportError drops a url prop and redacts url text in strings`() {
        val (s, host) = service()
        s.reportError("live_playback_freeze", mapOf("url" to "http://pro.example.tv/x", "note" to "failed http://pro.example.tv:80/y", "engine" to "exo"))
        val props = host.sent.single().props
        assertFalse("url" in props)
        assertFalse("pro.example" in (props["note"] as String), props["note"].toString())
        assertEquals("exo", props["engine"])
    }

    // ---- delivery: a flush is a request, not a delivery ----

    @Test
    fun `the crash record stays on disk after capture and flush until the app has run 90 more seconds`() = runTest {
        val store = FakeFileStore()
        val h1 = FakeTelemetryHost(store = store)
        newService(h1).handleCrash("main", IllegalStateException("x"))
        val h2 = FakeTelemetryHost(store = store)
        // A clock of its own, NOT runTest's testScheduler: sendPendingCrash hops to Dispatchers.Default, and while
        // the test body is suspended there runTest auto-advances its own scheduler, which would skip the 90 s
        // confirm delay and delete the record before the first assertion (a timing-dependent flake).
        val clock = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(clock))
        val s2 = TelemetryService(h2, scope = scope)
        s2.sendPendingCrash()
        assertEquals(1, h2.sent.size)
        assertTrue("pending_crash.json" in store.files, "flush alone must not delete the record")
        clock.advanceTimeBy(89_000L); clock.runCurrent()
        assertTrue("pending_crash.json" in store.files)
        clock.advanceTimeBy(2_000L); clock.runCurrent()
        assertFalse("pending_crash.json" in store.files, "deleted once the confirm window passed")
        assertTrue(h2.flushes >= 2, "flushed again at confirmation")
    }

    @Test
    fun `a kill inside the confirm window re-sends the same crash_id on the next launch`() = runTest {
        val store = FakeFileStore()
        newService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val h2 = FakeTelemetryHost(store = store)
        newService(h2).sendPendingCrash() // the process dies before the SDK queue reached disk
        val h3 = FakeTelemetryHost(store = store)
        newService(h3).sendPendingCrash()
        assertEquals(h2.sent.single().props["crash_id"], h3.sent.single().props["crash_id"])
    }

    @Test
    fun `a durable host deletes right after flush`() = runTest {
        val store = FakeFileStore()
        newService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val h2 = FakeTelemetryHost(store = store).also { it.deliveryDurable = true }
        newService(h2).sendPendingCrash()
        assertFalse("pending_crash.json" in store.files)
    }

    @Test
    fun `an old record and a new one are both sent in one start`() = runTest {
        val store = FakeFileStore()
        newService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("a"))
        store.rename("pending_crash.json", "sending.json")
        newService(FakeTelemetryHost(store = store).also { it.seed = 99 }).handleCrash("main", IllegalArgumentException("b"))
        val h = FakeTelemetryHost(store = store)
        newService(h).sendPendingCrash()
        assertEquals(2, h.sent.size)
    }

    // ---- crash handler under memory pressure ----

    @Test
    fun `a minimal record survives when the full write fails`() {
        val store = FakeFileStore().also { it.failWriteNumber = 2 }
        newService(FakeTelemetryHost(store = store)).handleCrash("main", IllegalStateException("x"))
        val record = CrashRecordCodec.decode(store.files["pending_crash.json"]!!)!!
        assertTrue(record.exception_type.endsWith("IllegalStateException"))
        assertTrue(record.stack.isEmpty())
    }

    @Test
    fun `a thread name with a host is not sent`() {
        val store = FakeFileStore()
        newService(FakeTelemetryHost(store = store)).handleCrash("OkHttp pro.provider.tv", IllegalStateException("x"))
        assertEquals("other", CrashRecordCodec.decode(store.files["pending_crash.json"]!!)!!.thread)
    }
}
