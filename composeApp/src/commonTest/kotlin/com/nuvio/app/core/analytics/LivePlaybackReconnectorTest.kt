package com.nuvio.app.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePlaybackReconnectorTest {
    private val reporter = LivePlaybackFreezeReporter(capture = { _, _ -> })
    private val reconnector = LivePlaybackReconnector(reporter)
    private var reconnects = 0

    private fun frozen(nowMs: Long) = reconnector.onFrozen(
        nowMs = nowMs,
        kind = LivePlaybackFreezePolicy.Kind.ENDED,
        reconnect = { reconnects++ },
    )

    @Test
    fun `gives up after five reconnects that never rendered and then stays quiet`() {
        // 2026-11-04: the provider closed the stream and every rebuild ended within a second.
        var now = 1_000L
        repeat(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK) {
            now += 60_000L
            assertTrue(frozen(now), "attempt ${it + 1} should reconnect")
        }
        assertEquals(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK, reconnects)
        assertFalse(reconnector.hasGivenUp)

        now += 60_000L
        assertFalse(frozen(now))
        assertTrue(reconnector.hasGivenUp)

        now += 60_000L
        assertFalse(frozen(now), "Failed is terminal: no timer-driven retry")
        assertEquals(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK, reconnects)
    }

    @Test
    fun `a rendered frame between drops resets the streak so a flaky channel never gives up`() {
        var now = 1_000L
        repeat(20) {
            now += 60_000L
            assertTrue(frozen(now))
            reconnector.onPlaybackConfirmed()
        }
        assertFalse(reconnector.hasGivenUp)
        assertEquals(20, reconnects)
    }

    @Test
    fun `playback resuming after give up re-opens the ladder`() {
        var now = 1_000L
        repeat(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK + 1) { now += 60_000L; frozen(now) }
        assertTrue(reconnector.hasGivenUp)
        reconnector.onPlaybackConfirmed()
        assertFalse(reconnector.hasGivenUp)
        now += 60_000L
        assertTrue(frozen(now))
    }

    // --- When the ladder starts over (skill 02 2.1: per stream identity, never per URL) ---

    private fun giveUp(startMs: Long): Long {
        var now = startMs
        repeat(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK + 1) { now += 60_000L; frozen(now) }
        assertTrue(reconnector.hasGivenUp)
        return now
    }

    @Test
    fun `a user Retry after give up starts a fresh ladder`() {
        // The Failed screen's Retry used to leave gaveUp set: a retried stream that stalled again got
        // no reconnect and no new Failed state, so the screen sat on a spinner.
        reconnector.onPlaybackArmed("xtream:a:live:1")
        val now = giveUp(1_000L)
        reconnector.onUserRetry()
        assertFalse(reconnector.hasGivenUp)
        val before = reconnects
        assertTrue(frozen(now + 100L), "the first attempt of a fresh ladder is immediate")
        assertEquals(before + 1, reconnects)
    }

    @Test
    fun `a user Retry still ends in Failed when the provider stays dead`() {
        reconnector.onPlaybackArmed("xtream:a:live:1")
        var now = giveUp(1_000L)
        reconnector.onUserRetry()
        now = giveUp(now)
        assertTrue(reconnector.hasGivenUp, "a Retry gets one more bounded ladder, not an endless one")
    }

    @Test
    fun `a fresh link on the same channel does not reset the ladder`() {
        // The full player re-arms the watcher whenever the URL changes, which a Stalker re-mint does
        // every time. Re-arming the same channel must keep the attempt count, so the backoff holds.
        reconnector.onPlaybackArmed("xtream:a:live:1")
        assertTrue(frozen(10_000L))
        reconnector.onPlaybackArmed("xtream:a:live:1")
        assertFalse(frozen(10_100L), "attempt 2 waits out its backoff after a re-mint")
        assertEquals(1, reconnects)
    }

    @Test
    fun `a different channel arms a fresh ladder`() {
        reconnector.onPlaybackArmed("xtream:a:live:1")
        val now = giveUp(1_000L)
        reconnector.onPlaybackArmed("xtream:a:live:2")
        assertFalse(reconnector.hasGivenUp)
        assertTrue(frozen(now + 100L))
    }

    @Test
    fun `zapping away and back to a channel starts a fresh ladder`() {
        reconnector.onPlaybackArmed("xtream:a:live:1")
        assertTrue(frozen(10_000L))
        reconnector.onChannelChanged()
        reconnector.onChannelChanged()
        reconnector.onPlaybackArmed("xtream:a:live:1")
        assertTrue(frozen(10_100L), "a new tune of the same channel is not the old ladder")
    }

    @Test
    fun `the automatic reconnect never resets the ladder`() {
        reconnector.onPlaybackArmed("xtream:a:live:1")
        val now = giveUp(1_000L)
        assertFalse(frozen(now + 60_000L))
        assertTrue(reconnector.hasGivenUp)
    }
}
