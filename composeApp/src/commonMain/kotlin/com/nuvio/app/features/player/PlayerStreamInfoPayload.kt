package com.nuvio.app.features.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Engine names shown in the stream info, shared so every platform spells them alike. */
internal const val ENGINE_LABEL_EXOPLAYER = "ExoPlayer"
internal const val ENGINE_LABEL_LIBMPV = "libmpv"

/**
 * Wire format for stream info crossing a native boundary — the iOS Swift bridge and the
 * desktop JNI bridge both hand back one JSON blob rather than a dozen scalar getters.
 *
 * Both sides read the same mpv properties, so they share this shape and this decoder:
 * a divergence between the two would show up as a phone and a desktop describing the
 * same stream differently.
 *
 * Field names match mpv's own vocabulary so the native side can populate them directly.
 * Every field is optional — mpv reports nothing at all for some live streams.
 */
@Serializable
internal data class PlayerStreamInfoPayload(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFps: Double? = null,
    val videoBitrate: Double? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val audioBitrate: Double? = null,
    /** HDR transfer (PQ / HLG) in use, when the native side knows. */
    val hdr: Boolean? = null,
    /** `hw` or `sw` video decoding, when the native side knows. */
    val decodeMode: String? = null,
)

private val streamInfoJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Decode a native stream-info payload. Returns an info object carrying only the engine
 * label when the payload is missing or malformed: this is diagnostics, so a bad blob
 * must degrade to an empty panel rather than propagate out of the UI event that opened it.
 */
internal fun decodePlayerStreamInfo(json: String?, engineLabel: String): PlayerStreamInfo {
    val payload = json
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { streamInfoJson.decodeFromString<PlayerStreamInfoPayload>(it) }.getOrNull() }
        ?: return PlayerStreamInfo(playerEngine = engineLabel)

    return PlayerStreamInfo(
        videoCodec = StreamCodecNames.display(payload.videoCodec),
        videoWidth = payload.videoWidth?.takeIf { it > 0 },
        videoHeight = payload.videoHeight?.takeIf { it > 0 },
        videoFrameRate = payload.videoFps?.toFloat()?.takeIf { it > 0f },
        videoBitrate = payload.videoBitrate?.takeIf { it > 0.0 }?.toInt(),
        audioCodec = StreamCodecNames.display(payload.audioCodec),
        audioChannelCount = payload.audioChannels?.takeIf { it > 0 },
        audioSampleRate = payload.audioSampleRate?.takeIf { it > 0 },
        audioBitrate = payload.audioBitrate?.takeIf { it > 0.0 }?.toInt(),
        playerEngine = engineLabel,
        hdr = payload.hdr,
        decodeMode = payload.decodeMode?.takeIf { it == "hw" || it == "sw" },
    )
}
