package com.nuvio.app.features.livetv.pause

/**
 * Pure "which HLS segments do we still need" decisions for a live capture, so every segment is fetched once.
 * Adapted (as a reference only, rewritten) from homelessbrian/NuvioPlusIPTV (GPL-3.0), LiveTvTimeshift.recordHls.
 *
 * Fixes the reference's defects: it kept `seq > lastSeq` forever, so a provider that restarted its numbering
 * froze the capture silently ([SequenceReset] accepts the new numbering and marks a discontinuity); it took the
 * highest BANDWIDTH variant ([LivePausePolicy.pickVariant] with a cap); it refetched the playlist with no cap
 * (the owner uses [LiveCaptureReconnect]); it recorded encrypted streams as garbage ([Update.Unsupported]).
 * "Unchanged for too long" is the OWNER's stall timer (monotonic), not the tracker's. Confined to one coroutine.
 */
class HlsLiveSegmentTracker {
    data class MediaSegment(val sequence: Long, val durationMs: Long, val uri: String, val discontinuity: Boolean)

    sealed interface Update {
        /** Only never-seen sequences, in order. */
        data class NewSegments(val segments: List<MediaSegment>) : Update

        /** The provider restarted its numbering: accepted, the first segment carries discontinuity = true. */
        data class SequenceReset(val segments: List<MediaSegment>) : Update

        data object Unchanged : Update

        /** EXT-X-KEY with METHOD other than NONE, EXT-X-BYTERANGE, or a playlist that is not media HLS. */
        data class Unsupported(val reason: String) : Update
    }

    var resetCount: Int = 0
        private set
    var targetDurationMs: Long = 0L
        private set
    var initSegmentUri: String? = null
        private set

    /** The last playlist carried EXT-X-ENDLIST. */
    var ended: Boolean = false
        private set

    private var lastSeen = -1L
    private val seenUris = LinkedHashMap<Long, String>()

    fun onPlaylist(text: String, baseUri: String): Update {
        val parsed = parse(text, baseUri)
        if (parsed.unsupported != null) return Update.Unsupported(parsed.unsupported)
        if (parsed.targetDurationMs > 0) targetDurationMs = parsed.targetDurationMs
        if (parsed.initUri != null) initSegmentUri = parsed.initUri
        ended = parsed.ended
        val segs = parsed.segments
        if (segs.isEmpty()) return Update.Unchanged

        if (lastSeen < 0) {
            remember(segs)
            return Update.NewSegments(segs)
        }

        val first = segs.first().sequence
        val window = segs.size
        val behind = first < lastSeen - maxOf(window, MIN_RESET_WINDOW)
        val renamed = first <= lastSeen && segs.any { s ->
            val saved = seenUris[s.sequence]
            s.sequence <= lastSeen && saved != null && saved != stable(s.uri)
        }
        if (behind || renamed) {
            resetCount++
            seenUris.clear()
            remember(segs)
            return Update.SequenceReset(listOf(segs.first().copy(discontinuity = true)) + segs.drop(1))
        }

        val fresh = segs.filter { it.sequence > lastSeen }
        if (fresh.isEmpty()) return Update.Unchanged
        // Missed segments between two polls: the timeline jumps, so tell the player.
        val gap = fresh.first().sequence > lastSeen + 1
        remember(fresh)
        return Update.NewSegments(if (gap) listOf(fresh.first().copy(discontinuity = true)) + fresh.drop(1) else fresh)
    }

    private fun remember(segs: List<MediaSegment>) {
        for (s in segs) seenUris[s.sequence] = stable(s.uri)
        lastSeen = segs.last().sequence
        while (seenUris.size > REMEMBERED) seenUris.remove(seenUris.keys.first())
    }

    /** Providers sign segment URLs with a token that changes per fetch; identity is the path. */
    private fun stable(uri: String): String = uri.substringBefore('#').substringBefore('?')

    private class Parsed(
        val segments: List<MediaSegment>,
        val targetDurationMs: Long,
        val initUri: String?,
        val unsupported: String?,
        val ended: Boolean = false,
    )

