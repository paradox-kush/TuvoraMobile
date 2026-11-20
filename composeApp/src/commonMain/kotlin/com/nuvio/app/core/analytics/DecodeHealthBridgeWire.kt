package com.nuvio.app.core.analytics

/**
 * The text line a Swift mpv bridge (iPhone, iPad, Apple TV) hands to Kotlin for decode-health telemetry, and its
 * parser. One short line instead of a getter per field, because the bridge protocol is hand-mirrored in Swift and
 * Gradle cannot check it (the `getStreamInfoJson` precedent); and not JSON, because it is read on every 250 ms poll.
 *
 * `v1,<avsync ms|->,<dropped frames>,<audio warns>,<video warns>,<audio selected 1|0>,<audio advancing 1|0|->,<muted 1|0>,<fps x100|->`
 *
 * Written by `DecodeHealthShadow.wireLine` in both Swift apps; keep the three in step. A `-` is "unknown". A line
 * that is empty, has another version or is malformed parses to null: the feed is diagnostics, so it degrades to
 * "no decode-health signals" and never throws.
 */
object DecodeHealthBridgeWire {
    const val VERSION = "v1"
    private const val FIELD_COUNT = 9

    fun parse(line: String?): DecodeHealthPolicy.Signals? {
        if (line.isNullOrBlank()) return null
        val f = line.trim().split(',')
        if (f.size != FIELD_COUNT || f[0] != VERSION) return null
        val avSync = number(f[1])
        if (f[1] != UNKNOWN && avSync == null) return null
        val dropped = number(f[2]) ?: return null
        val audioWarns = number(f[3]) ?: return null
        val videoWarns = number(f[4]) ?: return null
        val audioSelected = flag(f[5]) ?: return null
        val audioAdvancing = flag(f[6])
        if (f[6] != UNKNOWN && audioAdvancing == null) return null
        val muted = flag(f[7]) ?: return null
        val fpsX100 = number(f[8])
        if (f[8] != UNKNOWN && fpsX100 == null) return null
        return DecodeHealthPolicy.Signals(
            audioDecoderErrors = audioWarns.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            videoDecoderErrors = videoWarns.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            droppedFrames = dropped.coerceAtLeast(0L),
            audioAdvancing = audioAdvancing,
            audioSelected = audioSelected,
            audioMuted = muted,
            avSyncMs = avSync,
            videoFps = fpsX100?.let { it / 100f } ?: 0f,
        )
    }

    private const val UNKNOWN = "-"

    private fun number(text: String): Long? = if (text == UNKNOWN) null else text.toLongOrNull()

    private fun flag(text: String): Boolean? = when (text) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
