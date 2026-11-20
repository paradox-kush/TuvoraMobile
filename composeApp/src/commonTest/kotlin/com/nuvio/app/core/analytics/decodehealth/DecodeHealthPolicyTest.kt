package com.nuvio.app.core.analytics

import com.nuvio.app.core.analytics.DecodeHealthPolicy.Fault
import com.nuvio.app.core.analytics.DecodeHealthPolicy.Issue
import com.nuvio.app.core.analytics.DecodeHealthPolicy.Sample
import com.nuvio.app.core.analytics.DecodeHealthPolicy.Signals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias Tracker = DecodeHealthPolicy.Tracker

class DecodeHealthPolicyTest {

    private fun sample(
        nowMs: Long,
        signals: Signals = Signals(),
        playing: Boolean = true,
        hasVideo: Boolean = true,
        audible: Boolean = true,
        ticks: Long = nowMs / 100,
        positionMs: Long = nowMs,
    ) = Sample(
        nowMs = nowMs,
        playing = playing,
        hasVideoTrack = hasVideo,
        surfaceAudible = audible,
        videoProgressTicks = ticks,
        positionMs = positionMs,
        signals = signals,
    )

    private fun healthy(advancing: Boolean? = true) = Signals(audioAdvancing = advancing, audioSelected = true)

    private fun Tracker.issues(s: Sample): List<Issue> = onSample(s).map { it.issue }

    @Test
    fun `healthy playback reports nothing`() {
        val t = Tracker()
        var now = 0L
        repeat(240) {
            assertEquals(emptyList(), t.issues(sample(now, healthy())), "no finding at $now")
            now += 500L
        }
        assertEquals(0, t.eventsThisSession)
    }

    @Test
    fun `an audio sink discontinuity is an audio timestamp fault once per tune`() {
        val t = Tracker()
        t.onSample(sample(0, healthy()))
        val first = t.onSample(sample(500, healthy().copy(audioDiscontinuities = 1)))
        assertEquals(listOf(Issue.AUDIO_TIMESTAMP_FAULT), first.map { it.issue })
        assertEquals(Fault.SINK_DISCONTINUITY, first.single().fault)
        // A burst of them in the same tune is one event.
        assertEquals(emptyList(), t.issues(sample(1000, healthy().copy(audioDiscontinuities = 40))))
    }

    @Test
    fun `a new tune may report again and the session cap carries over`() {
        val t = Tracker(maxPerSession = 2)
        t.onSample(sample(0, healthy()))
        assertEquals(1, t.onSample(sample(500, healthy().copy(videoDecoderErrors = 1))).size)
        t.onTune()
        t.onSample(sample(1000, healthy()))
        assertEquals(1, t.onSample(sample(1500, healthy().copy(videoDecoderErrors = 1))).size)
        t.onTune()
        t.onSample(sample(2000, healthy()))
        assertEquals(emptyList(), t.issues(sample(2500, healthy().copy(videoDecoderErrors = 1))), "cap of 2 reached")
        assertEquals(2, t.eventsThisSession)
    }

    @Test
    fun `at most five events per session even when six issues fire at once`() {
        val t = Tracker()
        t.onSample(sample(0, healthy()))
        val bad = healthy().copy(
            audioDiscontinuities = 1, audioSinkErrors = 1, videoDecoderErrors = 1,
            audioUnderruns = 3, droppedFrames = 500, audioDecoderInitMs = 4_000,
        )
        val out = t.onSample(sample(500, bad))
        assertEquals(5, out.size)
        assertEquals(5, t.eventsThisSession)
        t.onTune()
        t.onSample(sample(1000, healthy()))
        assertEquals(emptyList(), t.issues(sample(1500, healthy().copy(videoDecoderErrors = 1))))
    }

