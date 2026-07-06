package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpStreamLines
import com.nuvio.app.features.iptv.epg.XmltvClient
import com.nuvio.app.features.iptv.content.IngestMeta
import com.nuvio.app.features.iptv.content.IptvCategoryRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentKind
import com.nuvio.app.features.iptv.content.IptvEpisodeRow
import com.nuvio.app.features.iptv.content.IptvSeriesRow
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [IptvClient] over an M3U URL playlist. There is NO API behind the URL — the parsed playlist is the
 * whole catalog — so [ingest] streams the M3U line-by-line (bounded memory), classifies each entry,
 * and chunk-inserts it into [IptvContentDb]; the query methods then read that DB and map rows back to
 * the SAME [XtreamChannel]/[XtreamMovie]/[XtreamSeriesItem] models Xtream emits. A live/movie/episode
 * stream URL is simply the URL that was on the M3U line (stored per row), so playback rides the exact
 * same registry -> detail -> player pipeline with zero source-specific plumbing.
 */
object M3UClient : IptvClient {

    private val log = Logger.withTag("M3UClient")

    // One ingest per playlist at a time (an add + a first-browse can race). Keyed by playlist id.
    private val ingestLock = Mutex()
    private val ingesting = mutableSetOf<String>()

    private const val CHUNK = 5_000
    /** How long a stored catalog is considered fresh before a browse re-ingests it. */
    private const val REFRESH_TTL_MS = 12L * 60 * 60 * 1000

    /**
     * Streams + parses the account's M3U URL into the content DB if it hasn't been ingested (or the
     * stored copy is stale). Safe to call from every hub/search entry point — it no-ops when a fresh
     * catalog already exists and de-dupes concurrent callers. Returns true when a usable catalog is
     * present afterwards.
     */
    suspend fun ensureIngested(acc: XtreamAccount, force: Boolean = false): Boolean {
        val meta = IptvContentDb.ingestMeta(acc.id)
        if (!force && meta != null && !isStale(meta)) return true
        val shouldRun = ingestLock.withLock {
            if (acc.id in ingesting) false else { ingesting.add(acc.id); true }
        }
        if (!shouldRun) {
            // Another ingest is in flight — report on whatever catalog currently exists.
            return IptvContentDb.ingestMeta(acc.id) != null
        }
        return try {
            ingest(acc).isSuccess
        } finally {
            ingestLock.withLock { ingesting.remove(acc.id) }
        }
    }

    /**
     * Full ingest: wipe prior rows, stream the URL, parse+classify each entry, chunk-insert every
     * [CHUNK] rows, then write the meta row LAST (crash-safe). Memory stays flat — at most one chunk
     * of parsed rows is held, and the raw 190+ MB body never materializes (it's read line-by-line).
     *
     * [httpStreamLines]' onLine is non-suspend, but it already runs on a dedicated IO thread, so a
     * full chunk is drained to the DB with [runBlocking] on that same thread (never the main thread)
     * — the accumulator can't outgrow one chunk.
     */
    internal suspend fun ingest(acc: XtreamAccount): Result<IngestMeta> = runCatching {
        val url = acc.baseUrl
        // A file playlist has no URL (its bytes are the saved local copy); a URL playlist must have one.
        if (acc.sourceType != SOURCE_TYPE_M3U_FILE) require(url.isNotBlank()) { "M3U playlist URL is blank" }
        IptvContentDb.beginIngest(acc.id)

        val collector = IngestCollector(acc.id)
        val parser = M3UParser.StreamingParser { entry -> collector.add(entry) }

        var lineCount = 0
        streamLines(acc, url) { line ->
            parser.onLine(line)
            lineCount++
        }
        collector.finish()

        // Capture the playlist's declared EPG url (#EXTM3U url-tvg) so XmltvClient can resolve a guide
        // when the account has no explicit epgUrl. Persisted on the meta row alongside the counts.
        val headerEpgUrl = parser.epgUrl
        IptvContentDb.finishIngest(acc.id, collector.liveCount, collector.vodCount, collector.seriesCount, headerEpgUrl)
        val meta = IngestMeta(0, collector.liveCount, collector.vodCount, collector.seriesCount, headerEpgUrl)
        log.i { "M3U ingest done acc=${acc.id} lines=$lineCount live=${collector.liveCount} vod=${collector.vodCount} series=${collector.seriesCount} episodes=${collector.episodeCount} epgUrl=$headerEpgUrl" }
        meta
    }.onFailure { log.w(it) { "M3U ingest failed for ${acc.id}" } }

    /**
     * The byte source for an ingest: an http(s) URL streams over the network; a `m3u_file` playlist
     * reads the local copy saved under app storage. Both are gzip-aware and bounded-memory.
     */
    private suspend fun streamLines(acc: XtreamAccount, url: String, onLine: (String) -> Unit) {
        if (acc.sourceType == SOURCE_TYPE_M3U_FILE) {
            val path = M3UFileStore.localPath(acc)
                ?: error("Playlist file for '${acc.name}' isn't on this device — re-import it here.")
            if (!fileExists(path)) error("Playlist file for '${acc.name}' is missing — re-import it on this device.")
            streamFileLines(path, onLine)
        } else {
            // dnsProvider (P3) routes the M3U fetch through the playlist's DoH resolver on Android.
            httpStreamLines(url, acc.userAgent(), acc.dnsProvider, onLine)
        }
    }

