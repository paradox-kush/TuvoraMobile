package com.nuvio.app.features.iptv.epg

/**
 * A pure-Kotlin, streaming XMLTV tokenizer + parser. Kept free of IO (and of any platform XML
 * library) so it (a) runs in commonTest against inline fixtures with no android.util.Xml / NSXMLParser
 * mock, and (b) parses a 50-100 MB `.xml`/`.xml.gz` guide with O(one element) memory — the whole
 * document NEVER materializes as a String.
 *
 * Only the two elements the guide needs are recognised:
 *   <channel id="cnn.us"> … </channel>
 *   <programme start="20260702183000 +0000" stop="20260702190000 +0000" channel="cnn.us">
 *       <title>News</title><desc>…</desc>
 *   </programme>
 *
 * Feed the document one chunk at a time (a line, or any substring — chunk boundaries can fall
 * anywhere, even mid-tag) via [feed]; the tokenizer buffers only the current partial token. A
 * completed <programme> whose channel id is in the caller-supplied allow-set is emitted to
 * [onProgramme]; a <channel> is emitted to [onChannel]. Everything else is skipped cheaply.
 */
class XmltvStreamingParser(
    /** Channel ids to keep. null = keep all (used to first harvest the <channel> id set). */
    private val keepChannelIds: Set<String>?,
    private val onChannel: (id: String, displayName: String?) -> Unit = { _, _ -> },
    private val onProgramme: (XmltvProgramme) -> Unit,
) {
    // The tokenizer buffers raw text between tag boundaries; capped so a pathological doc with no
    // '<' can't grow unbounded (a real guide breaks into tags constantly, so this never trips).
    private val buf = StringBuilder()
    private var inTag = false

    // Current-element state. Only ONE programme/channel is ever "open" at a time (XMLTV is flat).
    private var curChannelId: String? = null            // set inside <channel>
    private var curChannelName: String? = null
    private var prog: ProgBuilder? = null               // set inside <programme>
    private var textTarget: TextTarget = TextTarget.NONE // where character data is currently going

    private class ProgBuilder(val start: String?, val stop: String?, val channel: String?) {
        var title: String? = null
        var desc: String? = null
    }

    private enum class TextTarget { NONE, TITLE, DESC, DISPLAY_NAME }

    /** Feed the next chunk of the document. Safe to call with arbitrary substrings. */
    fun feed(chunk: CharSequence) {
        var i = 0
        val n = chunk.length
        while (i < n) {
            val c = chunk[i]
            if (inTag) {
                if (c == '>') {
                    handleTag(buf.toString())
                    buf.setLength(0)
                    inTag = false
                } else {
                    buf.append(c)
                    if (buf.length > MAX_TOKEN) buf.setLength(0) // runaway guard (never hit in practice)
                }
            } else {
                if (c == '<') {
                    if (buf.isNotEmpty()) {
                        emitText(buf.toString())
                        buf.setLength(0)
                    }
                    inTag = true
                } else {
                    // Only accumulate text when something wants it — keeps the common "skip" path allocation-free.
                    if (textTarget != TextTarget.NONE) {
                        buf.append(c)
                        if (buf.length > MAX_TEXT) buf.setLength(MAX_TEXT) // clamp absurd descriptions
                    }
                }
            }
            i++
        }
    }

    /** Call after the last chunk. A trailing partial token (truncated stream) is simply dropped. */
    fun finish() {
        buf.setLength(0)
        inTag = false
    }

    private fun emitText(raw: String) {
        val text = decodeEntities(raw).trim()
        when (textTarget) {
            TextTarget.TITLE -> prog?.let { if (it.title == null) it.title = text.ifBlank { null } }
            TextTarget.DESC -> prog?.let { if (it.desc == null) it.desc = text.ifBlank { null } }
            TextTarget.DISPLAY_NAME -> if (curChannelName == null) curChannelName = text.ifBlank { null }
            TextTarget.NONE -> Unit
        }
    }

    private fun handleTag(tagBody: String) {
        val body = tagBody.trim()
        if (body.isEmpty()) return
        when {
            body.startsWith("?") || body.startsWith("!") -> return // <?xml?>, <!-- -->, <!DOCTYPE>
            body.startsWith("/") -> handleCloseTag(body.substring(1).trim().substringBefore(' '))
            else -> handleOpenTag(body)
        }
    }

    private fun handleOpenTag(body: String) {
        val selfClosing = body.endsWith("/")
        val inner = if (selfClosing) body.dropLast(1).trim() else body
        val name = inner.substringBefore(' ').substringBefore('\t').lowercase()
        when (name) {
            "channel" -> {
                curChannelId = attr(inner, "id")
                curChannelName = null
                if (selfClosing) closeChannel()
            }
            "programme" -> {
                prog = ProgBuilder(
                    start = attr(inner, "start"),
                    stop = attr(inner, "stop"),
                    channel = attr(inner, "channel"),
                )
                if (selfClosing) closeProgramme()
            }
            "title" -> if (prog != null && !selfClosing) textTarget = TextTarget.TITLE
            "desc" -> if (prog != null && !selfClosing) textTarget = TextTarget.DESC
            "display-name" -> if (curChannelId != null && prog == null && !selfClosing) textTarget = TextTarget.DISPLAY_NAME
        }
    }

    private fun handleCloseTag(name: String) {
        when (name.lowercase()) {
            "title", "desc", "display-name" -> textTarget = TextTarget.NONE
            "channel" -> closeChannel()
            "programme" -> closeProgramme()
        }
    }

    private fun closeChannel() {
        val id = curChannelId?.trim()?.ifBlank { null }
        if (id != null) onChannel(id, curChannelName)
        curChannelId = null
        curChannelName = null
        textTarget = TextTarget.NONE
    }

    private fun closeProgramme() {
        val p = prog
        prog = null
        textTarget = TextTarget.NONE
        if (p == null) return
        val channel = p.channel?.trim()?.ifBlank { null } ?: return
        if (keepChannelIds != null && normalizeChannelId(channel) !in keepChannelIds) return
        val startMs = parseXmltvTime(p.start) ?: return
        val stopMs = parseXmltvTime(p.stop) ?: (startMs + DEFAULT_PROGRAMME_MS)
        onProgramme(
            XmltvProgramme(
                channelId = channel,
                startMs = startMs,
                endMs = stopMs,
                title = p.title ?: "",
                desc = p.desc,
            )
        )
    }

    private companion object {
        const val MAX_TOKEN = 64 * 1024      // a tag longer than this isn't a real XMLTV tag
        const val MAX_TEXT = 8 * 1024        // clamp a single title/desc's characters
        const val DEFAULT_PROGRAMME_MS = 60L * 60 * 1000  // 1h fallback when stop is missing
    }
}

