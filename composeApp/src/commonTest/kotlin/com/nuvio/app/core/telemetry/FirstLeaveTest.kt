package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.FirstRunNote
import com.nuvio.app.core.telemetry.api.FirstRunStep
import com.nuvio.app.core.telemetry.internal.FirstLeavePolicy
import com.nuvio.app.core.telemetry.internal.FirstLeaveVisit
import com.nuvio.app.core.telemetry.internal.FirstRunFunnel
import com.nuvio.app.core.telemetry.internal.FirstRunState
import com.nuvio.app.core.telemetry.internal.FirstRunStateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure first_leave pieces: no clock, no store, no SDK. */
class FirstLeaveTest {
    private val t0 = 1_000_000L

    private fun newInstall(): FirstRunState = assertNotNull(FirstRunFunnel.start(null, t0) { false }.state)

    private fun leave(state: FirstRunState, now: Long = t0 + 5_000, visit: Map<String, Any> = emptyMap()) =
        FirstRunFunnel.record(state, FirstRunStep.FIRST_LEAVE, now, visit)

    @Test
    fun `first_leave emits once on a new install and never again`() {
        val s = newInstall()
        val t = leave(s)
        val p = assertNotNull(t.emit)
        assertEquals("first_leave", p["step"])
        assertEquals(1, p["schema_version"])
        assertEquals(1, p["launch_index"])
        assertEquals(5, p["secs_since_first_launch"])
        assertNull(leave(assertNotNull(t.state)).emit)
    }

    @Test
    fun `first_leave never fires after first_playback`() {
        val played = assertNotNull(FirstRunFunnel.record(newInstall(), FirstRunStep.FIRST_PLAYBACK, t0, mapOf("kind" to "live")).state)
        val t = leave(played)
        assertNull(t.emit)
    }

    @Test
    fun `first_leave never fires for an existing install`() {
        val existing = assertNotNull(FirstRunFunnel.start(null, t0) { true }.state)
        assertNull(leave(existing).emit)
    }

