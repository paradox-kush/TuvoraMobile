package com.nuvio.app.features.iptv

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentKind
import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.match.IptvSourceCategoryPolicy
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import com.nuvio.app.features.iptv.overlay.CategoryOverlay
import com.nuvio.app.features.iptv.overlay.IptvHiddenItems
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.app.features.iptv.overlay.IptvOverlayRepository
import com.nuvio.app.features.iptv.stalker.StalkerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Xtream has no search API. Movies + series are served from the persistent SQLite match
 * index (the same one TMDB->stream matching builds: full catalog, 24h TTL, survives
 * restarts) — no re-downloading 50k+ item lists into RAM per session. Live channels
 * aren't in that index, so they keep the fetch-once-per-session RAM path.
 */
object XtreamSearchIndex {

    private val channelCache = mutableMapOf<String, List<XtreamChannel>>()
    private val channelJobs = mutableMapOf<String, Deferred<List<XtreamChannel>>>()
    /** "accountId|type" -> category names, read only when the overlay hides some category (F01). */
    private val categoryNameCache = mutableMapOf<String, List<IptvHiddenItemsPolicy.NamedCategory>>()
    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // a cold index build (huge catalogs) shouldn't stall a keystroke forever; an already
    // built index responds instantly, a building one fills in on a later keystroke
    private const val INDEX_WAIT_MS = 12_000L

