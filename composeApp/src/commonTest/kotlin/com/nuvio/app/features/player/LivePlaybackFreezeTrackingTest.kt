package com.nuvio.app.features.player

import com.nuvio.app.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.app.core.analytics.LivePlaybackReconnector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePlaybackFreezeTrackingTest {
    private val reporter = LivePlaybackFreezeReporter(capture = { _, _ -> })
    private val reconnector = LivePlaybackReconnector(reporter)
    private var reconnects = 0

    private fun feed(snapshot: PlayerPlaybackSnapshot) = reporter.onLiveSnapshot(
        snapshot = snapshot,
        engine = { "libmpv" },
        streamUrl = "http://h/live/u/p/1.ts",
        contentId = "xtream:a:live:1",
        surface = LIVE_FREEZE_SURFACE_DOCKED,
        reconnector = reconnector,
        reconnect = { reconnects++ },
    )

    @Test
    fun `a channel that never played is not treated as a frozen stream`() {
        // Regression (1.13.4, Android): libmpv's optimistic isPlaying armed the freeze watcher before
        // the file loaded, so a failed open read as a freeze and reconnected every 20 s forever —
        // the Live TV spinner never gave way to the error.
        feed(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true))
        feed(LivePlaybackStartupPolicy.endFileSnapshot(PlayerPlaybackSnapshot(), "error", true))

        assertFalse(reporter.isArmed, "nothing played, so there is nothing to freeze")
        assertEquals(0, reconnects, "a failed open must surface the error, not loop reconnecting")
    }

    @Test
    fun `a channel arms once a frame is drawn`() {
        feed(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, videoProgressTicks = 1))
        assertTrue(reporter.isArmed)
    }

    @Test
    fun `a trailing frame from a torn-down engine does not arm the watcher for the next channel`() {
        // Device pass 2026-11-18: zap from a playing channel to a refused one. The old engine's last snapshot lands
        // after the screen dropped its source, armed the watcher under the NEW channel's id, and the refused open
        // was then read as a mid-stream drop (freeze ladder, or a spinner that never ended). No source, no arming.
        reporter.onLiveSnapshot(
            snapshot = PlayerPlaybackSnapshot(positionMs = 11_000, videoProgressTicks = 3),
            engine = { "libmpv" },
            streamUrl = null,
            contentId = "xtream:a:live:2",
            surface = LIVE_FREEZE_SURFACE_DOCKED,
            reconnector = reconnector,
            reconnect = { reconnects++ },
        )
        assertFalse(reporter.isArmed, "a frame with no source on screen belongs to no channel")
    }
}
