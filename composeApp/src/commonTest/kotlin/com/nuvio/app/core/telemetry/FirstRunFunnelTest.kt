package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.FirstRunStep
import com.nuvio.app.core.telemetry.internal.FirstRunFunnel
import com.nuvio.app.core.telemetry.internal.FirstRunInstallSignals
import com.nuvio.app.core.telemetry.internal.FirstRunState
import com.nuvio.app.core.telemetry.internal.FirstRunStateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure policy: no clock, no store, no SDK. State in, state + event props out. */
class FirstRunFunnelTest {
    private val t0 = 1_000_000L

    private fun newInstall(now: Long = t0): FirstRunState {
        val t = FirstRunFunnel.start(null, now) { false }
        return assertNotNull(t.state)
    }

    @Test
    fun `a new install emits first_launch with launch_index 1 and zero seconds`() {
        val t = FirstRunFunnel.start(null, t0) { false }
        val props = assertNotNull(t.emit)
        assertEquals("first_launch", props["step"])
        assertEquals(1, props["schema_version"])
        assertEquals(1, props["launch_index"])
        assertEquals(0, props["secs_since_first_launch"])
        assertEquals(t0, t.state!!.firstLaunchAtMs)
        assertTrue(!t.state!!.existing)
    }

    @Test
    fun `an existing install persists all-done and emits nothing ever`() {
        val t = FirstRunFunnel.start(null, t0) { true }
        assertNull(t.emit)
        val state = assertNotNull(t.state)
        assertTrue(state.existing)
        for (step in FirstRunStep.values()) {
            val s = FirstRunFunnel.record(state, step, t0 + 5_000, emptyMap())
            assertNull(s.emit, "step $step must stay silent on an existing install")
        }
        // and a later process start does not turn it into a new install
        val again = FirstRunFunnel.start(state, t0 + 9_000) { false }
        assertNull(again.emit)
        assertTrue(again.state?.existing != false) // unchanged (null) or still existing, never a new install
    }

    @Test
    fun `the install check is not consulted once state is persisted`() {
        val state = newInstall()
        var asked = false
        FirstRunFunnel.start(state, t0 + 1) { asked = true; true }
        assertTrue(!asked)
    }

    @Test
    fun `each step emits once and never again`() {
        var state = newInstall()
        for (step in listOf(FirstRunStep.SOURCE_ADDED, FirstRunStep.FIRST_CONTENT_LOADED, FirstRunStep.FIRST_PLAYBACK)) {
            val first = FirstRunFunnel.record(state, step, t0 + 1_000, emptyMap())
            assertEquals(step.wire, first.emit!!["step"])
            state = first.state!!
            assertNull(FirstRunFunnel.record(state, step, t0 + 2_000, emptyMap()).emit)
        }
        // first_launch itself is also done and cannot be re-fired by a step call
        assertNull(FirstRunFunnel.record(state, FirstRunStep.FIRST_LAUNCH, t0 + 3_000, emptyMap()).emit)
    }

