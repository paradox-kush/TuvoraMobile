package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.MetaSourceProvider
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository

/**
 * Fork-side [MetaSourceProvider]: the Xtream/Stalker/M3U native-detail build that used to live in
 * MetaDetailsRepository. Behaviour is identical to the code it replaced (the four methods below are a
 * verbatim move) — same vodInfo/seriesInfo fetch, container-extension handling, Stalker blank
 * placeholder + fresh create_link mint, and TMDB enrichment. MetaDetailsRepository keeps only its
 * UI-state management and delegates the build here.
 */
internal object XtreamMetaSource : MetaSourceProvider {
    private val log = Logger.withTag("XtreamMetaSource")

    override fun handlesId(id: String): Boolean = XtreamItemRegistry.isXtreamId(id)

    override suspend fun buildNativeMeta(id: String): MetaDetails? {
        XtreamRepository.ensureLoaded()
        val parsed = XtreamItemRegistry.parseId(id) ?: return null
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return null
        return when (parsed.kind) {
            XtreamKind.SERIES -> buildXtreamSeriesMeta(id, parsed.accountId, account, parsed.id.toIntOrNull())
            else -> buildXtreamVodMeta(id, parsed.accountId, account, parsed.id.toIntOrNull())
        }
    }

    private suspend fun buildXtreamVodMeta(
        id: String,
        accountId: String,
        account: com.nuvio.app.features.iptv.XtreamAccount,
        streamId: Int?,
    ): MetaDetails? {
        if (streamId == null) return null
        val client = com.nuvio.app.features.iptv.IptvClient.forAccount(account)
        val registered = XtreamItemRegistry.getOrLoad(id)
        val detail = client.vodInfo(account, streamId).getOrNull()
        val name = xtreamMetaName(detail?.name, registered?.name, "Movie")
        val poster = registered?.poster
        // ponytail: prefer the real container_extension from vod_info over a stale registered URL.
        // The browse/list flow registers a ".mp4" URL (the list endpoint omits container_extension);
        // a wrong ".mp4" makes the Download save a mis-named/broken file the local player can't load (B11).
        // M3U's movieStreamUrl returns "" (URL is a stored line, not a template), so fall to the
        // registered URL, then the DB read — never a synthesized Xtream-shaped URL.
        val templated = detail?.containerExtension?.takeIf { it.isNotBlank() }
            ?.let { client.movieStreamUrl(account, streamId, it) }?.takeIf { it.isNotBlank() }
        // Stalker registers a blank placeholder — create_link is single-use, so the real URL is
        // resolved fresh at play via ensureXtreamStreamRegistered (mirrors the live async seam).
        val streamUrl = if (account.sourceType == com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER) {
            ""
        } else {
            templated
                ?: registered?.streamUrl
                ?: (if (account.sourceType == com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL)
                        com.nuvio.app.features.iptv.M3UClient.movieUrlFor(account, streamId)
                    else null)
                ?: client.movieStreamUrl(account, streamId, "mp4").takeIf { it.isNotBlank() }
                ?: return null
        }
        XtreamItemRegistry.register(XtreamResolvedItem(id, accountId, XtreamKind.VOD, name, streamUrl, poster))

        var meta = MetaDetails(
            id = id,
            type = "movie",
            name = name,
            poster = poster,
            description = detail?.plot,
            genres = detail?.genres ?: emptyList(),
            imdbRating = detail?.rating,
            releaseInfo = detail?.releaseDate?.take(4)?.ifBlank { null },
        )
        detail?.tmdbId?.let { meta = enrichXtreamMeta(meta, it) }
        return meta
    }

    /**
     * Rebuilds and re-registers the direct VOD stream item for a persisted `xtream:...:vod:...`
     * id whose in-memory registry entry was lost (e.g. Continue Watching / Library after a fresh
     * launch). Fetches vodInfo so the real container_extension is used instead of the "mp4"
     * default. Returns true once a playable item is registered. Called from the direct-play path
     * (StreamsRepository) so it doesn't have to go through the detail screen first.
     */
    override suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(30_000) { registerStreamWithinDeadline(id, forceFresh, forceMint) } ?: false