    @Test
    fun `audio not playing needs eight seconds of a flat audio clock`() {
        val t = Tracker()
        val flat = healthy(advancing = false)
        for (now in 0L..7_500L step 500L) assertEquals(emptyList(), t.issues(sample(now, flat)), "at $now")
        assertEquals(listOf(Issue.AUDIO_NOT_PLAYING), t.issues(sample(8_000, flat)))
    }

    @Test
    fun `audio not playing never fires for a muted viewer`() {
        val t = Tracker()
        val flat = healthy(advancing = false).copy(audioMuted = true)
        for (now in 0L..60_000L step 500L) assertEquals(emptyList(), t.issues(sample(now, flat)), "at $now")
    }

    @Test
    fun `audio not playing never fires on a muted surface`() {
        val t = Tracker()
        for (now in 0L..60_000L step 500L) {
            assertEquals(emptyList(), t.issues(sample(now, healthy(advancing = false), audible = false)), "at $now")
        }
    }

    @Test
    fun `unmuting restarts the audio idle clock`() {
        val t = Tracker()
        val flat = healthy(advancing = false)
        for (now in 0L..20_000L step 500L) t.onSample(sample(now, flat.copy(audioMuted = true)))
        // Unmuted at 20.5 s: the eight seconds count from here, not from the start of playback.
        assertEquals(emptyList(), t.issues(sample(20_500, flat)))
        assertEquals(emptyList(), t.issues(sample(28_000, flat)))
        assertEquals(listOf(Issue.AUDIO_NOT_PLAYING), t.issues(sample(28_500, flat)))
    }

    @Test
    fun `audio not playing is silent when no audio track is selected or the engine cannot say`() {
        val t = Tracker()
        for (now in 0L..30_000L step 500L) {
            assertEquals(emptyList(), t.issues(sample(now, Signals(audioSelected = false, audioAdvancing = false))), "no track at $now")
        }
        val u = Tracker()
        for (now in 0L..30_000L step 500L) {
            assertEquals(emptyList(), u.issues(sample(now, Signals(audioSelected = true, audioAdvancing = null))), "unknown at $now")
        }
    }

    @Test
    fun `audio not playing needs playback playing`() {
        val t = Tracker()
        for (now in 0L..30_000L step 500L) {
            assertEquals(emptyList(), t.issues(sample(now, healthy(advancing = false), playing = false)), "at $now")
        }
    }

    @Test
    fun `video frozen while audio plays is reported after the grace period`() {
        val t = Tracker()
        var seen: List<Issue> = emptyList()
        var now = 0L
        // The picture stops at tick 50 (5 s) while the audio clock keeps moving.
        while (now <= 20_000L && seen.isEmpty()) {
            seen = t.issues(sample(now, healthy(), ticks = minOf(now / 100, 50L)))
            now += 500L
        }
        assertEquals(listOf(Issue.VIDEO_FROZEN_AUDIO_PLAYING), seen)
        // Last picture at 5.0 s, flat for >= 5 s => 10 s; the 8 s start-up grace is behind us by then.
        assertTrue(now - 500L in 10_000L..10_500L, "reported at ${now - 500L}")
    }

    @Test
    fun `a deliberate interruption restarts the frozen picture clock but keeps the dedupe`() {
        val t = Tracker()
        for (now in 0L..4_500L step 500L) t.onSample(sample(now, healthy(), ticks = now / 100))
        // Display-mode switch: the picture idles for 20 s with no samples, then playback resumes flat for a moment.
        t.onDisruption()
        for (now in 25_000L..31_500L step 500L) {
            assertEquals(emptyList(), t.issues(sample(now, healthy(), ticks = 45L)), "at $now")
        }
    }

    @Test
    fun `video frozen is silent inside the start-up grace`() {
        val t = Tracker()
        for (now in 0L..7_500L step 500L) {
            assertEquals(emptyList(), t.issues(sample(now, healthy(), ticks = 0L)), "at $now")
        }
    }