    private fun parse(text: String, baseUri: String): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "#EXTM3U") return Parsed(emptyList(), 0, null, "malformed: no #EXTM3U")
        var mediaSeq = 0L
        var target = 0L
        var initUri: String? = null
        var pendingDuration = -1L
        var pendingDisc = false
        var ended = false
        val out = ArrayList<MediaSegment>()
        // First pass for the header tags that decide durations of segments lacking EXTINF.
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> return Parsed(emptyList(), 0, null, "master playlist")
                l.startsWith("#EXT-X-TARGETDURATION:") -> target = (l.substringAfter(':').trim().toLongOrNull() ?: 0L) * 1000L
            }
        }
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSeq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([A-Za-z0-9-]+)").find(l)?.groupValues?.get(1)
                    if (method != "NONE") return Parsed(emptyList(), 0, null, "encrypted: ${method ?: "unknown method"}")
                }
                l.startsWith("#EXT-X-BYTERANGE") -> return Parsed(emptyList(), 0, null, "byte-range segments")
                l.startsWith("#EXT-X-MAP:") ->
                    Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { initUri = resolveHlsUri(baseUri, it) }
                l.startsWith("#EXTINF:") -> {
                    val sec = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                    pendingDuration = if (sec == null) -1L else (sec * 1000.0).let { kotlin.math.round(it).toLong() }
                }
                l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> pendingDisc = true
                l == "#EXT-X-ENDLIST" -> ended = true
                l.startsWith("#") -> Unit
                else -> {
                    out += MediaSegment(
                        sequence = mediaSeq + out.size,
                        durationMs = if (pendingDuration >= 0) pendingDuration else target,
                        uri = resolveHlsUri(baseUri, l),
                        discontinuity = pendingDisc,
                    )
                    pendingDuration = -1L
                    pendingDisc = false
                }
            }
        }
        return Parsed(out, target, initUri, null, ended)
    }

    private companion object {
        const val MIN_RESET_WINDOW = 3
        const val REMEMBERED = 128
    }
}

/** Variants of an HLS master playlist, in file order; empty if [text] is not a master playlist. */
fun parseHlsMaster(text: String, baseUri: String): List<LivePausePolicy.HlsVariant> {
    val lines = text.lines().map { it.trim() }
    val out = ArrayList<LivePausePolicy.HlsVariant>()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        if (l.startsWith("#EXT-X-STREAM-INF:")) {
            val bandwidth = Regex("[:,]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
            var j = i + 1
            while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
            if (j < lines.size) {
                out += LivePausePolicy.HlsVariant(resolveHlsUri(baseUri, lines[j]), bandwidth, height)
                i = j
            }
        }
        i++
    }
    return out
}

/** RFC 3986-ish reference resolution without java.net, so it compiles for Kotlin/Native. */
fun resolveHlsUri(base: String, ref: String): String {
    val r = ref.trim()
    if (SCHEME.containsMatchIn(r)) return r
    val b = base.substringBefore('#')
    val scheme = SCHEME.find(b)?.value ?: "" // "https:" or ""
    var rest = b.substring(scheme.length)
    var authority: String? = null
    if (rest.startsWith("//")) {
        val after = rest.substring(2)
        val end = after.indexOfFirst { it == '/' || it == '?' }
        authority = if (end < 0) after else after.substring(0, end)
        rest = if (end < 0) "" else after.substring(end)
    }
    val origin = scheme + (if (authority != null) "//$authority" else "")
    val basePath = rest.substringBefore('?')

    if (r.startsWith("//")) return scheme + r
    if (r.isEmpty()) return origin + basePath
    if (r.startsWith("?")) return origin + basePath + r
    val refPath = r.substringBefore('?').substringBefore('#')
    val tail = r.substring(refPath.length)
    val merged = when {
        r.startsWith("/") -> refPath
        basePath.isEmpty() && authority != null -> "/$refPath"
        else -> basePath.substring(0, basePath.lastIndexOf('/') + 1) + refPath
    }
    return origin + removeDotSegments(merged) + tail
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

private fun removeDotSegments(path: String): String {
    val parts = path.split('/')
    val stack = ArrayList<String>()
    for ((idx, seg) in parts.withIndex()) {
        val last = idx == parts.lastIndex
        when (seg) {
            "." -> if (last) stack += ""
            ".." -> {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                if (last) stack += ""
            }
            else -> stack += seg
        }
    }
    return stack.joinToString("/")
}