/** One parsed programme (already channel-filtered + time-resolved). */
data class XmltvProgramme(
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val desc: String?,
)

/**
 * Parses an XMLTV timestamp `YYYYMMDDHHMMSS ±HHMM` (offset optional; seconds optional) to a UTC
 * epoch-ms. When no offset is present the time is ASSUMED to be UTC (the XMLTV spec's own default).
 * Returns null for anything not at least a YYYYMMDDHHMM.
 */
fun parseXmltvTime(raw: String?): Long? {
    val s = raw?.trim() ?: return null
    if (s.length < 12) return null
    // Split the digits (local wall-clock) from an optional trailing "±HHMM" / "±HH:MM" offset.
    val digitsEnd = s.indexOfFirst { it == ' ' || it == '+' || it == '-' || it == 'Z' }
        .let { if (it < 0) s.length else it }
    val digits = s.substring(0, digitsEnd)
    if (digits.length < 12 || !digits.all { it.isDigit() }) return null

    val year = digits.substring(0, 4).toInt()
    val month = digits.substring(4, 6).toInt()
    val day = digits.substring(6, 8).toInt()
    val hour = digits.substring(8, 10).toInt()
    val minute = digits.substring(10, 12).toInt()
    val second = if (digits.length >= 14) digits.substring(12, 14).toIntOrNull() ?: 0 else 0
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

    val baseUtc = civilToEpochMs(year, month, day, hour, minute, second)

    // Offset: everything after the digits. "Z" or absent -> UTC (offset 0).
    val offsetPart = s.substring(digitsEnd).trim()
    val offsetMs = parseOffsetMs(offsetPart) ?: 0L
    // A local time that is +HHMM ahead of UTC corresponds to an EARLIER UTC instant.
    return baseUtc - offsetMs
}