    @Test
    fun `video frozen is silent when the audio is flat too`() {
        val t = Tracker()
        for (now in 0L..9_500L step 500L) t.onSample(sample(now, healthy(advancing = false), ticks = 0L))
        // Both dead is a stall for the freeze policy, not a picture-only fault.
        assertFalse(
            t.onSample(sample(10_000, healthy(advancing = false), ticks = 0L)).any { it.issue == Issue.VIDEO_FROZEN_AUDIO_PLAYING },
        )
    }

    @Test
    fun `video frozen uses the playhead when the engine has no audio clock`() {
        val t = Tracker()
        var seen: List<Issue> = emptyList()
        var now = 0L
        while (now <= 20_000L && seen.isEmpty()) {
            seen = t.issues(sample(now, Signals(), ticks = 0L, positionMs = now))
            now += 500L
        }
        assertEquals(listOf(Issue.VIDEO_FROZEN_AUDIO_PLAYING), seen)
    }

    @Test
    fun `a radio channel with no picture never reports a frozen video`() {
        val t = Tracker()
        for (now in 0L..30_000L step 500L) {
            assertEquals(emptyList(), t.issues(sample(now, healthy(), hasVideo = false, ticks = 0L)), "at $now")
        }
    }

    @Test
    fun `dropping two seconds of frames inside the window is a burst`() {
        val t = Tracker()
        t.onSample(sample(0, Signals(videoFps = 50f, renderedFrames = 0)))
        // 99 dropped: not yet two seconds of 50 fps.
        assertEquals(emptyList(), t.issues(sample(500, Signals(videoFps = 50f, droppedFrames = 99, renderedFrames = 1_000))))
        val out = t.onSample(sample(1_000, Signals(videoFps = 50f, droppedFrames = 100, renderedFrames = 1_000)))
        assertEquals(listOf(Issue.VIDEO_DROP_BURST), out.map { it.issue })
        assertEquals(100L, out.single().framesDropped)
    }

    @Test
    fun `a quarter of the frames dropped over the window is a burst`() {
        val t = Tracker()
        t.onSample(sample(0, Signals(videoFps = 50f, renderedFrames = 0)))
        var dropped = 0L
        var rendered = 0L
        var found = emptyList<Issue>()
        // 10 dropped + 30 rendered per 500 ms is exactly 25 %, below the two-seconds-of-frames count.
        var now = 500L
        while (now <= 5_000L && found.isEmpty()) {
            dropped += 10
            rendered += 30
            found = t.issues(sample(now, Signals(videoFps = 50f, droppedFrames = dropped, renderedFrames = rendered)))
            now += 500L
        }
        assertEquals(listOf(Issue.VIDEO_DROP_BURST), found)
    }

    @Test
    fun `an occasional dropped frame is not a burst`() {
        val t = Tracker()
        t.onSample(sample(0, Signals(videoFps = 50f, renderedFrames = 0)))
        var rendered = 0L
        var dropped = 0L
        for (now in 500L..120_000L step 500L) {
            rendered += 25
            if (now % 10_000L == 0L) dropped += 2
            assertEquals(
                emptyList(),
                t.issues(sample(now, Signals(videoFps = 50f, droppedFrames = dropped, renderedFrames = rendered))),
                "at $now",
            )
        }
    }

    @Test
    fun `three underruns in ten seconds is a burst and three in thirty is not`() {
        val t = Tracker()
        t.onSample(sample(0, healthy()))
        assertEquals(emptyList(), t.issues(sample(4_000, healthy().copy(audioUnderruns = 1))))
        assertEquals(emptyList(), t.issues(sample(8_000, healthy().copy(audioUnderruns = 2))))
        assertEquals(listOf(Issue.AUDIO_UNDERRUN_BURST), t.issues(sample(9_000, healthy().copy(audioUnderruns = 3))))

        val u = Tracker()
        u.onSample(sample(0, healthy()))
        assertEquals(emptyList(), u.issues(sample(1_000, healthy().copy(audioUnderruns = 1))))
        assertEquals(emptyList(), u.issues(sample(15_000, healthy().copy(audioUnderruns = 2))))
        assertEquals(emptyList(), u.issues(sample(30_000, healthy().copy(audioUnderruns = 3))))
    }

