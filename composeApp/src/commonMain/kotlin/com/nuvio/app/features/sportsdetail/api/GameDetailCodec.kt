package com.nuvio.app.features.sportsdetail.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * JSON form of [GameDetail] (for caching / a backend-assembled game). Forward compatible: unknown fields are
 * ignored, unknown block types are dropped, an unknown state becomes UPCOMING. Never throws.
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

    fun decode(text: String): GameDetail? = runCatching {
        val d = json.decodeFromString(GameDetail.serializer(), text)
        d.copy(blocks = d.blocks.filter { it !is UnknownBlock })
    }.getOrNull()

    fun encode(detail: GameDetail): String = json.encodeToString(GameDetail.serializer(), detail)
}
