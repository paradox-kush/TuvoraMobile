package com.nuvio.app.features.sportsdetail.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * JSON form of [GameDetail]: the contract shape AND what the backend `game-detail` function returns. Forward
 * compatible: unknown fields are ignored, unknown block types are dropped, an unknown state becomes UPCOMING.
 * Never throws.
 *
 * Backend dialect accepted on top of our own: header discriminator `kind` (ours is `type`); broadcast as
 * `regions:[{country,channels}]`; lineup sides keyed by `team` (home first); numeric `round`.
 */
object GameDetailCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        classDiscriminator = "type"
        serializersModule = SerializersModule {
            polymorphic(GameBlock::class) { defaultDeserializer { UnknownBlock.serializer() } }
        }
    }

    fun decode(text: String): GameDetail? = runCatching { decodeElement(json.parseToJsonElement(text)) }.getOrNull()

    fun decodeElement(element: JsonElement): GameDetail? = runCatching {
        val obj = element as? JsonObject ?: return null
        val d = json.decodeFromJsonElement(GameDetail.serializer(), normalizeWire(obj))
        d.copy(blocks = d.blocks.filter { it !is UnknownBlock })
    }.getOrNull()

    fun encode(detail: GameDetail): String = json.encodeToString(GameDetail.serializer(), detail)

    private fun str(e: JsonElement?): JsonElement? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { JsonPrimitive(it.content) }

    private fun normalizeWire(o: JsonObject): JsonObject {
        val m = o.toMutableMap()
        (m["header"] as? JsonObject)?.let { h ->
            val kind = (h["kind"] as? JsonPrimitive)?.content
            if (kind != null && "type" !in h) m["header"] = JsonObject(h + ("type" to JsonPrimitive(kind)))
        }
        (m["asOf"] as? JsonObject)?.let { a ->
            m["asOf"] = JsonObject(a.mapNotNull { (k, v) ->
                val p = v as? JsonPrimitive ?: return@mapNotNull null
                val ms = if (p.isString) com.nuvio.app.features.sportsdetail.internal.Iso.parseMs(p.content) else p.content.toDoubleOrNull()?.toLong()
                ms?.let { k to JsonPrimitive(it) }
            }.toMap())
        }
        listOf("round", "venue").forEach { k -> m[k]?.let { v -> str(v)?.let { m[k] = it } } }
        (m["blocks"] as? JsonArray)?.let { arr -> m["blocks"] = JsonArray(arr.map { b -> (b as? JsonObject)?.let(::normalizeBlock) ?: b }) }
        return JsonObject(m)
    }

    private fun normalizeBlock(b: JsonObject): JsonObject {
        val type = (b["type"] as? JsonPrimitive)?.content
        val m = b.toMutableMap()
        if (type == "broadcast" && "byRegion" !in b && b["regions"] is JsonArray) {
            val by = LinkedHashMap<String, JsonElement>()
            (b["regions"] as JsonArray).forEach { r ->
                val ro = r as? JsonObject ?: return@forEach
                val country = (ro["country"] as? JsonPrimitive)?.content ?: return@forEach
                by[country] = ro["channels"] as? JsonArray ?: JsonArray(emptyList())
            }
            m["byRegion"] = JsonObject(by)
        }
        if (type == "lineup" && b["sides"] is JsonArray) {
            m["sides"] = JsonArray((b["sides"] as JsonArray).mapIndexed { i, s ->
                val so = s as? JsonObject ?: return@mapIndexed s
                if ("side" in so) so else JsonObject(so + ("side" to JsonPrimitive(if (i == 0) "home" else "away")))
            })
        }
        if (type == "form") { if ("home" !in m) m["home"] = JsonArray(emptyList()); if ("away" !in m) m["away"] = JsonArray(emptyList()) }
        return JsonObject(m)
    }
}