    @Test
    fun `decoder initialisation over three seconds is slow`() {
        val t = Tracker()
        assertEquals(emptyList(), t.issues(sample(0, healthy().copy(audioDecoderInitMs = 3_000, videoDecoderInitMs = 120))))
        assertEquals(listOf(Issue.DECODER_INIT_SLOW), t.issues(sample(500, healthy().copy(videoDecoderInitMs = 3_001))))
    }

    @Test
    fun `a stale slow init carried into a new tune is not re-reported`() {
        val t = Tracker()
        t.onSample(sample(0, healthy().copy(videoDecoderInitMs = 5_000)))
        t.onTune()
        // First sample of the new tune: the engine still shows the old reading.
        assertEquals(emptyList(), t.issues(sample(500, healthy().copy(videoDecoderInitMs = 5_000))))
        assertEquals(emptyList(), t.issues(sample(1_000, healthy().copy(videoDecoderInitMs = 5_000))))
        // A new, slow init is.
        assertEquals(listOf(Issue.DECODER_INIT_SLOW), t.issues(sample(1_500, healthy().copy(videoDecoderInitMs = 3_500))))
    }

    @Test
    fun `decoder errors name the right side`() {
        val t = Tracker()
        t.onSample(sample(0, healthy()))
        assertEquals(listOf(Issue.AUDIO_DECODER_ERROR), t.issues(sample(500, healthy().copy(audioDecoderErrors = 1))))
        assertEquals(
            listOf(Issue.VIDEO_DECODER_ERROR),
            t.issues(sample(1_000, healthy().copy(audioDecoderErrors = 1, videoDecoderErrors = 1))),
        )
        val u = Tracker()
        u.onSample(sample(0, healthy()))
        assertEquals(listOf(Issue.AUDIO_DECODER_ERROR), u.issues(sample(500, healthy().copy(audioSinkErrors = 1))))
    }

    @Test
    fun `an audio video offset past five seconds must hold for two seconds`() {
        val t = Tracker()
        assertEquals(emptyList(), t.issues(sample(0, healthy().copy(avSyncMs = 6_000))))
        assertEquals(emptyList(), t.issues(sample(1_000, healthy().copy(avSyncMs = 6_100))))
        val out = t.onSample(sample(2_000, healthy().copy(avSyncMs = -6_200)))
        assertEquals(listOf(Issue.AUDIO_TIMESTAMP_FAULT), out.map { it.issue })
        assertEquals(Fault.AV_OFFSET, out.single().fault)
        assertEquals("1-10s", DecodeHealthPolicy.avOffsetBucket(out.single().avOffsetMs!!))
    }

    @Test
    fun `a transient offset swing is not a fault`() {
        val t = Tracker()
        t.onSample(sample(0, healthy().copy(avSyncMs = 9_000)))
        t.onSample(sample(1_000, healthy().copy(avSyncMs = 9_000)))
        t.onSample(sample(1_500, healthy().copy(avSyncMs = 0)))
        assertEquals(emptyList(), t.issues(sample(3_000, healthy().copy(avSyncMs = 9_000))))
        assertEquals(emptyList(), t.issues(sample(4_000, healthy().copy(avSyncMs = 9_000))))
    }

