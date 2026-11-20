package com.nuvio.app.core.analytics

import kotlin.math.abs

/**
 * Decode-health telemetry for live channels: what the AUDIO and VIDEO decoders are doing wrong, named
 * as one closed [Issue] set so the fleet can answer "how common is a broken-timestamp channel?" before
 * any repair is built (research/live-engine-bench-2026-11-19/TIMESTAMP-SANITIZER-SPEC.md section 4,
 * tracker B265). REPORTING ONLY: nothing here changes playback, repairs a timestamp, routes an engine
 * or touches the sound.
 *
 * The decision is pure so it tests without a player, a network or a clock: engines feed cumulative
 * counters ([Signals]) on the snapshot cadence they already have, and [Tracker.onSample] answers which
 * [Finding]s are worth one event. Never on a timer: every evaluation is driven by an engine sample.
 *
 * Volume is bounded three ways. Once per `(issue, tune)`; at most [MAX_EVENTS_PER_SESSION] per player
 * session; and each issue needs a real condition (a sustained state or a burst), not one noisy sample.
 *
 * This object is self-contained on purpose: Desktop and NuvioTV hand-port it, and `:tvosCore` compiles
 * it, so it imports nothing but the Kotlin standard library.
 */
object DecodeHealthPolicy {

    /** The event name ([com.nuvio.app.core.telemetry.api.TelemetryPort.reportError]: an error-class event, sent with the trail). */
    const val EVENT = "playback_decode_health"

    // ---- Thresholds (spec section 4; tuned from production telemetry, never in the engine feeds) ----

    /** Audio selected and audible, playing this long, audio position still not advancing. */
    const val AUDIO_NOT_PLAYING_MS = 8_000L

    /** Rendered video frames flat this long while the audio clock advances. */
    const val VIDEO_FROZEN_MS = 5_000L

    /** Playing this long before a flat picture counts: a hardware decoder renders nothing while it warms up. */
    const val VIDEO_STARTUP_GRACE_MS = 8_000L

    /** Window for the dropped-frame rule. */
    const val DROP_WINDOW_MS = 5_000L

    /** Dropped share of the frames in the window that counts as a burst, in percent. */
    const val DROP_PERCENT = 25

    /** A burst of this many SECONDS of frames dropped inside the window counts, whatever the share. */
    const val DROP_BURST_SECONDS = 2

    /** The frame rate assumed when the stream did not declare one (only the burst rule needs it). */
    const val DROP_ASSUMED_FPS = 25f

    /** The percentage rule needs at least this many frames in the window, so a start-up hiccup is not a burst. */
    const val DROP_MIN_FRAMES = 50L

    const val UNDERRUN_WINDOW_MS = 10_000L
    const val UNDERRUN_BURST_COUNT = 3

    /** Audio or video decoder initialisation slower than this. */
    const val DECODER_INIT_SLOW_MS = 3_000L

    /** mpv's `avsync` magnitude that means the audio clock is on another timeline. */
    const val AV_OFFSET_FAULT_MS = 5_000L

    /** The offset must hold this long across samples: mpv's `avsync` swings through a seek or a start. */
    const val AV_OFFSET_SUSTAIN_MS = 2_000L

    const val MAX_EVENTS_PER_SESSION = 5

    /** What went wrong. [wire] is the `issue` property value; the set is closed so dashboards can enumerate it. */
    enum class Issue(val wire: String) {
        AUDIO_TIMESTAMP_FAULT("audio_timestamp_fault"),
        VIDEO_TIMESTAMP_FAULT("video_timestamp_fault"),
        AUDIO_NOT_PLAYING("audio_not_playing"),
        VIDEO_FROZEN_AUDIO_PLAYING("video_frozen_audio_playing"),
        AUDIO_DECODER_ERROR("audio_decoder_error"),
        VIDEO_DECODER_ERROR("video_decoder_error"),
        VIDEO_DROP_BURST("video_drop_burst"),
        AUDIO_UNDERRUN_BURST("audio_underrun_burst"),
        DECODER_INIT_SLOW("decoder_init_slow"),

