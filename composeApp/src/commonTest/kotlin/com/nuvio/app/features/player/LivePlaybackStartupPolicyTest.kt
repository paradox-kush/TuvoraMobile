package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePlaybackStartupPolicyTest {
    @Test fun `an immediate end before playback fails without waiting`() {
        assertTrue(LivePlaybackStartupPolicy().sample(PlayerPlaybackSnapshot(isEnded = true), 100))
    }
    @Test fun `a silent loading attempt has a finite deadline`() {
        val policy = LivePlaybackStartupPolicy()
        assertFalse(policy.sample(PlayerPlaybackSnapshot(), LivePlaybackStartupPolicy.TIMEOUT_MS - 1))
        assertTrue(policy.sample(PlayerPlaybackSnapshot(), LivePlaybackStartupPolicy.TIMEOUT_MS))
    }
    @Test fun `an idle attempt also has a finite deadline`() {
        assertTrue(LivePlaybackStartupPolicy().sample(PlayerPlaybackSnapshot(isLoading = false), LivePlaybackStartupPolicy.TIMEOUT_MS))
    }
    @Test fun `healthy radio needs no video frames`() {
        val policy = LivePlaybackStartupPolicy()
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isPlaying = true, positionMs = 500), 100))
        assertFalse(policy.sample(PlayerPlaybackSnapshot(), LivePlaybackStartupPolicy.TIMEOUT_MS))
    }
    @Test fun `playhead progress proves startup even if playing flag is delayed`() {
        val policy = LivePlaybackStartupPolicy()
        assertFalse(policy.sample(PlayerPlaybackSnapshot(positionMs = 500), 100))
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isEnded = true), LivePlaybackStartupPolicy.TIMEOUT_MS))
    }
    @Test fun `failure stays terminal until a new source attempt`() {
        val failed = LivePlaybackStartupPolicy()
        assertTrue(failed.sample(PlayerPlaybackSnapshot(isEnded = true), 100))
        assertTrue(failed.sample(PlayerPlaybackSnapshot(isPlaying = true), 200))
        assertFalse(LivePlaybackStartupPolicy().sample(PlayerPlaybackSnapshot(), 0))
    }
    @Test fun `an end after healthy playback belongs to recovery rather than startup`() {
        val policy = LivePlaybackStartupPolicy()
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isPlaying = true, positionMs = 500), 100))
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isEnded = true), 200))
    }
    @Test fun `an optimistic playing flag before the file loads does not prove startup`() {
        // Android libmpv echoes pause=false into isPlaying before anything has loaded. A channel
        // that then fails to open must end the attempt (error + Retry), not count as started.
        val policy = LivePlaybackStartupPolicy()
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true), 0))
        val failedOpen = LivePlaybackStartupPolicy.endFileSnapshot(PlayerPlaybackSnapshot(), "error", true)
        assertTrue(policy.sample(failedOpen, 0), "a failed open after a phantom playing flag must end the attempt")
    }
    @Test fun `a drawn frame proves startup even before the playhead moves`() {
        val policy = LivePlaybackStartupPolicy()
        assertFalse(policy.sample(PlayerPlaybackSnapshot(videoProgressTicks = 1), 100))
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isEnded = true), 200))
    }
    @Test fun `a reconnect of a channel that already played belongs to recovery not startup`() {
        // The freeze watcher reconnects through the same re-resolve as Retry, which starts a new
        // attempt. With the provider still down that attempt never plays; judged as a first open it
        // ended the indefinite reconnect after ~14 s and the channel never came back on its own.
        val reconnect = LivePlaybackStartupPolicy(recoveringPlayedChannel = true)
        val failedOpen = LivePlaybackStartupPolicy.endFileSnapshot(PlayerPlaybackSnapshot(), "error", true)
        assertFalse(reconnect.sample(failedOpen, 0))
        assertFalse(reconnect.sample(PlayerPlaybackSnapshot(), LivePlaybackStartupPolicy.TIMEOUT_MS))
    }
    @Test fun `mpv live end events are terminal even when EOF property was cleared`() {
        for (reason in listOf("eof", "error")) {
            val ended = LivePlaybackStartupPolicy.endFileSnapshot(PlayerPlaybackSnapshot(), reason, true)
            assertTrue(ended.isEnded)
            assertFalse(ended.isLoading)
            assertFalse(ended.isPlaying)
        }
    }
    @Test fun `mpv stop redirect and quit do not fail the replacement source`() {
        val pending = PlayerPlaybackSnapshot()
        for (reason in listOf("stop", "redirect", "quit", "unknown", null)) {
            assertFalse(LivePlaybackStartupPolicy.endFileSnapshot(pending, reason, true).isEnded)
        }
        assertFalse(LivePlaybackStartupPolicy.endFileSnapshot(pending, "error", false).isEnded)
    }
    @Test fun `a stale armed watcher from the previous channel does not make a new tune a recovery`() {
        // 2026-11-18 device pass: play channel A, zap to a refused channel B. The watcher is still armed for A when B's
        // startup policy is built, so B's refusal was judged a mid-stream drop and the freeze ladder re-asked it 4 times.
        assertFalse(LivePlaybackStartupPolicy.recoversPlayedChannel(armed = true, armedContentId = "A", contentId = "B"))
        assertTrue(LivePlaybackStartupPolicy.recoversPlayedChannel(armed = true, armedContentId = "A", contentId = "A"))
        assertFalse(LivePlaybackStartupPolicy.recoversPlayedChannel(armed = false, armedContentId = "A", contentId = "A"))
        assertTrue(
            LivePlaybackStartupPolicy.recoversPlayedChannel(armed = true, armedContentId = null, contentId = "A"),
            "an unnamed watcher keeps the old behaviour",
        )
    }
    @Test fun `an engine error on an attempt that drew nothing fails the open whatever the screen showed before`() {
        // Zap from a playing channel to a refused one: the screen's snapshot still holds the old channel's frame.
        // The error is judged by this attempt's own latch, so a fresh policy fails; one that drew a frame does not.
        assertTrue(LivePlaybackStartupPolicy().sampleError())
        val played = LivePlaybackStartupPolicy()
        played.sample(PlayerPlaybackSnapshot(positionMs = 5_000), 0)
        assertFalse(played.sampleError(), "a stream that played and dropped is the freeze ladder's, not a failed open")
        assertFalse(LivePlaybackStartupPolicy(recoveringPlayedChannel = true).sampleError())
    }
    @Test fun `a snapshot delivered to a closure built for the previous channel is stale`() {
        assertTrue(LivePlaybackStartupPolicy.isStale("A", "B"))
        assertFalse(LivePlaybackStartupPolicy.isStale("B", "B"))
        assertFalse(LivePlaybackStartupPolicy.isStale(null, null))
    }
}
