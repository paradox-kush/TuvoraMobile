package com.nuvio.app.features.player

import com.nuvio.app.features.player.LiveLinkReconnectPolicy.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveLinkReconnectPolicyTest {

    @Test
    fun `a live Stalker link is single-use`() {
        assertEquals(Mode.SINGLE_USE, LiveLinkReconnectPolicy.modeFor(isStalkerSource = true, isLive = true))
    }

    @Test
    fun `a single-use link turns every FFmpeg re-open off`() {
        val options = LiveLinkReconnectPolicy.mpvStreamLavfOptions(
            LiveLinkReconnectPolicy.modeFor(isStalkerSource = true, isLive = true),
        )
        val parsed = options.split(',').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("0", parsed["reconnect"], "reconnect must be off: $options")
        assertEquals("0", parsed["reconnect_streamed"], "reconnect_streamed must be off: $options")
        assertEquals("0", parsed["reconnect_on_network_error"], "reconnect_on_network_error must be off: $options")
    }

    @Test
    fun `a single-use link gets no Media3 transport retry`() {
        assertFalse(LiveLinkReconnectPolicy.allowsTransportRetry(LiveLinkReconnectPolicy.modeFor(singleUseLink = true)))
    }

    @Test
    fun `reusable live links keep the in-engine reconnect`() {
        // Xtream and M3U live, and any non-IPTV live stream.
        val mode = LiveLinkReconnectPolicy.modeFor(isStalkerSource = false, isLive = true)
        assertEquals(Mode.ENGINE_RECONNECT, mode)
        assertEquals(
            "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5",
            LiveLinkReconnectPolicy.mpvStreamLavfOptions(mode),
        )
        assertTrue(LiveLinkReconnectPolicy.allowsTransportRetry(mode))
    }

    @Test
    fun `Stalker VOD and a Stalker replay keep the reconnect`() {
        assertEquals(Mode.ENGINE_RECONNECT, LiveLinkReconnectPolicy.modeFor(isStalkerSource = true, isLive = false))
        assertEquals(
            Mode.ENGINE_RECONNECT,
            LiveLinkReconnectPolicy.modeFor(isStalkerSource = true, isLive = true, isCatchUp = true),
        )
    }
}
