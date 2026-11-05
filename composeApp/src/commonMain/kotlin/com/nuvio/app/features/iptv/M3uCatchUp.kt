package com.nuvio.app.features.iptv

/**
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), `CatchupUrlBuilder` and the catch-up part
 * of `M3uParser`. Re-expressed for commonMain: no java.time / SimpleDateFormat (dates are
 * formatted by [EpochCivil]), no device-timezone lookup (local-time placeholders take an explicit
 * offset), and the Xtream style delegates to [XtreamCatchUp] instead of re-deriving its URL shapes.
 *
 * Catch-up URLs for M3U playlists. The playlist says how to ask for an archive: a style
 * (`catchup` / `catchup-type`), usually a URL template (`catchup-source`) and how many days the
 * provider keeps (`catchup-days`, `tvg-rec` or `timeshift`). None of that is in a database yet;
 * this file only parses it and builds URLs.
 */
enum class M3uCatchUpType {
    /** `catchup-source` is appended to the live URL. */
    APPEND,

    /** `catchup-source` is a full URL template, or a suffix when it is relative. */
    DEFAULT,

    /** `?utc=<start>&lutc=<now>` on the live URL (also `timeshift`). */
    SHIFT,

    /** Flussonic archive: `index-<start>-<duration>.m3u8` or `timeshift_abs-<start>.ts`. */
    FLUSSONIC,

    /** Xtream `/timeshift/` URLs, built by [XtreamCatchUp]. */
    XTREAM,
}

/** Catch-up as one playlist entry declares it. [days] is at least 1. */
data class M3uCatchUpInfo(
    val type: M3uCatchUpType,
    val source: String?,
    val days: Int,
)

object M3uCatchUp {

    /** Attribute names read from `#EXTM3U` (as playlist-wide defaults) and from each `#EXTINF`. */
    val ATTRIBUTE_KEYS: List<String> = listOf(
        "catchup", "catchup-type", "catchup-source", "catchup-days", "tvg-rec", "timeshift",
    )

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * The style named by [value] (`catchup` / `catchup-type`). Missing or blank means
     * [M3uCatchUpType.DEFAULT]. An unknown name is [M3uCatchUpType.DEFAULT] when the playlist gave a
     * [source] template, and null (nothing buildable) when it did not.
     */
    fun catchUpType(value: String?, source: String?): M3uCatchUpType? {
        val v = value?.trim()?.lowercase().orEmpty()
        return when (v) {
            "" -> M3uCatchUpType.DEFAULT
            "default" -> M3uCatchUpType.DEFAULT
            "append" -> M3uCatchUpType.APPEND
            "shift", "timeshift" -> M3uCatchUpType.SHIFT
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> M3uCatchUpType.FLUSSONIC
            "xc", "xtream" -> M3uCatchUpType.XTREAM
            else -> if (source.isNullOrBlank()) null else M3uCatchUpType.DEFAULT
        }
    }

    /**
     * Reads one entry's catch-up. Every attribute falls back to the playlist-wide [header] value,
     * so an entry can override just the type or just the days. Null when nothing says the channel
     * has catch-up: a type, days above zero, or a `catchup-source` switches it on (without a type
     * the `default` style is meant).
     */
    fun fromAttributes(attrs: Map<String, String>, header: Map<String, String> = emptyMap()): M3uCatchUpInfo? {
        // Entry attributes win over header ones, key by key; blank counts as absent.
        fun first(vararg keys: String): String? {
            for (m in listOf(attrs, header)) for (k in keys) {
                val v = m[k]?.trim()
                if (!v.isNullOrEmpty()) return v
            }
            return null
        }
        val typeRaw = first("catchup", "catchup-type")
        val days = first("catchup-days", "tvg-rec", "timeshift")?.toIntOrNull()
        val source = first("catchup-source")
        if (typeRaw == null && (days ?: 0) <= 0 && source == null) return null
        val type = catchUpType(typeRaw, source) ?: return null
        return M3uCatchUpInfo(type, source, (days ?: 1).coerceAtLeast(1))
    }

    /** Whether a programme that started at [programStartMs] is inside the channel's archive. */
    fun isAvailable(info: M3uCatchUpInfo?, programStartMs: Long, nowMs: Long): Boolean {
        if (info == null) return false
        if (programStartMs >= nowMs) return false
        return nowMs - programStartMs <= info.days.coerceAtLeast(1) * DAY_MS
    }

