package com.nuvio.app.features.sportsdetail.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Delta rule (repo CLAUDE.md "recurring network work"): the 15 s live tick must not re-pull the whole-day ESPN
 * scoreboard (230-290 KB). The scoreboard is read ONCE to resolve the ESPN event (kept as the base event); every
 * live tick asks only `summary?event=<id>`, whose `header.competitions[0]` carries score, linescores, status and
 * clock. This overlays those live fields onto the base event so the same mapping paths keep working.
 */
internal object EspnLiveOverlay {
    private val COMPETITOR_LIVE_KEYS = setOf("score", "linescores", "hits", "errors", "winner")

    fun apply(base: JsonElement, summary: JsonElement?): JsonElement {
        val b = base as? JsonObject ?: return base
        val live = SportsPath.first(summary, "header.competitions[0]") as? JsonObject ?: return base
        val comps = (b["competitions"] as? JsonArray) ?: return base
        val baseComp = comps.firstOrNull() as? JsonObject ?: return base
        val liveCompetitors = (live["competitors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val competitors = JsonArray((baseComp["competitors"] as? JsonArray).orEmpty().map { c ->
            val co = c as? JsonObject ?: return@map c
            val ha = (co["homeAway"] as? JsonPrimitive)?.content
            val lc = liveCompetitors.firstOrNull { (it["homeAway"] as? JsonPrimitive)?.content == ha } ?: return@map c
            JsonObject(co + lc.filterKeys { it in COMPETITOR_LIVE_KEYS })
        })
        val comp = baseComp.toMutableMap().apply {
            put("competitors", competitors)
            (summary as? JsonObject)?.get("situation")?.let { put("situation", it) }
        }
        val status = ((b["status"] as? JsonObject).orEmpty() + ((live["status"] as? JsonObject).orEmpty()))
        return JsonObject(b + mapOf("status" to JsonObject(status), "competitions" to JsonArray(listOf<JsonElement>(JsonObject(comp)) + comps.drop(1))))
    }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
