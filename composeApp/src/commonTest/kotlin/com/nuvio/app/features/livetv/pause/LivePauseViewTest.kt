package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePauseViewTest {
    private val text: (LivePauseFailure) -> String = { "failed:$it" }

    private fun view(state: LivePauseState, available: Boolean = true) = LivePauseViews.of(state, available, text)

    private fun shifted(position: Long, paused: Boolean = false, start: Long = 100_000, end: Long = 400_000) =
        LivePauseState.Shifted(paused, (end - position).coerceAtLeast(0), start, end, position, false)

    @Test
    fun `a player the controller does not drive keeps the plain badge`() {
        assertEquals(LivePauseView.Kind.Unavailable, view(LivePauseState.Off, available = false).kind)
        assertEquals(LivePauseView.Kind.Unavailable, view(shifted(150_000), available = false).kind)
    }

    @Test
    fun `at live the badge says LIVE and the shift is on offer`() {
        val v = view(LivePauseState.Off)
        assertEquals(LivePauseView.Kind.Live, v.kind)
        assertEquals("LIVE", v.chipText)
        assertFalse(v.canGoLive, "already live")
        assertFalse(v.showsScrubBar)
    }

    @Test
    fun `time shifted shows how far behind and goes live on select`() {
        val v = view(shifted(position = 335_000, paused = true))
        assertEquals(LivePauseView.Kind.Shifted, v.kind)
        assertEquals("−1:05 · LIVE", v.chipText)
        assertEquals(65_000L, v.behindMs)
        assertTrue(v.paused)
        assertTrue(v.canGoLive)
        assertTrue(v.showsScrubBar)
        assertEquals("−5:00", v.reachText)
        assertEquals(0.7833f, v.progress, 0.001f)
    }

    @Test
    fun `shifted within the live slack still reads plain LIVE`() {
        val v = view(shifted(position = 399_000))
        assertEquals("LIVE", v.chipText)
        assertEquals(0.9967f, v.progress, 0.001f)
    }

    @Test
    fun `a window with no width draws no scrub bar`() {
        val v = view(LivePauseState.Shifted(true, 0, 0, 0, 0, false))
        assertEquals(LivePauseView.Kind.Shifted, v.kind)
        assertFalse(v.showsScrubBar)
        assertEquals(1f, v.progress)
        assertEquals("", v.reachText)
    }

    @Test
    fun `preparing carries its deadline so the screen can end the wait itself`() {
        val loading = BoundedLoad.begin(LoadSurface.LIVE_PAUSE)
        val v = view(LivePauseState.Preparing(loading))
        assertEquals(LivePauseView.Kind.Preparing, v.kind)
        assertEquals(loading.deadlineAtMs, v.preparingDeadlineEpochMs)
        assertFalse(v.canGoLive)
    }

    @Test
    fun `failed names the reason and offers live`() {
        val v = view(LivePauseState.Failed(LivePauseFailure.NO_SPACE))
        assertEquals(LivePauseView.Kind.Failed, v.kind)
        assertEquals("failed:NO_SPACE", v.failureText)
        assertTrue(v.canGoLive)
        assertNull(view(LivePauseState.Off).failureText)
    }
}
