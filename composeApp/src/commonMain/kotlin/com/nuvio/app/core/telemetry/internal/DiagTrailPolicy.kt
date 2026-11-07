package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What may enter the flight recorder and ride along on an error or report: an ALLOW-LIST of field names,
 * enum/number values only, with a URL scrub as a second guard. Anything else is dropped, never redacted-and-kept,
 * so a provider's host, a channel name or a free-text message cannot reach the wire through the trail.
 */
internal object DiagTrailPolicy {
    const val MAX_ENTRIES = 80
    const val MAX_BYTES = 12 * 1024
    private const val MAX_STRING = 48
    private const val MAX_FIELDS = 12

    /** Field names that carry only code, enum or numeric values. Add a name here only when its values are closed. */
    val ALLOWED_FIELDS: Set<String> = setOf(
        "engine", "phase", "outcome", "kind", "surface", "source", "lane", "reason", "state", "level", "screen",
        "from", "to", "bucket", "error_type", "error_class", "code", "container", "category",
        "attempt", "count", "total", "programmes", "channels", "channels_covered", "duration_ms", "position_ms",
        "java_mb", "java_max_mb", "native_mb", "pss_mb", "ram_free_mb", "ram_total_mb", "trim_level",
    )

    private val codeValue = Regex("""^[a-z0-9][a-z0-9_.:\-]{0,47}$""")
    private val urlLike = Regex("""(?i)([a-z][a-z0-9+.\-]*://|www\.|\b[a-z0-9\-]+(\.[a-z0-9\-]+)+\.[a-z]{2,}\b)""")

    /** Returns the entry with only allowed fields/values, or null when its own code is not a clean code. */
    fun sanitize(entry: DiagEntry): DiagEntry? {
        if (!isCode(entry.code)) return null
        val fields = LinkedHashMap<String, Any>()
        for ((k, v) in entry.fields) {
            if (fields.size >= MAX_FIELDS) break
            if (k !in ALLOWED_FIELDS) continue
            val clean = cleanValue(v) ?: continue
            fields[k] = clean
        }
        return entry.copy(fields = fields)
    }

    private fun isCode(s: String) = codeValue.matches(s) && !urlLike.containsMatchIn(s)

    private fun cleanValue(v: Any): Any? = when (v) {
        is Int, is Long, is Boolean -> v
        is Double -> if (v.isNaN() || v.isInfinite()) null else v
        is Float -> if (v.isNaN() || v.isInfinite()) null else v.toDouble()
        is Enum<*> -> v.name.lowercase().takeIf(::isCode)
        is String -> v.lowercase().takeIf { it.length <= MAX_STRING && isCode(it) }
        else -> null
    }

    private val urlRegex = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://[^\s"'<>]+""")
    private val dottedToken = Regex("""(?i)\b[a-z0-9_\-]+(?:\.[a-z0-9_\-]+)+(?::\d+)?\b""")
    private val packagePrefixes = listOf(
        "com.", "org.", "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "okhttp3.", "okio.",
        "io.", "dalvik.", "libcore.", "sun.", "jdk.", "platform.", "kfun:", "coil3.", "app.cash.",
    )

    /**
     * Free text (an exception message, a stack line) loses anything URL- or host-shaped before it is stored or
     * sent. Dotted class names survive (they start with a known package root or have a capitalised segment);
     * `example.com` and `10.0.0.5:8080` do not.
     */
    fun scrubText(text: String, maxLength: Int): String {
        val noUrls = urlRegex.replace(text, "[url]")
        val noHosts = dottedToken.replace(noUrls) { m ->
            val token = m.value
            val classLike = packagePrefixes.any { token.startsWith(it, ignoreCase = false) } ||
                token.split('.').any { it.firstOrNull()?.isUpperCase() == true } && !token.contains(':')
            if (classLike) token else "[host]"
        }
        return noHosts.take(maxLength)
    }

    /**
     * Serializes the newest entries that fit: at most [MAX_ENTRIES] and [MAX_BYTES]. Each entry is
     * `[ageMs, area, code, {fields}]`, newest last, so the line reads top to bottom as it happened.
     */
    fun serialize(entries: List<DiagEntry>, nowMs: Long, maxEntries: Int = MAX_ENTRIES, maxBytes: Int = MAX_BYTES): String {
        val picked = ArrayList<JsonElement>()
        var size = 2
        for (entry in entries.asReversed()) {
            if (picked.size >= maxEntries) break
            val clean = sanitize(entry) ?: continue
            val json = JsonArray(
                listOf(
                    JsonPrimitive((nowMs - clean.atMs).coerceAtLeast(0L)),
                    JsonPrimitive(clean.area.name.lowercase()),
                    JsonPrimitive(clean.code),
                    JsonObject(clean.fields.mapValues { toJson(it.value) }),
                ),
            )
            val cost = json.toString().length + 1
            if (size + cost > maxBytes) break
            size += cost
            picked.add(json)
        }
        return JsonArray(picked.asReversed()).toString()
    }

    private fun toJson(v: Any): JsonElement = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        else -> JsonNull
    }
}
