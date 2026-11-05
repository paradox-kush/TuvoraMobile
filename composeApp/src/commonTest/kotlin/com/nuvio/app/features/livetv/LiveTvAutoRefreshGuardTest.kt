package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvAutoRefreshGuardTest {

    @Test
    fun `a second error on the freshly minted link does not mint again`() {
        // The docked screen keyed its one automatic re-resolve on the failed URL. Every Stalker
        // create_link is a new URL, so each failure of a fresh link minted another one, with no cap
        // and no backoff: the fast re-mint pattern that got a viewer's IP banned.
        val guard = LiveTvAutoRefreshGuard()
        assertTrue(guard.admit(positionMs = 60_000L))
        assertFalse(guard.admit(positionMs = 62_000L))
        assertFalse(guard.admit(positionMs = 64_000L))
    }

    @Test
    fun `sustained playback after the refresh re-arms it`() {
        val guard = LiveTvAutoRefreshGuard()
        assertTrue(guard.admit(positionMs = 60_000L))
        guard.onPlayback(positionMs = 70_000L)
        assertFalse(guard.admit(positionMs = 71_000L), "10 s of play is not a recovery")
        guard.onPlayback(positionMs = 95_000L)
        assertTrue(guard.admit(positionMs = 96_000L), "35 s past the refresh is a recovery")
    }

    @Test
    fun `a zap or a user Retry re-arms it`() {
        val guard = LiveTvAutoRefreshGuard()
        assertTrue(guard.admit(positionMs = 1_000L))
        guard.reset()
        assertTrue(guard.admit(positionMs = 0L))
    }
}