    /**
     * Accumulates parsed entries into per-kind chunk buffers and flushes to the DB every [CHUNK]
     * rows via [runBlocking] (safe: the ingest runs on an IO thread). Keeps at most one chunk in RAM.
     */
    private class IngestCollector(private val playlistId: String) {
        private val channels = ArrayList<IptvStreamRow>(CHUNK)
        private val vod = ArrayList<IptvStreamRow>(CHUNK)
        private val series = ArrayList<IptvSeriesRow>(CHUNK)
        private val episodes = ArrayList<IptvEpisodeRow>(CHUNK)
        private val pendingCats = ArrayList<Triple<String, String, String>>()
        private val seenCats = HashSet<String>()
        private val seenSeries = HashSet<Int>()
        var liveCount = 0; private set
        var vodCount = 0; private set
        var episodeCount = 0; private set
        val seriesCount: Int get() = seenSeries.size

        fun add(entry: M3UParser.Entry) {
            val catId = categoryId(entry.group)
            when (entry.kind) {
                M3UKind.LIVE -> {
                    rememberCategory(IptvContentKind.LIVE.slug, catId, entry.group)
                    channels.add(IptvStreamRow(sidOf(entry.url), entry.name, entry.logo, entry.tvgId, catId, entry.url, entry.ext))
                    liveCount++
                }
                M3UKind.MOVIE -> {
                    rememberCategory(IptvContentKind.VOD.slug, catId, entry.group)
                    vod.add(IptvStreamRow(sidOf(entry.url), entry.name, entry.logo, null, catId, entry.url, entry.ext))
                    vodCount++
                }
                M3UKind.SERIES -> {
                    rememberCategory(IptvContentKind.SERIES.slug, catId, entry.group)
                    val key = entry.seriesKey ?: entry.name
                    val seriesSid = sidOf("series:$key")
                    if (seenSeries.add(seriesSid)) {
                        series.add(IptvSeriesRow(seriesSid, seriesTitle(key), entry.logo, catId))
                    }
                    episodes.add(
                        IptvEpisodeRow(
                            seriesSid = seriesSid,
                            episodeId = episodeIdOf(entry.url),
                            name = entry.name,
                            season = entry.season ?: 1,
                            episode = entry.episode ?: (episodeCount % 10_000),
                            logo = entry.logo,
                            url = entry.url,
                            ext = entry.ext,
                        )
                    )
                    episodeCount++
                }
            }
            if (channels.size >= CHUNK || vod.size >= CHUNK || episodes.size >= CHUNK || series.size >= CHUNK) flush()
        }

        private fun rememberCategory(type: String, id: String, name: String?) {
            if (seenCats.add("$type $id")) pendingCats.add(Triple(type, id, name ?: UNGROUPED_NAME))
        }

        fun finish() = flush()

        private fun flush() {
            if (channels.isEmpty() && vod.isEmpty() && series.isEmpty() && episodes.isEmpty() && pendingCats.isEmpty()) return
            runBlocking {
                IptvContentDb.insertChunk(playlistId, channels, vod, series, episodes, pendingCats)
            }
            channels.clear(); vod.clear(); series.clear(); episodes.clear(); pendingCats.clear()
        }
    }

    // --- IptvClient ------------------------------------------------------------

    /** Ingest doubles as verify: a URL that streams at least one parseable entry is usable. */
    override suspend fun verify(acc: XtreamAccount): Result<Unit> = runCatching {
        val meta = ingest(acc).getOrThrow()
        check(meta.liveCount + meta.vodCount + meta.seriesCount > 0) { "No channels, movies or series found in that M3U" }
    }

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = queryCats(acc, IptvContentKind.LIVE)
    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = queryCats(acc, IptvContentKind.VOD)
    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = queryCats(acc, IptvContentKind.SERIES)

