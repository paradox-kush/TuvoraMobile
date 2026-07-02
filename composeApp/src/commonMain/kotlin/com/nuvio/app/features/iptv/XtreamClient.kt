package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.httpGetText
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.util.decodeBase64String
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Talks to one Xtream panel: builds `player_api.php` + stream URLs, fetches via the
 * shared [httpGetText], maps DTOs -> domain models. KMP twin of NuvioTV's XtreamClient.
 *
 * ponytail: stream URLs reuse the account's entered baseUrl; short_epg gives now+next
 * cheaply. Full XMLTV grid and a per-host server_info override are the upgrade paths.
 */
object XtreamClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Verifies credentials. Success only when the panel reports auth=1 and an active status. */
    suspend fun verify(acc: XtreamAccount): Result<Unit> = call {
        val info = userInfo(playerApi(acc))
        check(info?.get("auth").asIntOrNull() == 1) { "Authentication failed" }
        val status = info?.get("status").asStringOrNull()?.lowercase() ?: ""
        check(status.isEmpty() || status == "active") { "Account status: ${info?.get("status").asStringOrNull()}" }
    }

    /** Live account status: active/expired, trial flag, expiry, and current vs max connections. */
    suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo?> = call {
        val info = userInfo(playerApi(acc)) ?: return@call null
        XtreamAccountInfo(
            status = info["status"].asStringOrNull(),
            isTrial = info["is_trial"].asStringOrNull() == "1",
            expiresAtEpochSec = info["exp_date"].asStringOrNull()?.toLongOrNull(),
            maxConnections = info["max_connections"].asIntOrNull(),
            activeConnections = info["active_cons"].asIntOrNull()
        )
    }

    /** `user_info` object from the no-action player_api call, parsed loosely. */
    private suspend fun userInfo(url: String): JsonObject? =
        runCatching { json.parseToJsonElement(httpGetText(url)).jsonObject["user_info"] as? JsonObject }.getOrNull()

    suspend fun liveCategories(acc: XtreamAccount) = categories(acc, "get_live_categories")
    suspend fun vodCategories(acc: XtreamAccount) = categories(acc, "get_vod_categories")
    suspend fun seriesCategories(acc: XtreamAccount) = categories(acc, "get_series_categories")

    // Bulk lists are parsed loosely — field by field from raw JSON, not through a strict DTO.
    // Xtream panels are wildly inconsistent about types (a field is an int on one panel, a
    // quoted string on another, a bare number where a string is expected on a third — e.g.
    // onnipsite sends `rating` as `0`, not `"0"`). A strict decode throws on the FIRST such
    // field and loses the ENTIRE catalog, so the provider's index silently never builds.
    suspend fun liveChannels(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamChannel>> = call {
        jsonArray(playerApi(acc, "get_live_streams", categoryId)).mapNotNull { o ->
            val id = o["stream_id"].asIntOrNull() ?: return@mapNotNull null
            XtreamChannel(
                streamId = id,
                name = o["name"].asStringOrNull() ?: "",
                logo = o["stream_icon"].asStringOrNull(),
                epgChannelId = o["epg_channel_id"].asStringOrNull(),
                categoryId = o["category_id"].asStringOrNull(),
                hasArchive = (o["tv_archive"].asIntOrNull() ?: 0) > 0,
                streamUrl = streamUrl(acc, "live", id, "ts")
            )
        }
    }

    suspend fun vodMovies(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamMovie>> = call {
        jsonArray(playerApi(acc, "get_vod_streams", categoryId)).mapNotNull { o -> parseVodItem(acc, o) }
    }

    suspend fun series(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamSeriesItem>> = call {
        jsonArray(playerApi(acc, "get_series", categoryId)).mapNotNull { o -> parseSeriesItem(o) }
    }

    suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int = 4): Result<List<XtreamProgram>> = call {
        val url = playerApi(acc, "get_short_epg") + "&stream_id=$streamId&limit=$limit"
        val root = runCatching { json.parseToJsonElement(httpGetText(url)).jsonObject }.getOrNull() ?: return@call emptyList()
        (root["epg_listings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o ->
            XtreamProgram(
                title = decodeXtreamBase64(o["title"].asStringOrNull()),
                description = decodeXtreamBase64(o["description"].asStringOrNull()),
                startMs = (o["start_timestamp"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
                endMs = (o["stop_timestamp"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
                nowPlaying = o["now_playing"].asIntOrNull() == 1,
            )
        }
    }

    /**
     * VOD detail for synthetic-meta + TMDB enrichment. Returns null (not a failure) when the
     * panel sends `info: []` — a known quirk — so callers fall back to bare Xtream metadata.
     */
    suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?> = call {
        val text = httpGetText(playerApi(acc, "get_vod_info") + "&vod_id=$vodId")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@call null
        val info = root["info"] as? JsonObject   // null when the panel sends info: []
        val movieData = root["movie_data"] as? JsonObject
        XtreamVodDetail(
            name = movieData?.get("name").asStringOrNull(),
            plot = info?.get("plot").asStringOrNull(),
            genres = info?.get("genre").asStringOrNull()?.splitCsv() ?: emptyList(),
            rating = info?.get("rating").asStringOrNull(),
            releaseDate = (info?.get("releasedate") ?: info?.get("release_date")).asStringOrNull(),
            tmdbId = info?.get("tmdb_id").asIntOrNull(),
            containerExtension = movieData?.get("container_extension").asStringOrNull()
        )
    }

    /**
     * Series detail incl. flattened episode list. Parsed leniently by hand because panels are
     * wildly inconsistent: an episode's `info` is an object on some episodes and `[]` on others
     * within the SAME series, and season/episode numbers arrive as int or quoted string. A strict
     * decode throws on the first `info: []` and loses every episode — so we walk the JSON instead.
     */
    suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?> = call {
        val text = httpGetText(playerApi(acc, "get_series_info") + "&series_id=$seriesId")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@call null
        val info = root["info"] as? JsonObject
        val episodes = (root["episodes"] as? JsonObject).orEmptyEntries().flatMap { (seasonKey, seasonEps) ->
            (seasonEps as? JsonArray).orEmpty().mapNotNull { element ->
                val e = element as? JsonObject ?: return@mapNotNull null
                val epId = e["id"].asStringOrNull() ?: return@mapNotNull null
                val epInfo = e["info"] as? JsonObject   // null when the panel sends info: []
                val num = e["episode_num"].asIntOrNull() ?: 0
                XtreamEpisode(
                    episodeId = epId,
                    season = e["season"].asIntOrNull() ?: seasonKey.toIntOrNull() ?: 0,
                    episodeNum = num,
                    title = e["title"].asStringOrNull() ?: "Episode $num",
                    plot = epInfo?.get("plot").asStringOrNull(),
                    still = epInfo?.get("movie_image").asStringOrNull(),
                    containerExtension = e["container_extension"].asStringOrNull()
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episodeNum }))
        XtreamSeriesDetail(
            name = info?.get("name").asStringOrNull(),
            poster = info?.get("cover").asStringOrNull(),
            tmdbId = (info?.get("tmdb_id") ?: info?.get("tmdb")).asIntOrNull(),
            plot = info?.get("plot").asStringOrNull(),
            genres = info?.get("genre").asStringOrNull()?.splitCsv() ?: emptyList(),
            rating = info?.get("rating").asStringOrNull(),
            releaseDate = (info?.get("releaseDate") ?: info?.get("release_date") ?: info?.get("releasedate")).asStringOrNull(),
            episodes = episodes
        )
    }

    // --- public stream-url builders (used by the registry / short-circuits) --

    fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String = "mp4"): String = streamUrl(acc, "movie", streamId, ext.ifBlank { "mp4" })
    fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String = streamUrl(acc, "live", streamId, "ts")
    fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String = "mp4"): String {
        val base = acc.baseUrl.trimEnd('/')
        return "$base/series/${acc.username.encodeURLPathPart()}/${acc.password.encodeURLPathPart()}/$episodeId.${ext.ifBlank { "mp4" }}"
    }

    // --- internals -----------------------------------------------------------

    private fun String.splitCsv(): List<String> = split(",").mapNotNull { it.trim().ifBlank { null } }

    private suspend fun categories(acc: XtreamAccount, action: String): Result<List<XtreamCategory>> = call {
        jsonArray(playerApi(acc, action)).mapNotNull { o ->
            val id = o["category_id"].asStringOrNull() ?: return@mapNotNull null
            XtreamCategory(id, o["category_name"].asStringOrNull() ?: "")
        }
    }

    /** One VOD list entry -> domain model, every field read tolerantly. internal for tests. */
    internal fun parseVodItem(acc: XtreamAccount, o: JsonObject): XtreamMovie? {
        val id = o["stream_id"].asIntOrNull() ?: return null
        val ext = o["container_extension"].asStringOrNull()
        return XtreamMovie(
            streamId = id,
            name = o["name"].asStringOrNull() ?: "",
            poster = o["stream_icon"].asStringOrNull(),
            categoryId = o["category_id"].asStringOrNull(),
            rating = o["rating"].asStringOrNull(),
            streamUrl = streamUrl(acc, "movie", id, ext ?: "mp4"),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            containerExtension = ext,
        )
    }

    /** One series list entry -> domain model, every field read tolerantly. internal for tests. */
    internal fun parseSeriesItem(o: JsonObject): XtreamSeriesItem? {
        val id = o["series_id"].asIntOrNull() ?: return null
        return XtreamSeriesItem(
            seriesId = id,
            name = o["name"].asStringOrNull() ?: "",
            poster = o["cover"].asStringOrNull(),
            categoryId = o["category_id"].asStringOrNull(),
            plot = o["plot"].asStringOrNull(),
            rating = o["rating"].asStringOrNull(),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            year = (o["releaseDate"] ?: o["release_date"]).asStringOrNull()?.trim()?.take(4)?.toIntOrNull(),
        )
    }

    private suspend fun jsonArray(url: String): List<JsonObject> =
        (json.parseToJsonElement(httpGetText(url)) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun playerApi(acc: XtreamAccount, action: String? = null, categoryId: String? = null): String {
        val base = acc.baseUrl.trimEnd('/')
        val sb = StringBuilder(base)
            .append("/player_api.php?username=").append(acc.username.encodeURLParameter())
            .append("&password=").append(acc.password.encodeURLParameter())
        if (action != null) sb.append("&action=").append(action)
        if (categoryId != null) sb.append("&category_id=").append(categoryId.encodeURLParameter())
        return sb.toString()
    }

    private fun streamUrl(acc: XtreamAccount, kind: String, id: Int, ext: String): String {
        val base = acc.baseUrl.trimEnd('/')
        return "$base/$kind/${acc.username.encodeURLPathPart()}/${acc.password.encodeURLPathPart()}/$id.$ext"
    }

    private inline fun <T> call(block: () -> T): Result<T> = runCatching { block() }

    private fun JsonObject?.orEmptyEntries(): Set<Map.Entry<String, JsonElement>> = this?.entries ?: emptySet()
    private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    private fun JsonElement?.asIntOrNull(): Int? {
        val p = this as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull()
    }
}

internal fun XtreamEpgEntryDto.toProgram(): XtreamProgram = XtreamProgram(
    title = decodeXtreamBase64(title),
    description = decodeXtreamBase64(description),
    startMs = (startTimestamp?.toLongOrNull() ?: 0L) * 1000,
    endMs = (stopTimestamp?.toLongOrNull() ?: 0L) * 1000,
    nowPlaying = nowPlaying == 1
)

/** Xtream base64-encodes EPG title/description. Returns "" on null/garbage rather than throwing. */
internal fun decodeXtreamBase64(s: String?): String {
    if (s.isNullOrBlank()) return ""
    return runCatching { s.trim().decodeBase64String() }.getOrDefault(s)
}
