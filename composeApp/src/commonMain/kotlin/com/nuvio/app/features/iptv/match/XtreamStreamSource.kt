package com.nuvio.app.features.iptv.match

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.CONTENT_TYPE_MOVIES
import com.nuvio.app.features.iptv.CONTENT_TYPE_SERIES
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.iptv.XtreamMovie
import com.nuvio.app.features.iptv.XtreamSeriesItem
import com.nuvio.app.features.iptv.stalker.StalkerClient
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbTitleBundle

/**
 * Turns a TMDB movie/episode into playable Xtream [StreamItem]s for one account —
 * the bridge that lets IPTV VOD show up next to addon/debrid streams on TMDB-driven
 * detail screens. Returns empty (never throws) when the account doesn't carry the title.
 */
internal object XtreamStreamSource {
    private val log = Logger.withTag("XtreamStreamSource")

    /** Prefix of every matched-lane group id — lets the player recognise an iptv-matched stream
     *  (and recover its account) from `activeProviderAddonId` alone. */
    const val GROUP_ID_PREFIX = "xtream-match:"

    fun groupId(acc: XtreamAccount): String = "$GROUP_ID_PREFIX${acc.id}"

    /**
     * Scheme for a Stalker source whose play link has NOT been minted yet.
     *
     * A MAG box calls create_link once, when the viewer presses play. We used to call it while
     * merely BUILDING the source list — up to [MAX_STALKER_EDITIONS] per account, so opening one
     * movie with three portals fired a dozen. Panels register a session/connection per created
     * link, and lines are commonly sold with max_connections=1, so the eager calls could occupy
     * the very slot the real playback then needed and the stream came back 401. Listing is now
     * free; [resolveDeferredUrl] mints exactly one link, for the edition actually chosen.
     */
    private const val DEFERRED_PREFIX = "stalker-deferred:"

    private fun deferredMovie(acc: XtreamAccount, streamId: Int, name: String): String =
        "$DEFERRED_PREFIX${acc.id}|movie|$streamId|${name.replace('|', ' ')}"

    private fun deferredEpisode(acc: XtreamAccount, seriesId: Int, season: Int, episode: Int): String =
        "$DEFERRED_PREFIX${acc.id}|episode|$seriesId|$season|$episode"

    fun isDeferred(url: String?): Boolean = url != null && url.startsWith(DEFERRED_PREFIX)

