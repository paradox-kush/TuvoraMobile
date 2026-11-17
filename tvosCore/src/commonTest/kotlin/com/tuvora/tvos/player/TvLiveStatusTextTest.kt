package com.tuvora.tvos.player

import com.nuvio.app.core.analytics.LiveReconnectReason
import com.nuvio.app.core.analytics.LiveReconnectStatus
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvLiveStatusTextTest {

    @Test
    fun `the reconnect line names the attempt and the reason`() {
        assertEquals(
            "Reconnecting… (attempt 2 of 5)\nProvider firewall blocked the stream",
            TvLiveStatusText.text(LiveReconnectStatus(2, 5, LiveReconnectReason.PROVIDER_FIREWALL)),
        )
    }

    @Test
    fun `libmpv's status text gets the matching hint and nothing else does`() {
        assertTrue(TvLiveStatusText.hintFor("[ffmpeg] http: HTTP error 456")!!.contains("User-Agent"))
        assertEquals(
            "Too many connections on this account. Close the stream on another device, or try again in a minute.",
            TvLiveStatusText.hintFor("[ffmpeg] http: HTTP error 509 Bandwidth Limit Exceeded"),
        )
        assertNull(TvLiveStatusText.hintFor("[mpv] loading failed"))
        assertNull(TvLiveStatusText.hintFor(null))
    }

    @Test
    fun `a 456 failure card advises a custom User-Agent`() {
        assertTrue(TvLiveFailureReason.of("HTTP error 456", offline = false)!!.contains("User-Agent"))
    }

    @Test
    fun `only a new frame after the reconnect counts as playing again`() {
        val frozen = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, videoProgressTicks = 40, hasVideoTrack = true)
        assertFalse(TvLiveStatusText.playingAgain(frozen, ticksAtReconnect = 40), "the frozen picture still claims playing")
        assertTrue(TvLiveStatusText.playingAgain(frozen.copy(videoProgressTicks = 3), ticksAtReconnect = 40))
        assertFalse(TvLiveStatusText.playingAgain(frozen.copy(videoProgressTicks = 3, isLoading = true), ticksAtReconnect = 40))
        val radio = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, hasVideoTrack = false)
        assertTrue(TvLiveStatusText.playingAgain(radio, ticksAtReconnect = 0), "a radio channel has no frames to count")
    }
}
