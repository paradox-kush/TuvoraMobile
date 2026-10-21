package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.epg.XmltvClient
import com.nuvio.app.features.iptv.content.IngestMeta
import com.nuvio.app.features.iptv.content.IptvCategoryRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentKind
import com.nuvio.app.features.iptv.content.IptvEpisodeRow
import com.nuvio.app.features.iptv.content.IptvSeriesRow
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    // One ingest per playlist, owned HERE rather than by whoever asked first (an add + a first browse
    // race). A second caller awaits the same import instead of reading a half-built catalog as "this
    // playlist has no categories", and a caller that stops waiting (the IPTV page's stall deadline, a
    // screen exit) never cancels an import the page will pick up when it lands (BoundedLoad).
    private val ingestLock = Mutex()
    private val ingestJobs = mutableMapOf<String, Pair<Any, kotlinx.coroutines.Deferred<Boolean>>>()
    private val ingestScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    /** A progress tick per this many parsed lines (restarts the page's stall deadline; cheap). */
    private const val PROGRESS_EVERY_LINES = 2_000

    private const val CHUNK = 5_000
    private const val M3U_PROBE_BYTES = 1024

    /**
     * Streams + parses the account's M3U URL into the content DB if it hasn't been ingested (or the
     * stored copy is stale). Safe to call from every hub/search entry point — it no-ops when a fresh
     * catalog already exists and de-dupes concurrent callers. Returns true when a usable catalog is
     * present afterwards.
     */
    suspend fun ensureIngested(acc: XtreamAccount, force: Boolean = false): Boolean {
        com.nuvio.app.core.sources.DeviceSources.guard("iptv",acc.id)
        val meta = IptvContentDb.ingestMeta(acc.id)
        // B64: a catalog built under the pre-B64 ids rebuilds once (and the saved refs follow, below).
        val oldIds = meta != null && meta.idScheme < M3U_ID_SCHEME
        if (!force && meta != null && !isStale(meta, acc.autoRefreshHours) && !oldIds) return true
        val job = sharedIngest(acc, oldIds)
        // Stale (older than the refresh TTL) but usable: serve the stored copy NOW and refresh behind it.
        // The served generation stays readable until the new one commits (IptvContentDb.beginIngest), and
        // IptvImportProgress.finished tells the IPTV page to re-read. Waiting on the whole re-download was
        // the 12-hour variant of "the IPTV page never finishes loading".
        if (!force && meta != null && !oldIds) return true
        return job.await()
    }

    /** Stops [accountId]'s import (the playlist was removed) so it cannot write rows for a deleted playlist. */
    suspend fun cancelIngest(accountId: String) {
        ingestLock.withLock { ingestJobs.remove(accountId) }?.second?.cancel()
    }

    private suspend fun sharedIngest(acc: XtreamAccount, oldIds: Boolean): kotlinx.coroutines.Deferred<Boolean> =
        ingestLock.withLock {
            ingestJobs[acc.id]?.second?.takeIf { it.isActive }?.let { return@withLock it }
            val token = Any()
            val job = ingestScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    if (oldIds) evictIdKeyedCaches(acc)
                    ingest(acc).isSuccess.also { ok ->
                        // B64: move this profile's saved refs onto the ids the catalog now carries. Off the
                        // browse path; idempotent, so a second run (next ingest) finds nothing to move.
                        if (ok) {
                            rekeyScope.launch { M3uIdRekeyRunner.runAfterIngest(acc) }
                            IptvImportProgress.finished(acc.id)
                        }
                    }
                } finally {
                    ingestLock.withLock { if (ingestJobs[acc.id]?.first === token) ingestJobs.remove(acc.id) }
                }
            }
            ingestJobs[acc.id] = token to job
            job.start()
            job
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

        // B64: item ids are derived from stream URLs with THIS playlist's login removed.
        val collector = IngestCollector(acc.id, if (acc.sourceType == SOURCE_TYPE_M3U_FILE) null else M3uIdentity.loginOf(url))
        val parser = M3UParser.StreamingParser { entry -> collector.add(entry) }

        var lineCount = 0
        streamLines(acc, url) { line ->
            parser.onLine(line)
            lineCount++
            if (lineCount % PROGRESS_EVERY_LINES == 0) IptvImportProgress.tick(acc.id)
        }
        collector.finish()

        // Capture the playlist's declared EPG url (#EXTM3U url-tvg) so XmltvClient can resolve a guide
        // when the account has no explicit epgUrl. Persisted on the meta row alongside the counts.
        val headerEpgUrl = parser.epgUrl
        IptvContentDb.finishIngest(acc.id, collector.liveCount, collector.vodCount, collector.seriesCount, headerEpgUrl, idScheme = M3U_ID_SCHEME)
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
            // Step 0.3: the download fails over to the playlist's backup URLs — but only until the
            // first line reached the parser: rows already chunk-inserted can't be taken back, so a
            // body that dies part-way surfaces as the failure it is instead of splicing two hosts.
            var delivered = false
            PlaylistServerFailover.run(acc, canRetry = { !delivered }, probe = ::failoverProbe) { a ->
                IptvTransport.current.streamLines(a.baseUrl, acc.userAgent(), acc.dnsProvider) { line ->
                    delivered = true
                    onLine(line)
                }
            }
        }
    }

    /**
     * The failover race's validation probe (Step 0.3b): `GET` with `Range: bytes=0-1023`, at most ~1 KB
     * read and the call closed even if the server ignored Range and is streaming the whole playlist.
     * VALID = the first non-BOM/whitespace bytes are `#EXTM3U`; anything else (HTML from a parked domain,
     * a JSON error, nothing) is [FailoverInvalidResponseException] and fails over.
     */
    internal suspend fun failoverProbe(a: XtreamAccount) {
        val prefix = IptvTransport.current.readPrefix(
            a.baseUrl, a.userAgent(), a.dnsProvider, mapOf("Range" to "bytes=0-${M3U_PROBE_BYTES - 1}"), M3U_PROBE_BYTES,
        )
        FailoverProbePolicy.toFailure(FailoverProbePolicy.m3uPrefix(prefix), "M3U probe")?.let { throw it }
    }

    /**
     * Accumulates parsed entries into per-kind chunk buffers and flushes to the DB every [CHUNK]
     * rows via [runBlocking] (safe: the ingest runs on an IO thread). Keeps at most one chunk in RAM.
     */
    private class IngestCollector(private val playlistId: String, private val login: M3uIdentity.Login?) {
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
            when (val mapped = M3uIngestMapping.map(entry, login, episodeCount)) {
                is M3uIngestRow.Channel -> {
                    rememberCategory(IptvContentKind.LIVE.slug, mapped.categoryId, entry.group)
                    channels.add(mapped.row)
                    liveCount++
                }
                is M3uIngestRow.Movie -> {
                    rememberCategory(IptvContentKind.VOD.slug, mapped.categoryId, entry.group)
                    vod.add(mapped.row)
                    vodCount++
                }
                is M3uIngestRow.Episode -> {
                    rememberCategory(IptvContentKind.SERIES.slug, mapped.categoryId, entry.group)
                    if (seenSeries.add(mapped.series.sid)) series.add(mapped.series)
                    episodes.add(mapped.row)
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

    // A re-key failure must never reach the app: it only logs, and the next ingest retries.
    private val rekeyScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> log.w(e) { "M3U id re-key failed" } },
    )

    /**
     * B64: caches keyed by the pre-B64 item ids (TMDB match index, canonical-EPG mapping, learned
     * catch-up facts) would key the rebuilt catalog's items wrongly — drop them; they rebuild.
     */
    private suspend fun evictIdKeyedCaches(acc: XtreamAccount) {
        runCatching { com.nuvio.app.features.iptv.match.XtreamMatchIndex.purge(acc.id) }
        runCatching { com.nuvio.app.features.epg.EpgMirrorRepository.purgeProvider(acc.id) }
        runCatching { CatchUpEpgRepository.forget(acc.id) }
    }

    // --- IptvClient ------------------------------------------------------------

    /** Ingest doubles as verify: a URL that streams at least one parseable entry is usable. */
    override suspend fun verify(acc: XtreamAccount): Result<Unit> = runCatching {
        val meta = ingest(acc).getOrThrow()
        if (meta.liveCount + meta.vodCount + meta.seriesCount <= 0) throw M3UNoContentException()
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
        // Kick a background guide refresh if none/stale (no-op when fresh or no EPG source), then read.
        // B10: by the ingest's channel map, so a channel with NO tvg-id still gets the guide its
        // name matched (it used to return empty right here, before even looking).
        XmltvClient.ensureEpg(acc)
        XmltvClient.storedNowNext(acc, streamId, limit)
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
    suspend fun liveUrlFor(acc: XtreamAccount, streamId: Int): String? { com.nuvio.app.core.sources.DeviceSources.guard("iptv",acc.id); return IptvContentDb.channelRow(acc.id, streamId)?.url }
    suspend fun movieUrlFor(acc: XtreamAccount, streamId: Int): String? { com.nuvio.app.core.sources.DeviceSources.guard("iptv",acc.id); return IptvContentDb.vodRow(acc.id, streamId)?.url }
    suspend fun episodeUrlFor(acc: XtreamAccount, episodeId: String): String? { com.nuvio.app.core.sources.DeviceSources.guard("iptv",acc.id); return IptvContentDb.episodeUrl(acc.id, episodeId)?.first }

    // --- windowed browse reads (item 5) — also serve the Stalker lineup, which lives in the
    // same channels table. ensure* is the caller's job (hub does it before the first window).
    suspend fun liveChannelsPage(acc: XtreamAccount, categoryId: String?, offset: Int, limit: Int): List<XtreamChannel> =
        IptvContentDb.pageChannels(acc.id, categoryId, offset, limit).map { it.toChannel() }

    suspend fun vodMoviesPage(acc: XtreamAccount, categoryId: String?, offset: Int, limit: Int): List<XtreamMovie> =
        IptvContentDb.pageVod(acc.id, categoryId, offset, limit).map { it.toMovie() }

    suspend fun seriesPage(acc: XtreamAccount, categoryId: String?, offset: Int, limit: Int): List<XtreamSeriesItem> =
        IptvContentDb.pageSeries(acc.id, categoryId, offset, limit).map { it.toSeriesItem() }

    /** Whether a playlist has a completed ingest. */
    suspend fun isIngested(acc: XtreamAccount): Boolean = IptvContentDb.ingestMeta(acc.id) != null

    suspend fun clear(acc: XtreamAccount) = IptvContentDb.clear(acc.id)

    // --- helpers ---------------------------------------------------------------

    private fun isStale(meta: IngestMeta, refreshHours: Int): Boolean {
        if (meta.builtAtMs <= 0) return false
        return IptvCatalogFreshness.stale(meta.builtAtMs, refreshHours, TraktPlatformClock.nowEpochMs(), fallbackHours = 12)
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
    internal fun sidOf(s: String): Int = M3uIdentity.sidOf(s)

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

internal const val M3U_NO_CONTENT_MESSAGE = "No channels, movies or series found in that M3U"

/** The playlist downloaded fine but held nothing playable — our own sentence, safe to show. */
internal class M3UNoContentException : IllegalStateException(M3U_NO_CONTENT_MESSAGE)