    @Test
    fun `first_leave carries the persisted form and save facts`() {
        var s = newInstall()
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SourceFormOpened))
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveTried))
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("wrong_credentials")))
        val p = assertNotNull(leave(s, visit = mapOf("last_screen" to "settings_page", "secs_in_app" to 42, "screens_seen" to 3)).emit)
        assertEquals("settings_page", p["last_screen"])
        assertEquals(42, p["secs_in_app"])
        assertEquals(3, p["screens_seen"])
        assertEquals(true, p["opened_source_form"])
        assertEquals(true, p["tried_save"])
        assertEquals("wrong_credentials", p["save_error"])
    }

    @Test
    fun `without a form or a failed save the flags are false and save_error is none`() {
        val p = assertNotNull(leave(newInstall()).emit)
        assertEquals(false, p["opened_source_form"])
        assertEquals(false, p["tried_save"])
        assertEquals("none", p["save_error"])
        assertEquals("other", p["last_screen"])
        assertEquals(0, p["secs_in_app"])
        assertEquals(0, p["screens_seen"])
    }

    @Test
    fun `save_error is the last failure and an unknown kind becomes other`() {
        var s = newInstall()
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("unreachable")))
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("invalid_address")))
        assertEquals("invalid_address", assertNotNull(leave(s).emit)["save_error"])
        val weird = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("http://host.example/x?token=abc")))
        assertEquals("other", assertNotNull(leave(weird).emit)["save_error"])
    }

    @Test
    fun `notes change nothing on an existing install and once the leave gate is closed`() {
        val existing = assertNotNull(FirstRunFunnel.start(null, t0) { true }.state)
        assertNull(FirstRunFunnel.note(existing, FirstRunNote.SaveTried))
        assertNull(FirstRunFunnel.note(null, FirstRunNote.SaveTried))
        val left = assertNotNull(leave(newInstall()).state)
        assertNull(FirstRunFunnel.note(left, FirstRunNote.SaveTried))
        val played = assertNotNull(FirstRunFunnel.record(newInstall(), FirstRunStep.FIRST_PLAYBACK, t0, emptyMap()).state)
        assertNull(FirstRunFunnel.note(played, FirstRunNote.SourceFormOpened))
        // A screen note is in-memory only: it never touches the persisted state.
        assertNull(FirstRunFunnel.note(newInstall(), FirstRunNote.Screen("home")))
    }

    @Test
    fun `an unchanged flag is not a state change`() {
        val s = assertNotNull(FirstRunFunnel.note(newInstall(), FirstRunNote.SaveTried))
        assertNull(FirstRunFunnel.note(s, FirstRunNote.SaveTried))
    }

    @Test
    fun `the flags survive a restart from persisted state`() {
        var s = newInstall()
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SourceFormOpened))
        s = assertNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("unreachable")))
        val back = assertNotNull(FirstRunStateCodec.decode(FirstRunStateCodec.encode(s)))
        val relaunched = assertNotNull(FirstRunFunnel.start(back, t0 + 60_000) { false }.state)
        val p = assertNotNull(leave(relaunched, now = t0 + 61_000).emit)
        assertEquals(2, p["launch_index"])
        assertEquals(true, p["opened_source_form"])
        assertEquals("unreachable", p["save_error"])
    }

    @Test
    fun `state persisted before this feature decodes with the flags off`() {
        val old = """{"existing":false,"firstLaunchAtMs":5,"launchIndex":3,"done":["first_launch"]}"""
        val s = assertNotNull(FirstRunStateCodec.decode(old))
        assertEquals(false, s.openedSourceForm)
        assertEquals(false, s.triedSave)
        assertNull(s.saveError)
    }

    @Test
    fun `a screen identifier is lower-cased and reduced to a-z 0-9 underscore and dot`() {
        assertEquals("tabs.home", FirstLeavePolicy.sanitizeScreen("Tabs.Home"))
        assertEquals("settings_page", FirstLeavePolicy.sanitizeScreen("settings_page"))
        assertEquals("my_screen_name_x", FirstLeavePolicy.sanitizeScreen("My Screen/Name?x"))
        assertEquals("other", FirstLeavePolicy.sanitizeScreen(""))
        assertEquals("other", FirstLeavePolicy.sanitizeScreen("   "))
        assertEquals("other", FirstLeavePolicy.sanitizeScreen("///"))
        assertEquals("other", FirstLeavePolicy.sanitizeScreen(null))
        assertEquals(40, FirstLeavePolicy.sanitizeScreen("a".repeat(100)).length)
    }

    @Test
    fun `the leave props sanitise the screen even if a caller passes junk`() {
        val p = assertNotNull(leave(newInstall(), visit = mapOf("last_screen" to "Some Title/Of A Movie", "secs_in_app" to -5, "screens_seen" to "x")).emit)
        assertEquals("some_title_of_a_movie", p["last_screen"])
        assertEquals(0, p["secs_in_app"])
        assertEquals(0, p["screens_seen"])
        for (forbidden in listOf("code", "state", "url", "token")) assertTrue(forbidden !in p, forbidden)
    }

    @Test
    fun `the visit tracks the last screen and counts distinct screens`() {
        val v = FirstLeaveVisit(t0)
        v.screen("Tabs.Home"); v.screen("settings_page"); v.screen("Tabs.Home")
        val p = v.props(t0 + 12_500)
        assertEquals("tabs.home", p["last_screen"])
        assertEquals(2, p["screens_seen"])
        assertEquals(12, p["secs_in_app"])
    }

    @Test
    fun `secs_in_app counts from the last return to the foreground`() {
        val v = FirstLeaveVisit(t0)
        v.foreground(t0 + 100_000)
        assertEquals(7, v.props(t0 + 107_900)["secs_in_app"])
    }

    @Test
    fun `an empty visit reports other and zero screens`() {
        val p = FirstLeaveVisit(t0).props(t0)
        assertEquals("other", p["last_screen"])
        assertEquals(0, p["screens_seen"])
    }

    @Test
    fun `a blank or repeated screen name is not a new screen`() {
        val v = FirstLeaveVisit(t0)
        v.screen(""); v.screen("  ")
        assertEquals(0, v.props(t0)["screens_seen"])
    }
}
