package com.nuvio.app.features.iptv

/**
 * Pure, streaming-friendly parser for M3U / M3U-plus playlists. Kept free of IO so it unit-tests
 * against inline fixtures and so the ingest path can feed it one line at a time (a provider M3U is
 * commonly 190+ MB / 685k entries — it can NEVER be held in RAM as a String).
 *
 * Wire format:
 *   #EXTM3U                                  (header, ignored)
 *   #EXTINF:-1 tvg-id="" tvg-name="X" tvg-logo="url" group-title="CATEGORY",Display Name
 *   http://host/live/user/pass/12345.ts      (the stream URL on the NEXT non-comment line)
 *
 * There is no API behind an M3U URL — the parsed playlist IS the whole catalog. So each parsed
 * entry is classified into live / movie / series purely from the entry itself (URL path segment,
 * then file extension) and written straight to the on-disk content DB in chunks.
 */
object M3UParser {

    /** One classified playlist entry. [seriesKey]/[season]/[episode] are set only for [M3UKind.SERIES]. */
    data class Entry(
        val kind: M3UKind,
        val name: String,
        val url: String,
        val logo: String?,
        val tvgId: String?,
        val group: String?,
        val ext: String?,
        val seriesKey: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    /**
     * Feed lines in order; emits a fully-formed [Entry] whenever an `#EXTINF` is followed by its
     * stream URL. Hold ONE instance per ingest and call [onLine] for every line, then [flushPending]
     * is a no-op (a trailing #EXTINF with no URL is simply dropped). State is a single pending
     * #EXTINF — memory is O(1) regardless of playlist size.
     */
    class StreamingParser(private val onEntry: (Entry) -> Unit) {
        private var pendingExtInf: String? = null

        /**
         * The playlist's EPG URL declared on the `#EXTM3U` header (`url-tvg` or `x-tvg-url`), captured
         * as the header streams past. Null until the header is seen (or if it carries no tvg url). The
         * ingest reads this AFTER the stream completes to persist the EPG source. Only the FIRST value
         * wins (some playlists repeat the header).
         */
        var epgUrl: String? = null
            private set

        fun onLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty()) return
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> captureHeaderEpgUrl(line)
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingExtInf = line
                line.startsWith("#") -> Unit // #EXTGRP, #KODIPROP, etc. — ignored
                else -> {
                    val ext = pendingExtInf
                    if (ext != null) {
                        parseEntry(ext, line)?.let(onEntry)
                        pendingExtInf = null
                    }
                    // A bare URL with no preceding #EXTINF is skipped (can't classify/name it well).
                }
            }
        }

        private fun captureHeaderEpgUrl(headerLine: String) {
            if (epgUrl != null) return
            // Read the header's tvg-url attribute DIRECTLY (not via parseAttributes, which truncates at
            // the first ',' — and a url-tvg value is often a comma-separated list). Providers use either
            // spelling; the first URL in a list wins.
            val raw = quotedHeaderAttr(headerLine, "url-tvg")
                ?: quotedHeaderAttr(headerLine, "x-tvg-url")
                ?: quotedHeaderAttr(headerLine, "tvg-url")
                ?: return
            epgUrl = raw.split(',').firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }
        }

        /** Reads `name="…"` from the #EXTM3U header, allowing commas inside the quoted value. */
        private fun quotedHeaderAttr(line: String, name: String): String? {
            val key = "$name="
            var from = 0
            while (true) {
                val idx = line.indexOf(key, from, ignoreCase = true)
                if (idx < 0) return null
                val before = if (idx == 0) ' ' else line[idx - 1]
                val q = idx + key.length
                if ((before == ' ' || before == '\t' || idx == 0) && q < line.length && line[q] == '"') {
                    val close = line.indexOf('"', q + 1)
                    if (close > q) return line.substring(q + 1, close)
                    return null
                }
                from = idx + key.length
            }
        }
    }

    /** Builds an [Entry] from one `#EXTINF` header line + its stream URL. Public for unit tests. */
    fun parseEntry(extInf: String, url: String): Entry? {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank() || cleanUrl.startsWith("#")) return null
        val attrs = parseAttributes(extInf)
        val displayName = extInf.substringAfter(',', "").trim()
            .ifBlank { attrs["tvg-name"] ?: cleanUrl.substringAfterLast('/') }
        val group = attrs["group-title"]?.ifBlank { null }
        val ext = extensionOf(cleanUrl)
        val kind = classify(cleanUrl, ext, group)
        return if (kind == M3UKind.SERIES) {
            val se = seasonEpisodeOf(displayName)
            Entry(
                kind = kind,
                name = displayName,
                url = cleanUrl,
                logo = attrs["tvg-logo"]?.ifBlank { null },
                tvgId = attrs["tvg-id"]?.ifBlank { null },
                group = group,
                ext = ext,
                seriesKey = seriesKeyOf(displayName, attrs["tvg-name"], group),
                season = se?.first,
                episode = se?.second,
            )
        } else {
            Entry(
                kind = kind,
                name = displayName,
                url = cleanUrl,
                logo = attrs["tvg-logo"]?.ifBlank { null },
                tvgId = attrs["tvg-id"]?.ifBlank { null },
                group = group,
                ext = ext,
            )
        }
    }

    /**
     * Classify by the Xtream-style URL path segment first (`/live/` `/movie/` `/series/`), which
     * providers set reliably, then fall back to the file extension. VOD containers (.mp4/.mkv/.avi/…)
     * with no path hint are movies; playlist/transport extensions (.ts/.m3u8) are live.
     */
    fun classify(url: String, ext: String?, group: String?): M3UKind {
        val lower = url.lowercase()
        when {
            "/series/" in lower -> return M3UKind.SERIES
            "/movie/" in lower -> return M3UKind.MOVIE
            "/live/" in lower -> return M3UKind.LIVE
        }
        return when (ext?.lowercase()) {
            "ts", "m3u8" -> M3UKind.LIVE
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "mpg", "mpeg", "webm" -> M3UKind.MOVIE
            else -> M3UKind.LIVE // no extension (bare live path, or a token URL) reads as a channel
        }
    }

    /** Extracts the `key="value"` attributes from an #EXTINF line (before the trailing `,name`). */
    fun parseAttributes(extInf: String): Map<String, String> {
        val head = extInf.substringBefore(',')
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < head.length) {
            val eq = head.indexOf('=', i)
            if (eq < 0) break
            // key = the run of attr-name chars immediately before '='
            var keyStart = eq - 1
            while (keyStart >= 0 && (head[keyStart].isLetterOrDigit() || head[keyStart] == '-' || head[keyStart] == '_')) keyStart--
            keyStart++
            val key = head.substring(keyStart, eq).trim()
            val afterEq = eq + 1
            if (afterEq >= head.length) break
            if (head[afterEq] == '"') {
                val close = head.indexOf('"', afterEq + 1)
                if (close < 0) break
                if (key.isNotEmpty()) out[key.lowercase()] = head.substring(afterEq + 1, close)
                i = close + 1
            } else {
                var end = afterEq
                while (end < head.length && !head[end].isWhitespace()) end++
                if (key.isNotEmpty()) out[key.lowercase()] = head.substring(afterEq, end)
                i = end
            }
        }
        return out
    }

    private fun extensionOf(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val lastSeg = path.substringAfterLast('/')
        val dot = lastSeg.lastIndexOf('.')
        if (dot <= 0 || dot == lastSeg.length - 1) return null
        val ext = lastSeg.substring(dot + 1)
        // Guard against a domain-only "url" or a very long tail that isn't really an extension.
        return ext.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
    }

    // "Show Name S01 E02" / "Show Name S01E02" / "Show Name 1x02" -> (1, 2)
    private val seasonEpisodeRegex = Regex("""[sS](\d{1,3})\s*[eExX]\s*(\d{1,4})""")
    private val altSeasonEpisodeRegex = Regex("""(?<![\dsS])(\d{1,2})[xX](\d{1,3})(?!\d)""")

    fun seasonEpisodeOf(name: String): Pair<Int, Int>? {
        seasonEpisodeRegex.find(name)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        altSeasonEpisodeRegex.find(name)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return null
    }

    /**
     * The stable key episodes of one show share, so the DB can GROUP BY it into a single series row.
     * Prefer the display name with any SxxExx / trailing quality tag stripped; fall back to tvg-name
     * or the group. Two episodes of "Breaking Bad S01E01" and "Breaking Bad S01E02" collapse to
     * "breaking bad".
     */
    fun seriesKeyOf(displayName: String, tvgName: String?, group: String?): String {
        val base = displayName
            .replace(seasonEpisodeRegex, " ")
            .replace(altSeasonEpisodeRegex, " ")
        val stripped = base
            .replace(Regex("""[\[(].*?[\])]"""), " ")            // (2021), [FHD]
            .replace(Regex("""\b(FHD|HD|SD|4K|UHD|HEVC|H265|H264)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('-', '·', '|', ':')
            .trim()
        val key = stripped.ifBlank { tvgName?.trim().orEmpty() }.ifBlank { group?.trim().orEmpty() }
        return key.lowercase().ifBlank { displayName.lowercase() }
    }
}

enum class M3UKind { LIVE, MOVIE, SERIES }
