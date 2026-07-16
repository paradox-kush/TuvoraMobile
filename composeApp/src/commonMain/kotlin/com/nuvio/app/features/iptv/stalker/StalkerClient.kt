package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.IptvClient
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamAccountInfo
import com.nuvio.app.features.iptv.XtreamCategory
import com.nuvio.app.features.iptv.XtreamChannel
import com.nuvio.app.features.iptv.XtreamEpisode
import com.nuvio.app.features.iptv.XtreamMovie
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.XtreamSeriesDetail
import com.nuvio.app.features.iptv.XtreamSeriesItem
import com.nuvio.app.features.iptv.XtreamVodDetail
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The [IptvClient] for a Stalker portal (MAG/Ministra), mirroring NuvioTV's StalkerClient: it browses
 * via the stateful [StalkerSession] and maps the raw `{"js": …}` responses to the SAME domain models
 * Xtream/M3U emit, so the whole hybrid lane (registry ids, native detail, direct-stream playback) is
 * identical downstream.
 *
 * PLAYBACK: create_link carries a single-use / time-limited play_token, so it is NEVER cached. The
 * sync stream-URL methods return "" (a placeholder, like M3U) and the real URL is resolved FRESH at
 * play time via [resolveLiveUrl] / [resolveMovieUrl] / [resolveEpisodeUrl] (wired into the registry's
 * async live seam and MetaDetailsRepository's VOD/episode ensure-seam).
 */
object StalkerClient : IptvClient {

    private data class Entry(val session: StalkerSession, val fingerprint: String)

    private val sessions = mutableMapOf<String, Entry>()
    private val sessionsMutex = Mutex()

    private suspend fun sessionFor(acc: XtreamAccount): StalkerSession = sessionsMutex.withLock {
        // Fingerprint mirrors NuvioTV's StalkerSessionManager: serial/device-id/login edits don't
        // change acc.id (it's portal+MAC), so a cached session must be dropped when they change or
        // the edit silently keeps the OLD device identity.
        val fp = fingerprint(acc)
        val existing = sessions[acc.id]
        if (existing != null && existing.fingerprint == fp) return@withLock existing.session
        StalkerSession(acc).also { sessions[acc.id] = Entry(it, fp) }
    }

    private fun fingerprint(a: XtreamAccount): String =
        listOf(a.baseUrl, a.macAddress, a.serialNumber, a.deviceId, a.sendDeviceId.toString(),
            a.stalkerUsername, a.stalkerPassword).joinToString("|")

    /** Verify = a successful get_genres proves the full handshake + get_profile + authorised-browse chain. */
    override suspend fun verify(acc: XtreamAccount): Result<Unit> = runCatching {
        sessionFor(acc).request(mapOf("type" to "itv", "action" to "get_genres"))
        Unit
    }

    /** Account status for the settings row. Stalker returns expiry as free text in `phone`. */
    override suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo?> = runCatching {
        val js = sessionFor(acc).request(mapOf("type" to "account_info", "action" to "get_main_info"))
        // `phone` is free text like "February 20, 2027" — surface it verbatim (matches NuvioTV).
        val expiry = (js as? JsonObject)?.str("phone")?.takeIf { it.isNotBlank() }
        XtreamAccountInfo(
            status = if (expiry != null) "Active" else null,
            isTrial = false,
            expiresAtEpochSec = null,
            maxConnections = null,
            activeConnections = null,
            expiresText = expiry,
        )
    }

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "itv", "get_genres")

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "vod", "get_categories")

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "series", "get_categories")

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = runCatching {
        orderedList(acc, "itv", categoryId).map { item ->
            XtreamChannel(
                streamId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                logo = item.str("logo")?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                epgChannelId = item.str("xmltv_id")?.takeIf { it.isNotBlank() },
                categoryId = item.str("tv_genre_id") ?: item.str("genre_id"),
                hasArchive = (item.int("tv_archive") ?: 0) > 0,
                streamUrl = ""   // create_link resolves the real single-use URL at play time
            )
        }.filter { it.streamId > 0 }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = runCatching {
        orderedList(acc, "vod", categoryId).map { item ->
            XtreamMovie(
                streamId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                categoryId = item.str("category_id"),
                rating = item.str("rating_imdb") ?: item.str("rating"),
                streamUrl = "",
                tmdb = null,
                containerExtension = null
            )
        }.filter { it.streamId > 0 }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = runCatching {
        orderedList(acc, "series", categoryId).map { item ->
            XtreamSeriesItem(
                seriesId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                categoryId = item.str("category_id"),
                plot = item.str("description"),
                rating = item.str("rating_imdb") ?: item.str("rating"),
                tmdb = null,
                year = item.str("year")?.trim()?.take(4)?.toIntOrNull()
            )
        }.filter { it.seriesId > 0 }
    }

    override suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?> = runCatching {
        val row = orderedList(acc, "vod", null).firstOrNull { it.int("id") == vodId } ?: return@runCatching null
        XtreamVodDetail(
            name = row.str("name"),
            plot = row.str("description"),
            genres = emptyList(),
            rating = row.str("rating_imdb") ?: row.str("rating"),
            releaseDate = row.str("year"),
            tmdbId = null,
            containerExtension = null
        )
    }

    /**
     * Series detail incl. episode list. Portals have no get_series_info, so we re-read the series row:
     * it carries a `series` array of episode numbers; each episode plays via create_link on the series
     * cmd with `series={n}`. ponytail: seasons aren't modelled by these portals (flat list) — all land
     * in season 1; grouping by a season field is the upgrade path if a portal ever provides one.
     */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?> = runCatching {
        val row = orderedList(acc, "series", null).firstOrNull { it.int("id") == seriesId } ?: return@runCatching null
        val episodeNums = (row["series"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull() }
            .orEmpty()
        val episodes = episodeNums.sorted().map { n ->
            XtreamEpisode(
                // Encodes seriesId + episode so the play seam can rebuild the create_link cmd. Uses '_'
                // (both are ints) — NOT ':', which is the registry content-id delimiter parseId splits on.
                episodeId = "${seriesId}_$n",
                season = 1,
                episodeNum = n,
                title = "Episode $n",
                plot = null,
                still = null,
                containerExtension = null
            )
        }
        XtreamSeriesDetail(
            name = row.str("name"),
            poster = (row.str("screenshot_uri") ?: row.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
            tmdbId = null,
            plot = row.str("description"),
            genres = emptyList(),
            rating = row.str("rating_imdb") ?: row.str("rating"),
            releaseDate = row.str("year"),
            episodes = episodes
        )
    }

    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = runCatching {
        val js = sessionFor(acc).request(
            mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to streamId.toString(), "size" to limit.toString())
        )
        val list = (js as? JsonArray) ?: ((js as? JsonObject)?.get("data") as? JsonArray) ?: return@runCatching emptyList()
        list.mapNotNull { it as? JsonObject }.map { p ->
            val startMs = (p.long("start_timestamp") ?: 0L) * 1000
            val endMs = (p.long("stop_timestamp") ?: 0L) * 1000
            XtreamProgram(
                title = p.str("name").orEmpty(),
                description = p.str("descr").orEmpty(),
                startMs = startMs,
                endMs = endMs,
                // nowPlaying is decided by the caller against the device clock; the portal also hints it.
                nowPlaying = (p.int("mark_memo") ?: 0) == 0 && p === list.firstOrNull()
            )
        }
    }

    // Sync stream URLs are placeholders (like M3U) — Stalker MUST create_link fresh at play time.
    override fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String): String = ""
    override fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String = ""
    override fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String): String = ""

    // --- Fresh play-time resolution (create_link) -----------------------------

    suspend fun resolveLiveUrl(acc: XtreamAccount, streamId: Int): String? {
        val cmd = liveCmd(acc, streamId) ?: return null
        return createLink(acc, "itv", cmd)
    }

    suspend fun resolveMovieUrl(acc: XtreamAccount, streamId: Int): String? {
        val cmd = vodCmd(acc, streamId) ?: return null
        return createLink(acc, "vod", cmd)
    }

    suspend fun resolveEpisodeUrl(acc: XtreamAccount, seriesId: Int, episodeNum: Int): String? {
        val cmd = seriesCmd(acc, seriesId) ?: return null
        return createLink(acc, "vod", cmd, extraParams = mapOf("series" to episodeNum.toString()))
    }

    private suspend fun createLink(
        acc: XtreamAccount,
        type: String,
        cmd: String,
        extraParams: Map<String, String> = emptyMap()
    ): String? {
        val params = buildMap {
            put("type", type)
            put("action", "create_link")
            put("cmd", cmd)
            put("forced_storage", "undefined")
            put("disable_ad", "0")
            putAll(extraParams)
        }
        val js = runCatching { sessionFor(acc).request(params) }.getOrNull() as? JsonObject ?: return null
        return StalkerProtocol.extractStreamUrl(js.str("cmd"))
    }

    // --- cmd lookup (browse-time cmd needed for create_link) ------------------

    private suspend fun liveCmd(acc: XtreamAccount, streamId: Int): String? =
        orderedList(acc, "itv", null).firstOrNull { it.int("id") == streamId }?.str("cmd")

    private suspend fun vodCmd(acc: XtreamAccount, streamId: Int): String? =
        orderedList(acc, "vod", null).firstOrNull { it.int("id") == streamId }?.str("cmd")

    private suspend fun seriesCmd(acc: XtreamAccount, seriesId: Int): String? =
        orderedList(acc, "series", null).firstOrNull { it.int("id") == seriesId }?.str("cmd")

    // --- request helpers ------------------------------------------------------

    private suspend fun categories(acc: XtreamAccount, type: String, action: String): Result<List<XtreamCategory>> = runCatching {
        val arr = sessionFor(acc).request(mapOf("type" to type, "action" to action)) as? JsonArray
            ?: return@runCatching emptyList()
        arr.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val id = obj.str("id") ?: return@mapNotNull null
            if (id == "*") return@mapNotNull null   // "*" = All; the hub adds its own "All"
            XtreamCategory(id, obj.str("title") ?: obj.str("name").orEmpty())
        }
    }

    /** Paginated get_ordered_list across pages (js.total_items bounds the loop), capped so an "All"
     *  fetch can't run away — categories are the real browse path. */
    private suspend fun orderedList(acc: XtreamAccount, type: String, categoryId: String?): List<JsonObject> {
        val session = sessionFor(acc)
        val out = ArrayList<JsonObject>()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.size < total && out.size < MAX_ITEMS && page <= MAX_PAGES) {
            val params = buildMap {
                put("type", type)
                put("action", "get_ordered_list")
                put("genre", categoryId ?: "*")
                if (type != "itv") put("category", categoryId ?: "*")
                put("p", page.toString())
                put("sortby", "number")
            }
            val obj = runCatching { session.request(params) }.getOrNull() as? JsonObject ?: break
            total = obj.int("total_items") ?: obj.int("max_page_items")?.let { it * MAX_PAGES } ?: out.size
            val data = obj["data"] as? JsonArray ?: break
            if (data.isEmpty()) break
            data.mapNotNullTo(out) { it as? JsonObject }
            page++
        }
        return out
    }

    /** Portal logos/screenshots may be relative — resolve against the portal base. */
    private fun absolutize(acc: XtreamAccount, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = acc.baseUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    // --- lenient kotlinx JSON accessors (portals type fields inconsistently) --

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = str(key)?.trim()?.toIntOrNull()
    private fun JsonObject.long(key: String): Long? = str(key)?.trim()?.toLongOrNull()

    private const val MAX_ITEMS = 8000    // ponytail: categories are the browse path; don't slurp 26k
    private const val MAX_PAGES = 200
}
