package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.BundledSportsMapping
import com.nuvio.app.features.sportsdetail.internal.SportsMapping
import com.nuvio.app.features.sportsdetail.internal.SportsMappingParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

internal fun bundledMapping(): SportsMapping =
    (SportsMappingParser.parse(BundledSportsMapping.JSON) as SportsMappingParser.ParseResult.Ok).mapping

/** The bundled default with [from] replaced by [to] in its JSON text — models a remote mapping edit. */
internal fun editedMappingJson(from: String, to: String): String {
    require(BundledSportsMapping.JSON.contains(from)) { "bundled mapping has no '$from'" }
    return BundledSportsMapping.JSON.replace(from, to)
}

internal fun mappingOf(json: String): SportsMapping =
    (SportsMappingParser.parse(json) as SportsMappingParser.ParseResult.Ok).mapping
