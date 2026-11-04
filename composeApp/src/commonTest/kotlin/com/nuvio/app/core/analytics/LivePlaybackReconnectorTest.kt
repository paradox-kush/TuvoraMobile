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
}