    /**
     * The archive URL for the programme [startMs]..[endMs] on the channel whose live URL is
     * [liveUrl], or null when the style cannot be built from what we have.
     *
     * @param offsetMs UTC offset of the clock the playlist means. The bare `{Y}{m}{d}{H}{M}{S}`
     *   placeholders are local time, and the Xtream start stamp is written in the panel's clock
     *   ([ServerClockOffset]); the caller supplies it (commonMain has no timezone database).
     */
    fun build(
        info: M3uCatchUpInfo,
        liveUrl: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        offsetMs: Long = 0L,
    ): String? {
        val start = startMs.floorDiv(1000L)
        val end = endMs.floorDiv(1000L)
        val now = nowMs.floorDiv(1000L)
        val duration = (end - start).coerceAtLeast(60L)
        // A link shaped like Xtream's, whose playlist does not say which catch-up it speaks, gets
        // Xtream's /timeshift/ form (what TiviMate assumes), else the generic shift query.
        fun guess(): String? = xtream(liveUrl, startMs, duration, offsetMs) ?: shift(liveUrl, start, now)
        val src = info.source
        return when (info.type) {
            M3uCatchUpType.APPEND -> src?.let { liveUrl + fill(it, start, end, now, duration, offsetMs) }
            M3uCatchUpType.DEFAULT -> when {
                src.isNullOrBlank() -> guess()
                src.startsWith("http", ignoreCase = true) -> fill(src, start, end, now, duration, offsetMs)
                else -> liveUrl + fill(src, start, end, now, duration, offsetMs)
            }
            M3uCatchUpType.SHIFT -> if (src.isNullOrBlank()) guess() else shift(liveUrl, start, now)
            M3uCatchUpType.FLUSSONIC -> flussonic(liveUrl, start, duration)
            M3uCatchUpType.XTREAM -> xtream(liveUrl, startMs, duration, offsetMs)
        }
    }