    private suspend fun registerStreamWithinDeadline(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean {
        // A blank registered URL is a Stalker placeholder — fall through to resolve it fresh.
        // [forceFresh] skips the cache short-circuit: Stalker create_link URLs are single-use /
        // short-TTL, so a replay (or a mid-playback 401) must mint a NEW link even though the
        // registry still holds the previous, already-consumed one. [forceMint] additionally
        // bypasses the static-cmd verdict (StalkerPlaybackLinkPolicy): the in-player 401 refresh
        // must not rebuild the very static URL that just died.
        val existing = XtreamItemRegistry.getOrLoad(id)
        if (!forceFresh && existing != null && !existing.streamUrl.isNullOrBlank()) return true
        XtreamRepository.ensureLoaded()
        val parsed = XtreamItemRegistry.parseId(id) ?: return false
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return false

        // Stalker resolves a FRESH single-use create_link at play time for both movies and episodes
        // (the episode id is "{seriesId}_{episodeNum}"). Registered blank -> real URL, then play.
        if (account.sourceType == com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER) {
            val stalker = com.nuvio.app.features.iptv.stalker.StalkerClient
            val url = when (parsed.kind) {
                XtreamKind.VOD -> parsed.id.toIntOrNull()?.let { stalker.resolveMovieUrl(account, it, existing?.name, forceMint) }
                XtreamKind.EPISODE -> {
                    // "{seriesId}_{season}_{episode}"; a legacy 2-part id has no season -> null.
                    val parts = parsed.id.split("_")
                    val seriesId = parts.getOrNull(0)?.toIntOrNull()
                    val season = if (parts.size >= 3) parts.getOrNull(1)?.toIntOrNull() else null
                    val episodeNum = (if (parts.size >= 3) parts.getOrNull(2) else parts.getOrNull(1))?.toIntOrNull()
                    if (seriesId != null && episodeNum != null) {
                        stalker.resolveEpisodeUrl(account, seriesId, season, episodeNum)
                    } else null
                }
                else -> null
            } ?: return false
            XtreamItemRegistry.register(
                XtreamResolvedItem(id, parsed.accountId, parsed.kind, existing?.name ?: "Video", url, existing?.poster)
            )
            return true
        }

        // Xtream / M3U: VOD only (episodes carry stable URLs registered at detail build).
        if (parsed.kind != XtreamKind.VOD) return false
        val streamId = parsed.id.toIntOrNull() ?: return false
        val client = com.nuvio.app.features.iptv.IptvClient.forAccount(account)
        val detail = client.vodInfo(account, streamId).getOrNull()
        // ponytail: vodInfo gives the correct container_extension; fall back to mp4 only if absent.
        // M3U's movieStreamUrl is "" (stored line), so read the DB URL instead.
        val streamUrl = client.movieStreamUrl(account, streamId, detail?.containerExtension ?: "mp4")
            .takeIf { it.isNotBlank() }
            ?: (if (account.sourceType == com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL)
                    com.nuvio.app.features.iptv.M3UClient.movieUrlFor(account, streamId)
                else null)
            ?: return false
        XtreamItemRegistry.register(
            XtreamResolvedItem(id, parsed.accountId, XtreamKind.VOD, detail?.name ?: "Movie", streamUrl)
        )
        return true
    }

    private suspend fun buildXtreamSeriesMeta(
        id: String,
        accountId: String,
        account: com.nuvio.app.features.iptv.XtreamAccount,
        seriesId: Int?,
    ): MetaDetails? {
        if (seriesId == null) return null
        val isM3u = account.sourceType == com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
        val client = com.nuvio.app.features.iptv.IptvClient.forAccount(account)
        val registered = XtreamItemRegistry.getOrLoad(id)
        val detail = client.seriesInfo(account, seriesId).getOrNull()
        val videos = detail?.episodes.orEmpty().map { ep ->
            val episodeContentId = XtreamItemRegistry.episodeId(accountId, ep.episodeId)
            // Xtream builds the episode URL from its id; M3U reads the stored line from the DB.
            val episodeUrl = if (isM3u) {
                com.nuvio.app.features.iptv.M3UClient.episodeUrlFor(account, ep.episodeId) ?: ""
            } else {
                client.episodeStreamUrl(account, ep.episodeId, ep.containerExtension ?: "mp4")
            }
            XtreamItemRegistry.register(
                XtreamResolvedItem(episodeContentId, accountId, XtreamKind.EPISODE, ep.title, episodeUrl)
            )
            MetaVideo(
                id = episodeContentId,
                title = ep.title,
                season = ep.season,
                episode = ep.episodeNum,
                overview = ep.plot,
                thumbnail = ep.still,
                // A blank URL (Stalker placeholder, or an M3U episode with no stored line) means "no
                // direct stream" — leave streams empty so the play flow resolves it via the xtream-id
                // miss path (ensureXtreamStreamRegistered) instead of trying to play "".
                streams = if (episodeUrl.isBlank()) emptyList() else listOf(
                    XtreamResolvedItem(episodeContentId, accountId, XtreamKind.EPISODE, ep.title, episodeUrl)
                        .toStreamItem(account.name, StreamUserAgentPolicy.resolve(account))!!
                ),
            )
        }
        val name = xtreamMetaName(detail?.name, registered?.name, "Series")
        var meta = MetaDetails(
            id = id,
            type = "series",
            name = name,
            poster = registered?.poster ?: detail?.poster,
            description = detail?.plot,
            genres = detail?.genres ?: emptyList(),
            imdbRating = detail?.rating,
            videos = videos,
        )
        detail?.tmdbId?.let { meta = enrichXtreamMeta(meta, it) }
        return meta
    }

    private suspend fun enrichXtreamMeta(meta: MetaDetails, tmdbId: Int): MetaDetails {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled) return meta
        return runCatching { TmdbMetadataService.enrichMeta(meta, "tmdb:$tmdbId", settings) }
            .onFailure { log.w { "Xtream TMDB enrichment failed for tmdb:$tmdbId: ${it.message}" } }
            .getOrDefault(meta)
    }
}

/**
 * The detail page's title: the catalog's name, else the registered one, else the generic [fallback]. A
 * BLANK name falls through too (device pass T2: a blank one left the page untitled). Pure.
 */
internal fun xtreamMetaName(detail: String?, registered: String?, fallback: String): String =
    detail?.takeIf { it.isNotBlank() } ?: registered?.takeIf { it.isNotBlank() } ?: fallback
