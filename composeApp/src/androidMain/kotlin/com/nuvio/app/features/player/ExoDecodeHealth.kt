@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.app.core.analytics.DecodeHealthPolicy

/**
 * The ExoPlayer feed for decode-health telemetry: counts what the audio sink and the codecs report and
 * hands [DecodeHealthPolicy] the cumulative totals on each snapshot. Reporting only; it never touches
 * playback, and it reads nothing that could name a host (counts, durations, a decoder-kind word).
 *
 * One instance per player build (the player is rebuilt per source), so its counters start at zero with
 * the engine. Callbacks run on the player's application looper, the same thread the snapshot is built on.
 *
 * `onAudioPositionAdvancing` is the audio-playing signal: Media3 raises it when the audio track's
 * position starts moving (start, after a flush, after an underrun recovers). Audio is reported as
 * not-advancing from the moment the audio renderer is enabled, or an error or underrun interrupts it,
 * until that callback arrives, which is exactly "audio selected, playing, position not advancing".
 */
internal class ExoDecodeHealth : AnalyticsListener {
    @Volatile private var audioSinkErrors = 0
    @Volatile private var audioDiscontinuities = 0
    @Volatile private var audioDecoderErrors = 0
    @Volatile private var videoDecoderErrors = 0
    @Volatile private var audioUnderruns = 0
    @Volatile private var droppedFrames = 0L
    @Volatile private var audioDecoderInitMs = 0L
    @Volatile private var videoDecoderInitMs = 0L
    @Volatile private var audioEnabled = false
    @Volatile private var audioPlayout = false
    @Volatile private var softwareVideoDecoder: Boolean? = null

    /** `hw` or `sw` for the video decoder in use, null until one initialised. Derived from the codec's name only. */
    val videoDecodeMode: String?
        get() = softwareVideoDecoder?.let { if (it) "sw" else "hw" }

    fun signals(player: ExoPlayer): DecodeHealthPolicy.Signals = DecodeHealthPolicy.Signals(
        audioSinkErrors = audioSinkErrors,
        audioDiscontinuities = audioDiscontinuities,
        audioDecoderErrors = audioDecoderErrors,
        videoDecoderErrors = videoDecoderErrors,
        audioUnderruns = audioUnderruns,
        droppedFrames = droppedFrames,
        renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L,
        audioAdvancing = if (audioEnabled) audioPlayout else null,
        audioSelected = player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
        audioMuted = player.volume == 0f,
        audioDecoderInitMs = audioDecoderInitMs,
        videoDecoderInitMs = videoDecoderInitMs,
        videoFps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f,
    )

    override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
        audioEnabled = true
        audioPlayout = false
    }

    override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
        audioEnabled = false
        audioPlayout = false
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        audioPlayout = true
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        // The discontinuity is the timestamp fault (B268: an audio clock on another timeline); any other sink
        // failure is a device or format problem. Both interrupt playout until the sink recovers.
        if (audioSinkError is AudioSink.UnexpectedDiscontinuityException) audioDiscontinuities += 1 else audioSinkErrors += 1
        audioPlayout = false
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        audioUnderruns += 1
        audioPlayout = false
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        audioDecoderErrors += 1
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        videoDecoderErrors += 1
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames.coerceAtLeast(0)
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoderInitMs = initializationDurationMs.coerceAtLeast(0L)
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoderInitMs = initializationDurationMs.coerceAtLeast(0L)
        softwareVideoDecoder = isSoftwareDecoderName(decoderName)
    }

    internal companion object {
        /** Android's software codecs carry these prefixes; anything else is a vendor (hardware) decoder. */
        fun isSoftwareDecoderName(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.startsWith("c2.google.") ||
                n.contains("ffmpeg") || n.contains("libgav1") || n.contains("libvpx") || n.contains("dav1d")
        }
    }
}
