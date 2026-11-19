package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The portal answered, but not with a category list (an object, a string, a number). */
internal class StalkerMalformedReplyException(message: String) : IllegalStateException(message)

/** Parses a Stalker `get_genres` / `get_categories` reply (pure: no portal, no session). */
internal object StalkerCategoryReply {
    fun parse(js: JsonElement): List<XtreamCategory> {
        // A portal that answered with anything but an array did not tell us "no categories": it gave us
        // something we cannot read. Say so, so the hub shows Failed + Retry instead of "Nothing here yet".
        val arr = js as? JsonArray ?: throw StalkerMalformedReplyException("Stalker category reply was not a JSON array")
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (id == "*") return@mapNotNull null   // "*" = All; the hub adds its own "All"
            XtreamCategory(id, (obj["title"] as? JsonPrimitive)?.contentOrNull ?: (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }
}
