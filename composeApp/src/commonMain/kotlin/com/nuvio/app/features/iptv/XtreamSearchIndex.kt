package com.nuvio.app.features.iptv

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
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
    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val PER_TYPE_CAP = 30

    // a cold index build (huge catalogs) shouldn't stall a keystroke forever; an already
    // built index responds instantly, a building one fills in on a later keystroke
    private const val INDEX_WAIT_MS = 12_000L

    suspend fun search(query: String): List<HomeCatalogSection> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        if (accounts.isEmpty()) return emptyList()

        val channels = mutableListOf<MetaPreview>()
        val series = mutableListOf<MetaPreview>()
        val movies = mutableListOf<MetaPreview>()
        for (account in accounts) {
            ensureChannels(account).asSequence()
                .filter { it.name.contains(q, ignoreCase = true) }.take(PER_TYPE_CAP).forEach {
                    XtreamItemRegistry.registerChannel(account.id, it); channels += it.toMetaPreview(account.id)
                }

            withTimeoutOrNull(INDEX_WAIT_MS) { XtreamTmdbResolver.ensureIndexed(account, MatchKind.MOVIE) }
            XtreamMatchIndex.searchByName(account.id, MatchKind.MOVIE, q, PER_TYPE_CAP).forEach { item ->
                val movie = XtreamMovie(
                    streamId = item.sid,
                    name = item.name,
                    poster = item.poster,
                    categoryId = null,
                    rating = null,
                    streamUrl = XtreamClient.movieStreamUrl(account, item.sid, item.ext ?: "mp4"),
                    tmdb = item.tmdb,
                    containerExtension = item.ext,
                )
                XtreamItemRegistry.registerMovie(account.id, movie)
                movies += movie.toMetaPreview(account.id)
            }

            withTimeoutOrNull(INDEX_WAIT_MS) { XtreamTmdbResolver.ensureIndexed(account, MatchKind.SERIES) }
            XtreamMatchIndex.searchByName(account.id, MatchKind.SERIES, q, PER_TYPE_CAP).forEach { item ->
                val seriesItem = XtreamSeriesItem(
                    seriesId = item.sid,
                    name = item.name,
                    poster = item.poster,
                    categoryId = null,
                    plot = null,
                    rating = null,
                    tmdb = item.tmdb,
                    year = item.year,
                )
                XtreamItemRegistry.registerSeries(account.id, seriesItem)
                series += seriesItem.toMetaPreview(account.id)
            }
        }
        return listOfNotNull(
            section("xtream_channels", "IPTV Channels", "tv", channels),
            section("xtream_movies", "IPTV Movies", "movie", movies),
            section("xtream_series", "IPTV Series", "series", series),
        )
    }

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
                    val channels = XtreamClient.liveChannels(account).getOrDefault(emptyList())
                    mutex.withLock { channelCache[account.id] = channels }
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
    }
}