private fun parseOffsetMs(offset: String): Long? {
    if (offset.isEmpty() || offset.equals("Z", ignoreCase = true)) return 0L
    val sign = when (offset[0]) {
        '+' -> 1
        '-' -> -1
        else -> return 0L // unrecognised trailing text -> treat as UTC
    }
    val body = offset.substring(1).replace(":", "").trim()
    if (body.length < 4 || !body.take(4).all { it.isDigit() }) return 0L
    val oh = body.substring(0, 2).toInt()
    val om = body.substring(2, 4).toInt()
    return sign * (oh * 3600L + om * 60L) * 1000L
}

/** Days-from-civil (Howard Hinnant) -> epoch-ms at UTC. Matches the algorithm used elsewhere in the app. */
private fun civilToEpochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val doy = ((153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1).toLong()
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468L
    return ((days * 86400L) + hour * 3600L + minute * 60L + second) * 1000L
}

/**
 * Normalizes a channel id (M3U tvg-id or XMLTV channel id) into a match key: lowercase, trimmed,
 * inner whitespace removed. Providers are sloppy ("CNN.us" vs "cnn.us", "Sky Sports HD" vs
 * "skysportshd") so both sides fold through this before comparison.
 */
fun normalizeChannelId(id: String): String {
    val sb = StringBuilder(id.length)
    for (c in id.trim()) if (!c.isWhitespace()) sb.append(c.lowercaseChar())
    return sb.toString()
}

/** Minimal XML entity decode for the five predefined entities + numeric refs. */
internal fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') {
            val semi = s.indexOf(';', i + 1)
            if (semi in (i + 1)..(i + 10)) {
                val entity = s.substring(i + 1, semi)
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let { cp -> codePointToString(cp) }
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let { cp -> codePointToString(cp) }
                    else -> null
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun codePointToString(cp: Int): String? = when {
    cp in 0..0xFFFF -> cp.toChar().toString()
    cp in 0x10000..0x10FFFF -> {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
    else -> null
}

/** Extracts a double-or-single-quoted attribute value from an open-tag body. */
internal fun attr(tagBody: String, name: String): String? {
    var searchFrom = 0
    while (true) {
        val idx = tagBody.indexOf(name, searchFrom, ignoreCase = true)
        if (idx < 0) return null
        // Ensure it's a standalone attribute name (preceded by whitespace/start, followed by '=').
        val before = if (idx == 0) ' ' else tagBody[idx - 1]
        var after = idx + name.length
        while (after < tagBody.length && tagBody[after] == ' ') after++
        if ((before == ' ' || before == '\t' || idx == 0) && after < tagBody.length && tagBody[after] == '=') {
            var q = after + 1
            while (q < tagBody.length && tagBody[q] == ' ') q++
            if (q < tagBody.length && (tagBody[q] == '"' || tagBody[q] == '\'')) {
                val quote = tagBody[q]
                val close = tagBody.indexOf(quote, q + 1)
                if (close > q) return decodeEntities(tagBody.substring(q + 1, close))
            }
            return null
        }
        searchFrom = idx + name.length
    }
}
