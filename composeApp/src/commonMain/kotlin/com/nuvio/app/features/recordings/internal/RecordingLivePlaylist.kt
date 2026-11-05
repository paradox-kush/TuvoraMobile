package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.livetv.pause.LocalHlsPlaylist
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Joins the playlists of a live recording's attempts into the one `index.m3u8` that plays it. Each attempt
 * records into its own directory (`p0`, `p1`, ...) with a playlist the capture loop owns; this reads them and
 * writes a single ended EVENT playlist whose pieces are named through their attempt's directory, with a
 * discontinuity where one attempt follows another. Pure, so the join tests without a disk.
 */
internal object RecordingLivePlaylist {
    class Part(val dir: String, val playlist: String)

    /** [files] = every file the playlist names (pieces and init segments), relative to the recording directory, in order. */
    class Combined(val text: String, val files: List<String>, val durationMs: Long)

    private class Piece(val name: String, val durationMs: Long, val discontinuity: Boolean, val map: String?)

    fun combine(parts: List<Part>): Combined? {
        val pieces = ArrayList<Pair<String, Piece>>()   // (part dir, piece)
        for (part in parts) for (piece in parse(part.playlist)) pieces += part.dir to piece
        if (pieces.isEmpty()) return null

        val body = StringBuilder()
        val files = ArrayList<String>()
        var emittedMap: String? = null
        var longestMs = 0L
        var totalMs = 0L
        var sawMap = false
        var previousDir: String? = null
        for ((dir, piece) in pieces) {
            val first = previousDir == null
            val map = piece.map?.let { "$dir/$it" }
            val newMap = map != null && map != emittedMap
            val joined = !first && previousDir != dir
            if (!first && (piece.discontinuity || joined || newMap)) body.append("#EXT-X-DISCONTINUITY\n")
            if (newMap) {
                body.append("#EXT-X-MAP:URI=\"").append(map).append("\"\n")
                emittedMap = map
                sawMap = true
                files += map!!
            }
            body.append("#EXTINF:").append(LocalHlsPlaylist.formatSeconds(piece.durationMs)).append(",\n")
            val name = "$dir/${piece.name}"
            body.append(name).append('\n')
            files += name
            totalMs += piece.durationMs
            longestMs = maxOf(longestMs, piece.durationMs)
            previousDir = dir
        }
        val target = maxOf(MIN_TARGET_SEC, ceil(longestMs / 1000.0).toInt())
        val text = buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:").append(if (sawMap) 6 else 3).append('\n')
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            // Finished and growing-only: an EVENT playlist with ENDLIST plays and seeks like a recording.
            append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
            append(body)
            append("#EXT-X-ENDLIST\n")
        }
        return Combined(text, files, totalMs)
    }

    fun durationMs(playlist: String): Long = parse(playlist).sumOf { it.durationMs }

    private fun parse(playlist: String): List<Piece> {
        val out = ArrayList<Piece>()
        var pendingDiscontinuity = false
        var pendingDuration: Long? = null
        var map: String? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line == "#EXT-X-DISCONTINUITY" -> pendingDiscontinuity = true
                line.startsWith("#EXT-X-MAP:") -> map = Regex("URI=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                line.startsWith("#EXTINF:") -> pendingDuration = line.removePrefix("#EXTINF:").substringBefore(',').trim()
                    .toDoubleOrNull()?.let { (it * 1000.0).roundToLong() }
                line.startsWith("#") -> Unit
                else -> {
                    val d = pendingDuration
                    if (d != null) {
                        out += Piece(line, d, pendingDiscontinuity, map)
                        pendingDiscontinuity = false
                        pendingDuration = null
                    }
                }
            }
        }
        return out
    }

    private const val MIN_TARGET_SEC = 2
}
