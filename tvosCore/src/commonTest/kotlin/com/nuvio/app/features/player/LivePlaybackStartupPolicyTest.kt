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
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isPlaying = true), 100))
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
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isPlaying = true), 100))
        assertFalse(policy.sample(PlayerPlaybackSnapshot(isEnded = true), 200))
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
}