    @Test
    fun `mpv log prefixes map to a decoder side and url bearing modules map to none`() {
        assertEquals(DecodeHealthPolicy.MpvLogSide.AUDIO, DecodeHealthPolicy.mpvLogSide("ad"))
        assertEquals(DecodeHealthPolicy.MpvLogSide.AUDIO, DecodeHealthPolicy.mpvLogSide("ao"))
        assertEquals(DecodeHealthPolicy.MpvLogSide.AUDIO, DecodeHealthPolicy.mpvLogSide("ao/coreaudio"))
        assertEquals(DecodeHealthPolicy.MpvLogSide.VIDEO, DecodeHealthPolicy.mpvLogSide("vd"))
        assertEquals(DecodeHealthPolicy.MpvLogSide.VIDEO, DecodeHealthPolicy.mpvLogSide(" VD "))
        for (other in listOf("cplayer", "stream", "ffmpeg", "ffmpeg/video", "vo", "file", "demux", "")) {
            assertNull(DecodeHealthPolicy.mpvLogSide(other), "prefix '$other' must not be counted")
        }
    }

    @Test
    fun `offset buckets`() {
        assertEquals("<1s", DecodeHealthPolicy.avOffsetBucket(999))
        assertEquals("<1s", DecodeHealthPolicy.avOffsetBucket(-999))
        assertEquals("1-10s", DecodeHealthPolicy.avOffsetBucket(1_000))
        assertEquals("1-10s", DecodeHealthPolicy.avOffsetBucket(9_999))
        assertEquals("10-60s", DecodeHealthPolicy.avOffsetBucket(10_000))
        assertEquals("10-60s", DecodeHealthPolicy.avOffsetBucket(-60_000))
        assertEquals(">60s", DecodeHealthPolicy.avOffsetBucket(60_001))
    }

    @Test
    fun `a reloaded engine counters restart without reading as news`() {
        val t = Tracker()
        t.onSample(sample(0, healthy().copy(audioDiscontinuities = 3, videoDecoderErrors = 2, droppedFrames = 900)))
        t.onSample(sample(500, healthy().copy(audioDiscontinuities = 3, videoDecoderErrors = 2, droppedFrames = 900)))
        // Reload: everything back to zero. Not a finding.
        assertEquals(emptyList(), t.issues(sample(1_000, healthy())))
        // A real new one afterwards is.
        assertEquals(listOf(Issue.VIDEO_DECODER_ERROR), t.issues(sample(1_500, healthy().copy(videoDecoderErrors = 1))))
    }

    @Test
    fun `a baseline already above zero is not a finding`() {
        val t = Tracker()
        val first = healthy().copy(audioDiscontinuities = 7, audioSinkErrors = 2, droppedFrames = 400, audioUnderruns = 5)
        assertEquals(emptyList(), t.issues(sample(0, first)))
    }

    @Test
    fun `timestamp repaired is defined and never produced`() {
        assertTrue(Issue.entries.any { it == Issue.TIMESTAMP_REPAIRED })
        val t = Tracker(maxPerSession = 1_000)
        t.onSample(sample(0, healthy()))
        var now = 500L
        repeat(50) { i ->
            t.onTune()
            t.onSample(sample(now, healthy()))
            now += 500L
            val bad = healthy(advancing = false).copy(
                audioDiscontinuities = i + 1, audioSinkErrors = i + 1, audioDecoderErrors = i + 1, videoDecoderErrors = i + 1,
                audioUnderruns = 3 * (i + 1), droppedFrames = 1_000L * (i + 1), avSyncMs = 20_000, audioDecoderInitMs = 9_000,
            )
            repeat(30) {
                val out = t.onSample(sample(now, bad, ticks = 1))
                assertFalse(out.any { f -> f.issue == Issue.TIMESTAMP_REPAIRED })
                now += 500L
            }
        }
    }

