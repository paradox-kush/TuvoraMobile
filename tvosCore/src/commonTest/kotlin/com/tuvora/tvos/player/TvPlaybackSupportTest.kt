package com.tuvora.tvos.player

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlaybackSupportTest {

    @Test
    fun `headers drop blanks and Range`() {
        assertEquals(
            mapOf("User-Agent" to "UA", "Referer" to "r"),
            TvPlaybackHeaders.sanitize(mapOf(" User-Agent " to " UA ", "Range" to "bytes=0-", "X" to " ", "Referer" to "r")),
        )
    }

    @Test
    fun `a completed save is not undone by a later stale save`() {
        val gate = TvProgressGate()
        val done = PlayerPlaybackSnapshot(isLoading = false, isEnded = true, durationMs = 3_600_000, positionMs = 3_600_000)
        val stale = PlayerPlaybackSnapshot(isLoading = false, durationMs = 0, positionMs = 12_000)
        assertTrue(gate.admit("ep1", done))
        assertFalse(gate.admit("ep1", stale))
        assertTrue(gate.admit("ep2", stale), "the next episode saves normally")
    }

    private fun playing(pos: Long, buf: Long = pos, ticks: Long = pos / 40) =
        PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = pos, bufferedPositionMs = buf, videoProgressTicks = ticks, hasVideoTrack = true)

    @Test
    fun `a healthy live stream is left alone`() {
        val m = TvLiveMonitor()
        var now = 0L
        repeat(60) { i -> assertEquals(TvLiveAction.None, m.sample(now, playing(i * 500L), wantsToPlay = true)); now += 500 }
    }

    @Test
    fun `a frozen live stream reconnects after the freeze threshold`() {
        val m = TvLiveMonitor()
        var now = 0L
        repeat(20) { i -> m.sample(now, playing(i * 500L), wantsToPlay = true); now += 500 }
        val frozen = playing(9_500)
        val actions = (0 until 20).map { m.sample(now, frozen, wantsToPlay = true).also { now += 500 } }
        assertTrue(TvLiveAction.Reconnect in actions, "expected a reconnect, got $actions")
    }

    @Test
    fun `a paused live stream is never treated as frozen`() {
        val m = TvLiveMonitor()
        var now = 0L
        val paused = PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, positionMs = 5_000, bufferedPositionMs = 5_000)
        repeat(60) { assertEquals(TvLiveAction.None, m.sample(now, paused, wantsToPlay = false)); now += 500 }
    }

    @Test
    fun `an ended live stream reconnects`() {
        val m = TvLiveMonitor()
        var now = 0L
        repeat(10) { i -> m.sample(now, playing(i * 500L), wantsToPlay = true); now += 500 }
        val ended = PlayerPlaybackSnapshot(isLoading = false, isEnded = true, positionMs = 5_000, bufferedPositionMs = 5_000)
        val actions = (0 until 20).map { m.sample(now, ended, wantsToPlay = true).also { now += 500 } }
        assertTrue(TvLiveAction.Reconnect in actions, "expected a reconnect, got $actions")
    }

    @Test
    fun `after a reconnect a healthy stream is not reconnected again`() {
        // Simulator 2026-09-28: the source dropped, the reconnect worked, and 8 s later the healthy
        // recovered stream was reconnected a second time.
        val m = TvLiveMonitor()
        var now = 0L
        repeat(20) { i -> m.sample(now, playing(i * 500L), wantsToPlay = true); now += 500 }
        val frozen = playing(9_500)
        var reconnected = false
        while (!reconnected) { reconnected = m.sample(now, frozen, wantsToPlay = true) == TvLiveAction.Reconnect; now += 500 }
        val after = (0 until 60).map { i -> m.sample(now, playing(i * 500L), wantsToPlay = true).also { now += 500 } }
        assertTrue(after.all { it == TvLiveAction.None }, "healthy stream after a reconnect was acted on: $after")
    }

    @Test
    fun `separate outages hours apart never add up to giving up`() {
        val m = TvLiveMonitor()
        var now = 0L
        repeat(8) {
            repeat(120) { i -> m.sample(now, playing(i * 500L), wantsToPlay = true); now += 500 }
            val frozen = playing(59_500)
            var action = TvLiveAction.None
            while (action == TvLiveAction.None) { action = m.sample(now, frozen, wantsToPlay = true); now += 500 }
            assertEquals(TvLiveAction.Reconnect, action)
        }
    }

    @Test
    fun `a user Retry after give up gets a fresh reconnect ladder`() {
        // Apple TV: the Failed screen's Retry left the monitor at its cap, so the first sample of the
        // retried stream answered GiveUp again and Retry could never recover a dropped channel.
        val m = TvLiveMonitor()
        var now = 0L
        repeat(10) { i -> m.sample(now, playing(i * 500L), wantsToPlay = true); now += 500 }
        val ended = PlayerPlaybackSnapshot(isLoading = false, isEnded = true, positionMs = 5_000, bufferedPositionMs = 5_000)
        var gaveUp = false
        var guard = 0
        while (!gaveUp && guard++ < 2_000) { gaveUp = m.sample(now, ended, wantsToPlay = true) == TvLiveAction.GiveUp; now += 500 }
        assertTrue(gaveUp, "the ladder should end in GiveUp on a dead provider")

        m.onUserRetry()
        val first = m.sample(now, ended, wantsToPlay = true)
        assertEquals(TvLiveAction.Reconnect, first, "a Retry's first failure reconnects instead of giving up at once")
    }
}