    private fun shift(url: String, start: Long, now: Long): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}utc=$start&lutc=$now"
    }

    private val FLUSSONIC_URL = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""")

    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val m = FLUSSONIC_URL.find(url) ?: return null
        val host = m.groupValues[1]
        val stream = m.groupValues[2]
        val file = m.groupValues[3]
        val query = m.groupValues[4]
        return if (file.endsWith(".m3u8") || file.isEmpty()) {
            val base = file.removeSuffix(".m3u8").ifEmpty { "index" }
            "$host/$stream/$base-$start-$duration.m3u8$query"
        } else {
            "$host/$stream/timeshift_abs-$start.ts$query"
        }
    }

    // http://host:port/(live/)?user/pass/id(.ext)
    private val XTREAM_LIVE = Regex(
        """^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun xtream(url: String, startMs: Long, durationSecs: Long, offsetMs: Long): String? {
        val m = XTREAM_LIVE.find(url) ?: return null
        val id = m.groupValues[4].toIntOrNull() ?: return null
        val ext = m.groupValues[5].removePrefix(".").ifEmpty { null }
        // XtreamCatchUp percent-encodes credentials itself; the live URL's are already encoded.
        return XtreamCatchUp.candidateUrls(
            baseUrl = m.groupValues[1],
            username = percentDecode(m.groupValues[2]),
            password = percentDecode(m.groupValues[3]),
            streamId = id,
            startMs = startMs,
            endMs = startMs + durationSecs * 1000L,
            containerExtension = ext,
            serverOffsetMs = offsetMs,
        ).firstOrNull()
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes.add(hex.toByte()); i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes.add(it) }; i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    // --- Xtream alternatives ----------------------------------------------------------------

    private val TIMESHIFT_URL = Regex(
        """^(https?://[^/]+)/timeshift/([^/]+)/([^/]+)/(\d+)/([0-9-]+:[0-9-]+)/(\d+)(\.[a-z0-9]+)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMESHIFT_PATH = Regex("""/timeshift/[^/]+/[^/]+/(\d+)/([0-9-]+:[0-9-]+)/\d+""", RegexOption.IGNORE_CASE)
    private val STAMP = Regex("""^(\d{4})-(\d{2})-(\d{2}):(\d{2})-(\d{2})$""")

    /**
     * Other ways to ask for the same Xtream catch-up, tried in turn if [url] fails, because panels
     * differ: TS instead of HLS (or the reverse), a window cut to what has aired or to the show's
     * own length, the older `timeshift.php` form, and the start written in the other clock.
     *
     * @param showMinutes the show's own length: some panels refuse a longer window
     * @param airedMinutes a show still on air: how much of it has aired (some panels refuse a
     *   window reaching into the future)
     * @param offsetMs the offset [url]'s start stamp was written with (the panel's clock, 0 for UTC)
     * @param otherOffsetMs the other clock to try, or null for no extra link. By default: UTC when
     *   the stamp is in a panel clock, nothing when it is already UTC.
     */
    fun alternatives(
        url: String,
        offsetMs: Long = 0L,
        otherOffsetMs: Long? = if (offsetMs != 0L) 0L else null,
        showMinutes: Long? = null,
        airedMinutes: Long? = null,
    ): List<String> {
        val path = url.substringBefore('?')
        val query = url.substring(path.length)
        val m = TIMESHIFT_URL.find(path) ?: return listOfNotNull(tsFallback(url))
        val (host, user, pass, minutes, stamp, id) = m.destructured
        val ext = m.groupValues[7]
        val flip = if (ext.equals(".m3u8", true)) ".ts" else ".m3u8"
        val same = ext.ifEmpty { ".ts" }
        val total = minutes.toLongOrNull() ?: 0L
        val out = LinkedHashSet<String>()
        // TS answered with something that is not video: many panels only replay as HLS (and back).
        out += "$host/timeshift/$user/$pass/$minutes/$stamp/$id$flip$query"
        // A show still on air: only what has aired so far. Tried before the other-clock link, so a
        // refusal does not end on a link that plays an earlier show.
        airedMinutes?.coerceAtLeast(1)?.takeIf { it < total }?.let { a ->
            out += "$host/timeshift/$user/$pass/$a/$stamp/$id$same$query"
            out += "$host/timeshift/$user/$pass/$a/$stamp/$id$flip$query"
        }
        // Just the show, without the extra time after it.
        showMinutes?.coerceAtLeast(1)?.takeIf { it < total }?.let { s ->
            out += "$host/timeshift/$user/$pass/$s/$stamp/$id$same$query"
            out += "$host/timeshift/$user/$pass/$s/$stamp/$id$flip$query"
        }
        val stampMs = STAMP.find(stamp)?.destructured?.let { (y, mo, d, h, mi) ->
            EpochCivil.toEpochMs(y.toLong(), mo.toLong(), d.toLong(), h.toLong(), mi.toLong()) - offsetMs
        }
        // The timeshift.php form comes from XtreamCatchUp, so its shape lives in one place.
        val php = stampMs?.let {
            XtreamCatchUp.candidateUrls(
                baseUrl = host, username = percentDecode(user), password = percentDecode(pass),
                streamId = id.toIntOrNull() ?: return@let null,
                startMs = it, endMs = it + total * 60_000L, containerExtension = null, serverOffsetMs = offsetMs,
            ).firstOrNull { c -> "/streaming/timeshift.php?" in c && "extension=" !in c }
        } ?: "$host/streaming/timeshift.php?username=$user&password=$pass&stream=$id&start=$stamp&duration=$minutes"
        out += php
        if (otherOffsetMs != null && stampMs != null) {
            val other = XtreamCatchUp.formatStart(stampMs, otherOffsetMs)
            if (other != stamp) out += "$host/timeshift/$user/$pass/$minutes/$other/$id$same$query"
        }
        out.remove(url)
        return out.toList()
    }

    /** The TS version of an Xtream HLS catch-up link, for panels that do not offer HLS. */
    fun tsFallback(url: String): String? {
        val path = url.substringBefore('?')
        if (!path.contains("/timeshift/") || !path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(".m3u8".length) + ".ts" + url.substring(path.length)
    }

    /**
     * Xtream replay links name a whole minute (9:10, never 9:10:45): the replay really starts at
     * the start of that minute.
     */
    fun wholeMinutes(url: String): Boolean {
        val path = url.substringBefore('?')
        return TIMESHIFT_PATH.containsMatchIn(path) || path.endsWith("timeshift.php", ignoreCase = true)
    }

    /**
     * Which kind of link [url] is, next to the first link tried ([primary]): `php`, or `hls` / `ts`,
     * plus `+clock` when the time is written in the other clock and `+short` for a shorter window.
     * Remembered per provider so the next replay goes straight to the kind that worked.
     */
    fun linkForm(url: String, primary: String): String {
        val path = url.substringBefore('?')
        if (path.endsWith("timeshift.php", ignoreCase = true)) return "php"
        val ext = if (path.endsWith(".m3u8", ignoreCase = true)) "hls" else "ts"
        val m = TIMESHIFT_PATH.find(path)
        val pm = TIMESHIFT_PATH.find(primary.substringBefore('?'))
        val clock = if (m != null && pm != null && m.groupValues[2] != pm.groupValues[2]) "+clock" else ""
        val short = if (m != null && pm != null &&
            (m.groupValues[1].toLongOrNull() ?: 0L) < (pm.groupValues[1].toLongOrNull() ?: 0L)
        ) "+short" else ""
        return ext + clock + short
    }

    /** [primary] and its [alternatives] in the order to try them: the kind that worked last time ([form]) first. */
    fun preferForm(primary: String, alternatives: List<String>, form: String?): List<String> {
        val all = listOf(primary) + alternatives
        if (form == null) return all
        // HLS was switched off for this provider (it failed there): do not go back to it.
        if (form.startsWith("hls") && !primary.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return all
        val pick = all.firstOrNull { linkForm(it, primary) == form }
            ?: all.firstOrNull { linkForm(it, primary) == form.replace("+short", "") }
            ?: return all
        return listOf(pick) + all.filter { it != pick }
    }

    // --- Template placeholders --------------------------------------------------------------

    private val FORMATTED = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)\}""")
    private val DIVISOR = Regex("""\$?\{(duration|offset):(\d+)\}""")
    private val SIMPLE = Regex("""\$?\{(utc|start|timestamp|utcend|end|lutc|now|duration|offset)\}""")
    private val LOCAL = Regex("""\$?\{([YmdHMS])\}""")

    /**
     * Fills a `catchup-source` template. All times are epoch seconds. `{utc}` `{start}`: programme
     * start; `{utcend}` `{end}`: its end; `{lutc}` `{now}` `{timestamp}`: now; `{duration}` seconds;
     * `{offset}` seconds from start to now; `{duration:N}` `{offset:N}` divided by N;
     * `{utc:fmt}` etc. a date in UTC where Y m d H M S stand for year, month, day, hour, minute and
     * second; the bare `{Y}{m}{d}{H}{M}{S}` are the programme start in local time ([offsetMs]).
     * `${...}` is accepted everywhere `{...}` is.
     */
    internal fun fill(template: String, start: Long, end: Long, now: Long, duration: Long, offsetMs: Long = 0L): String {
        var out = FORMATTED.replace(template) { m ->
            val epoch = when (m.groupValues[1]) {
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                else -> start
            }
            formatPattern(m.groupValues[2], epoch, 0L)
        }
        out = DIVISOR.replace(out) { m ->
            val divisor = (m.groupValues[2].toLongOrNull() ?: 1L).coerceAtLeast(1L)
            val base = if (m.groupValues[1] == "duration") duration else now - start
            (base / divisor).toString()
        }
        out = SIMPLE.replace(out) { m ->
            when (m.groupValues[1]) {
                "utc", "start" -> start
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                "duration" -> duration
                else -> now - start
            }.toString()
        }
        out = LOCAL.replace(out) { m -> formatPattern(m.groupValues[1], start, offsetMs) }
        return out
    }

    private fun formatPattern(pattern: String, epochSec: Long, offsetMs: Long): String {
        val c = EpochCivil.fromEpochMs(epochSec * 1000L, offsetMs)
        return buildString {
            pattern.forEach { ch ->
                when (ch) {
                    'Y' -> append(EpochCivil.pad(c.year, 4))
                    'm' -> append(EpochCivil.pad(c.month))
                    'd' -> append(EpochCivil.pad(c.day))
                    'H' -> append(EpochCivil.pad(c.hour))
                    'M' -> append(EpochCivil.pad(c.minute))
                    'S' -> append(EpochCivil.pad(c.second))
                    else -> append(ch)
                }
            }
        }
    }
}