    @Test
    fun `issue wire names are the spec set`() {
        assertEquals(
            setOf(
                "audio_timestamp_fault", "video_timestamp_fault", "audio_not_playing", "video_frozen_audio_playing",
                "audio_decoder_error", "video_decoder_error", "video_drop_burst", "audio_underrun_burst",
                "decoder_init_slow", "timestamp_repaired",
            ),
            Issue.entries.map { it.wire }.toSet(),
        )
        assertEquals(Issue.entries.size, Issue.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun `properties carry the shape and no forbidden key`() {
        val shape = DecodeHealthPolicy.StreamShape(
            engine = "exoplayer", container = "ts", audioCodec = "E-AC-3", audioChannels = 6, audioSampleRate = 48_000,
            videoCodec = "HEVC", videoHeight = 2160, videoFps = 50f, hdr = true, decodeMode = "hw",
        )
        val finding = DecodeHealthPolicy.Finding(
            Issue.AUDIO_TIMESTAMP_FAULT, Fault.AV_OFFSET, avOffsetMs = 6_500, secondsInState = 3,
            framesDropped = 12, underruns = 1, sinkErrors = 4,
        )
        val p = DecodeHealthPolicy.properties(finding, shape, surface = "player", platform = "android")
        assertEquals("audio_timestamp_fault", p["issue"])
        assertEquals("av_offset", p["fault"])
        assertEquals("exoplayer", p["engine"])
        assertEquals("ts", p["container"])
        assertEquals("e-ac-3", p["audio_codec"])
        assertEquals(6, p["audio_channels"])
        assertEquals(48_000, p["audio_sample_rate"])
        assertEquals("hevc", p["video_codec"])
        assertEquals(2160, p["video_height"])
        assertEquals(50, p["video_fps"])
        assertEquals(true, p["hdr"])
        assertEquals("hw", p["decode_mode"])
        assertEquals("1-10s", p["av_offset_bucket"])
        assertEquals(3L, p["seconds_in_bucket"])
        assertEquals(12L, p["frames_dropped"])
        assertEquals(1, p["underruns"])
        assertEquals(4, p["sink_errors"])
        assertEquals("player", p["surface"])
        assertEquals("android", p["platform"])
        for (forbidden in listOf("code", "state", "url", "token", "host", "channel", "username", "name")) {
            assertFalse(forbidden in p.keys, "property $forbidden must not exist")
        }
        assertEquals(
            setOf(
                "issue", "fault", "engine", "container", "audio_codec", "audio_channels", "audio_sample_rate", "video_codec",
                "video_height", "video_fps", "hdr", "decode_mode", "b_frames", "av_offset_bucket", "seconds_in_bucket", "frames_dropped",
                "underruns", "sink_errors", "surface", "platform",
            ),
            p.keys,
        )
    }

    @Test
    fun `b frames maps reorder depth to yes no or unknown`() {
        assertEquals("yes", DecodeHealthPolicy.bFrames(2))
        assertEquals("yes", DecodeHealthPolicy.bFrames(1))
        assertEquals("no", DecodeHealthPolicy.bFrames(0))
        assertEquals("unknown", DecodeHealthPolicy.bFrames(null))
        assertEquals("unknown", DecodeHealthPolicy.bFrames(-1))
    }

    @Test
    fun `video streams report the frame type and audio only streams do not`() {
        val finding = DecodeHealthPolicy.Finding(Issue.AUDIO_DECODER_ERROR)
        val withDepth = DecodeHealthPolicy.properties(
            finding, DecodeHealthPolicy.StreamShape(videoCodec = "HEVC", reorderDepth = 2), "player", "android",
        )
        assertEquals("yes", withDepth["b_frames"])
        assertEquals(2, withDepth["reorder_depth"])

        val none = DecodeHealthPolicy.properties(
            finding, DecodeHealthPolicy.StreamShape(videoHeight = 1080, reorderDepth = 0), "player", "android",
        )
        assertEquals("no", none["b_frames"])
        assertEquals(0, none["reorder_depth"])

        val unknown = DecodeHealthPolicy.properties(
            finding, DecodeHealthPolicy.StreamShape(videoCodec = "H.264"), "player", "ios",
        )
        assertEquals("unknown", unknown["b_frames"])
        assertFalse("reorder_depth" in unknown.keys)

        val radio = DecodeHealthPolicy.properties(
            finding, DecodeHealthPolicy.StreamShape(audioCodec = "AAC", audioChannels = 2), "player", "ios",
        )
        assertFalse("b_frames" in radio.keys)
        assertFalse("reorder_depth" in radio.keys)
        assertFalse("b_frames" in DecodeHealthPolicy.properties(finding, null, "player", "ios").keys)
    }

    @Test
    fun `unknown shape facts are omitted and unclean labels dropped`() {
        val p = DecodeHealthPolicy.properties(
            DecodeHealthPolicy.Finding(Issue.VIDEO_DECODER_ERROR),
            DecodeHealthPolicy.StreamShape(audioCodec = "http://provider.example/x", videoCodec = "Some Channel Name HD", decodeMode = "maybe"),
            surface = "livetv_docked", platform = "ios",
        )
        assertNull(p["audio_codec"])
        assertNull(p["video_codec"])
        assertNull(p["decode_mode"])
        assertNull(p["fault"])
        assertNull(p["av_offset_bucket"])
        assertEquals("video_decoder_error", p["issue"])
    }

    @Test
    fun `heights fps and sample rates are bucketed`() {
        assertEquals(1080, DecodeHealthPolicy.bucketHeight(1088))
        assertEquals(2160, DecodeHealthPolicy.bucketHeight(2160))
        assertEquals(576, DecodeHealthPolicy.bucketHeight(544))
        assertEquals(25, DecodeHealthPolicy.bucketFps(24.97f))
        assertEquals(30, DecodeHealthPolicy.bucketFps(29.97f))
        assertEquals(50, DecodeHealthPolicy.bucketFps(50f))
        assertEquals(60, DecodeHealthPolicy.bucketFps(59.94f))
        assertEquals(48_000, DecodeHealthPolicy.bucketSampleRate(48_000))
        assertEquals(44_100, DecodeHealthPolicy.bucketSampleRate(44_100))
    }

    @Test
    fun `reporter sends one error event per finding with the shape and starts a tune on a new key`() {
        val sent = mutableListOf<Pair<String, Map<String, Any>>>()
        val r = DecodeHealthReporter(platform = "tvos", report = { e, p -> sent += e to p })
        val shape = { DecodeHealthPolicy.StreamShape(engine = "libmpv", container = "ts") }
        r.onSample("ch1", sample(0, healthy()), "player", shape)
        r.onSample("ch1", sample(500, healthy().copy(videoDecoderErrors = 1)), "player", shape)
        r.onSample("ch1", sample(1_000, healthy().copy(videoDecoderErrors = 2)), "player", shape)
        assertEquals(1, sent.size)
        assertEquals("playback_decode_health", sent.single().first)
        assertEquals("video_decoder_error", sent.single().second["issue"])
        assertEquals("libmpv", sent.single().second["engine"])
        assertEquals("tvos", sent.single().second["platform"])
        // Next channel: the same issue may report again.
        r.onSample("ch2", sample(1_500, healthy()), "player", shape)
        r.onSample("ch2", sample(2_000, healthy().copy(videoDecoderErrors = 1)), "player", shape)
        assertEquals(2, sent.size)
        assertEquals(2, r.eventsThisSession)
    }

    @Test
    fun `reporter never builds the shape when nothing is reported`() {
        var built = 0
        val r = DecodeHealthReporter(platform = "android", report = { _, _ -> })
        for (now in 0L..30_000L step 500L) {
            r.onSample("c", sample(now, healthy()), "player") { built += 1; null }
        }
        assertEquals(0, built)
    }

    @Test
    fun `a throwing shape provider still reports the issue`() {
        val sent = mutableListOf<Map<String, Any>>()
        val r = DecodeHealthReporter(platform = "android", report = { _, p -> sent += p })
        r.onSample("c", sample(0, healthy()), "player") { error("boom") }
        r.onSample("c", sample(500, healthy().copy(audioDecoderErrors = 1)), "player") { error("boom") }
        assertEquals(1, sent.size)
        assertEquals("audio_decoder_error", sent.single()["issue"])
        assertFalse("engine" in sent.single())
    }
}
