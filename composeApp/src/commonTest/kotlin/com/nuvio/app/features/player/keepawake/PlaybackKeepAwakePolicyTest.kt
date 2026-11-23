package com.nuvio.app.features.player.keepawake

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackKeepAwakePolicyTest {
    private fun hold(
        playIntent: Boolean = true,
        userPaused: Boolean = false,
        ended: Boolean = false,
        error: Boolean = false,
        inBackground: Boolean = false,
    ) = PlaybackKeepAwakePolicy.shouldHold(playIntent, userPaused, ended, error, inBackground)

    @Test
    fun `live stall with the engine not playing still holds the screen`() {
        // The engine reports isPlaying=false during a stall; the policy never sees it, only intent.
        assertEquals(true, hold(playIntent = true), "a stalled live channel is still being watched")
    }

    @Test
    fun `buffering at start holds the screen`() {
        assertEquals(true, hold(playIntent = true), "opening a channel counts as watching")
    }

    @Test
    fun `user pause releases the screen`() {
        assertEquals(false, hold(userPaused = true), "a deliberate pause lets the screen sleep")
    }

    @Test
    fun `ended releases the screen`() {
        assertEquals(false, hold(ended = true), "finished playback releases the hold")
    }

    @Test
    fun `terminal error releases the screen`() {
        assertEquals(false, hold(error = true), "a failed player must not pin the screen on")
    }

    @Test
    fun `background releases the screen`() {
        assertEquals(false, hold(inBackground = true), "no hold while the app is not active")
    }

    @Test
    fun `no play intent releases the screen`() {
        assertEquals(false, hold(playIntent = false), "nothing is being watched")
    }
}