    suspend fun search(query: String): List<HomeCatalogSection> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.availableOnDevice }
        if (accounts.isEmpty()) return emptyList()
        IptvOverlayRepository.ensureLoaded()
        val overlay = IptvOverlayRepository.uiState.value

        val channels = mutableListOf<MetaPreview>()
        val series = mutableListOf<MetaPreview>()
        val movies = mutableListOf<MetaPreview>()
        for (account in accounts) {
            // B19: each hit honours the playlist's in-app settings — content-type toggles and the
            // per-type category include list — filtered BEFORE the per-type cap (every row carries
            // its categoryId: live, match-index, Stalker and M3U alike). F01: what the viewer hid (a
            // channel, or a whole group, on any device or the website) is left out too.
            // UX44: live dividers are dropped inside IptvChannelSearchPolicy.search.
            if (account.searchIncludesType(CONTENT_TYPE_LIVE)) {
                val matched = IptvChannelSearchPolicy.search(ensureChannels(account), q) { it.name }
                val visible = IptvHiddenItemsPolicy.visibleHits(
                    matched,
                    hiddenCategoryIds(account, CONTENT_TYPE_LIVE, overlay.categories),
                    overlay.channels,
                    categoryOf = { it.categoryId },
                    entityOf = { IptvIdentity.entityId(account.id, it.name, it.epgChannelId) },
                )
                offeredHits(account, CONTENT_TYPE_LIVE, visible) { it.categoryId }
                    .forEach {
                        XtreamItemRegistry.registerChannel(account.id, it); channels += it.toMetaPreview(account.id)
                    }
            }

            if (account.searchIncludesType(CONTENT_TYPE_MOVIES)) {
                val hits: List<XtreamMovie> = if (account.sourceType.isM3u()) {
                    // M3U catalog lives in the content DB (no TMDB match index) — substring the stored rows.
                    M3UClient.ensureIngested(account)
                    IptvContentDb.searchByName(account.id, IptvContentKind.VOD, q, scanLimit(account, CONTENT_TYPE_MOVIES))
                        .map { row -> XtreamMovie(row.sid, row.name, row.logo, row.categoryId, null, row.url, null, row.ext) }
                } else if (account.sourceType == SOURCE_TYPE_STALKER) {
                    // Stalker never enters the match index (its player_api builds fail into backoff,
                    // mirroring NuvioTV) — search the portal directly via get_ordered_list&search=.
                    StalkerClient.searchMovies(account, q)
                } else {
                    withTimeoutOrNull(INDEX_WAIT_MS) { XtreamTmdbResolver.ensureIndexed(account, MatchKind.MOVIE) }
                    XtreamMatchIndex.searchByName(account.id, MatchKind.MOVIE, q, scanLimit(account, CONTENT_TYPE_MOVIES)).map { item ->
                        XtreamMovie(
                            streamId = item.sid,
                            name = item.name,
                            poster = item.poster,
                            categoryId = item.categoryId,
                            rating = null,
                            streamUrl = XtreamClient.movieStreamUrl(account, item.sid, item.ext ?: "mp4"),
                            tmdb = item.tmdb,
                            containerExtension = item.ext,
                        )
                    }
                }
                // UX44: provider heading rows are not titles — drop them before the per-type cap.
                val shownMovies = withoutHiddenGroups(
                    account, CONTENT_TYPE_MOVIES, overlay.categories,
                    IptvSearchRowFilter.withoutDividers(hits) { it.name },
                ) { it.categoryId }
                offeredHits(account, CONTENT_TYPE_MOVIES, shownMovies) { it.categoryId }.forEach { movie ->
                    XtreamItemRegistry.registerMovie(account.id, movie)
                    movies += movie.toMetaPreview(account.id)
                }
            }

            if (account.searchIncludesType(CONTENT_TYPE_SERIES)) {
                val hits: List<XtreamSeriesItem> = if (account.sourceType.isM3u()) {
                    M3UClient.ensureIngested(account)
                    IptvContentDb.searchByName(account.id, IptvContentKind.SERIES, q, scanLimit(account, CONTENT_TYPE_SERIES))
                        .map { row -> XtreamSeriesItem(row.sid, row.name, row.logo, row.categoryId, null, null, null, null) }
                } else if (account.sourceType == SOURCE_TYPE_STALKER) {
                    StalkerClient.searchSeries(account, q)
                } else {
                    withTimeoutOrNull(INDEX_WAIT_MS) { XtreamTmdbResolver.ensureIndexed(account, MatchKind.SERIES) }
                    XtreamMatchIndex.searchByName(account.id, MatchKind.SERIES, q, scanLimit(account, CONTENT_TYPE_SERIES)).map { item ->
                        XtreamSeriesItem(
                            seriesId = item.sid,
                            name = item.name,
                            poster = item.poster,
                            categoryId = item.categoryId,
                            plot = null,
                            rating = null,
                            tmdb = item.tmdb,
                            year = item.year,
                        )
                    }
                }
                val shownSeries = withoutHiddenGroups(
                    account, CONTENT_TYPE_SERIES, overlay.categories,
                    IptvSearchRowFilter.withoutDividers(hits) { it.name },
                ) { it.categoryId }
                offeredHits(account, CONTENT_TYPE_SERIES, shownSeries) { it.categoryId }.forEach { seriesItem ->
                    XtreamItemRegistry.registerSeries(account.id, seriesItem)
                    series += seriesItem.toMetaPreview(account.id)
                }
            }
        }
        return listOfNotNull(
            section("xtream_channels", "IPTV Channels", "tv", channels),
            section("xtream_movies", "IPTV Movies", "movie", movies),
            section("xtream_series", "IPTV Series", "series", series),
        )
    }

    /** F01: hits whose group the viewer hid are left out of search. */
    private suspend fun <T> withoutHiddenGroups(
        account: XtreamAccount,
        type: String,
        overlay: Map<String, CategoryOverlay>,
        hits: List<T>,
        categoryOf: (T) -> String?,
    ): List<T> {
        if (hits.isEmpty()) return hits
        val hidden = hiddenCategoryIds(account, type, overlay)
        if (hidden.isEmpty()) return hits
        return hits.filter { val c = categoryOf(it); c == null || c !in hidden }
    }

    /** Category ids of [type] the overlay hides for [account]; names are fetched once per session. */
    private suspend fun hiddenCategoryIds(account: XtreamAccount, type: String, overlay: Map<String, CategoryOverlay>): Set<String> {
        if (overlay.values.none { it.hidden }) return emptySet()
        val key = "${account.id}|$type"
        val names = mutex.withLock { categoryNameCache[key] }
            ?: IptvHiddenItems.categoryNames(account, type).also { fetched ->
                if (fetched.isNotEmpty()) mutex.withLock { categoryNameCache[key] = fetched }
            }
        return IptvHiddenItemsPolicy.hiddenCategoryIds(account.id, type, names, overlay)
    }

    /** Hits a playlist may show for [type]: category-filtered first, then capped (B19). */
    internal fun <T> offeredHits(account: XtreamAccount, type: String, items: List<T>, categoryOf: (T) -> String?): List<T> =
        IptvSourceCategoryPolicy.keepCapped(account, type, items, PER_TYPE_CAP, categoryOf)

    /** Rows a capped local query reads: wider under a partial selection, so filtering precedes the cap. */
    internal fun scanLimit(account: XtreamAccount, type: String): Int =
        IptvSourceCategoryPolicy.scanLimit(account, type, PER_TYPE_CAP)

    internal const val PER_TYPE_CAP = 30

    private fun section(key: String, title: String, type: String, items: List<MetaPreview>): HomeCatalogSection? {
        if (items.isEmpty()) return null
        return HomeCatalogSection(
            key = key,
            title = title,
            subtitle = "IPTV",
            addonName = "IPTV",
            target = CatalogTarget.Library(contentType = type, sectionType = "xtream"),
            items = items,
            availableItemCount = items.size,
            hasMore = false,
        )
    }

    /**
     * Live channels once per account per session, in [bgScope] so a keystroke that cancels
     * the search job can't abort the fetch.
     */
    private suspend fun ensureChannels(account: XtreamAccount): List<XtreamChannel> {
        val job = mutex.withLock {
            channelJobs.getOrPut(account.id) {
                bgScope.async {
                    val result = IptvClient.forAccount(account).liveChannels(account)
                    val channels = result.getOrDefault(emptyList())
                    mutex.withLock {
                        channelCache[account.id] = channels
                        // A FAILED fetch must not poison the whole session ("live search dead
                        // until restart") — drop the job so a later search retries. A
                        // successful-but-empty list stays cached.
                        if (result.isFailure) channelJobs.remove(account.id)
                    }
                    channels
                }
            }
        }
        job.await()
        return mutex.withLock { channelCache[account.id] } ?: emptyList()
    }

    /** All live channels of one account from the session cache (fetching on first use) — the
     *  Sports Centre channel matcher's candidate pool. */
    suspend fun liveChannelsFor(account: XtreamAccount): List<XtreamChannel> = ensureChannels(account)

    fun resetForProfile() {
        channelCache.clear()
        channelJobs.clear()
        categoryNameCache.clear()
    }

    /**
     * Drops one account's cached channel list so the next search re-fetches it fresh. Used by the P3
     * auto-refresh for Xtream playlists (which are API-on-demand — there's no catalog to re-ingest, so
     * "refreshing" just means invalidating the session cache + re-warming it).
     */
    suspend fun invalidate(accountId: String) {
        mutex.withLock {
            channelCache.remove(accountId)
            channelJobs.remove(accountId)?.cancel()
            categoryNameCache.keys.removeAll { it.startsWith("$accountId|") }
        }
    }
}

/**
 * Whether search should return [type] for this playlist at all: the type is switched on and its
 * category selection is not the explicit empty "none". Per-item category filtering then happens in
 * [XtreamSearchIndex.offeredHits].
 */
internal fun XtreamAccount.searchIncludesType(type: String): Boolean = IptvSourceCategoryPolicy.offers(this, type)