        /** Defined for the day a timestamp sanitizer exists; no feed produces it and [Tracker] never returns it. */
        TIMESTAMP_REPAIRED("timestamp_repaired"),
    }

    /**
     * Which timestamp fault, where the engine can name it. The first six are the sanitizer's classes
     * (spec section 2) and are reserved for a feed that reads PES timestamps; the last two are what an
     * engine can observe without a demuxer hook.
     */
    enum class Fault(val wire: String) {
        FOREIGN_BASE("foreign_base"),
        STALLED_CLOCK("stalled_clock"),
        RUNAWAY_CLOCK("runaway_clock"),
        BACKWARD_JUMP("backward_jump"),
        FORWARD_JUMP("forward_jump"),
        PTS_WRAP("pts_wrap"),

        /** Media3 `UnexpectedDiscontinuityException` from the audio sink: the audio timeline jumped under the renderer. */
        SINK_DISCONTINUITY("sink_discontinuity"),

        /** mpv's audio/video offset (`avsync`) held beyond [AV_OFFSET_FAULT_MS]. */
        AV_OFFSET("av_offset"),
    }

    /**
     * Cumulative engine facts, read off the snapshot. Counters only ever grow within one engine session;
     * the tracker treats a decrease as a reload and rebases, so a feed never has to subtract.
     */
    data class Signals(
        /** Audio sink init/write failures that were NOT a timestamp discontinuity. */
        val audioSinkErrors: Int = 0,
        /** Audio sink `UnexpectedDiscontinuityException`s. */
        val audioDiscontinuities: Int = 0,
        val audioDecoderErrors: Int = 0,
        val videoDecoderErrors: Int = 0,
        val audioUnderruns: Int = 0,
        /** Frames the renderer or VO dropped. */
        val droppedFrames: Long = 0L,
        /** Frames the renderer put on screen, or -1 when the engine has no such count (mpv). */
        val renderedFrames: Long = -1L,
        /** Whether the audio position is advancing; null when the engine cannot say. */
        val audioAdvancing: Boolean? = null,
        /** An audio track is selected; null when unknown. A silent channel (radio-less video) never has one. */
        val audioSelected: Boolean? = null,
        /** Audio output is muted (viewer mute, or a muted surface). */
        val audioMuted: Boolean = false,
        /** Signed audio/video offset in ms, mpv `avsync`; null when unavailable. */
        val avSyncMs: Long? = null,
        val audioDecoderInitMs: Long = 0L,
        val videoDecoderInitMs: Long = 0L,
        /** Declared video frame rate, 0 when unknown; scales the burst rule. */
        val videoFps: Float = 0f,
    )

    /** One engine sample: the playback state the freeze tracking already has, plus [signals]. */
    data class Sample(
        val nowMs: Long,
        /** Playing now: not loading, not paused, not ended. */
        val playing: Boolean,
        val hasVideoTrack: Boolean,
        /** The viewer can hear this player: false for a muted surface (a muted multiview tile, a preview). */
        val surfaceAudible: Boolean,
        /** The engine's monotonic picture-alive counter (what the freeze policy reads). */
        val videoProgressTicks: Long,
        val positionMs: Long,
        val signals: Signals,
    )

    /** One reportable observation, before it is turned into properties. */
    data class Finding(
        val issue: Issue,
        val fault: Fault? = null,
        /** Signed audio/video offset when the finding is about one. */
        val avOffsetMs: Long? = null,
        /** How long the bad state had held when it was reported, in seconds (0 for an instantaneous event). */
        val secondsInState: Long = 0L,
        val framesDropped: Long = 0L,
        val underruns: Int = 0,
        val sinkErrors: Int = 0,
    )

    /**
     * Stream-shape facts, all optional. Codec names are the engine's own labels, reduced to a short token by
     * [codecToken]; there is no field that could carry a host, URL, channel name or account.
     */
    data class StreamShape(
        val engine: String? = null,
        val container: String? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
        val audioSampleRate: Int? = null,
        val videoCodec: String? = null,
        val videoHeight: Int? = null,
        val videoFps: Float? = null,
        val hdr: Boolean? = null,
        /** `hw` or `sw`, when the engine knows. */
        val decodeMode: String? = null,
        /**
         * The video's reorder depth (how many frames the decoder must hold to output in display order), when the
         * engine can say; null when it cannot. 0 means no B-frames, above 0 means frames that reorder.
         */
        val reorderDepth: Int? = null,
    ) {
        /** Whether the shape carries any video fact, so audio-only streams do not report a frame type. */
        val hasVideo: Boolean get() = videoCodec != null || videoHeight != null || videoFps != null
    }

