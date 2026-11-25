package com.nuvio.app.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reporter is what turns policy decisions into the one `live_playback_freeze` event, so
 * these tests drive it with explicit clocks and assert on the emitted properties — the two
 * field-data bugs (engine="unknown", impossible frozen_ms) both lived in that assembly.
 */
class LivePlaybackFreezeReporterTest {

    private val events = mutableListOf<Pair<String, Map<String, Any>>>()
    private val signals = mutableListOf<com.nuvio.app.core.telemetry.api.PlaybackSignal>()
    private val reporter = LivePlaybackFreezeReporter(
        capture = { name, properties -> events += name to properties },
        recordPlayback = { signals += it },
    )

    private fun profile(engine: String = "libmpv") = LivePlaybackFreezeReporter.Profile(
        engine = engine,
        streamContainer = "ts",
        iptvKind = "xtream",
        surface = "player",
        // The platform-published thresholds are irrelevant here and unset in a JVM test.
        buffers = null,
    )

    /**
     * Plays healthily until [videoStallAtMs]: the playhead, buffered edge and frame counter all
     * advance on every sample. After that the frame counter pins while audio keeps the playhead
     * moving — the reported "picture froze, audio kept going" shape.
     */
    private fun playHealthyThenStallVideo(videoStallAtMs: Long) {
        reporter.onLivePlaybackStarted(
            profile = profile(),
            nowMs = 0L,
            positionMs = 0L,
            videoProgressTicks = 100L,
        )
        var ticks = 100L
        var t = 0L
        while (t < videoStallAtMs) {
            t += 10_000L
            ticks += 300L
            sample(nowMs = t, ticks = ticks)
        }
    }

    private fun sample(
        nowMs: Long,
        ticks: Long,
    ): LivePlaybackFreezePolicy.Decision = reporter.onSample(
        nowMs = nowMs,
        positionMs = nowMs,
        bufferedPositionMs = nowMs + 5_000L,
        state = LivePlaybackFreezePolicy.PlaybackState.READY,
        wantsToPlay = true,
        videoProgressTicks = ticks,
        hasVideoTrack = true,
    )

    @Test
    fun `a video freeze reports frozen time from the video stall start`() {
        // Last frame at t=20s; audio keeps the playhead advancing afterwards.
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)

        // The buffered edge is a healthy 5s ahead (see sample()), so the fast video-stall
        // threshold (2s, Fix 3) applies. 1s after the last frame it has not been crossed yet.
        val notYet = sample(nowMs = 21_000L, ticks = 700L)
        assertEquals(LivePlaybackFreezePolicy.Decision.Idle, notYet)

        // Past 2s since the last frame: the freeze is reported.
        val start = sample(nowMs = 24_000L, ticks = 700L)
        assertEquals(
            LivePlaybackFreezePolicy.Decision.Start(LivePlaybackFreezePolicy.Kind.VIDEO_STALLED),
            start,
        )

        // Frames come back one second later.
        val recover = sample(nowMs = 27_500L, ticks = 730L)
        assertEquals(LivePlaybackFreezePolicy.Decision.Recover, recover)

