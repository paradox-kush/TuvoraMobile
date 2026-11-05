package com.nuvio.app.features.livetv.pause

/** One saved piece of a live capture (a TS piece the segmenter cut, or an HLS segment downloaded once). */
data class LivePiece(
    val sequence: Long,
    val name: String,
    val durationMs: Long,
    val bytes: Long = 0L,
    val discontinuity: Boolean = false,
)

/**
 * Pure text builder for the local `index.m3u8` the player reloads like any live playlist
 * (research/catchup-recordings-2026-11.md section 5). Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0),
 * TsSegmenter.writePlaylist / LiveTvTimeshift.writeLocalPlaylist: durations hand-formatted (no String.format,
 * Kotlin/Native), discontinuity and init-segment support merged into one builder.
 */
object LocalHlsPlaylist {
    fun render(
        pieces: List<LivePiece>,
        targetDurationSec: Int,
        mediaSequence: Long,
        ended: Boolean,
        eventType: Boolean,
        initSegment: String?,
    ): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:").append(if (initSegment != null) 6 else 3).append('\n')
        sb.append("#EXT-X-TARGETDURATION:").append(targetDurationSec).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n')
        // A rewind buffer drops its oldest pieces, which an EVENT playlist is not allowed to do.
        if (eventType) sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        if (initSegment != null) sb.append("#EXT-X-MAP:URI=\"").append(initSegment).append("\"\n")
        for (piece in pieces) {
            if (piece.discontinuity) sb.append("#EXT-X-DISCONTINUITY\n")
            sb.append("#EXTINF:").append(formatSeconds(piece.durationMs)).append(",\n")
            sb.append(piece.name).append('\n')
        }
        if (ended) sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /** 6000 -> "6.000", 1234 -> "1.234". */
    internal fun formatSeconds(ms: Long): String {
        val v = ms.coerceAtLeast(0L)
        val frac = (v % 1000).toString().padStart(3, '0')
        return "${v / 1000}.$frac"
    }
}