    private suspend fun queryCats(acc: XtreamAccount, kind: IptvContentKind): Result<List<XtreamCategory>> = runCatching {
        ensureIngested(acc)
        IptvContentDb.categoriesFor(acc.id, kind).map { it.toCategory() }
    }

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = runCatching {
        ensureIngested(acc)
        IptvContentDb.channelsFor(acc.id, categoryId).map { it.toChannel() }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = runCatching {
        ensureIngested(acc)
        IptvContentDb.vodFor(acc.id, categoryId).map { it.toMovie() }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = runCatching {
        ensureIngested(acc)
        IptvContentDb.seriesFor(acc.id, categoryId).map { it.toSeriesItem() }
    }

    /**
     * now/next for an M3U live channel, from the XMLTV guide (P2b). The channel's tvg-id (stored on its
     * row) is the XMLTV key; with no tvg-id there's nothing to match, so it's empty. The guide is fetched
     * lazily+cached by [XmltvClient.ensureEpg]; here we just read what's stored (a cheap indexed range).
     */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = runCatching {
        val tvgId = IptvContentDb.channelRow(acc.id, streamId)?.tvgId?.takeIf { it.isNotBlank() }
            ?: return@runCatching emptyList()
        // Kick a background guide refresh if none/stale (no-op when fresh or no EPG source), then read.
        XmltvClient.ensureEpg(acc)
        XmltvClient.nowNext(acc, tvgId, limit)
    }

    /** M3U carries no rich detail — surface just the stored name; enrichment (TMDB) still applies upstream. */
    override suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?> = runCatching {
        val row = IptvContentDb.vodRow(acc.id, vodId) ?: return@runCatching null
        XtreamVodDetail(
            name = row.name,
            plot = null,
            genres = emptyList(),
            rating = null,
            releaseDate = null,
            tmdbId = null,
            containerExtension = row.ext,
        )
    }

    /** Rebuilds series detail (name + episode list) from the grouped episode rows in the DB. */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?> = runCatching {
        val row = IptvContentDb.seriesRow(acc.id, seriesId) ?: return@runCatching null
        val episodes = IptvContentDb.episodesFor(acc.id, seriesId).map { ep ->
            XtreamEpisode(
                episodeId = ep.episodeId,
                season = ep.season,
                episodeNum = ep.episode,
                title = ep.name,
                plot = null,
                still = ep.logo,
                containerExtension = ep.ext,
            )
        }
        XtreamSeriesDetail(
            name = row.name,
            poster = row.logo,
            tmdbId = null,
            plot = null,
            genres = emptyList(),
            rating = null,
            releaseDate = null,
            episodes = episodes,
        )
    }

    // Stream URLs come from the stored M3U line, not a template. These non-suspend builders (part of
    // the IptvClient contract) can't reach the DB with only a sid, so they return "" — the M3U play
    // paths use the suspend resolvers below (and the registry already carries the real URL after a
    // browse). Episode URLs ARE embedded in the meta's MetaVideo.streams, so this is never hit for them.
    override fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String): String = ""
    override fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String = ""
    override fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String): String = ""

    /** Suspend URL resolvers that read the stored M3U line — used by the cold-launch play/meta paths. */
    suspend fun liveUrlFor(acc: XtreamAccount, streamId: Int): String? = IptvContentDb.channelRow(acc.id, streamId)?.url
    suspend fun movieUrlFor(acc: XtreamAccount, streamId: Int): String? = IptvContentDb.vodRow(acc.id, streamId)?.url
    suspend fun episodeUrlFor(acc: XtreamAccount, episodeId: String): String? = IptvContentDb.episodeUrl(acc.id, episodeId)?.first

    /** Whether a playlist has a completed ingest. */
    suspend fun isIngested(acc: XtreamAccount): Boolean = IptvContentDb.ingestMeta(acc.id) != null

    suspend fun clear(acc: XtreamAccount) = IptvContentDb.clear(acc.id)

    // --- helpers ---------------------------------------------------------------

    private fun isStale(meta: IngestMeta): Boolean {
        if (meta.builtAtMs <= 0) return false
        return TraktPlatformClock.nowEpochMs() - meta.builtAtMs > REFRESH_TTL_MS
    }

    private fun IptvCategoryRow.toCategory() = XtreamCategory(id, name)

    private fun IptvStreamRow.toChannel() = XtreamChannel(
        streamId = sid,
        name = name,
        logo = logo,
        epgChannelId = tvgId,
        categoryId = categoryId,
        hasArchive = false,
        streamUrl = url,
    )

    private fun IptvStreamRow.toMovie() = XtreamMovie(
        streamId = sid,
        name = name,
        poster = logo,
        categoryId = categoryId,
        rating = null,
        streamUrl = url,
        tmdb = null,
        containerExtension = ext,
    )

    private fun IptvSeriesRow.toSeriesItem() = XtreamSeriesItem(
        seriesId = sid,
        name = name,
        poster = logo,
        categoryId = categoryId,
        plot = null,
        rating = null,
        tmdb = null,
        year = null,
    )

    /** A stable non-negative Int id from an arbitrary string (URL / series key). FNV-1a, masked. */
    internal fun sidOf(s: String): Int {
        var hash = -0x7ee3623b // FNV offset basis (0x811C9DC5) as a signed Int
        for (c in s) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash and 0x7fffffff
    }

    /** Episode ids stay strings (the registry EPISODE kind expects a string id) — hex of the url hash. */
    internal fun episodeIdOf(url: String): String = sidOf(url).toString(16)

    /** Category id = a stable hash of the group-title (or a fixed id for ungrouped). */
    internal fun categoryId(group: String?): String =
        if (group.isNullOrBlank()) UNGROUPED_ID else sidOf(group).toString()

    /** Series display title = key with word-initial letters upper-cased. */
    internal fun seriesTitle(key: String): String =
        key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.trim().ifBlank { key }

    private const val UNGROUPED_ID = "0"
    private const val UNGROUPED_NAME = "Uncategorized"
    private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /** The playlist's custom User-Agent, or a VLC default many providers gate on. */
    private fun XtreamAccount.userAgent(): String = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
}
