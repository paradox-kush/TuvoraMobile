package com.tuvora.tvos.player

import com.nuvio.app.features.player.LiveLinkReconnectPolicy.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Stalker create_link is spent by its first open: nothing in the Apple TV player may open it twice. */
class TvSingleUseLinkPolicyTest {
    @Test
    fun `a single-use archive replay turns every ffmpeg re-open off`() {
        // The session used to ask for a reusable link whenever the launch was a replay, so mpv's
        // reconnect=1 re-opened the spent create_link after a drop.
        assertEquals(Mode.SINGLE_USE, TvSingleUseLinkPolicy.reconnectMode(singleUse = true))
    }

    @Test
    fun `a single-use live channel turns every ffmpeg re-open off`() {
        assertEquals(Mode.SINGLE_USE, TvSingleUseLinkPolicy.reconnectMode(singleUse = true))
    }

    @Test
    fun `a reusable link keeps the engine reconnect`() {
        assertEquals(Mode.ENGINE_RECONNECT, TvSingleUseLinkPolicy.reconnectMode(singleUse = false))
        assertEquals(Mode.ENGINE_RECONNECT, TvSingleUseLinkPolicy.reconnectMode(singleUse = false))
    }

    @Test
    fun `a spent link is never re-opened by engine escalation or the player retry`() {
        assertFalse(TvSingleUseLinkPolicy.allowsLaneEscalation(singleUse = true))
        assertFalse(TvSingleUseLinkPolicy.allowsPlayerRetry(singleUse = true))
        assertTrue(TvSingleUseLinkPolicy.allowsLaneEscalation(singleUse = false))
        assertTrue(TvSingleUseLinkPolicy.allowsPlayerRetry(singleUse = false))
    }
}
