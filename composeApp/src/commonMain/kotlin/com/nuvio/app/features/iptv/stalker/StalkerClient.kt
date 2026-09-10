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
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentKind
import com.nuvio.app.features.iptv.content.IptvEpisodeRow
import com.nuvio.app.features.iptv.content.IptvSeriesRow
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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

    // Browse-time rows keyed accountId:type:id — see [row]. This is what keeps play/detail from
    // re-paging the whole catalog (the request storm that got a live portal to block us).
    private val rowCache = mutableMapOf<String, JsonObject>()

    /** A row's use_http_tmp_link/use_load_balancing verdicts (null = key absent on the row). */
    private data class LinkFlags(val useHttpTmpLink: Boolean?, val useLoadBalancing: Boolean?)

    // Static-vs-mint evidence per row, keyed like [rowCache] — small enough to keep for the whole
    // lineup (the raw 13MB rows are NOT retained; get_all_channels items never enter rowCache).
    private val linkFlags = mutableMapOf<String, LinkFlags>()

    // The live lineup lives in IptvContentDb (P6): one get_all_channels per [LINEUP_TTL_MS],
    // replaced wholesale via replaceLiveLineup, every browse a local indexed read. That kills the
    // 13MB re-download every cold start AND makes a favorited channel playable offline — the cmd
    // (create_link's stable input) is persisted per row; only the single-use play URL never is.
    private val liveMutex = Mutex()

    // Bulk EPG lands in IptvContentDb.epg_programmes (streamed, chunk-inserted — see
    // [ensureBulkEpg]); nothing guide-sized is retained in memory anymore. This set only marks
    // portals whose get_epg_info genuinely has no data, so they use the per-channel fallback.
    private val epgUnsupported = mutableSetOf<String>()
    private val epgMutex = Mutex()
    private val epgJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // Season rows per series (one movie_id=<id> request), keyed accountId:seriesId — see [seasonsOf].
    private val seasonCache = mutableMapOf<String, List<StalkerSeason>>()
    private val seasonMutex = Mutex()

    /** Test seam: lets a test drive the whole client against a fake portal (StalkerRequestCountTest). */
    internal var sessionFactory: (XtreamAccount) -> StalkerSession = { StalkerSession(it) }

    /** Test seam: simulates a process death — in-memory caches gone, the SQLite store intact. */
    internal fun clearMemoryCachesForTest() {
        rowCache.clear()
        linkFlags.clear()
        seasonCache.clear()
    }

    private suspend fun sessionFor(acc: XtreamAccount): StalkerSession = sessionsMutex.withLock {
        // Fingerprint mirrors NuvioTV's StalkerSessionManager: serial/device-id/login edits don't
        // change acc.id (it's portal+MAC), so a cached session must be dropped when they change or
        // the edit silently keeps the OLD device identity.
        val fp = fingerprint(acc)
        val existing = sessions[acc.id]
        if (existing != null && existing.fingerprint == fp) return@withLock existing.session
        // Config changed (or first use) — the cached rows/cmds belong to the OLD portal identity,
        // and the replaced session's watchdog must not keep pinging it.
        existing?.session?.shutdown()
        rowCache.keys.removeAll { it.startsWith("${acc.id}:") }
        linkFlags.keys.removeAll { it.startsWith("${acc.id}:") }
        epgUnsupported.remove(acc.id)
        seasonCache.keys.removeAll { it.startsWith("${acc.id}:") }
        sessionFactory(acc).also { sessions[acc.id] = Entry(it, fp) }
    }

    // dnsProvider is in here because the session's HTTP seams close over it: without it an edited
    // DNS choice wouldn't reach the portal until the process restarted.
    private fun fingerprint(a: XtreamAccount): String =
        listOf(a.baseUrl, a.macAddress, a.serialNumber, a.deviceId, a.sendDeviceId.toString(),
            a.stalkerUsername, a.stalkerPassword, a.dnsProvider).joinToString("|")

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

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = runCatching {
        if (ensureLineup(acc)) {
            return@runCatching IptvContentDb.categoriesFor(acc.id, IptvContentKind.LIVE).map { XtreamCategory(it.id, it.name) }
        }
        // Mirror unavailable (portal down mid-refresh with no stored lineup): live portal call.
        categories(acc, "itv", "get_genres").getOrThrow()
    }

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "vod", "get_categories")

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "series", "get_categories")

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = runCatching {
        if (!ensureLineup(acc)) return@runCatching emptyList()
        IptvContentDb.channelsFor(acc.id, categoryId).map { it.toChannel(acc) }
    }

    /** Windowed lineup read for the hub (item 5). Ensures the mirror, then a paged indexed read. */
    suspend fun liveChannelsPage(acc: XtreamAccount, categoryId: String?, offset: Int, limit: Int): List<XtreamChannel> {
        if (!ensureLineup(acc)) return emptyList()
        return IptvContentDb.pageChannels(acc.id, categoryId, offset, limit).map { it.toChannel(acc) }
    }

    private fun com.nuvio.app.features.iptv.content.IptvStreamRow.toChannel(acc: XtreamAccount) = XtreamChannel(
        streamId = sid,
        name = name,
        logo = logo?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
        epgChannelId = tvgId,
        categoryId = categoryId,
        hasArchive = hasArchive,
        streamUrl = ""   // create_link resolves the real single-use URL at play time
    )

    /**
     * Ensures a fresh (≤[LINEUP_TTL_MS]) live lineup for [acc] is stored in [IptvContentDb],
     * mirroring it when stale: genres + the WHOLE lineup in ONE `get_all_channels` (what every real
     * MAG client uses — stalkerhek / magplex / stalker-to-m3u; TiviMate's playlist add does exactly
     * this, measured in research/iptv-catalog-loading.md). Portals without get_all_channels fall
     * back to the bounded paged fetch, persisted the same way.
     *
     * The lineup used to live in an in-memory map — 13 MB re-downloaded every cold start, and a
     * favorited channel unplayable from Library until the hub happened to be browsed. Now every
     * browse is an indexed read, and the refresh only ever runs from a FOREGROUND browse — never a
     * background worker, because a Stalker handshake evicts the other device on a shared MAC.
     * Returns true when a usable lineup is stored.
     */
    private suspend fun ensureLineup(acc: XtreamAccount, force: Boolean = false): Boolean {
        val now = TraktPlatformClock.nowEpochMs()
        if (!force) {
            IptvContentDb.ingestMeta(acc.id)?.takeIf { now - it.builtAtMs < LINEUP_TTL_MS }
                ?.let { return it.liveCount > 0 }
        }
        return liveMutex.withLock {
            IptvContentDb.ingestMeta(acc.id)?.takeIf { !force && now - it.builtAtMs < LINEUP_TTL_MS }
                ?.let { return@withLock it.liveCount > 0 }   // raced: another caller mirrored
            val cats = runCatching { categories(acc, "itv", "get_genres").getOrThrow() }.getOrNull()
            val js = runCatching {
                sessionFor(acc).request(mapOf("type" to "itv", "action" to "get_all_channels"))
            }.getOrNull()
            var items = ((js as? JsonObject)?.get("data") as? JsonArray ?: js as? JsonArray)
                ?.mapNotNull { it as? JsonObject }.orEmpty()
            // A portal without get_all_channels: bounded paged fetch (rowCache keeps the raw rows).
            if (items.isEmpty()) items = orderedList(acc, "itv", null)
            val rows = items.mapNotNull { item ->
                val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                // The raw 13MB rows are dropped after this mapping, so the static-vs-mint flags
                // must be picked off here or the whole lineup would lose its evidence.
                rememberLinkFlags(acc.id, "itv", item, id)
                com.nuvio.app.features.iptv.content.IptvStreamRow(
                    sid = id,
                    name = item.str("name").orEmpty(),
                    logo = item.str("logo")?.takeIf { it.isNotBlank() },
                    tvgId = item.str("xmltv_id")?.takeIf { it.isNotBlank() },
                    categoryId = item.str("tv_genre_id") ?: item.str("genre_id"),
                    url = "",
                    ext = null,
                    cmd = item.str("cmd"),
                    hasArchive = (item.int("tv_archive") ?: 0) > 0,
                    // Stored as plain booleans (false = flag absent OR panel said false — the
                    // column cannot tell them apart), so reads treat only TRUE as evidence.
                    useHttpTmpLink = item.flag("use_http_tmp_link") ?: false,
                    useLoadBalancing = item.flag("use_load_balancing") ?: false,
                )
            }
            // Nothing usable fetched: keep whatever lineup is already stored (stale beats empty),
            // and don't stamp freshness — the next browse retries.
            if (rows.isEmpty()) {
                return@withLock (IptvContentDb.ingestMeta(acc.id)?.liveCount ?: 0) > 0
            }
            IptvContentDb.replaceLiveLineup(acc.id, rows, cats.orEmpty().map { it.id to it.name })
            true
        }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = runCatching {
        val items = orderedList(acc, "vod", categoryId, maxItems = CATEGORY_ITEMS)
        writeThroughVod(acc, items)
        items.map { movieOf(acc, it) }.filter { it.streamId > 0 }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = runCatching {
        val items = orderedList(acc, "series", categoryId, maxItems = CATEGORY_ITEMS)
        writeThroughSeries(acc, items)
        items.map { seriesOf(acc, it) }.filter { it.seriesId > 0 }
    }

    /**
     * Write-through cache (P6): every VOD/series page browsed is upserted into [IptvContentDb] with
     * its `cmd`, so anything the user has EVER seen on this device stays playable after a cold
     * start (Library / Continue Watching) without re-finding it on the portal. Deliberately
     * best-effort and NEVER a reason for extra requests — a full Stalker VOD mirror is impossible
     * (14 rows/page, thousands of pages; measured in research/iptv-catalog-loading.md).
     */
    private suspend fun writeThroughVod(acc: XtreamAccount, items: List<JsonObject>) {
        if (items.isEmpty()) return
        val rows = items.mapNotNull { item ->
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            IptvStreamRow(
                sid = id,
                name = item.str("name").orEmpty(),
                logo = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() },
                tvgId = null,
                categoryId = item.str("category_id"),
                url = "",
                ext = null,
                cmd = item.str("cmd"),
            )
        }
        runCatching { IptvContentDb.insertChunk(acc.id, channels = emptyList(), vod = rows, series = emptyList(), episodes = emptyList(), categories = emptyList()) }
    }

    private suspend fun writeThroughSeries(acc: XtreamAccount, items: List<JsonObject>) {
        if (items.isEmpty()) return
        val rows = items.mapNotNull { item ->
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            IptvSeriesRow(
                sid = id,
                name = item.str("name").orEmpty(),
                logo = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() },
                categoryId = item.str("category_id"),
            )
        }
        runCatching { IptvContentDb.insertChunk(acc.id, channels = emptyList(), vod = emptyList(), series = rows, episodes = emptyList(), categories = emptyList()) }
    }

    /**
     * Portal-side VOD/series search via get_ordered_list's `search` param (what the MAG UI's own
     * search uses) — Stalker content never enters the TMDB match index (those player_api builds
     * just fail into backoff), so the search screen queries the portal directly. Never throws.
     */
    suspend fun searchMovies(acc: XtreamAccount, query: String): List<XtreamMovie> = runCatching {
        val items = orderedList(acc, "vod", null, search = query, maxItems = SEARCH_ITEMS)
        writeThroughVod(acc, items)   // a searched-then-favorited movie must survive a cold start too
        items.map { movieOf(acc, it) }.filter { it.streamId > 0 }
    }.getOrDefault(emptyList())

    suspend fun searchSeries(acc: XtreamAccount, query: String): List<XtreamSeriesItem> = runCatching {
        val items = orderedList(acc, "series", null, search = query, maxItems = SEARCH_ITEMS)
        writeThroughSeries(acc, items)
        items.map { seriesOf(acc, it) }.filter { it.seriesId > 0 }
    }.getOrDefault(emptyList())

    private fun movieOf(acc: XtreamAccount, item: JsonObject) = XtreamMovie(
        streamId = item.int("id") ?: 0,
        name = item.str("name").orEmpty(),
        poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
        categoryId = item.str("category_id"),
        rating = item.str("rating_imdb") ?: item.str("rating"),
        streamUrl = "",
        tmdb = null,
        containerExtension = null
    )

    private fun seriesOf(acc: XtreamAccount, item: JsonObject) = XtreamSeriesItem(
        seriesId = item.int("id") ?: 0,
        name = item.str("name").orEmpty(),
        poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
        categoryId = item.str("category_id"),
        plot = item.str("description"),
        rating = item.str("rating_imdb") ?: item.str("rating"),
        tmdb = null,
        year = item.str("year")?.trim()?.take(4)?.toIntOrNull()
    )

    override suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?> = runCatching {
        // Rich detail from this session's browse row when we have it; else the write-through store
        // gives a name-only detail (like M3U's vodInfo) WITHOUT the bounded portal scan — TMDB
        // enrichment upstream fills the rest.
        val cached = rowCache[rowKey(acc.id, "vod", vodId)]
        if (cached == null) {
            IptvContentDb.vodRow(acc.id, vodId)?.let { db ->
                return@runCatching XtreamVodDetail(
                    name = db.name,
                    plot = null,
                    genres = emptyList(),
                    rating = null,
                    releaseDate = null,
                    tmdbId = null,
                    containerExtension = null
                )
            }
        }
        val row = cached ?: row(acc, "vod", vodId) ?: return@runCatching null
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
     * Series detail incl. episode list. Portals have no get_series_info; a series is a two-level tree:
     * the top-level row is just a container (its own `series` array is EMPTY), and the real episodes
     * hang off SEASON rows fetched with `movie_id=<seriesId>`.
     *
     * We used to read the episode list off the top-level row, which is always empty — so every Stalker
     * series showed zero episodes. Seasons ARE modelled (verified on a real portal: Breaking Bad
     * returns Season 2..5 rows, each carrying its own episode numbers + cmd).
     */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?> = runCatching {
        // Cold start (Library detail, portal not yet browsed this session): serve the write-through
        // rows — name/poster + the stored episode list — without the portal scan. Falls through to
        // the live path when nothing (or no episodes) is stored yet.
        if (rowCache[rowKey(acc.id, "series", seriesId)] == null) {
            IptvContentDb.seriesRow(acc.id, seriesId)?.let { db ->
                val eps = IptvContentDb.episodesFor(acc.id, seriesId).map { ep ->
                    XtreamEpisode(
                        episodeId = ep.episodeId,
                        season = ep.season,
                        episodeNum = ep.episode,
                        title = ep.name,
                        plot = null,
                        still = null,
                        containerExtension = null
                    )
                }
                if (eps.isNotEmpty()) {
                    return@runCatching XtreamSeriesDetail(
                        name = db.name,
                        poster = db.logo?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                        tmdbId = null,
                        plot = null,
                        genres = emptyList(),
                        rating = null,
                        releaseDate = null,
                        episodes = eps
                    )
                }
            }
        }
        val row = row(acc, "series", seriesId) ?: return@runCatching null
        val episodes = seasonsOf(acc, seriesId).flatMap { season ->
            season.episodeNums.map { n ->
                XtreamEpisode(
                    // Encodes seriesId + season + episode so the play seam can rebuild the create_link
                    // cmd. Uses '_' (all ints) — NOT ':', the registry content-id delimiter parseId
                    // splits on. Old 2-part ids (no season) still parse; see MetaDetailsRepository.
                    episodeId = "${seriesId}_${season.number}_$n",
                    season = season.number,
                    episodeNum = n,
                    title = "Episode $n",
                    plot = null,
                    still = null,
                    containerExtension = null
                )
            }
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

    /**
     * The warm scope. A lineup + bulk-EPG pull must OUTLIVE the screen that triggered it, so it never
     * rides a ViewModel/tile-queue job: scrolling cancels those, and before this the bulk
     * `get_epg_info` only ever finished once the user stopped moving (the "Stalker EPG only shows
     * after OK" symptom, ported from NuvioTV 2026-08-20).
     */
    // Dispatchers.Default (not IO): IO is JVM-only — internal on Kotlin/Native (iOS). The warm mostly
    // awaits network anyway, which the platform HTTP clients run on their own threads.
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Pull this account's lineup + the ONE bulk `get_epg_info` into the local store up front, so the
     * guide serves now/next from the store as the user scrolls. Fire-and-forget on [warmScope]; both
     * steps single-flight + TTL-gate internally ([ensureLineup]/[ensureBulkEpg]), so calling it on
     * every account visit is cheap.
     */
    override fun warm(acc: XtreamAccount) {
        warmScope.launch {
            runCatching { ensureLineup(acc) }
            runCatching { ensureBulkEpg(acc) }
        }
    }

    /**
     * Now/next for one channel — served from the ONE bulk [epgSnapshot] fetch, not a request per
     * channel. The hub calls this from a LaunchedEffect per tile, so the old per-channel
     * `get_short_epg` meant a request for every tile scrolled into view (measured: 132 in one
     * browse, the biggest single load left after get_all_channels).
     */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = runCatching {
        if (ensureBulkEpg(acc)) {
            val now = TraktPlatformClock.nowEpochMs()
            return@runCatching IptvContentDb.epgAround(acc.id, streamId.toString(), now, limit).map {
                XtreamProgram(
                    title = it.title,
                    description = it.desc.orEmpty(),
                    startMs = it.startMs,
                    endMs = it.endMs,
                    nowPlaying = now in it.startMs until it.endMs,
                )
            }
        }
        // Transient bulk failure (network/cooldown): return empty rather than fanning out a
        // per-channel request per visible tile — the next ensure retries. Only a portal that
        // GENUINELY lacks get_epg_info takes the per-channel path.
        if (acc.id !in epgUnsupported) return@runCatching emptyList()
        val js = sessionFor(acc).request(
            mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to streamId.toString(), "size" to limit.toString())
        )
        val list = (js as? JsonArray) ?: ((js as? JsonObject)?.get("data") as? JsonArray) ?: return@runCatching emptyList()
        val rows = list.mapNotNull { it as? JsonObject }
        rows.map { programOf(it, firstIsNow = it === rows.firstOrNull()) }
    }

    /**
     * Ensures a fresh (≤[EPG_TTL_MS]) bulk guide for [acc] is stored in [IptvContentDb], fetching
     * `get_epg_info&period=3` when stale. The response is STREAMED through
     * [StalkerEpgStreamParser] and chunk-inserted — it used to be read into one String plus a full
     * JsonElement tree plus a retained byChannel map, which is the 174.5 MB failure mode a real
     * client trace demonstrated (research/iptv-catalog-loading.md §3). Peak memory is now one
     * insert chunk regardless of guide size, and the rows double as [IptvContentDb.epgSearch]
     * input, so the sports matcher can finally see a Stalker portal's own guide.
     *
     * Returns true when the DB holds programmes for this playlist. Marks [epgUnsupported] ONLY
     * when a healthy body genuinely carries no `data` object — a transport failure stays
     * retryable (the old code marked unsupported on any failure, so one network blip put the hub
     * on the per-channel fan-out path for the whole session).
     */
    private suspend fun ensureBulkEpg(acc: XtreamAccount): Boolean {
        if (acc.id in epgUnsupported) return false
        val now = TraktPlatformClock.nowEpochMs()
        IptvContentDb.epgMeta(acc.id)?.takeIf { now - it.builtAtMs < EPG_TTL_MS }
            ?.let { return it.programmeCount > 0 }
        return epgMutex.withLock {
            IptvContentDb.epgMeta(acc.id)?.takeIf { now - it.builtAtMs < EPG_TTL_MS }
                ?.let { return@withLock it.programmeCount > 0 }   // raced: another caller ingested
            val ingest = EpgIngest(acc.id, epgJson)
            val streamed = runCatching {
                ingest.begin()
                sessionFor(acc).requestStream(
                    params = mapOf("type" to "itv", "action" to "get_epg_info", "period" to EPG_PERIOD_HOURS),
                    onRestart = { ingest.restart() },
                    onChunk = { ingest.feed(it) },
                )
            }
            if (streamed.isFailure) return@withLock false   // retryable — meta stays absent/stale
            if (!ingest.sawData) {
                epgUnsupported += acc.id                    // healthy body, genuinely no guide
                // A completed fetch with no data must not blank a good guide — keep the prior.
                IptvContentDb.finishEpg(acc.id, 0, keepPriorIfEmpty = true)
                return@withLock false
            }
            val count = ingest.finish()
            count > 0
        }
    }

    /**
     * One bulk-EPG ingest attempt: buffers parsed programme rows and flushes every [EPG_FLUSH]
     * via [kotlinx.coroutines.runBlocking] on the transport's IO thread (the established
     * M3U-ingest idiom — never the main thread). [restart] wipes and re-arms for the session's
     * single re-auth retry.
     */
    private class EpgIngest(private val playlistId: String, private val json: Json) {
        private val buffer = ArrayList<EpgProgrammeRow>(EPG_FLUSH)
        private var parser = newParser()
        private var count = 0

        val sawData: Boolean get() = parser.sawData

        private fun newParser() = StalkerEpgStreamParser(json) { chId, prog ->
            buffer.add(
                EpgProgrammeRow(
                    channelId = chId.toString(),
                    startMs = prog.startMs,
                    endMs = prog.endMs,
                    title = prog.title,
                    desc = prog.description.takeIf { it.isNotBlank() },
                )
            )
            count++
            if (buffer.size >= EPG_FLUSH) flushBlocking()
        }

        fun begin() = runBlocking { IptvContentDb.beginEpg(playlistId) }

        fun restart() {
            buffer.clear()
            count = 0
            parser = newParser()
            begin()
        }

        fun feed(chunk: String) = parser.feed(chunk)

        private fun flushBlocking() = runBlocking {
            IptvContentDb.insertEpgChunk(playlistId, buffer)
            buffer.clear()
        }

        /** Flushes the tail and writes the meta row LAST (crash-safe, like every other ingest). */
        fun finish(): Int {
            if (buffer.isNotEmpty()) flushBlocking()
            // A completed fetch that parsed to nothing must not blank a good guide — keep the prior.
            runBlocking { IptvContentDb.finishEpg(playlistId, count, keepPriorIfEmpty = true) }
            return count
        }
    }

    /** [nowMs] > 0 decides nowPlaying against the clock; else the portal's first-entry hint is used. */
    private fun programOf(p: JsonObject, nowMs: Long = 0L, firstIsNow: Boolean = false): XtreamProgram {
        val startMs = (p.long("start_timestamp") ?: 0L) * 1000
        val endMs = (p.long("stop_timestamp") ?: 0L) * 1000
        return XtreamProgram(
            title = p.str("name").orEmpty(),
            description = p.str("descr").orEmpty(),
            startMs = startMs,
            endMs = endMs,
            nowPlaying = if (nowMs > 0) nowMs in startMs until endMs
            else (p.int("mark_memo") ?: 0) == 0 && firstIsNow
        )
    }

    // Sync stream URLs are placeholders (like M3U) — Stalker MUST create_link fresh at play time.
    override fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String): String = ""
    override fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String = ""
    override fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String): String = ""

    // --- Fresh play-time resolution (create_link) -----------------------------

    /**
     * [forceMint] is the one-shot 401/403/410 refresh ladder's entry: it bypasses the static
     * verdict so a static play that died still gets exactly one fresh create_link.
     */
    suspend fun resolveLiveUrl(acc: XtreamAccount, streamId: Int, forceMint: Boolean = false): String? {
        val cmd = liveCmd(acc, streamId) ?: return null
        staticUrlOrNull(acc, "itv", streamId, cmd, forceMint)?.let { return it }
        return createLink(acc, "itv", cmd)
    }

    /** [nameHint] lets a cold-start play (Library/Continue Watching) find the row via the portal's own
     *  search instead of scanning a 63k-item catalog — pass the registered title when you have it. */
    suspend fun resolveMovieUrl(acc: XtreamAccount, streamId: Int, nameHint: String? = null, forceMint: Boolean = false): String? {
        val cmd = vodCmd(acc, streamId, nameHint) ?: return null
        staticUrlOrNull(acc, "vod", streamId, cmd, forceMint)?.let { return it }
        return createLink(acc, "vod", cmd)
    }

    /**
     * The static play URL when [StalkerPlaybackLinkPolicy] rules create_link unnecessary for this
     * row, else null (mint as always).
     *
     * Evidence arrives two ways: live rows via [rememberLinkFlags] (can prove true OR false),
     * and stored lineup rows via [noteStoredFlags] (true only — see its note on why the boolean
     * columns cannot prove false). A row with no evidence mints; cold-start static playback for
     * known-false rows waits on a tri-state column.
     */
    private fun staticUrlOrNull(acc: XtreamAccount, type: String, id: Int, cmd: String, forceMint: Boolean): String? {
        if (forceMint) return null
        val flags = linkFlags[rowKey(acc.id, type, id)]
        val decision = StalkerPlaybackLinkPolicy.decide(
            useHttpTmpLink = flags?.useHttpTmpLink,
            useLoadBalancing = flags?.useLoadBalancing,
            cmd = cmd,
        )
        return (decision as? StalkerPlaybackLinkPolicy.Decision.Static)?.url
    }

    /** Keep a row's flag evidence — only when the row actually carries a flag key. */
    private fun rememberLinkFlags(accId: String, type: String, item: JsonObject, id: Int) {
        val tmp = item.flag("use_http_tmp_link")
        val lb = item.flag("use_load_balancing")
        if (tmp == null && lb == null) return
        if (linkFlags.size > MAX_CACHED_ROWS) linkFlags.clear()   // same crude cap as rowCache
        linkFlags[rowKey(accId, type, id)] = LinkFlags(tmp, lb)
    }

    /**
     * Play one episode. The create_link cmd belongs to the SEASON row (it decodes to
     * `{"type":"series","series_id":536,"season_num":2}`), and the episode is passed as `series={n}` —
     * NOT the top-level series row, whose cmd is empty. [season] null = an old 2-part episode id from
     * before seasons were modelled; fall back to the first season we find.
     *
     * Episodes ALWAYS mint: the `series={n}` parameter is create_link's argument — the season cmd
     * is a container reference, not a playable address, so the static-cmd policy never applies.
     */
    suspend fun resolveEpisodeUrl(acc: XtreamAccount, seriesId: Int, season: Int?, episodeNum: Int): String? {
        // Season cmd resolution, cheapest first: this session's cache -> the write-through rows
        // (cold-start Continue Watching plays with ZERO portal requests before create_link) ->
        // the portal's season fetch.
        val cmd = seasonCache["${acc.id}:$seriesId"]
            ?.let { s -> (season?.let { n -> s.firstOrNull { it.number == n } } ?: s.firstOrNull())?.cmd }
            ?: IptvContentDb.episodesFor(acc.id, seriesId)
                .let { rows -> (season?.let { n -> rows.firstOrNull { it.season == n } } ?: rows.firstOrNull())?.cmd }
            ?: seasonsOf(acc, seriesId)
                .let { s -> (season?.let { n -> s.firstOrNull { it.number == n } } ?: s.firstOrNull())?.cmd }
            ?: return null
        return createLink(acc, "vod", cmd, extraParams = mapOf("series" to episodeNum.toString()))
    }

    private class StalkerSeason(val number: Int, val cmd: String, val episodeNums: List<Int>)

    /**
     * The season rows for a series (`movie_id=<seriesId>`), each with its own create_link cmd and
     * episode numbers. One request, cached for the session — a series' seasons don't change mid-browse.
     */
    private suspend fun seasonsOf(acc: XtreamAccount, seriesId: Int): List<StalkerSeason> =
        seasonMutex.withLock {
            seasonCache["${acc.id}:$seriesId"]?.let { return@withLock it }
            val js = runCatching {
                sessionFor(acc).request(
                    mapOf("type" to "series", "action" to "get_ordered_list",
                        "movie_id" to seriesId.toString(), "p" to "1")
                )
            }.getOrNull()
            val rows = ((js as? JsonObject)?.get("data") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val seasons = rows.mapNotNull { r ->
                val cmd = r.str("cmd")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // id is "<seriesId>:<season>"; the name ("Season 2") is the fallback.
                val num = r.str("id")?.substringAfter(':', "")?.trim()?.toIntOrNull()
                    ?: SEASON_NAME.find(r.str("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val eps = (r["series"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull() }
                    ?.sorted().orEmpty()
                StalkerSeason(num, cmd, eps)
            }.sortedBy { it.number }
            if (seasons.isNotEmpty()) {
                seasonCache["${acc.id}:$seriesId"] = seasons
                // Write-through (P6): each episode row carries its SEASON's cmd, so an episode in
                // Continue Watching resumes after a cold start with zero portal requests before
                // the create_link itself.
                val epRows = seasons.flatMap { s ->
                    s.episodeNums.map { n ->
                        IptvEpisodeRow(
                            seriesSid = seriesId,
                            episodeId = "${seriesId}_${s.number}_$n",
                            name = "Episode $n",
                            season = s.number,
                            episode = n,
                            logo = null,
                            url = "",
                            ext = null,
                            cmd = s.cmd,
                        )
                    }
                }
                runCatching { IptvContentDb.insertChunk(acc.id, channels = emptyList(), vod = emptyList(), series = emptyList(), episodes = epRows, categories = emptyList()) }
            }
            seasons
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

    private suspend fun liveCmd(acc: XtreamAccount, streamId: Int): String? {
        // The mirrored lineup carries every channel's cmd — playing a channel costs nothing but
        // the create_link itself, even on a cold start with the portal briefly unreachable.
        ensureLineup(acc)
        val stored = IptvContentDb.channelRow(acc.id, streamId)
        if (stored != null) noteStoredFlags(acc.id, "itv", streamId, stored.useHttpTmpLink, stored.useLoadBalancing)
        return stored?.cmd ?: row(acc, "itv", streamId)?.str("cmd")
    }

    /**
     * Cold-start evidence off a stored lineup row. The columns are plain booleans, so only TRUE
     * is evidence ("this panel flagged the row" — mint, protecting masked rows before any live
     * catalog fetch); false/false stays "no evidence" and mints anyway, because the store cannot
     * distinguish a panel that said false from a row written before the flags existed. Live
     * in-session evidence (rememberLinkFlags) is never overwritten — it can also prove false.
     */
    private fun noteStoredFlags(accId: String, type: String, id: Int, tmpLink: Boolean, loadBalancing: Boolean) {
        if (!tmpLink && !loadBalancing) return
        val key = rowKey(accId, type, id)
        if (linkFlags.containsKey(key)) return
        if (linkFlags.size > MAX_CACHED_ROWS) linkFlags.clear()
        linkFlags[key] = LinkFlags(tmpLink, loadBalancing)
    }

    private suspend fun vodCmd(acc: XtreamAccount, streamId: Int, nameHint: String? = null): String? =
        // Hot browse rows first, then the write-through store (anything EVER browsed on this
        // device — the cold-start Library play that used to fall into a hopeless 280-item scan),
        // then the portal's own search / bounded scan as the true cold miss.
        rowCache[rowKey(acc.id, "vod", streamId)]?.str("cmd")
            ?: IptvContentDb.vodRow(acc.id, streamId)?.cmd
            ?: row(acc, "vod", streamId, nameHint)?.str("cmd")

    private suspend fun seriesCmd(acc: XtreamAccount, seriesId: Int, nameHint: String? = null): String? =
        row(acc, "series", seriesId, nameHint)?.str("cmd")

    /**
     * The browse row for ONE item. `get_ordered_list` already returns each item's `cmd` (the
     * create_link input), so [orderedList] caches every row it sees and playing anything you browsed
     * or searched costs ZERO extra requests.
     *
     * This used to re-page the ENTIRE catalog (genre=*, up to [MAX_PAGES] requests) per lookup — one
     * tap = ~200 requests — which is what got a real portal's Cloudflare to block the whole IP. The
     * cold-start miss (play straight from Library/Continue Watching) still scans, but stops at the
     * match instead of slurping everything first.
     */
    private suspend fun row(acc: XtreamAccount, type: String, id: Int, nameHint: String? = null): JsonObject? =
        rowCache[rowKey(acc.id, type, id)]
            // Cold start (play straight from Library/Continue Watching): ask the PORTAL to find it by
            // name — 1-2 requests. Scanning is hopeless at 63k movies / 4,509 pages.
            ?: nameHint?.takeIf { it.isNotBlank() }?.let { name ->
                orderedList(acc, type, null, search = name, maxItems = SEARCH_ITEMS,
                    stopWhen = { it.int("id") == id }).firstOrNull { it.int("id") == id }
            }
            ?: orderedList(acc, type, null, maxItems = FALLBACK_SCAN_ITEMS,
                stopWhen = { it.int("id") == id }).firstOrNull { it.int("id") == id }

    private fun rowKey(accId: String, type: String, id: Int) = "$accId:$type:$id"

    private fun cacheRows(accId: String, type: String, rows: List<JsonObject>) {
        // ponytail: crude cap, not an LRU — a full catalog is ~26k rows and we only need what was
        // actually browsed. Swap in an LRU only if this ever shows up in a memory profile.
        if (rowCache.size > MAX_CACHED_ROWS) rowCache.clear()
        rows.forEach { r ->
            r.int("id")?.let {
                rowCache[rowKey(accId, type, it)] = r
                rememberLinkFlags(accId, type, r, it)
            }
        }
    }

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
    private suspend fun orderedList(
        acc: XtreamAccount,
        type: String,
        categoryId: String?,
        search: String? = null,
        maxItems: Int = MAX_ITEMS,
        stopWhen: ((JsonObject) -> Boolean)? = null,
    ): List<JsonObject> {
        val session = sessionFor(acc)
        val out = ArrayList<JsonObject>()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.size < total && out.size < maxItems && page <= MAX_PAGES) {
            val params = buildMap {
                put("type", type)
                put("action", "get_ordered_list")
                put("genre", categoryId ?: "*")
                if (type != "itv") put("category", categoryId ?: "*")
                search?.let { put("search", it) }
                put("p", page.toString())
                put("sortby", "number")
            }
            val obj = runCatching { session.request(params) }.getOrNull() as? JsonObject ?: break
            total = obj.int("total_items") ?: obj.int("max_page_items")?.let { it * MAX_PAGES } ?: out.size
            val data = obj["data"] as? JsonArray ?: break
            if (data.isEmpty()) break
            val rows = data.mapNotNull { it as? JsonObject }
            // Every row carries its `cmd` — keep them so play/detail never re-pages to find one.
            cacheRows(acc.id, type, rows)
            out += rows
            if (stopWhen != null && rows.any(stopWhen)) break   // found the target — stop paging
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

    /** Portal flags arrive as booleans, numbers or quoted strings — like the tv_archive parse.
     *  Null = the key is absent (or unreadable), which callers treat as "no evidence". */
    private fun JsonObject.flag(key: String): Boolean? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return when (val s = prim.contentOrNull?.trim()?.lowercase()) {
            null, "" -> null
            "true" -> true
            "false" -> false
            else -> s.toIntOrNull()?.let { it != 0 }
        }
    }

    private const val MAX_ITEMS = 8000
    private const val MAX_PAGES = 200
    private const val SEARCH_ITEMS = 100  // search results: a page or two is plenty
    private const val MAX_CACHED_ROWS = 10_000

    // A hub category is ONE horizontal poster row (no see-all), and this portal serves get_ordered_list
    // 14 rows a page — so paging a 5,000-movie category cost ~200 requests to fill a row nobody scrolls
    // to the end of. 70 items = 5 requests.
    // ponytail: fixed cap, not incremental paging. If a row ever needs to go deeper, page it on demand
    // as the row scrolls rather than raising this.
    private const val CATEGORY_ITEMS = 70

    // Last-resort scan when we know an id but have no cached row and no name to search by. Bounded
    // because it's near-useless at portal scale (63k movies / 14 per page = 4,509 pages): it can only
    // ever cover the first slice, so let it fail fast instead of firing 200 requests to still miss.
    private const val FALLBACK_SCAN_ITEMS = 280

    // get_epg_info window + how long the stored guide stays fresh. 3h covers now/next comfortably;
    // re-ingested every 30 min so "now" keeps up. EPG_FLUSH bounds ingest memory to one chunk.
    private const val EPG_PERIOD_HOURS = "3"
    private const val EPG_TTL_MS = 30 * 60 * 1000L
    private const val EPG_FLUSH = 2_000

    // How long the mirrored live lineup stays fresh. Refreshed ONLY from a foreground browse
    // (a background Stalker sync would evict the other device on a shared MAC); 12h matches the
    // M3U catalog's cadence.
    private const val LINEUP_TTL_MS = 12L * 60 * 60 * 1000
    private val SEASON_NAME = Regex("""season\s*(\d+)""", RegexOption.IGNORE_CASE)
}
