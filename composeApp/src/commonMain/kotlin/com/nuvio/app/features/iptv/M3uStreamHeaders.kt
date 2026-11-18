package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Per-channel request headers an M3U playlist declares, and the order they win in (pure).
 *
 * Playlists built for VLC / Kodi / TiviMate carry them three ways, all supported:
 *  - `#EXTINF` attributes: `http-user-agent` / `user-agent`, `http-referrer` / `http-referer` / `referer`;
 *  - `#EXTVLCOPT:http-user-agent=…` / `#EXTVLCOPT:http-referrer=…` lines (VLC; before or after the `#EXTINF`);
 *  - `#EXTHTTP:{"User-Agent":"…","Referer":"…"}` (TiviMate / Kodi), a JSON object.
 *
 * Only [ALLOWED] reach playback: User-Agent, Referer and Origin are what providers key their WAF and
 * hotlink checks on. A Cookie (or anything else) in a playlist is dropped — a shared playlist must
 * never carry a session into the request, and libmpv re-sends custom headers on every redirect hop.
 * Values with a line break (header injection) or longer than [MAX_VALUE_LENGTH] are dropped.
 */
object M3uStreamHeaders {

    const val USER_AGENT = "User-Agent"
    const val REFERER = "Referer"
    const val ORIGIN = "Origin"

    /** The canonical header names a playlist may set. */
    val ALLOWED = listOf(USER_AGENT, REFERER, ORIGIN)

    const val MAX_VALUE_LENGTH = 512

    private val ATTRIBUTE_NAMES = mapOf(
        "http-user-agent" to USER_AGENT,
        "user-agent" to USER_AGENT,
        "http-referrer" to REFERER,
        "http-referer" to REFERER,
        "referrer" to REFERER,
        "referer" to REFERER,
        "http-origin" to ORIGIN,
        "origin" to ORIGIN,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The canonical name for a header key from any source, or null when it is not allowed. */
    fun canonicalName(key: String): String? {
        val k = key.trim().lowercase()
        ALLOWED.firstOrNull { it.lowercase() == k }?.let { return it }
        return ATTRIBUTE_NAMES[k]
    }

    private fun cleanValue(value: String): String? {
        val v = value.trim().removeSurrounding("\"").trim()
        if (v.isEmpty() || v.length > MAX_VALUE_LENGTH) return null
        if (v.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        return v
    }

    /** The headers in an `#EXTINF` line's attributes (already parsed, lower-case keys). */
    fun fromAttributes(attrs: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in attrs) {
            val name = ATTRIBUTE_NAMES[key] ?: continue
            cleanValue(value)?.let { out[name] = it }
        }
        return out
    }

    /** One `#EXTVLCOPT:key=value` line; empty for an option that is not a header we allow. */
    fun fromVlcOpt(line: String): Map<String, String> {
        val body = line.substringAfter(':', "").trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return emptyMap()
        val name = ATTRIBUTE_NAMES[body.substring(0, eq).trim().lowercase()] ?: return emptyMap()
        val value = cleanValue(body.substring(eq + 1)) ?: return emptyMap()
        return mapOf(name to value)
    }

    /** One `#EXTHTTP:{json}` line; empty when the JSON does not parse or names no allowed header. */
    fun fromExtHttp(line: String): Map<String, String> {
        val body = line.substringAfter(':', "").trim()
        if (!body.startsWith("{")) return emptyMap()
        val obj: JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, element) in obj) {
            val name = ALLOWED.firstOrNull { it.equals(key.trim(), ignoreCase = true) } ?: continue
            val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            cleanValue(raw)?.let { out[name] = it }
        }
        return out
    }

    /**
     * The request headers for a channel's stream: [base] (the DNS rewrite's `Host`, or nothing), then the playlist's
     * own User-Agent ([playlistUserAgent], [StreamUserAgentPolicy]) replacing any UA there, then the [channel]'s own
     * headers replacing either. Channel > playlist > engine default. Header names compare case-insensitively.
     */
    fun merge(base: Map<String, String>, playlistUserAgent: String?, channel: Map<String, String>): Map<String, String> {
        var out: Map<String, String> = base
        playlistUserAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { ua ->
            out = out.filterKeys { !it.equals(USER_AGENT, ignoreCase = true) } + (USER_AGENT to ua)
        }
        for ((name, value) in channel) {
            out = out.filterKeys { !it.equals(name, ignoreCase = true) } + (name to value)
        }
        return out
    }

    /** Storage form for the content DB (a JSON object); null for none. */
    fun encode(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString()
    }

    /** The stored form back to headers, re-checked against [ALLOWED]; empty for null or anything unreadable. */
    fun decode(stored: String?): Map<String, String> {
        if (stored.isNullOrBlank()) return emptyMap()
        return fromExtHttp("#EXTHTTP:$stored")
    }
}
