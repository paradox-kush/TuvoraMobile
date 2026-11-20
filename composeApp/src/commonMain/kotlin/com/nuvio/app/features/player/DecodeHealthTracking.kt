package com.nuvio.app.features.player

import com.nuvio.app.core.analytics.DecodeHealthPolicy
import com.nuvio.app.core.analytics.DecodeHealthReporter
import com.nuvio.app.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.app.features.trakt.TraktPlatformClock

/** `android` or `ios`; Apple TV builds its own reporter with `tvos` (tvosMain). */
internal val decodeHealthPlatform: String get() = if (com.nuvio.app.isIos) "ios" else "android"

/** A reporter for one live player session on this platform. */
fun newDecodeHealthReporter(): DecodeHealthReporter = DecodeHealthReporter(platform = decodeHealthPlatform)

/**
 * Feeds one engine snapshot to decode-health telemetry. Called beside
 * [LivePlaybackFreezeReporter.onLiveSnapshot] from the same snapshot callback, so it adds no timer and
 * no poll: the engine samples are the clock. Reporting only; nothing here can affect playback.
 *
 * @param streamInfo the engine's own description of the stream, pulled only when something is reported.
 * @param streamUrl only used to derive the container (`ts`, `m3u8`, ...); never sent.
 * @param contentId names the channel tune (a change starts a new tune); never a URL.
 * @param surfaceAudible false for a surface the viewer cannot hear (a muted preview tile), so
 *   AUDIO_NOT_PLAYING cannot fire there. Timestamp faults still report: they are real stream faults.
 */
fun DecodeHealthReporter.onLiveSnapshot(
    snapshot: PlayerPlaybackSnapshot,
    streamInfo: () -> PlayerStreamInfo?,
    streamUrl: String?,
    contentId: String?,
    surface: String,
    surfaceAudible: Boolean = true,
) {
    val signals = snapshot.decodeHealth ?: return
    onSample(
        tune = contentId,
        sample = DecodeHealthPolicy.Sample(
            nowMs = TraktPlatformClock.nowEpochMs(),
            playing = snapshot.isPlaying && !snapshot.isLoading && !snapshot.isEnded,
            hasVideoTrack = snapshot.hasVideoTrack,
            surfaceAudible = surfaceAudible,
            videoProgressTicks = snapshot.videoProgressTicks,
            positionMs = snapshot.positionMs,
            signals = signals,
        ),
        surface = surface,
        shape = { decodeHealthShape(streamInfo(), streamUrl) },
    )
}

/** Stream-shape facts for an event, from the engine's stream info. The URL contributes only its container. */
fun decodeHealthShape(info: PlayerStreamInfo?, streamUrl: String?): DecodeHealthPolicy.StreamShape =
    DecodeHealthPolicy.StreamShape(
        engine = info?.playerEngine?.lowercase(),
        container = LivePlaybackFreezeReporter.streamContainerOf(streamUrl),
        audioCodec = info?.audioCodec,
        audioChannels = info?.audioChannelCount,
        audioSampleRate = info?.audioSampleRate,
        videoCodec = info?.videoCodec,
        videoHeight = info?.videoHeight,
        videoFps = info?.videoFrameRate,
        hdr = info?.hdr,
        decodeMode = info?.decodeMode,
        reorderDepth = info?.videoReorderDepth,
    )
