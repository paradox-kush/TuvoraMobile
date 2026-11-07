package com.nuvio.app.features.sportsdetail.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Faithful port of `read` in research/sports-mapping/validate.py — the reference path reader the mapping file is
 * written against. Syntax: `a.b[0].c[*].d`, filter `[key=value]` (case-insensitive on the stringified value),
 * `[-1]` from the end (Python bounds: -len <= i < len). A missing step yields nothing; NEVER throws.
 *
 * Python semantics kept on purpose: a key step skips non-objects and null values; `[*]` and `[n]` apply to
 * arrays only; characters the token regex does not match are skipped (so a malformed path degrades to whatever
 * its valid tokens select, exactly like `finditer`).
 */
internal object SportsPath {
    private val TOKEN = Regex("""\.?([A-Za-z0-9_]+)|\[(\*)\]|\[(-?\d+)\]|\[([A-Za-z0-9_]+)=([^\]]+)\]""")

    fun read(root: JsonElement?, path: String): List<JsonElement> {
        if (root == null) return emptyList()
        return try {
            var cur: List<JsonElement> = listOf(root)
            for (m in TOKEN.findAll(path)) {
                val key = m.groups[1]?.value
                val star = m.groups[2]?.value
                val idx = m.groups[3]?.value
                val fk = m.groups[4]?.value
                val fv = m.groups[5]?.value
                val next = ArrayList<JsonElement>()
                for (c in cur) {
                    when {
                        key != null -> if (c is JsonObject) c[key]?.takeIf { it !is JsonNull }?.let(next::add)
                        star != null -> if (c is JsonArray) next.addAll(c)
                        idx != null -> if (c is JsonArray) {
                            val i = idx.toIntOrNull()
                            if (i != null && i >= -c.size && i < c.size) next.add(c[if (i < 0) c.size + i else i])
                        }
                        fk != null && fv != null -> if (c is JsonArray) {
                            for (x in c) if (x is JsonObject && pyStr(x[fk]).equals(fv, ignoreCase = true)) next.add(x)
                        }
                    }
                }
                cur = next
            }
            cur
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun first(root: JsonElement?, path: String?): JsonElement? =
        if (path.isNullOrBlank()) null else read(root, path).firstOrNull()

    /** First match as a display string; null when missing or when the match is a container, not a value. */
    fun text(root: JsonElement?, path: String?): String? = first(root, path)?.let(::display)

    fun texts(root: JsonElement?, path: String?): List<String> =
        if (path.isNullOrBlank()) emptyList() else read(root, path).mapNotNull(::display)

    /** A display string for a primitive: whole numbers without ".0", booleans as true/false. Containers -> null. */
    fun display(e: JsonElement?): String? {
        if (e !is JsonPrimitive || e is JsonNull) return null
        if (e.isString) return e.content
        val c = e.content
        val d = c.toDoubleOrNull()
        return if (d != null && c.contains('.') && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else c
    }

    /** Python `str(x.get(k))` for the filter comparison: missing/null -> "None", booleans -> "True"/"False". */
    private fun pyStr(e: JsonElement?): String = when {
        e == null || e is JsonNull -> "None"
        e is JsonPrimitive && !e.isString && (e.content == "true" || e.content == "false") -> e.content.replaceFirstChar { it.uppercase() }
        e is JsonPrimitive -> display(e) ?: e.content
        else -> e.toString()
    }
}
