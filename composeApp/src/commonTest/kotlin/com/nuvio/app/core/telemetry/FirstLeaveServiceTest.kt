package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.FirstRunStep
import com.nuvio.app.core.telemetry.api.funnelSaveFailed
import com.nuvio.app.core.telemetry.api.funnelSaveTried
import com.nuvio.app.core.telemetry.api.funnelScreen
import com.nuvio.app.core.telemetry.api.funnelSourceFormOpened
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** first_leave wired into the service: lifecycle, once-gate, consent, persistence, races, flush. */
class FirstLeaveServiceTest {
    private fun svc(host: FakeTelemetryHost) = TelemetryService(host, scope = CoroutineScope(Dispatchers.Unconfined))
    private fun FakeTelemetryHost.leaves() = sent.filter { it.event == "first_run_step" && it.props["step"] == "first_leave" }
    private fun ready(host: FakeTelemetryHost = FakeTelemetryHost()) = svc(host).also { it.onSdkReady(); it.startFirstRunFunnel { false } } to host

    @Test
    fun `first_leave fires on the first background with the visit summary`() {
        val (s, host) = ready()
        s.onForeground()
        s.funnelScreen("Tabs.Home"); s.funnelScreen("settings_page")
        s.funnelSourceFormOpened(); s.funnelSaveTried(); s.funnelSaveFailed("unreachable")
        host.now += 30_000
        s.onBackground()
        val p = host.leaves().single().props
        assertEquals("settings_page", p["last_screen"])
        assertEquals(2, p["screens_seen"])
        assertEquals(30, p["secs_in_app"])
        assertEquals(true, p["opened_source_form"])
        assertEquals(true, p["tried_save"])
        assertEquals("unreachable", p["save_error"])
        assertEquals(1, p["launch_index"])
        assertEquals(30, p["secs_since_first_launch"])
        assertTrue("diag_trail" !in p)
    }

    @Test
    fun `it is flushed right after it is captured`() {
        val (s, host) = ready()
        val before = host.flushes
        s.onBackground()
        assertEquals(1, host.leaves().size)
        assertTrue(host.flushes > before, "the SDK flush must be requested after first_leave is captured")
    }

    @Test
    fun `it fires once even when the person comes back and leaves again`() {
        val (s, host) = ready()
        s.onBackground(); s.onForeground(); s.onBackground(); s.onBackground()
        assertEquals(1, host.leaves().size)
    }

    @Test
    fun `it never fires after first_playback`() {
        val (s, host) = ready()
        s.firstRunStep(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "vod", "engine" to "exo"))
        s.onBackground()
        assertTrue(host.leaves().isEmpty())
    }

    @Test
    fun `it never fires for an existing install`() {
        val host = FakeTelemetryHost().apply { storedCode = "K7Q29MPX" }
        val s = svc(host).also { it.onSdkReady(); it.startFirstRunFunnel { false } }
        s.onBackground()
        assertTrue(host.leaves().isEmpty())
    }

    @Test
    fun `consent off marks it done and it is never sent after consent is turned on`() {
        val host = FakeTelemetryHost(consent = false)
        val (s, _) = ready(host)
        s.onBackground()
        assertTrue(host.leaves().isEmpty())
        host.consent = true
        s.onForeground(); s.onBackground()
        assertTrue(host.leaves().isEmpty())
    }

    @Test
    fun `two concurrent background signals send one event`() {
        val (s, host) = ready()
        runBlocking { (1..8).map { async(Dispatchers.Default) { repeat(50) { s.onBackground() } } }.awaitAll() }
        assertEquals(1, host.leaves().size)
    }

    @Test
    fun `the form and save facts survive a restart and a leave in the next launch carries them`() {
        val (s, host) = ready()
        s.funnelSourceFormOpened(); s.funnelSaveTried(); s.funnelSaveFailed("wrong_credentials")
        host.now += 5_000
        val s2 = svc(host).also { it.onSdkReady(); it.startFirstRunFunnel { false } }
        s2.funnelScreen("Tabs.Iptv")
        host.now += 7_000
        s2.onBackground()
        val p = host.leaves().single().props
        assertEquals(2, p["launch_index"])
        assertEquals(true, p["opened_source_form"])
        assertEquals(true, p["tried_save"])
        assertEquals("wrong_credentials", p["save_error"])
        assertEquals("tabs.iptv", p["last_screen"])
        assertEquals(1, p["screens_seen"])
    }

    @Test
    fun `a screen name with spaces uppercase and slashes is sent clean`() {
        val (s, host) = ready()
        s.funnelScreen("Some Movie/Title?")
        s.onBackground()
        assertEquals("some_movie_title", host.leaves().single().props["last_screen"])
    }

    @Test
    fun `no save failure reads none and an empty visit reads other`() {
        val (s, host) = ready()
        s.onBackground()
        val p = host.leaves().single().props
        assertEquals("none", p["save_error"])
        assertEquals("other", p["last_screen"])
        assertEquals(false, p["opened_source_form"])
    }

    @Test
    fun `a leave before the sdk is ready is held and sent when it is`() {
        val host = FakeTelemetryHost()
        val s = svc(host)
        s.startFirstRunFunnel { false }
        s.onBackground()
        assertTrue(host.leaves().isEmpty())
        s.onSdkReady()
        assertEquals(1, host.leaves().size)
    }

    @Test
    fun `no forbidden property names ride on the event`() {
        val (s, host) = ready()
        s.onBackground()
        val p = host.leaves().single().props
        for (k in listOf("code", "state", "url", "token")) assertTrue(k !in p, k)
    }
}
