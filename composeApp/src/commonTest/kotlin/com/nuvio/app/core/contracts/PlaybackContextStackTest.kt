package com.nuvio.app.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shell's "what is playing" port. kotlin.test → assertEquals(expected, actual, message). */
class PlaybackContextStackTest {

    @Test
    fun `nothing is playing until a surface enters`() {
        assertEquals(PlaybackContext.None, PlaybackContextStack().current.value, "starts idle")
    }

    @Test
    fun `the most recent surface still shown wins`() {
        val stack = PlaybackContextStack()
        val live = stack.enter(PlaybackContext.LiveTv)
        val film = stack.enter(PlaybackContext.Vod)
        assertEquals(PlaybackContext.Vod, stack.current.value, "a film over live TV")
        film.dispose()
        assertEquals(PlaybackContext.LiveTv, stack.current.value, "back to live TV")
        live.dispose()
        assertEquals(PlaybackContext.None, stack.current.value, "idle again")
    }

    @Test
    fun `leaving out of order and twice is harmless`() {
        val stack = PlaybackContextStack()
        val live = stack.enter(PlaybackContext.LiveTv)
        val film = stack.enter(PlaybackContext.Vod)
        live.dispose()
        live.dispose()
        assertEquals(PlaybackContext.Vod, stack.current.value, "the film is still on screen")
        film.dispose()
        assertEquals(PlaybackContext.None, stack.current.value, "idle")
    }

    @Test
    fun `an unregistered port reads as idle`() {
        PlaybackContextAccess.resetForTest()
        val port = PlaybackContextAccess.current()
        port.enter(PlaybackContext.LiveTv)
        assertEquals(PlaybackContext.None, port.current.value, "no-op default")
    }
}
