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
 *
 * Game-screen redesign: the 15 s tick is now the single-event scoreboard ([mergeEvent]) and the summary only rides the
 * 60 s tick, so the summary can be OLDER than the event. [apply] then keeps the event's own live keys and status
 * (`baseIsFresher`) and takes from the summary only a `situation` the event does not have.
 */
internal object EspnLiveOverlay {
    private val COMPETITOR_LIVE_KEYS = setOf("score", "linescores", "hits", "errors", "winner")

    fun apply(base: JsonElement, summary: JsonElement?, baseIsFresher: Boolean = false): JsonElement {
        val b = base as? JsonObject ?: return base
        if (baseIsFresher) return withSituationOnly(b, summary)
        val live = SportsPath.first(summary, "header.competitions[0]") as? JsonObject ?: return base
        // A summary fetched later can still be an OLDER cached copy (CDN max-age differs per endpoint): within a game a score never
        // goes down, so a summary behind the event is treated as the older of the two.
        if (summaryBehind(b, live)) return withSituationOnly(b, summary)
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

    private fun scoresOf(comp: JsonObject?): Map<String, Int> =
        (comp?.get("competitors") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { c ->
            val ha = (c["homeAway"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val sc = (c["score"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: return@mapNotNull null
            ha to sc
        }.toMap()

    private fun summaryBehind(base: JsonObject, live: JsonObject): Boolean {
        val bs = scoresOf((base["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject)
        val ls = scoresOf(live)
        return ls.any { (ha, v) -> (bs[ha] ?: return@any false) > v }
    }

    private fun withSituationOnly(b: JsonObject, summary: JsonElement?): JsonElement {
        val comps = (b["competitions"] as? JsonArray) ?: return b
        val baseComp = comps.firstOrNull() as? JsonObject ?: return b
        if (baseComp["situation"] != null) return b
        val sit = (summary as? JsonObject)?.get("situation") ?: return b
        return JsonObject(b + ("competitions" to JsonArray(listOf<JsonElement>(JsonObject(baseComp + ("situation" to sit))) + comps.drop(1))))
    }

    /**
     * Folds a single-event scoreboard answer into the cached base event. The fresh answer wins key by key, but a key it omits
     * keeps its last value (a thin answer must not blank the team names or leaders) -- except `situation`, which is the live
     * moment and is taken ONLY from the fresh answer (a stale down-and-distance must not outlive the play).
     */
    fun mergeEvent(old: JsonElement, fresh: JsonElement): JsonElement {
        val o = old as? JsonObject ?: return fresh
        val f = fresh as? JsonObject ?: return old
        val oc = (o["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject
        val fc = (f["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject
        val comp: JsonObject? = when {
            oc != null && fc != null -> {
                val fresher = (fc["competitors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val competitors = (oc["competitors"] as? JsonArray)?.map { c ->
                    val co = c as? JsonObject ?: return@map c
                    val ha = (co["homeAway"] as? JsonPrimitive)?.content
                    val fcmp = fresher.firstOrNull { (it["homeAway"] as? JsonPrimitive)?.content == ha }
                    if (fcmp == null) co else JsonObject(co + fcmp)
                } ?: fresher
                JsonObject((oc - "situation") + fc + ("competitors" to JsonArray(competitors)))
            }
            else -> fc ?: oc
        }
        val merged = o + f + ("status" to JsonObject(o["status"].asObj() + f["status"].asObj()))
        return if (comp == null) JsonObject(merged) else JsonObject(merged + ("competitions" to JsonArray(listOf<JsonElement>(comp))))
    }

    private fun JsonElement?.asObj(): Map<String, JsonElement> = (this as? JsonObject) ?: emptyMap()
    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
