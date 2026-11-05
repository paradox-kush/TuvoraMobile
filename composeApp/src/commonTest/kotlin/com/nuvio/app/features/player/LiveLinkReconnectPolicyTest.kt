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

    @Test
    fun `a single-use link is never failed over to the other engine or cached`() {
        val spent = LiveLinkReconnectPolicy.modeFor(singleUseLink = true)
        assertFalse(LiveLinkReconnectPolicy.allowsEngineFailover(spent), "the same spent url would be opened again")
        assertFalse(LiveLinkReconnectPolicy.allowsMediaCache(spent), "no caching data source re-requests a spent url")
    }

    @Test
    fun `a reusable link keeps the engine failover and the cache`() {
        val reusable = LiveLinkReconnectPolicy.modeFor(singleUseLink = false)
        assertTrue(LiveLinkReconnectPolicy.allowsEngineFailover(reusable))
        assertTrue(LiveLinkReconnectPolicy.allowsMediaCache(reusable))
    }

    @Test
    fun `a single-use link is not GET-probed for redirects and not rebuilt after a decoder failure`() {
        val spent = LiveLinkReconnectPolicy.modeFor(singleUseLink = true)
        assertFalse(LiveLinkReconnectPolicy.allowsRedirectProbe(spent), "the probe GET spends the token")
        assertFalse(LiveLinkReconnectPolicy.allowsDecoderFallback(spent), "the rebuild re-opens the spent url")
        val reusable = LiveLinkReconnectPolicy.modeFor(singleUseLink = false)
        assertTrue(LiveLinkReconnectPolicy.allowsRedirectProbe(reusable))
        assertTrue(LiveLinkReconnectPolicy.allowsDecoderFallback(reusable))
    }
}
