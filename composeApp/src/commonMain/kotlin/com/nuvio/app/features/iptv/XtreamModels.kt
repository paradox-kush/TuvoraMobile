package com.nuvio.app.features.iptv

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Tolerates the same field arriving as a JSON number, a quoted string, or a bool —
 * real Xtream panels are inconsistent (verified live: tmdb_id is "936075" on one
 * server, 24831 on another). Twin of NuvioTV's FlexIntAdapter.
 */
object FlexIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? {
        val jd = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val prim = jd.decodeJsonElement() as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content.trim().toIntOrNull()
        else prim.booleanOrNull?.let { if (it) 1 else 0 } ?: prim.intOrNull
    }
    override fun serialize(encoder: Encoder, value: Int?) { encoder.encodeInt(value ?: 0) }
}

/**
 * Xtream Codes `player_api.php` response shapes (only fields we use) plus the
 * domain models the UI consumes. KMP twin of NuvioTV's XtreamDto/XtreamClient.
 *
 * ponytail: ids that panels reliably send as ints are Int; category_id is String
 * because it comes back quoted. If a panel sends category_id as a bare int,
 * decode will fail here — switch it to a JsonElement-tolerant type then.
 */

// --- Wire DTOs (kotlinx.serialization) --------------------------------------

@Serializable
data class XtreamAccountDto(
    @SerialName("user_info") val userInfo: XtreamUserInfoDto? = null
    // ponytail: server_info is skipped via ignoreUnknownKeys — its fields were never read,
    // and panels send `port` as a bare int which broke String decoding. Re-add a DTO
    // (with FlexIntSerializer on port) only if something actually needs server_info.
)

@Serializable
data class XtreamUserInfoDto(
    @Serializable(with = FlexIntSerializer::class) val auth: Int? = null,
    val status: String? = null,
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("is_trial") val isTrial: String? = null
)

@Serializable
data class XtreamCategoryDto(
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String? = null
)

@Serializable
data class XtreamLiveStreamDto(
    val name: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("stream_id") val streamId: Int? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("tv_archive") val tvArchive: Int? = null
)

@Serializable
data class XtreamVodStreamDto(
    val name: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("stream_id") val streamId: Int? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    val rating: String? = null
)

@Serializable
data class XtreamSeriesDto(
    @Serializable(with = FlexIntSerializer::class) @SerialName("series_id") val seriesId: Int? = null,
    val name: String? = null,
    val cover: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val plot: String? = null,
    val rating: String? = null
)

@Serializable
data class XtreamShortEpgResponseDto(
    @SerialName("epg_listings") val listings: List<XtreamEpgEntryDto>? = null
)

@Serializable
data class XtreamEpgEntryDto(
    val title: String? = null,            // base64
    val description: String? = null,      // base64
    @SerialName("start_timestamp") val startTimestamp: String? = null,
    @SerialName("stop_timestamp") val stopTimestamp: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("now_playing") val nowPlaying: Int? = null
)

// --- Domain models ----------------------------------------------------------

@Serializable
data class XtreamAccount(
    val id: String,
    val name: String,
    val baseUrl: String,      // http://host:port (no trailing slash, no path)
    val username: String,
    val password: String,
    val enabled: Boolean = true
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamChannel(
    val streamId: Int,
    val name: String,
    val logo: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val hasArchive: Boolean,
    val streamUrl: String
)

data class XtreamMovie(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: String?,
    val streamUrl: String
)

data class XtreamSeriesItem(
    val seriesId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: String?
)

data class XtreamProgram(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val nowPlaying: Boolean
)

data class XtreamAccountInfo(
    val status: String?,
    val isTrial: Boolean,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?
)

// get_vod_info + get_series_info are parsed by hand in XtreamClient (panels send `info` as
// object-or-[] inconsistently), so no strict *_info DTOs live here.

// --- detail domain models ---------------------------------------------------

data class XtreamVodDetail(
    val name: String?,
    val plot: String?,
    val genres: List<String>,
    val rating: String?,
    val releaseDate: String?,
    val tmdbId: Int?,
    val containerExtension: String?
)

data class XtreamSeriesDetail(
    val name: String?,
    val poster: String?,
    val tmdbId: Int?,
    val plot: String?,
    val genres: List<String>,
    val rating: String?,
    val episodes: List<XtreamEpisode>
)

data class XtreamEpisode(
    val episodeId: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val plot: String?,
    val still: String?,
    val containerExtension: String?
)
