package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.EmptyResponseBodyException
import com.nuvio.app.features.iptv.match.IndexedItem
import com.nuvio.app.features.iptv.match.TitleNormalizer
import com.nuvio.app.features.iptv.match.XtreamCatalogIndexParser
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
object XtreamClient : IptvClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies credentials. Success only when the panel reports auth=1 and an active status.
     *
     * UX20: transport failures (DNS, refused, timeout, TLS) and HTTP statuses PROPAGATE — the user
     * must be told the server is unreachable, not that their password is wrong. Only a panel that
     * answered (with `auth != 1`, an unparseable body, or an empty one — how some panels say "no")
     * is a credential rejection.
     */
    override suspend fun verify(acc: XtreamAccount): Result<Unit> = call {
        // Through the backup-server walk (Step 0.3); a panel that answered "no" (empty body, auth != 1,
        // or a definitive refusal on every server) is a credential rejection, transport failures propagate.
        val body = try {
            loginText(acc)
        } catch (e: EmptyResponseBodyException) {
            throw XtreamAuthRejectedException()
        } catch (e: FailoverAuthRejectedException) {
            throw XtreamAuthRejectedException()
        }
        val info = runCatching { json.parseToJsonElement(body).jsonObject["user_info"] as? JsonObject }.getOrNull()
        if (info?.get("auth").asIntOrNull() != 1) throw XtreamAuthRejectedException()
        val status = info?.get("status").asStringOrNull()?.lowercase() ?: ""
        if (status.isNotEmpty() && status != "active") {
            throw XtreamAccountInactiveException(info?.get("status").asStringOrNull().orEmpty())
        }
    }

    /** Live account status: active/expired, trial flag, expiry, and current vs max connections. */
    override suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo?> = call {
        val info = userInfo(acc) ?: return@call null
        XtreamAccountInfo(
            status = info["status"].asStringOrNull(),
            isTrial = info["is_trial"].asStringOrNull() == "1",
            expiresAtEpochSec = info["exp_date"].asStringOrNull()?.toLongOrNull(),
            maxConnections = info["max_connections"].asIntOrNull(),
            activeConnections = info["active_cons"].asIntOrNull()
        )
    }

    /**
     * `user_info` object from the no-action player_api call (the login/account call — fails over to a
     * backup server, Step 0.3), parsed loosely. Any failure still reads as null, as it always has.
     */
    private suspend fun userInfo(acc: XtreamAccount): JsonObject? =
        runCatching { json.parseToJsonElement(loginText(acc)).jsonObject["user_info"] as? JsonObject }.getOrNull()

    /** The raw no-action player_api body, from whichever server answers (Step 0.3). */
    private suspend fun loginText(acc: XtreamAccount): String = panelText(acc) { a -> playerApi(a) }

    /**
     * One whole-body fail-over-able panel request (Step 0.3): [url] is rebuilt for each server the
     * [PlaylistServerFailover] walk tries, so the request lands on that server with the same path.
     */
    private suspend fun panelText(acc: XtreamAccount, url: (XtreamAccount) -> String): String =
        PlaylistServerFailover.run(acc, probe = ::failoverProbe) { a -> panelGetText(url(a), a.dnsProvider) }

    override suspend fun liveCategories(acc: XtreamAccount) = categories(acc, "get_live_categories")
    override suspend fun vodCategories(acc: XtreamAccount) = categories(acc, "get_vod_categories")
    override suspend fun seriesCategories(acc: XtreamAccount) = categories(acc, "get_series_categories")

    // Bulk lists are parsed loosely — field by field from raw JSON, not through a strict DTO.
    // Xtream panels are wildly inconsistent about types (a field is an int on one panel, a
    // quoted string on another, a bare number where a string is expected on a third — e.g.
    // onnipsite sends `rating` as `0`, not `"0"`). A strict decode throws on the FIRST such
    // field and loses the ENTIRE catalog, so the provider's index silently never builds.
    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = call {
        // The stream URLs are built on the server that served THIS list (Step 0.3).
        streamArray(acc, { a -> playerApi(a, "get_live_streams", categoryId) }) { a, o ->
            val id = o["stream_id"].asIntOrNull() ?: return@streamArray null
            XtreamChannel(
                streamId = id,
                name = o["name"].asStringOrNull() ?: "",
                logo = o["stream_icon"].asStringOrNull(),
                epgChannelId = o["epg_channel_id"].asStringOrNull(),
                categoryId = o["category_id"].asStringOrNull(),
                hasArchive = (o["tv_archive"].asIntOrNull() ?: 0) > 0,
                // String on exactly the archive-bearing rows in the field — asIntOrNull is lenient.
                catchUpDays = (o["tv_archive_duration"].asIntOrNull() ?: 0).coerceAtLeast(0),
                streamUrl = streamUrl(a, "live", id, "ts")
            )
        }.let { rows ->
            // B179: provider divider rows ("BG: #### Bulgaria Channels") are not streams.
            ChannelSeparatorPolicy.withoutSeparators(rows, { it.name }, { it.epgChannelId }, { it.hasArchive })
        }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = call {
        streamArray(acc, { a -> playerApi(a, "get_vod_streams", categoryId) }) { a, o -> parseVodItem(a, o) }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = call {
        streamArray(acc, { a -> playerApi(a, "get_series", categoryId) }) { _, o -> parseSeriesItem(o) }
    }

    /**
     * Catalog reduced to match-index rows.
     *
     * [vodMovies]/[series] stay as they are for the browse screens, which need the full model.
     * The index only stores six fields, so building the full model first cost an extra
     * whole-catalog copy in heap — including a stream URL constructed per item that the index
     * has no field for. That peak is what was getting the app lowmemorykilled on low-RAM
     * devices right after a playlist was added.
     */
    internal suspend fun vodIndexItems(acc: XtreamAccount): Result<List<IndexedItem>> = call {
        streamArray(acc, { a -> playerApi(a, "get_vod_streams") }) { _, o -> parseVodIndexItem(o) }
    }

    /** Series half of [vodIndexItems]. */
    internal suspend fun seriesIndexItems(acc: XtreamAccount): Result<List<IndexedItem>> = call {
        streamArray(acc, { a -> playerApi(a, "get_series") }) { _, o -> parseSeriesIndexItem(o) }
    }

    /**
     * [vodIndexItems], streamed: each parsed row goes straight to [onItem] and is then garbage —
     * the full catalog never exists in heap as one list. This is the index build's path: the list
     * variant peaked at the whole catalog (~40-50 MB of IndexedItem for a 175k panel) on exactly
     * the devices whose heap can't take it. Returns the delivered-row count; throws (like the list
     * variant) on a truncated body, so a partial catalog can't finalize a sync.
     */
    internal suspend fun vodIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit, categoryId: String? = null): Result<Int> = call {
        streamArrayInto(acc, { a -> playerApi(a, "get_vod_streams", categoryId) }, { o -> parseVodIndexItem(o) }, onItem)
    }

    /** Series half of [vodIndexItemsInto]. */
    internal suspend fun seriesIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit, categoryId: String? = null): Result<Int> = call {
        streamArrayInto(acc, { a -> playerApi(a, "get_series", categoryId) }, { o -> parseSeriesIndexItem(o) }, onItem)
    }

    /** One VOD list entry -> index row, skipping the domain model entirely. internal for tests. */
    internal fun parseVodIndexItem(o: JsonObject): IndexedItem? {
        val id = o["stream_id"].asIntOrNull() ?: return null
        val name = o["name"].asStringOrNull() ?: ""
        return IndexedItem(
            sid = id,
            name = name,
            year = TitleNormalizer.yearOf(name),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            ext = o["container_extension"].asStringOrNull()?.takeIf { it.isNotBlank() },
            poster = o["stream_icon"].asStringOrNull()?.takeIf { it.isNotBlank() },
            categoryId = o["category_id"].asStringOrNull(),
        )
    }

    /** One live list entry -> index row (P7: the index doubles as the browse catalog). */
    internal fun parseLiveIndexItem(o: JsonObject): IndexedItem? {
        val id = o["stream_id"].asIntOrNull() ?: return null
        return IndexedItem(
            sid = id,
            name = o["name"].asStringOrNull() ?: "",
            year = null,
            tmdb = null,
            ext = null,
            poster = o["stream_icon"].asStringOrNull()?.takeIf { it.isNotBlank() },
            categoryId = o["category_id"].asStringOrNull(),
            epgId = o["epg_channel_id"].asStringOrNull(),
            hasArchive = (o["tv_archive"].asIntOrNull() ?: 0) > 0,
        )
    }

    /** Live half of [vodIndexItemsInto]. */
    internal suspend fun liveIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit, categoryId: String? = null): Result<Int> = call {
        streamArrayInto(acc, { a -> playerApi(a, "get_live_streams", categoryId) }, { o -> parseLiveIndexItem(o) }, onItem)
    }

    /** One series list entry -> index row. internal for tests. */
    internal fun parseSeriesIndexItem(o: JsonObject): IndexedItem? {
        val id = o["series_id"].asIntOrNull() ?: return null
        val name = o["name"].asStringOrNull() ?: ""
        return IndexedItem(
            sid = id,
            name = name,
            year = (o["releaseDate"] ?: o["release_date"]).asStringOrNull()?.trim()?.take(4)?.toIntOrNull()
                ?: TitleNormalizer.yearOf(name),
            tmdb = o["tmdb"].asIntOrNull()?.takeIf { it > 0 },
            ext = null,
            poster = o["cover"].asStringOrNull()?.takeIf { it.isNotBlank() },
            categoryId = o["category_id"].asStringOrNull(),
        )
    }

    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = call {
        // panelText stays OUTSIDE the runCatching so a transport error propagates to call{}
        // and becomes Result.failure — the shape vodInfo/seriesInfo already use. Inlining it here
        // turned every timeout into Result.success(emptyList()), i.e. "the panel has no EPG".
        val text = panelText(acc) { a -> playerApi(a, "get_short_epg") + "&stream_id=$streamId&limit=$limit" }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@call emptyList()
        val rows = (root["epg_listings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Epoch-skew gate (XtreamEpochSkew): the manual per-playlist offset wins outright, and the
        // clock pair is fetched ONLY once a response has actually voted LIAR — honest panels (the
        // population) never pay a request or a changed byte for the lie, per the onnipsite probe.
        val manualOffsetMs = acc.guideEpgCorrectionMs()
        val offsetMs = when {
            manualOffsetMs != null -> manualOffsetMs
            epgSkewVerdict(rows) == XtreamEpochSkew.Verdict.LIAR -> XtreamEpochSkew.effectiveOffsetMs(
                null, XtreamEpochSkew.Verdict.LIAR, XtreamPanelClock.measuredOffsetMs(acc),
            )
            else -> 0L
        }
        rows.map { o -> parseEpgProgramme(o).shiftedBy(offsetMs) }
    }

    /**
     * One response's [XtreamEpochSkew] vote over the (start string, epoch) pairs already in its
     * rows — the wa12/onnipsite separator. internal for tests.
     */
    internal fun epgSkewVerdict(rows: List<JsonObject>): XtreamEpochSkew.Verdict =
        XtreamEpochSkew.verdictOf(
            rows.map { o ->
                o["start"].asStringOrNull() to o["start_timestamp"].asStringOrNull()?.trim()?.toLongOrNull()
            }
        )

    /**
     * One EPG row -> domain model, every field read tolerantly (the same rows come back from
     * get_short_epg AND get_simple_data_table; only the latter carries has_archive). internal
     * for tests.
     */
    internal fun parseEpgProgramme(o: JsonObject): XtreamProgram = XtreamProgram(
        title = decodeXtreamBase64(o["title"].asStringOrNull()),
        description = decodeXtreamBase64(o["description"].asStringOrNull()),
        startMs = (o["start_timestamp"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
        endMs = (o["stop_timestamp"].asStringOrNull()?.toLongOrNull() ?: 0L) * 1000,
        nowPlaying = o["now_playing"].asIntOrNull() == 1,
        // Any positive count is a mark; junk or absence stays null — silence, not "no".
        hasArchive = o["has_archive"].asIntOrNull()?.let { it > 0 },
    )

    /**
     * One channel's FULL guide table, streamed straight to [onProgramme].
     *
     * `get_short_epg` returns now-and-next only, so this is the sole source of the PAST programmes
     * a catch-up guide exists to show. It is also the biggest EPG body the app asks any panel for,
     * which is why it never lands in a String: rows are admitted or refused one at a time against
     * [CatchUpEpgPolicy]'s window, so a provider keeping a fortnight of schedule costs the same
     * heap as one keeping a day.
     *
     * Throws on a truncated or listings-less body (see [XtreamEpgTableParser.finish]) — the caller
     * replaces the channel's stored rows wholesale, so a partial answer must never look complete.
     */
    internal suspend fun simpleDataTableInto(
        acc: XtreamAccount,
        streamId: Int,
        nowMs: Long,
        catchUpDays: Int,
        onProgramme: (XtreamProgram) -> Unit,
    ): Int {
        // The stream parse can't suspend mid-body, so the clock pair is resolved up front when
        // auto-detection could need it (manual unset). Session-memoized in XtreamPanelClock —
        // usually already seeded by a liar short-EPG response or a replay's panelFacts.
        val manualOffsetMs = acc.guideEpgCorrectionMs()
        val clockPairOffsetMs = if (manualOffsetMs == null) XtreamPanelClock.measuredOffsetMs(acc) else null
        val parser = XtreamEpgTableParser(json, nowMs, catchUpDays, manualOffsetMs, clockPairOffsetMs, onProgramme)
        // Guarded like every other panel request (WP6) so the breaker counts it exactly once. Fails
        // over (Step 0.3) only until the first line reached the parser — rows already handed to
        // [onProgramme] must never be spliced with another server's.
        var delivered = false
        PlaylistServerFailover.run(acc, canRetry = { !delivered }, probe = ::failoverProbe) { a ->
            val url = playerApi(a, "get_simple_data_table") + "&stream_id=$streamId"
            IptvPanelGuard.guard.guardedPanelRequest(url) {
                IptvTransport.current.streamLines(url, userAgent = null, dnsProvider = a.dnsProvider) {
                    delivered = true
                    parser.feed(it)
                }
            }
        }
        return parser.finish()
    }

    /**
     * The panel's UTC offset measured from `server_info`'s clock PAIR, or null when the panel
     * doesn't send one.
     *
     * Replay `start` strings are interpreted in the PANEL's timezone, so a panel in New York
     * replaying a programme described in UTC lands hours off. The pair beats the panel's own
     * `timezone` field, which is routinely junk or missing — see [ServerClockOffset].
     *
     * `server_info` is deliberately read as loose JSON rather than through a DTO: panels disagree
     * about the types of its other fields (`port` arrives as a bare int), and a strict decode
     * throwing on one of them would lose the clock pair too.
     */
    internal suspend fun serverClockOffsetMs(acc: XtreamAccount): Long? = runCatching {
        val root = json.parseToJsonElement(loginText(acc)).jsonObject
        val server = root["server_info"] as? JsonObject ?: return@runCatching null
        val timestampNow = server["timestamp_now"].asStringOrNull()?.toLongOrNull() ?: return@runCatching null
        ServerClockOffset.offsetMs(server["time_now"].asStringOrNull(), timestampNow)
    }.getOrNull()

    /**
     * What the panel says it can emit (`allowed_output_formats`), for pruning the dialect ladder.
     *
     * Absent on three of three real panels measured, so "unknown = prune nothing" is the normal
     * path rather than an edge case — null here means exactly that.
     */
    internal suspend fun allowedOutputFormats(acc: XtreamAccount): List<String>? = runCatching {
        val root = json.parseToJsonElement(loginText(acc)).jsonObject
        val server = root["server_info"] as? JsonObject ?: return@runCatching null
        (server["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { it.asStringOrNull() }
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * VOD detail for synthetic-meta + TMDB enrichment. Returns null (not a failure) when the
     * panel sends `info: []` — a known quirk — so callers fall back to bare Xtream metadata.
     */
    override suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?> = call {
        val text = panelText(acc) { a -> playerApi(a, "get_vod_info") + "&vod_id=$vodId" }
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
     * Artwork for one VOD item from get_vod_info — the lazy fallback for panels whose bulk
     * list ships empty stream_icons. null = the panel has no art for it either.
     */
    suspend fun vodArtwork(acc: XtreamAccount, vodId: Int): Result<String?> = call {
        val text = panelText(acc) { a -> playerApi(a, "get_vod_info") + "&vod_id=$vodId" }
        val info = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.get("info") as? JsonObject
        info?.get("movie_image").asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: info?.get("cover_big").asStringOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Series half of [vodArtwork] (get_series_info `info.cover`). */
    suspend fun seriesArtwork(acc: XtreamAccount, seriesId: Int): Result<String?> = call {
        val text = panelText(acc) { a -> playerApi(a, "get_series_info") + "&series_id=$seriesId" }
        val info = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.get("info") as? JsonObject
        info?.get("cover").asStringOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Series detail incl. flattened episode list. Parsed leniently by hand because panels are
     * wildly inconsistent: an episode's `info` is an object on some episodes and `[]` on others
     * within the SAME series, and season/episode numbers arrive as int or quoted string. A strict
     * decode throws on the first `info: []` and loses every episode — so we walk the JSON instead.
     */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?> = call {
        val text = panelText(acc) { a -> playerApi(a, "get_series_info") + "&series_id=$seriesId" }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@call null
        val info = root["info"] as? JsonObject
        val episodes = XtreamEpisodeRows.parse(root["episodes"]).map { e ->
            XtreamEpisode(e.id, e.season, e.number, e.title, e.plot, e.still, e.extension)
        }
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

    // Stream/catch-up URLs are built on the playlist's ACTIVE server (Step 0.3) and never fail over
    // themselves: a playback failure says nothing about which server should serve the catalog.
    override fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String): String =
        streamUrl(PlaylistServerFailover.activeAccount(acc), "movie", streamId, ext.ifBlank { "mp4" })
    override fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String =
        streamUrl(PlaylistServerFailover.activeAccount(acc), "live", streamId, "ts")

    /**
     * Catch-up (tv_archive) replay URL — XUI's standard timeshift path form (the first entry of
     * [liveTimeshiftUrls]). Empty only for blank credentials, which [XtreamCatchUp.candidateUrls]
     * refuses to build garbage for — callers are Xtream-gated so it doesn't happen, but a crash
     * would be the worst answer.
     */
    fun liveTimeshiftUrl(acc: XtreamAccount, streamId: Int, startEpochMs: Long, durationMinutes: Int): String =
        liveTimeshiftUrls(acc, streamId, startEpochMs, durationMinutes).firstOrNull().orEmpty()

    /**
     * Every catch-up URL worth trying for this channel, best-known first — panels disagree about
     * the shape and none advertise which they speak, so the caller walks the list until one plays
     * (CatchUpDialectWalk owns the walking policy). The first entry is the form Tuvora has always
     * sent; the date maths and dialects live in [XtreamCatchUp] (KMP twin of NuvioTV's).
     */
    fun liveTimeshiftUrls(
        acc: XtreamAccount,
        streamId: Int,
        startEpochMs: Long,
        durationMinutes: Int,
        containerExtension: String? = null,
        serverOffsetMs: Long? = null,
    ): List<String> = XtreamCatchUp.candidateUrls(
        baseUrl = PlaylistServerFailover.activeAccount(acc).baseUrl,
        username = acc.username,
        password = acc.password,
        streamId = streamId,
        startMs = startEpochMs,
        endMs = startEpochMs + durationMinutes * 60_000L,
        containerExtension = containerExtension,
        serverOffsetMs = serverOffsetMs,
    )

    override fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String): String {
        val base = PlaylistServerFailover.activeAccount(acc).baseUrl.trimEnd('/')
        return "$base/series/${acc.username.encodeURLPathPart()}/${acc.password.encodeURLPathPart()}/$episodeId.${ext.ifBlank { "mp4" }}"
    }

    // --- internals -----------------------------------------------------------

    /**
     * The client's whole-body transport, admission-checked (WP6): every player_api request this
     * client sends funnels through here or [streamArray]/[streamArrayInto], so the per-origin
     * breaker sees each panel request exactly once. A refused admission throws the policy's
     * distinct [PanelHostFastFailException] before any bytes move.
     */
    private suspend fun panelGetText(url: String, dnsProvider: String?): String =
        IptvPanelGuard.guard.guardedPanelRequest(url) { IptvTransport.current.getText(url, dnsProvider) }

    /**
     * The failover race's validation probe (Step 0.3b): the no-action `player_api.php` login on server [a].
     * Returns normally only for a VALID answer — JSON with `user_info` and `server_info` and auth=1 on a
     * live account. A 200 that is not the panel (parked domain, CDN error page, blank body) throws
     * [FailoverInvalidResponseException] (fails over); auth=0 / Expired / Banned / Disabled throws
     * [FailoverAuthRejectedException] (the same on every server: surfaced, never failed over). Goes
     * through the same breaker-guarded transport as every panel request.
     *
     * The body is the very `user_info` [accountInfo] parses (status, exp_date, is_trial,
     * max_connections, active_cons), so the expiry reminder can reuse that mapping on it later.
     */
    internal suspend fun failoverProbe(a: XtreamAccount) {
        val body = try {
            panelGetText(playerApi(a), a.dnsProvider)
        } catch (e: com.nuvio.app.features.addons.EmptyResponseBodyException) {
            throw FailoverInvalidResponseException("Xtream login probe: empty body")
        }
        FailoverProbePolicy.toFailure(FailoverProbePolicy.xtreamLogin(body), "Xtream login probe")?.let { throw it }
    }

    private fun String.splitCsv(): List<String> = split(",").mapNotNull { it.trim().ifBlank { null } }

    private suspend fun categories(acc: XtreamAccount, action: String): Result<List<XtreamCategory>> = call {
        streamArray(acc, { a -> playerApi(a, action) }) { _, o ->
            val id = o["category_id"].asStringOrNull() ?: return@streamArray null
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

    /**
     * Fetches a bulk list and maps it element by element, never holding the response whole.
     *
     * Every `player_api.php` array endpoint goes through here. [httpGetText] used to, and on a
     * large provider its single ~27 MB body allocation was enough to OOM the match-index build
     * outright — see [XtreamCatalogIndexParser] for the failure and the shape of the fix. The
     * mapping functions are unchanged, so field-level parsing behaves exactly as before.
     *
     * User-Agent is deliberately left null: that is what the panel saw on the [httpGetText]
     * path, and a UA a user configured for their M3U host is not necessarily one the panel
     * accepts.
     */
    private suspend fun <T> streamArray(
        acc: XtreamAccount,
        url: (XtreamAccount) -> String,
        map: (XtreamAccount, JsonObject) -> T?,
    ): List<T> =
        // A catalog call fails over (Step 0.3). The list is collected by a fresh parser per server,
        // so a body that died part-way can safely be fetched whole from the next one; [map] gets the
        // server that answered, so URLs built from a row point at it.
        PlaylistServerFailover.run(acc, probe = ::failoverProbe) { a ->
            val parser = XtreamCatalogIndexParser(json, { o: JsonObject -> map(a, o) })
            val u = url(a)
            // Guarded like panelGetText (WP6). A parser throw classifies as HTTP_RESPONSE — body
            // bytes arrived, which is all the breaker measures.
            IptvPanelGuard.guard.guardedPanelRequest(u) {
                IptvTransport.current.streamLines(u, userAgent = null, dnsProvider = a.dnsProvider) { parser.accept(it) }
            }
            parser.finish()
        }

    /**
     * [streamArray] in sink mode: rows go to [onItem] as they parse; returns the count. Fails over
     * only until the first line arrived — rows already in the caller's sink can't be taken back.
     */
    private suspend fun <T> streamArrayInto(
        acc: XtreamAccount,
        url: (XtreamAccount) -> String,
        map: (JsonObject) -> T?,
        onItem: (T) -> Unit,
    ): Int {
        var delivered = false
        return PlaylistServerFailover.run(acc, canRetry = { !delivered }, probe = ::failoverProbe) { a ->
            val parser = XtreamCatalogIndexParser(json, map, sink = onItem)
            val u = url(a)
            IptvPanelGuard.guard.guardedPanelRequest(u) {
                IptvTransport.current.streamLines(u, userAgent = null, dnsProvider = a.dnsProvider) {
                    delivered = true
                    parser.accept(it)
                }
            }
            parser.finishCount()
        }
    }

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

/** The panel answered and refused these credentials (`auth != 1`). Message kept for logs only. */
internal class XtreamAuthRejectedException : IllegalStateException("Authentication failed")

/** The credentials are right but the line is not active (expired, banned, disabled). */
internal class XtreamAccountInactiveException(status: String) : IllegalStateException("Account status: $status")

internal fun XtreamEpgEntryDto.toProgram(): XtreamProgram = XtreamProgram(
    title = decodeXtreamBase64(title),
    description = decodeXtreamBase64(description),
    startMs = (startTimestamp?.toLongOrNull() ?: 0L) * 1000,
    endMs = (stopTimestamp?.toLongOrNull() ?: 0L) * 1000,
    nowPlaying = nowPlaying == 1,
    // FlexInt already coerced "1"/true; any positive count is a mark, junk decoded to null stays
    // null — silence, not "no".
    hasArchive = hasArchive?.let { it > 0 }
)

/**
 * The epoch-skew correction, applied to one programme. Only REAL epochs move: an absent timestamp
 * parses to 0, and a "corrected" 0 would be negative garbage that `actionFor`'s degenerate-row
 * guard could no longer recognise as absent. A zero shift returns the same instance — the honest
 * path stays byte-identical.
 */
internal fun XtreamProgram.shiftedBy(offsetMs: Long): XtreamProgram {
    if (offsetMs == 0L) return this
    return copy(
        startMs = if (startMs > 0) startMs + offsetMs else startMs,
        endMs = if (endMs > 0) endMs + offsetMs else endMs,
    )
}

/**
 * Xtream base64-encodes EPG title/description — except on the panels that don't.
 *
 * Catching the decoder's throw is not enough of a fallback: a SHORT plain title is frequently
 * valid base64 by accident ("News" is four characters straight from the alphabet), so the decode
 * succeeds and returns mojibake. Nothing downstream can tell that apart from a real title, and it
 * lands in the guide, the programme sheet and the now-bar.
 *
 * So the decode has to be checked rather than merely attempted: the input must actually look like
 * base64, and the RESULT must look like text. Invalid UTF-8 decodes to replacement characters
 * rather than throwing, which is exactly the case a try/catch misses.
 *
 * Returns "" on null/blank rather than throwing.
 */
internal fun decodeXtreamBase64(s: String?): String {
    if (s.isNullOrBlank()) return ""
    val trimmed = s.trim()
    if (!looksBase64(trimmed)) return trimmed
    val decoded = runCatching { trimmed.decodeBase64String() }.getOrNull() ?: return trimmed
    return if (isPlausibleText(decoded)) decoded else trimmed
}

/** Cheap shape test: anything outside the alphabet (a space, a colon) settles it immediately. */
private fun looksBase64(value: String): Boolean {
    if (value.length < 2) return false
    return value.all { it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/' || it == '=' }
}

/**
 * Whether a decode produced text rather than bytes that happen to be printable-ish. Replacement
 * characters mean the bytes were not UTF-8; C0 controls mean they were not text at all.
 */
private fun isPlausibleText(value: String): Boolean {
    if (value.isEmpty()) return false
    return value.none { it == '�' || (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') }
}