    /** `yes` (reorder depth above 0), `no` (0) or `unknown` (the engine cannot say). */
    fun bFrames(reorderDepth: Int?): String = when {
        reorderDepth == null || reorderDepth < 0 -> "unknown"
        reorderDepth == 0 -> "no"
        else -> "yes"
    }

    /** Which decoder side an mpv log line belongs to. */
    enum class MpvLogSide { AUDIO, VIDEO }

    /**
     * Classifies an mpv log prefix: `ad` (audio decoder) and `ao` (audio output) are the audio side, `vd` (video
     * decoder) the video side, with or without a `/sub` part. Anything else (`cplayer`, `stream`, `ffmpeg`, `vo`)
     * is not counted: those modules can print URLs, and the spec limits decode-health to ad/ao/vd (B116).
     * The iPhone and Apple TV Swift bridges mirror this rule by hand; keep them in step.
     */
    fun mpvLogSide(prefix: String): MpvLogSide? {
        val p = prefix.trim().lowercase().substringBefore('/')
        return when (p) {
            "ad", "ao" -> MpvLogSide.AUDIO
            "vd" -> MpvLogSide.VIDEO
            else -> null
        }
    }

    /** `<1s`, `1-10s`, `10-60s`, `>60s` of absolute audio/video offset. */
    fun avOffsetBucket(offsetMs: Long): String {
        val a = abs(offsetMs)
        return when {
            a < 1_000L -> "<1s"
            a < 10_000L -> "1-10s"
            a <= 60_000L -> "10-60s"
            else -> ">60s"
        }
    }

    /** Builds the event properties. Nothing here can name a host, URL, channel, user or token. */
    fun properties(finding: Finding, shape: StreamShape?, surface: String, platform: String): Map<String, Any> = buildMap {
        put("issue", finding.issue.wire)
        finding.fault?.let { put("fault", it.wire) }
        putAll(shapeProperties(shape))
        finding.avOffsetMs?.let { put("av_offset_bucket", avOffsetBucket(it)) }
        put("seconds_in_bucket", finding.secondsInState.coerceIn(0L, 3_600L))
        put("frames_dropped", finding.framesDropped.coerceAtLeast(0L))
        put("underruns", finding.underruns.coerceAtLeast(0))
        put("sink_errors", finding.sinkErrors.coerceAtLeast(0))
        put("surface", surface)
        put("platform", platform)
    }

    /**
     * The stream-shape properties alone, bucketed, for events other than `playback_decode_health` that
     * want the same facts (B265: the live failure events). Unknown or unclean facts are left out.
     */
    fun shapeProperties(shape: StreamShape?): Map<String, Any> = buildMap {
        if (shape == null) return@buildMap
        shape.engine?.let { codeToken(it)?.let { v -> put("engine", v) } }
        shape.container?.let { codeToken(it)?.let { v -> put("container", v) } }
        shape.audioCodec?.let { codeToken(it)?.let { v -> put("audio_codec", v) } }
        shape.audioChannels?.takeIf { it in 1..16 }?.let { put("audio_channels", it) }
        shape.audioSampleRate?.takeIf { it > 0 }?.let { put("audio_sample_rate", bucketSampleRate(it)) }
        shape.videoCodec?.let { codeToken(it)?.let { v -> put("video_codec", v) } }
        shape.videoHeight?.takeIf { it > 0 }?.let { put("video_height", bucketHeight(it)) }
        shape.videoFps?.takeIf { it > 0f }?.let { put("video_fps", bucketFps(it)) }
        shape.hdr?.let { put("hdr", it) }
        shape.decodeMode?.takeIf { it == "hw" || it == "sw" }?.let { put("decode_mode", it) }
        // Frame type (video streams only): how many channels have skippable B-frames.
        if (shape.hasVideo) {
            put("b_frames", bFrames(shape.reorderDepth))
            shape.reorderDepth?.takeIf { it in 0..64 }?.let { put("reorder_depth", it) }
        }
    }