    /**
     * Mints the real play link for a [isDeferred] URL. Returns null when the account is gone or the
     * portal won't issue a link (callers surface that as an unplayable source).
     *
     * [forceMint] — the 401-refresh path only: bypass a static-cmd verdict (the static URL just
     * died); the normal pick-time resolve keeps the policy's static shortcut.
     */
    suspend fun resolveDeferredUrl(url: String, forceMint: Boolean = false): String? {
        if (!isDeferred(url)) return url
        val d = parseDeferred(url) ?: return null
        com.nuvio.app.features.iptv.XtreamRepository.ensureLoaded()
        val acc = com.nuvio.app.features.iptv.XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == d.accountId } ?: return null
        return if (d.isMovie) {
            StalkerClient.resolveMovieUrl(acc, d.a, d.name, forceMint)
        } else {
            StalkerClient.resolveEpisodeUrl(acc, d.a, d.b, d.c)
        }
    }

    internal data class Deferred(
        val accountId: String,
        val isMovie: Boolean,
        val a: Int,           // movie streamId, or series id
        val b: Int = 0,       // season
        val c: Int = 0,       // episode
        val name: String? = null,
    )

    /**
     * Pure parse of a deferred URL. Split out so the tricky part is testable without a portal: a
     * Stalker account id is ITSELF pipe-delimited ("stalker|http://host|MAC"), so this anchors on
     * the kind marker instead of splitting blindly, and everything before it is the account id.
     */
    internal fun parseDeferred(url: String): Deferred? {
        if (!isDeferred(url)) return null
        val parts = url.removePrefix(DEFERRED_PREFIX).split("|")
        val kindIdx = parts.indexOfFirst { it == "movie" || it == "episode" }
        if (kindIdx <= 0) return null
        val accountId = parts.subList(0, kindIdx).joinToString("|")
        if (accountId.isBlank()) return null
        return if (parts[kindIdx] == "movie") {
            Deferred(
                accountId = accountId,
                isMovie = true,
                a = parts.getOrNull(kindIdx + 1)?.toIntOrNull() ?: return null,
                name = parts.getOrNull(kindIdx + 2),
            )
        } else {
            Deferred(
                accountId = accountId,
                isMovie = false,
                a = parts.getOrNull(kindIdx + 1)?.toIntOrNull() ?: return null,
                b = parts.getOrNull(kindIdx + 2)?.toIntOrNull() ?: return null,
                c = parts.getOrNull(kindIdx + 3)?.toIntOrNull() ?: return null,
            )
        }
    }

    suspend fun streamsFor(acc: XtreamAccount, type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> {
        val kind = when (TmdbService.normalizeMediaType(type)) {
            "movie" -> MatchKind.MOVIE
            "tv" -> MatchKind.SERIES
            else -> return emptyList()
        }
        // B19: a content type switched off (or set to "no categories") in this playlist's settings
        // offers no sources at all, so skip the TMDB and provider work outright.
        if (!offersKind(acc, kind)) return emptyList()
        val tmdbId = TmdbService.ensureTmdbId(videoId, type)?.toIntOrNull() ?: run {
            log.w { "skip $videoId: no TMDB id (missing API key or unknown id)" }
            return emptyList()
        }
        val titles = TmdbService.titleBundle(tmdbId, type) ?: run {
            log.w { "skip tmdb=$tmdbId: title bundle unavailable (API key/network)" }
            return emptyList()
        }
        // Stalker has no match index — the resolver builds one from player_api bulk lists a portal
        // doesn't have, and paging its 63k-movie catalog is what got a portal to ban us. Instead ask
        // the PORTAL to find the title (get_ordered_list&search=, 1-2 requests).
        if (acc.sourceType == SOURCE_TYPE_STALKER) return stalkerStreams(acc, kind, titles, season, episode)

        // Series need season-aware gathering across entries (split-season panels give each season
        // its own id), so they don't go through the single-match movie path.
        if (kind == MatchKind.SERIES) return seriesStreams(acc, tmdbId, titles, season, episode)

        val match = XtreamTmdbResolver.resolve(acc, kind, tmdbId, titles) ?: return emptyList()

        return when (kind) {
            MatchKind.LIVE -> emptyList()   // live never TMDB-resolves; the guide plays directly
            MatchKind.MOVIE -> {
                // catalogs carry several editions (4K/HD/language) of the same film —
                // surface them all: by shared tmdb id where the panel provides ids, else
                // by shared normalized name (year-guarded; the verified match stays first)
                val editions = movieEditions(acc, XtreamMatchIndex.byTmdb(acc.id, kind, tmdbId)) {
                    sameNameEditions(acc.id, kind, match.item, titles.year)
                }
                editions.map { item ->
                    // items synthesized from a synced mapping have no container ext — look it
                    // up so the stream URL is right on panels that don't use mp4
                    val ext = item.ext ?: XtreamClient.vodInfo(acc, item.sid).getOrNull()?.containerExtension ?: "mp4"
                    StreamItem(
                        // the panel's own catalog name — carries the useful bits (4K/NF/language)
                        name = item.name,
                        title = null,
                        url = XtreamClient.movieStreamUrl(acc, item.sid, ext),
                        addonName = acc.name,
                        addonId = groupId(acc),
                        deviceSourceKind = "iptv", deviceSourceRef = acc.id,
                    )
                }
            }
            MatchKind.SERIES -> emptyList()   // handled by seriesStreams (returned above)
        }
    }

    /**
     * Series episodes for a TMDB (show, season, episode) over an Xtream panel. Unlike the movie
     * path this can't rely on one id per show: split-season panels (e.g. xsc.loruhon.com) publish
     * each season as its OWN series_id named "<Show> S<n> <lang>", with episodes flattened to an
     * internal season 1. So we gather every entry for the show by its base name key (plus any
     * tmdb-id and the verified match), then let [XtreamSeriesEpisodePolicy] read the real season
     * from each entry's name and pick episodes accordingly. Whole-series panels fall through the
     * same policy unchanged (no name-season -> real internal-season match).
     *
     * Year is deliberately NOT gated here: a split entry carries its SEASON's air year, not the
     * show's first-air year, so year-guarding would drop later seasons (same reasoning as the
     * Stalker series path).
     */
    private suspend fun seriesStreams(
        acc: XtreamAccount,
        tmdbId: Int,
        titles: TmdbTitleBundle,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val s = season ?: return emptyList()
        val e = episode ?: return emptyList()
        val entries = LinkedHashSet<IndexedItem>()
        // resolve() contributes the verified/synced entry (and keeps the cross-device cache warm);
        // it may be null on split panels where the earliest season's year misses the show's first-
        // air year — that's fine, the name probe below is the real source of truth for series.
        XtreamTmdbResolver.resolve(acc, MatchKind.SERIES, tmdbId, titles)?.let { entries.add(it.item) }
        entries.addAll(XtreamMatchIndex.byTmdb(acc.id, MatchKind.SERIES, tmdbId))
        for (key in listOfNotNull(titles.primary, titles.original)
            .map { TitleNormalizer.normKey(it) }.filter { it.isNotEmpty() }.toSet()
        ) {
            entries.addAll(XtreamMatchIndex.probe(acc.id, MatchKind.SERIES, key))
        }
        val editions = seriesEditions(acc, entries.toList(), s)
        return editions.flatMap { ed ->
            val detail = XtreamClient.seriesInfo(acc, ed.sid).getOrNull() ?: return@flatMap emptyList<StreamItem>()
            XtreamSeriesEpisodePolicy.pickEpisodes(ed, detail.episodes, s, e).map { ep ->
                StreamItem(
                    name = "S${s}E${e} · ${ep.title}",
                    // the edition's catalog name so language/season variants are tellable apart
                    title = ed.name,
                    url = XtreamClient.episodeStreamUrl(acc, ep.episodeId, ep.containerExtension ?: "mp4"),
                    addonName = acc.name,
                    addonId = groupId(acc),
                        deviceSourceKind = "iptv", deviceSourceRef = acc.id,
                )
            }
        }
    }

    /**
     * Stalker VOD/series for a TMDB title, via the portal's own search. Panels ship no tmdb ids, so the
     * match is name-key equality + a year guard — the same rule [sameNameEditions] uses for id-less
     * panels. Series resolve the real season/episode: a portal models a series as a two-level tree and
     * [StalkerClient.seriesInfo] walks it, so a TMDB S/E maps exactly.
     */
    private suspend fun stalkerStreams(
        acc: XtreamAccount,
        kind: MatchKind,
        titles: TmdbTitleBundle,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val query = titles.primary?.takeIf { it.isNotBlank() } ?: return emptyList()
        // B122: matched under the SAME keys the Xtream index uses (StalkerTitleMatchPolicy), so
        // "EN - The Matrix (1999)" is the TMDB "The Matrix" here exactly as it is for Xtream.
        val wantKeys = StalkerTitleMatchPolicy.wantKeys(listOf(titles.primary, titles.original))
        if (wantKeys.isEmpty()) return emptyList()

        return when (kind) {
            MatchKind.LIVE -> emptyList()   // live never TMDB-resolves; the guide plays directly
            MatchKind.MOVIE ->
                stalkerMovieEditions(acc, StalkerClient.searchMovies(acc, query), wantKeys, titles.year)
                    .map { movie ->
                        StreamItem(
                            name = movie.name,    // the portal's own name — carries 4K/language/etc
                            title = null,
                            // DEFERRED — see [deferredMovie]. create_link runs when the user picks
                            // this edition, not while the list is merely being built.
                            url = deferredMovie(acc, movie.streamId, movie.name),
                            addonName = acc.name,
                            addonId = groupId(acc),
                        deviceSourceKind = "iptv", deviceSourceRef = acc.id,
                        )
                    }

            MatchKind.SERIES -> {
                val s = season ?: return emptyList()
                val e = episode ?: return emptyList()
                // Year is NOT guarded here: a panel names a series "Breaking Bad", rarely with a year,
                // and TMDB's year is the FIRST-air year — guarding would drop later-season matches.
                stalkerSeriesEditions(acc, StalkerClient.searchSeries(acc, query), wantKeys, s)
                    .map { series ->
                        val url = deferredEpisode(acc, series.seriesId, s, e)
                        StreamItem(
                            // The card renders `name` only (StreamItem.streamLabel) — `title` is never
                            // drawn — so the portal's own name has to live here, exactly like the movie
                            // branch. Stalker episode titles are generic ("Episode 7"), so the series
                            // name is what actually distinguishes editions ("Breaking Bad (Hindi)").
                            name = "${series.name} · S${s}E${e}",
                            title = series.name,
                            url = url,
                            addonName = acc.name,
                            addonId = groupId(acc),
                        deviceSourceKind = "iptv", deviceSourceRef = acc.id,
                        )
                    }
            }
        }
    }

    // --- B19: which candidates this playlist may offer -------------------------------------------
    // Pure decisions over already-fetched candidates, so they test without TMDB, the index or a
    // portal. Every one filters through [IptvSourceCategoryPolicy] BEFORE its cap, and always AFTER
    // resolve(): the resolver's tmdb->sid mappings sync to every device on the provider and must stay
    // selection-neutral.

    /** False when the playlist's settings switch [kind] off entirely — no source lookup at all. */
    internal fun offersKind(acc: XtreamAccount, kind: MatchKind): Boolean =
        IptvSourceCategoryPolicy.offers(acc, IptvSourceCategoryPolicy.typeOf(kind))

    /**
     * Movie editions to offer: the tmdb-id editions in allowed categories, else the allowed
     * same-name editions. A resolved match in a hidden category therefore still lets its allowed
     * siblings through. An item whose category is unknown (a synced mapping with no local index)
     * is not offered under a partial selection — the same rule browse and search apply.
     */
    internal suspend fun movieEditions(
        acc: XtreamAccount,
        byTmdb: List<IndexedItem>,
        sameName: suspend () -> List<IndexedItem>,
    ): List<IndexedItem> {
        fun allowed(items: List<IndexedItem>) =
            IptvSourceCategoryPolicy.keep(acc, CONTENT_TYPE_MOVIES, items) { it.categoryId }
        return allowed(byTmdb).ifEmpty { allowed(sameName()) }
    }

    /** Series editions for [season]: hidden ones are dropped BEFORE the cap, so they can't crowd
     *  allowed ones out of it (one get_series_info per edition — the cap bounds that). */
    internal fun seriesEditions(acc: XtreamAccount, entries: List<IndexedItem>, season: Int): List<IndexedItem> =
        XtreamSeriesEpisodePolicy.editionsForSeason(
            IptvSourceCategoryPolicy.keep(acc, CONTENT_TYPE_SERIES, entries) { it.categoryId },
            season,
        ).take(MAX_SERIES_EDITIONS)

    /** Stalker movie editions: index-key + year match (B122), category-filtered, then capped
     *  (a catalog carries 4K/HD/language cuts of one film). */
    internal fun stalkerMovieEditions(
        acc: XtreamAccount,
        results: List<XtreamMovie>,
        wantKeys: Set<String>,
        year: Int?,
    ): List<XtreamMovie> = IptvSourceCategoryPolicy.keepCapped(
        acc, CONTENT_TYPE_MOVIES,
        results.filter { StalkerTitleMatchPolicy.movieMatches(it.name, wantKeys, year) },
        cap = MAX_STALKER_EDITIONS,
    ) { it.categoryId }

    /** Stalker series editions: name-key match, category-filtered, then capped (language cuts such
     *  as "Breaking Bad (Hindi)" are separate entries). */
    internal fun stalkerSeriesEditions(
        acc: XtreamAccount,
        results: List<XtreamSeriesItem>,
        wantKeys: Set<String>,
        season: Int,
    ): List<XtreamSeriesItem> = IptvSourceCategoryPolicy.keepCapped(
        acc, CONTENT_TYPE_SERIES,
        results.filter { StalkerTitleMatchPolicy.seriesMatches(it.name, wantKeys, season) },
        cap = MAX_STALKER_EDITIONS,
    ) { it.categoryId }

    /**
     * Editions of the same title on panels that ship no tmdb ids: items sharing the matched
     * item's normalized name key, year-compatible with the target. The verified match leads.
     */
    private suspend fun sameNameEditions(provider: String, kind: MatchKind, matched: IndexedItem, targetYear: Int?): List<IndexedItem> {
        val key = TitleNormalizer.normKey(matched.name)
        if (key.isEmpty()) return listOf(matched)
        val siblings = XtreamMatchIndex.probe(provider, kind, key).filter {
            it.year == null || targetYear == null || (if (it.year > targetYear) it.year - targetYear else targetYear - it.year) <= 1
        }
        return (listOf(matched) + siblings).distinctBy { it.sid }
    }

    internal const val MAX_SERIES_EDITIONS = 5
    internal const val MAX_STALKER_EDITIONS = 5
}