        assertEquals(1, events.size)
        val properties = events.single().second
        // The viewer stared at a frozen picture from t=20s until t=27.5s. Basing this at the
        // detection tick instead reported a fraction of that — and in the field, sub-threshold
        // impossibilities like 16ms — which is what made the first fleet numbers unusable.
        assertEquals(7_500L, properties["frozen_ms"], "frozen_ms must count from the video stall start")
        assertEquals(
            20_000L,
            properties["played_ms_before_freeze"],
            "played_ms_before_freeze ends when the picture died, not when detection fired",
        )
        assertEquals("video_stalled", properties["freeze_kind"])
        assertEquals(true, properties["recovered"])
    }

    @Test
    fun `a video freeze flushed unrecovered still counts from the video stall start`() {
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        sample(nowMs = 26_500L, ticks = 700L)
        assertTrue(reporter.isFreezeOpen)

        // The viewer gives up and leaves the channel at t=30s with the picture still dead.
        reporter.onLivePlaybackStopped(nowMs = 30_000L, positionMs = 30_000L, bufferedPositionMs = 32_000L)

        assertEquals(1, events.size)
        val properties = events.single().second
        assertEquals(10_000L, properties["frozen_ms"], "frozen_ms must count from the video stall start")
        assertEquals(false, properties["recovered"])
    }

    @Test
    fun `a freeze still open when the viewer leaves counts as an abandoned stall`() {
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        sample(nowMs = 26_500L, ticks = 700L)
        assertTrue(reporter.isFreezeOpen)

        reporter.onLivePlaybackStopped(nowMs = 30_000L, positionMs = 30_000L, bufferedPositionMs = 32_000L)

        assertEquals(
            listOf(com.nuvio.app.core.telemetry.api.PlaybackSignal.Stall(
                com.nuvio.app.core.telemetry.api.PlaybackKind.LIVE,
                com.nuvio.app.core.telemetry.api.StallOutcome.ABANDONED,
                10_000L,
            )),
            signals,
            "one abandoned stall, counted from the video stall start",
        )
    }

    @Test
    fun `leaving a healthy channel counts no stall`() {
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        reporter.onLivePlaybackStopped(nowMs = 21_000L, positionMs = 21_000L, bufferedPositionMs = 26_000L)
        assertEquals(emptyList(), signals, "no freeze was open, so nothing is counted")
    }

    @Test
    fun `a recovered freeze counts recovered and a later leave adds nothing`() {
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        sample(nowMs = 24_000L, ticks = 700L)
        sample(nowMs = 27_500L, ticks = 730L)
        reporter.onLivePlaybackStopped(nowMs = 40_000L, positionMs = 40_000L, bufferedPositionMs = 45_000L)

        assertEquals(
            listOf(com.nuvio.app.core.telemetry.api.StallOutcome.RECOVERED),
            signals.map { (it as com.nuvio.app.core.telemetry.api.PlaybackSignal.Stall).outcome },
            "recovered once, never also abandoned",
        )
    }

    @Test
    fun `a freeze that gave up is not counted again as abandoned on leave`() {
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        sample(nowMs = 26_500L, ticks = 700L)
        reporter.onRecoveryGaveUp(nowMs = 28_000L)
        reporter.onLivePlaybackStopped(nowMs = 30_000L, positionMs = 30_000L, bufferedPositionMs = 32_000L)

        assertEquals(
            listOf(com.nuvio.app.core.telemetry.api.StallOutcome.GAVE_UP),
            signals.map { (it as com.nuvio.app.core.telemetry.api.PlaybackSignal.Stall).outcome },
            "gave_up wins; the later leave is the same incident",
        )
    }

    @Test
    fun `an engine learned after arming reaches the freeze event`() {
        // The wiring arms on the first playing snapshot, which can beat the platform surface
        // handing over its controller — that race is why every Android event said "unknown".
        reporter.onLivePlaybackStarted(
            profile = profile(engine = LivePlaybackFreezeReporter.ENGINE_UNKNOWN),
            nowMs = 0L,
            positionMs = 1_000L,
        )
        assertTrue(reporter.needsEngine)
        reporter.onEngineKnown("libmpv")
        assertEquals(false, reporter.needsEngine)

        // Open a stall and flush it so the event assembly runs.
        reporter.onSample(
            nowMs = 7_000L,
            positionMs = 1_000L,
            bufferedPositionMs = 1_000L,
            state = LivePlaybackFreezePolicy.PlaybackState.BUFFERING,
            wantsToPlay = true,
        )
        reporter.onLivePlaybackStopped(nowMs = 9_000L, positionMs = 1_000L, bufferedPositionMs = 1_000L)

        assertEquals(1, events.size)
        assertEquals("libmpv", events.single().second["engine"])
    }

    @Test
    fun `a known engine is never downgraded or replaced`() {
        reporter.onLivePlaybackStarted(profile = profile(engine = "exoplayer"), nowMs = 0L, positionMs = 1_000L)
        assertEquals(false, reporter.needsEngine)
        reporter.onEngineKnown("libmpv")

        reporter.onSample(
            nowMs = 7_000L,
            positionMs = 1_000L,
            bufferedPositionMs = 1_000L,
            state = LivePlaybackFreezePolicy.PlaybackState.BUFFERING,
            wantsToPlay = true,
        )
        reporter.onLivePlaybackStopped(nowMs = 9_000L, positionMs = 1_000L, bufferedPositionMs = 1_000L)

        assertEquals("exoplayer", events.single().second["engine"])
    }

    @Test
    fun `a pipe stall keeps its detection-based frozen time`() {
        // Nothing moves from t=0: the playhead and the buffered edge both pin where they armed.
        reporter.onLivePlaybackStarted(profile = profile(), nowMs = 0L, positionMs = 1_000L)
        val start = reporter.onSample(
            nowMs = 7_000L,
            positionMs = 1_000L,
            bufferedPositionMs = 1_000L,
            state = LivePlaybackFreezePolicy.PlaybackState.BUFFERING,
            wantsToPlay = true,
        )
        assertEquals(
            LivePlaybackFreezePolicy.Decision.Start(LivePlaybackFreezePolicy.Kind.STALLED),
            start,
        )
        reporter.onLivePlaybackStopped(nowMs = 9_000L, positionMs = 1_000L, bufferedPositionMs = 1_000L)

        assertEquals(1, events.size)
        // STALLED keeps the historical detection basis; only the video kind was rebased. The
        // fleet has been read with this meaning since the detector shipped.
        assertEquals(2_000L, events.single().second["frozen_ms"])
    }
}