    /** A short lowercase token (`ec-3`, `hevc`, `ts`), or null when the label is empty or not clean. */
    fun codeToken(label: String?): String? {
        val t = label?.trim()?.lowercase().orEmpty()
        if (t.isEmpty() || t.length > 24) return null
        if (!t.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '+' }) return null
        return t
    }

    /** Standard heights, so the property has a handful of values (a non-standard 1088 reports as 1080). */
    fun bucketHeight(height: Int): Int = when {
        height <= 360 -> 360
        height <= 480 -> 480
        height <= 576 -> 576
        height <= 720 -> 720
        height <= 1088 -> 1080
        height <= 1440 -> 1440
        height <= 2160 -> 2160
        else -> 4320
    }

    /** Nearest of the broadcast rates. */
    fun bucketFps(fps: Float): Int = when {
        fps < 27f -> 25
        fps < 40f -> 30
        fps < 55f -> 50
        else -> 60
    }

    fun bucketSampleRate(hz: Int): Int = when {
        hz <= 24_000 -> 22_050
        hz <= 44_100 -> 44_100
        hz <= 48_000 -> 48_000
        else -> 96_000
    }

    /**
     * Stateful half of the policy, still free of I/O and time: [onSample] is its only input and the clock is
     * the sample's own. One instance per player session; [onTune] starts a new channel tune inside it.
     */
    class Tracker(
        private val maxPerSession: Int = MAX_EVENTS_PER_SESSION,
    ) {
        private val reportedThisTune = HashSet<Issue>()
        private var sessionCount = 0

        // Baselines for the cumulative counters.
        private var seeded = false
        private var lastSinkErrors = 0
        private var lastDiscontinuities = 0
        private var lastAudioDecoderErrors = 0
        private var lastVideoDecoderErrors = 0
        private var lastUnderruns = 0
        private var lastDropped = 0L
        private var lastRendered = -1L
        private var lastAudioInitMs = 0L
        private var lastVideoInitMs = 0L

        // Sliding windows.
        private val dropWindow = ArrayList<DropPoint>()
        private val underrunTimes = ArrayList<Long>()

        // Sustained conditions.
        private var playingSinceMs = -1L
        private var audioIdleSinceMs = -1L
        private var lastTicks = -1L
        private var lastTicksAdvanceMs = -1L
        private var lastPositionMs = -1L
        private var offsetSinceMs = -1L

        // Accumulated per tune, reported with the finding.
        private var tuneDropped = 0L
        private var tuneUnderruns = 0
        private var tuneSinkErrors = 0

        /** Events accepted so far in this player session. */
        val eventsThisSession: Int get() = sessionCount

        /** A new channel tune: its issues may report again, the session cap carries over. */
        fun onTune() {
            reportedThisTune.clear()
            seeded = false
            dropWindow.clear()
            underrunTimes.clear()
            playingSinceMs = -1L
            audioIdleSinceMs = -1L
            lastTicks = -1L
            lastTicksAdvanceMs = -1L
            lastPositionMs = -1L
            offsetSinceMs = -1L
            tuneDropped = 0L
            tuneUnderruns = 0
            tuneSinkErrors = 0
        }

        /**
         * Playback was interrupted on purpose (a display-mode switch idles the video output): restart the
         * sustained-state clocks and windows so the gap is not read as a frozen picture, and keep what was
         * already reported (dedupe and the session cap carry over).
         */
        fun onDisruption() {
            dropWindow.clear()
            underrunTimes.clear()
            playingSinceMs = -1L
            audioIdleSinceMs = -1L
            lastTicks = -1L
            lastTicksAdvanceMs = -1L
            lastPositionMs = -1L
            offsetSinceMs = -1L
        }

        fun onSample(s: Sample): List<Finding> {
            val g = s.signals
            val now = s.nowMs
            val found = ArrayList<Finding>(2)

            // An engine that reloaded restarts its counters: rebase instead of reading the drop as news.
            val rebased = seeded && (
                g.audioSinkErrors < lastSinkErrors || g.audioDiscontinuities < lastDiscontinuities ||
                    g.audioDecoderErrors < lastAudioDecoderErrors || g.videoDecoderErrors < lastVideoDecoderErrors ||
                    g.audioUnderruns < lastUnderruns || g.droppedFrames < lastDropped ||
                    (g.renderedFrames >= 0L && lastRendered >= 0L && g.renderedFrames < lastRendered)
                )
            val first = !seeded || rebased
            val dSink = if (first) 0 else g.audioSinkErrors - lastSinkErrors
            val dDisc = if (first) 0 else g.audioDiscontinuities - lastDiscontinuities
            val dAudioDec = if (first) 0 else g.audioDecoderErrors - lastAudioDecoderErrors
            val dVideoDec = if (first) 0 else g.videoDecoderErrors - lastVideoDecoderErrors
            val dUnder = if (first) 0 else g.audioUnderruns - lastUnderruns
            val dDropped = if (first) 0L else g.droppedFrames - lastDropped
            val dRendered = if (first || g.renderedFrames < 0L || lastRendered < 0L) 0L else g.renderedFrames - lastRendered
            // A slow initialisation is a level, not a counter: only a value that CHANGED since the last sample is
            // news, so a stale reading carried over from the previous tune on a reused engine is not re-reported.
            val audioInitNew = !first && g.audioDecoderInitMs != lastAudioInitMs
            val videoInitNew = !first && g.videoDecoderInitMs != lastVideoInitMs
            seeded = true
            lastAudioInitMs = g.audioDecoderInitMs
            lastVideoInitMs = g.videoDecoderInitMs
            lastSinkErrors = g.audioSinkErrors
            lastDiscontinuities = g.audioDiscontinuities
            lastAudioDecoderErrors = g.audioDecoderErrors
            lastVideoDecoderErrors = g.videoDecoderErrors
            lastUnderruns = g.audioUnderruns
            lastDropped = g.droppedFrames
            lastRendered = g.renderedFrames
            if (rebased) {
                dropWindow.clear()
                underrunTimes.clear()
            }

            tuneSinkErrors += dSink + dDisc
            tuneDropped += dDropped.coerceAtLeast(0L)
            tuneUnderruns += dUnder.coerceAtLeast(0)

            // ---- Events (a counter moved) ----
            if (dDisc > 0) {
                found += Finding(Issue.AUDIO_TIMESTAMP_FAULT, Fault.SINK_DISCONTINUITY, sinkErrors = tuneSinkErrors)
            }
            if (dSink > 0 || dAudioDec > 0) {
                found += Finding(Issue.AUDIO_DECODER_ERROR, sinkErrors = tuneSinkErrors)
            }
            if (dVideoDec > 0) {
                found += Finding(Issue.VIDEO_DECODER_ERROR)
            }
            val slowInitMs = maxOf(
                if (audioInitNew) g.audioDecoderInitMs else 0L,
                if (videoInitNew) g.videoDecoderInitMs else 0L,
            )
            if (slowInitMs > DECODER_INIT_SLOW_MS) {
                found += Finding(Issue.DECODER_INIT_SLOW, secondsInState = slowInitMs / 1_000L)
            }

            // ---- Underrun burst: counted per underrun, windowed ----
            repeat(dUnder.coerceIn(0, 50)) { underrunTimes.add(now) }
            underrunTimes.removeAll { now - it > UNDERRUN_WINDOW_MS }
            if (underrunTimes.size >= UNDERRUN_BURST_COUNT) {
                found += Finding(Issue.AUDIO_UNDERRUN_BURST, underruns = tuneUnderruns, secondsInState = UNDERRUN_WINDOW_MS / 1_000L)
            }

            // ---- Drop burst ----
            if (dDropped > 0L || dRendered > 0L) {
                dropWindow.add(DropPoint(now, dDropped.coerceAtLeast(0L), dRendered))
            }
            dropWindow.removeAll { now - it.atMs > DROP_WINDOW_MS }
            if (dropWindow.isNotEmpty()) {
                val dropped = dropWindow.sumOf { it.dropped }
                val rendered = dropWindow.sumOf { it.rendered }
                val fps = if (g.videoFps > 0f) g.videoFps else DROP_ASSUMED_FPS
                val burstFrames = (fps * DROP_BURST_SECONDS).toLong()
                val byBurst = dropped >= burstFrames
                val total = dropped + rendered
                val byShare = g.renderedFrames >= 0L && total >= DROP_MIN_FRAMES && dropped * 100L >= total * DROP_PERCENT
                if (byBurst || byShare) {
                    found += Finding(Issue.VIDEO_DROP_BURST, framesDropped = tuneDropped, secondsInState = DROP_WINDOW_MS / 1_000L)
                }
            }

            // ---- Sustained states ----
            if (s.playing) {
                if (playingSinceMs < 0L) playingSinceMs = now
            } else {
                playingSinceMs = -1L
                audioIdleSinceMs = -1L
                lastTicksAdvanceMs = -1L
                offsetSinceMs = -1L
            }

            // Audio selected, audible and playing, but its position is not advancing.
            val audioExpected = s.playing && g.audioSelected != false && !g.audioMuted && s.surfaceAudible
            if (audioExpected && g.audioAdvancing == false) {
                if (audioIdleSinceMs < 0L) audioIdleSinceMs = now
                val idleFor = now - audioIdleSinceMs
                if (idleFor >= AUDIO_NOT_PLAYING_MS && now - playingSinceMs >= AUDIO_NOT_PLAYING_MS) {
                    found += Finding(Issue.AUDIO_NOT_PLAYING, secondsInState = idleFor / 1_000L)
                }
            } else {
                audioIdleSinceMs = -1L
            }

            // Picture flat while audio keeps advancing. Audio "advancing" is the engine's word when it has one,
            // else the playhead moving (audio is what moves it when the picture is dead).
            if (s.playing && s.hasVideoTrack) {
                if (lastTicks < 0L || s.videoProgressTicks != lastTicks) {
                    lastTicks = s.videoProgressTicks
                    lastTicksAdvanceMs = now
                }
                val positionMoved = lastPositionMs >= 0L && s.positionMs != lastPositionMs
                val audioAlive = g.audioAdvancing ?: positionMoved
                val flatFor = if (lastTicksAdvanceMs >= 0L) now - lastTicksAdvanceMs else 0L
                if (audioAlive && flatFor >= VIDEO_FROZEN_MS && now - playingSinceMs >= VIDEO_STARTUP_GRACE_MS) {
                    found += Finding(Issue.VIDEO_FROZEN_AUDIO_PLAYING, secondsInState = flatFor / 1_000L)
                }
            } else {
                lastTicks = -1L
                lastTicksAdvanceMs = -1L
            }
            lastPositionMs = s.positionMs

            // Audio and video on different timelines (mpv avsync).
            val offset = g.avSyncMs
            if (s.playing && offset != null && abs(offset) > AV_OFFSET_FAULT_MS) {
                if (offsetSinceMs < 0L) offsetSinceMs = now
                if (now - offsetSinceMs >= AV_OFFSET_SUSTAIN_MS) {
                    found += Finding(
                        Issue.AUDIO_TIMESTAMP_FAULT, Fault.AV_OFFSET, avOffsetMs = offset,
                        secondsInState = (now - offsetSinceMs) / 1_000L,
                    )
                }
            } else {
                offsetSinceMs = -1L
            }

            return accept(found)
        }

        /** Applies the once-per-(issue, tune) and per-session caps, in the order found. */
        private fun accept(candidates: List<Finding>): List<Finding> {
            if (candidates.isEmpty()) return candidates
            val out = ArrayList<Finding>(candidates.size)
            for (c in candidates) {
                if (c.issue == Issue.TIMESTAMP_REPAIRED) continue
                if (sessionCount >= maxPerSession) break
                if (!reportedThisTune.add(c.issue)) continue
                sessionCount += 1
                out += c
            }
            return out
        }

        private class DropPoint(val atMs: Long, val dropped: Long, val rendered: Long)
    }
}