    @Test
    fun `once-only holds across a simulated restart from persisted state`() {
        var state = newInstall()
        state = FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0 + 1_000, emptyMap()).state!!
        val restored = FirstRunStateCodec.decode(FirstRunStateCodec.encode(state))
        val boot = FirstRunFunnel.start(restored, t0 + 60_000) { false }
        assertNull(boot.emit)
        assertNull(FirstRunFunnel.record(boot.state, FirstRunStep.SOURCE_ADDED, t0 + 61_000, emptyMap()).emit)
        assertNotNull(FirstRunFunnel.record(boot.state, FirstRunStep.FIRST_PLAYBACK, t0 + 61_000, emptyMap()).emit)
    }

    @Test
    fun `later steps carry the launch index and elapsed seconds`() {
        var state = newInstall()
        state = FirstRunFunnel.start(state, t0 + 100_000) { false }.state!!
        state = FirstRunFunnel.start(state, t0 + 200_000) { false }.state!!
        val p = FirstRunFunnel.record(state, FirstRunStep.FIRST_PLAYBACK, t0 + 125_900, mapOf("kind" to "live", "engine" to "exo")).emit!!
        assertEquals(3, p["launch_index"])
        assertEquals(125, p["secs_since_first_launch"])
        assertEquals("live", p["kind"])
        assertEquals("exo", p["engine"])
    }

    @Test
    fun `a step is independent of the earlier ones`() {
        val state = newInstall()
        assertNotNull(FirstRunFunnel.record(state, FirstRunStep.FIRST_PLAYBACK, t0 + 1, emptyMap()).emit)
        assertNotNull(FirstRunFunnel.record(state, FirstRunStep.FIRST_CONTENT_LOADED, t0 + 1, emptyMap()).emit)
    }

    @Test
    fun `a step before the funnel was started is dropped`() {
        val t = FirstRunFunnel.record(null, FirstRunStep.SOURCE_ADDED, t0, emptyMap())
        assertNull(t.emit)
        assertNull(t.state)
    }

    @Test
    fun `a clock that went backwards never yields negative seconds`() {
        val state = newInstall(now = t0)
        val p = FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0 - 5_000, emptyMap()).emit!!
        assertEquals(0, p["secs_since_first_launch"])
    }

    @Test
    fun `source props use the closed vocabulary and fall back to unknown`() {
        val state = newInstall()
        val ok = FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0, mapOf("source_kind" to "m3u", "via" to "setup_code")).emit!!
        assertEquals("m3u", ok["source_kind"]); assertEquals("setup_code", ok["via"])
        val bad = FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0, mapOf("source_kind" to "http://host/x", "via" to "whatever")).emit!!
        assertEquals("unknown", bad["source_kind"]); assertEquals("unknown", bad["via"])
        val missing = FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0, emptyMap()).emit!!
        assertEquals("unknown", missing["source_kind"]); assertEquals("unknown", missing["via"])
    }

    @Test
    fun `free-text props are reduced to code-like tokens and no forbidden keys appear`() {
        val state = newInstall()
        val p = FirstRunFunnel.record(
            state, FirstRunStep.FIRST_PLAYBACK, t0,
            mapOf("kind" to "VOD", "engine" to "mpv https://secret.example/x?token=abc", "url" to "http://x", "code" to "1", "state" to "s", "token" to "t"),
        ).emit!!
        assertEquals("vod", p["kind"])
        assertTrue((p["engine"] as String).none { it == ':' || it == '/' || it == ' ' || it == '?' })
        for (k in listOf("url", "code", "state", "token")) assertTrue(k !in p, "forbidden key $k present")
        assertEquals(setOf("step", "schema_version", "launch_index", "secs_since_first_launch", "kind", "engine"), p.keys)
    }

    @Test
    fun `first_content_loaded carries only the surface`() {
        val state = newInstall()
        val p = FirstRunFunnel.record(state, FirstRunStep.FIRST_CONTENT_LOADED, t0, mapOf("surface" to "hub_categories", "x" to "y")).emit!!
        assertEquals("hub_categories", p["surface"])
        assertTrue("x" !in p)
    }

    @Test
    fun `corrupt or unknown persisted state decodes to null`() {
        assertNull(FirstRunStateCodec.decode("not json"))
        assertNull(FirstRunStateCodec.decode(""))
        assertNull(FirstRunStateCodec.decode(null))
    }

    @Test
    fun `stored blobs count as user state only when they hold something`() {
        assertTrue(!FirstRunInstallSignals.playlistsPresent(null))
        assertTrue(!FirstRunInstallSignals.playlistsPresent(" [] "))
        assertTrue(FirstRunInstallSignals.playlistsPresent("[{\"id\":\"a\"}]"))
        assertTrue(!FirstRunInstallSignals.hasEntries(null))
        assertTrue(!FirstRunInstallSignals.hasEntries("{\"entries\":[],\"dirtyProgressKeys\":[],\"deltaCursorEventId\":0}"))
        assertTrue(FirstRunInstallSignals.hasEntries("{\"entries\":[{\"id\":\"x\"}]}"))
        assertTrue(FirstRunInstallSignals.hasEntries("garbage that is not json"))
    }
}
