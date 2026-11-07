package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.BundledSportsMapping
import com.nuvio.app.features.sportsdetail.internal.SportsMapping
import com.nuvio.app.features.sportsdetail.internal.SportsMappingParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

internal fun bundledMapping(): SportsMapping =
    (SportsMappingParser.parse(BundledSportsMapping.JSON) as SportsMappingParser.ParseResult.Ok).mapping

/** JSON text with all whitespace outside string literals removed, so test edits do not depend on how the mapping is formatted. */
internal fun squeezeJson(text: String): String {
    val out = StringBuilder(text.length)
    var inString = false
    var escaped = false
    for (ch in text) {
        if (inString) {
            out.append(ch)
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
        } else if (ch == '"') { inString = true; out.append(ch) }
        else if (!ch.isWhitespace()) out.append(ch)
    }
    return out.toString()
}

/** The bundled default with [from] replaced by [to] (whitespace-insensitive) — models a remote mapping edit. */
internal fun editedMappingJson(from: String, to: String): String {
    val bundled = squeezeJson(BundledSportsMapping.JSON)
    val f = squeezeJson(from)
    require(bundled.contains(f)) { "bundled mapping has no '$from'" }
    return bundled.replace(f, squeezeJson(to))
}

internal fun mappingOf(json: String): SportsMapping =
    (SportsMappingParser.parse(json) as SportsMappingParser.ParseResult.Ok).mapping
