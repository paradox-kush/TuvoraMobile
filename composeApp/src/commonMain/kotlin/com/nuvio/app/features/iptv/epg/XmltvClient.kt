package com.nuvio.app.features.iptv.epg

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpStreamLines
import com.nuvio.app.features.epg.GuideChannelMatcher
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.channelNameRules
import com.nuvio.app.features.iptv.content.EpgCensusRow
import com.nuvio.app.features.iptv.content.EpgGuideChannelRow
import com.nuvio.app.features.iptv.content.EpgMappingWrite
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.epg.EpgTelemetry
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches + parses an XMLTV guide for an M3U (or custom-EPG) playlist and serves now/next from it.
 *
 * Source resolution (in order): the account's explicit [XtreamAccount.epgUrl], else the M3U
 * `url-tvg` / `x-tvg-url` header captured into ingest_meta during the catalog ingest. The guide is
 * commonly a 50-100 MB `.xml`/`.xml.gz`, so it is streamed line-by-line through the bounded-memory
 * [XmltvStreamingParser] (which itself holds only one element) and chunk-inserted into
 * `epg_programmes`. Crucially the parse is FILTERED to the tvg-ids the playlist actually has (queried
 * up front) so a guide covering thousands of channels only stores rows for ours.
 *
 * now/next is then a tiny indexed range read via [nowNext]; [M3UClient.shortEpg] delegates here so the
 * hub live guide shows real programmes for M3U live instead of the empty list P2a shipped.
 */
object XmltvClient {

    private val log = Logger.withTag("XmltvClient")

    private const val CHUNK = 5_000
    /** EPG is refreshed on ingest and then roughly twice a day; older than this and a browse re-fetches. */
    private const val REFRESH_TTL_MS = 12L * 60 * 60 * 1000

    /**
     * The ingest's own scope. A whole-guide download outlives any screen that asks for it — the
     * 2026-08-18 mirror bug was exactly this mistake (a screen's scope cancelled a 76-second sync,
     * and the completion stamp is written last, so it repeated forever).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire-and-forget [ensureEpg] on the ingest's own scope. Safe to call on every visit. */
    fun warm(acc: XtreamAccount) {
        scope.launch { runCatching { ensureEpg(acc) } }
    }

    /** Re-match + re-ingest now (a user changed something the match depends on). Ingest scope, never a screen's. */
    fun refreshNow(acc: XtreamAccount) {
        scope.launch { runCatching { ensureEpg(acc, force = true) } }
    }

    private val fetchLock = Mutex()
    private val fetching = mutableSetOf<String>()
    /** Playlist id → when its last whole-guide fetch failed (in-memory, like TV's). */
    private val lastFailedMs = mutableMapOf<String, Long>()

    /**
     * Ensures a fresh-enough EPG for [acc] is stored, fetching + parsing the guide when none exists or
     * the stored copy is stale. No-ops (and de-dupes concurrent callers) when a fresh guide is present
     * or the playlist has no resolvable EPG source. Returns true when programmes are available after.
     */
    suspend fun ensureEpg(acc: XtreamAccount, force: Boolean = false): Boolean {
        com.nuvio.app.core.sources.DeviceSources.guard("iptv",acc.id)
        val partition = partitionOf(acc)
        val meta = IptvContentDb.epgMeta(partition)
        if (!force && meta != null && !isStale(meta.builtAtMs)) return meta.programmeCount > 0
        val sources = resolveSources(acc)
        if (sources.isEmpty()) return (meta?.programmeCount ?: 0) > 0
        val shouldRun = fetchLock.withLock {
            val nowMs = TraktPlatformClock.nowEpochMs()
            val backedOff = !force && !XmltvFailureBackoff.allows(lastFailedMs[acc.id], nowMs)
            val notReady = !force && lineupNotReadyMs[acc.id]?.let { nowMs - it < LINEUP_RETRY_MS } == true
            if (backedOff || notReady || acc.id in fetching) false else { fetching.add(acc.id); true }
        }
        if (!shouldRun) return (IptvContentDb.epgMeta(partition)?.programmeCount ?: 0) > 0
        return try {
            val result = refresh(acc, sources)
            fetchLock.withLock {
                if (result.isFailure) lastFailedMs[acc.id] = TraktPlatformClock.nowEpochMs()
                else lastFailedMs.remove(acc.id)
            }
            result.getOrDefault(0) > 0
        } finally {
            fetchLock.withLock { fetching.remove(acc.id) }
        }
    }

    /** Playlist id -> when its lineup was last found empty (index not built yet): a short retry, not a 12h stamp. */
    private val lineupNotReadyMs = mutableMapOf<String, Long>()
    private const val LINEUP_RETRY_MS = 2L * 60 * 1000

    /**
     * Streams every source in priority order ([EpgSourcePlan]), matches the lineup onto each source's
     * `<channel>` list after a complete streaming census ([GuideChannelMatcher], B10), keeps
     * only the matched (and manually picked) channels' programmes, and writes programmes + channel map
     * + census in ONE swap. Memory stays flat: one parsed chunk + the parser's open element + the
     * guide's channel list (ids and names, no programmes).
     *
     * A later source is only downloaded while eligible channels are still unmatched; one source
     * failing does not lose the others (the ingest fails only if every attempted source failed).
     */
    internal suspend fun refresh(acc: XtreamAccount, sources: List<EpgSource>): Result<Int> = runCatching {
        val startedAtMs = TraktPlatformClock.nowEpochMs()
        val partition = partitionOf(acc)
        val lineup = lineupFor(acc)
        val picked = runCatching { com.nuvio.app.features.iptv.overlay.IptvOverlayStore.epgOverrideGuideIds(acc.id) }
            .getOrDefault(emptySet()).map { normalizeChannelId(it) }.toHashSet()
        if (lineup.isEmpty() && picked.isEmpty()) {
            // Nothing to attach a guide to YET — an Xtream index still building, an M3U not
            // ingested. Not stamped (a 12h "empty" stamp here blanked new playlists' guides for half
            // a day); retried after LINEUP_RETRY_MS instead.
            fetchLock.withLock { lineupNotReadyMs[acc.id] = TraktPlatformClock.nowEpochMs() }
            EpgTelemetry.ingestFinished(
                source = EpgTelemetry.Source.PLAYLIST_XMLTV,
                outcome = EpgTelemetry.Outcome.SKIPPED,
                durationMs = TraktPlatformClock.nowEpochMs() - startedAtMs,
            )
            return@runCatching 0
        }
        fetchLock.withLock { lineupNotReadyMs.remove(acc.id) }

        IptvContentDb.beginEpg(partition)
        val collector = EpgCollector(partition)
        val rules = acc.channelNameRules()
        val assignments = HashMap<Int, Pair<String, String>>()
        val guideRows = ArrayList<EpgGuideChannelRow>()
        var remaining = lineup
        var byId = 0; var byName = 0; var fuzzy = 0
        var attempted = 0; var failed = 0
        var lastError: Throwable? = null
        for ((index, source) in sources.withIndex()) {
            if (index > 0 && remaining.none { GuideChannelMatcher.isEligible(it.name) }) break
            attempted++
            val outcome = runCatching { ingestSource(acc, index, source, remaining, picked, rules, collector, guideRows) }
            val result = outcome.getOrElse {
                failed++; lastError = it
                log.w(it) { "XMLTV source ${index + 1}/${sources.size} (${source.kind}) failed for ${acc.id}" }
                null
            } ?: continue
            val matched = HashSet<Int>(result.assignments.size)
            for (a in result.assignments) {
                assignments[a.streamId] = EpgSourcePlan.storedKey(index, a.guideId) to a.tier.slug
                matched.add(a.streamId)
            }
            byId += result.census.id; byName += result.census.name; fuzzy += result.census.fuzzy
            remaining = remaining.filter { it.streamId !in matched }
        }
        if (attempted > 0 && failed == attempted) throw lastError ?: IllegalStateException("every EPG source failed")
        collector.finish()
        val census = EpgCensusRow(
            lineup = lineup.size,
            eligible = lineup.count { GuideChannelMatcher.isEligible(it.name) },
            manual = 0, byId = byId, byName = byName, fuzzy = fuzzy,
            sources = attempted, sourcesFailed = failed,
            builtAtMs = TraktPlatformClock.nowEpochMs(),
        )
        // A completed fetch that parsed to nothing (truncated/garbage body) must not blank a good
        // guide — keep the prior generation (and its map); the throttle still advances inside finishEpg.
        IptvContentDb.finishEpg(
            partition, collector.count, keepPriorIfEmpty = true,
            mapping = EpgMappingWrite(assignments, guideRows, census),
        )
        log.i {
            "XMLTV ingest done acc=${acc.id} sources=$attempted failed=$failed programmes=${collector.count} " +
                "lineup=${lineup.size} matched=${assignments.size} (id=$byId name=$byName)"
        }
        EpgTelemetry.ingestFinished(
            source = EpgTelemetry.Source.PLAYLIST_XMLTV,
            outcome = if (collector.count > 0) EpgTelemetry.Outcome.OK else EpgTelemetry.Outcome.EMPTY,
            programmes = collector.count,
            channels = lineup.size,
            channelsCovered = collector.channelsCovered,
            durationMs = TraktPlatformClock.nowEpochMs() - startedAtMs,
        )
        // A guide just landed, so every "this channel had nothing" verdict taken before it is
        // stale. Observed on the emulator (2026-08-18): two tiles asked 1s apart on a cold
        // playlist — the later one joined the in-flight ingest and got its programmes, the earlier
        // one checked an empty table, answered n=0, and the cooldown then pinned it on "No
        // information" for a further minute with the data already on disk beside it.
        com.nuvio.app.features.iptv.XtreamHubRepository.onGuideDataChanged()
        collector.count
    }.onFailure {
        log.w(it) { "XMLTV ingest failed for ${acc.id}" }
        EpgTelemetry.ingestFinished(
            source = EpgTelemetry.Source.PLAYLIST_XMLTV,
            outcome = EpgTelemetry.Outcome.ERROR,
            // Class only — a panel's message routinely quotes the request URL, credentials included.
            errorClass = it::class.simpleName,
        )
    }

    /** One source: harvest the full census, match once, replay only selected programmes locally. */
    private suspend fun ingestSource(
        acc: XtreamAccount,
        index: Int,
        source: EpgSource,
        lineup: List<GuideChannelMatcher.LineupChannel>,
        picked: Set<String>,
        rules: com.nuvio.app.features.epg.ChannelNameCleaner.Rules,
        collector: EpgCollector,
        guideRows: MutableList<EpgGuideChannelRow>,
    ): GuideChannelMatcher.Result {
        val replay = XmltvGuideReplay(lineup, picked, rules, TraktPlatformClock.nowEpochMs())
        fun feedLine(line: String) { replay.feed(line); replay.feed("\n") }
        try {
            if (source.kind == EpgSourceKind.XTREAM_DERIVED) {
                var delivered = false
                com.nuvio.app.features.iptv.PlaylistServerFailover.run(acc, canRetry = { !delivered }, probe = { a -> com.nuvio.app.features.iptv.XtreamClient.failoverProbe(a) }) { a ->
                    val derived = derivedXmltvUrl(a) ?: source.url
                    streamGuideLines(EpgSource(derived, source.kind), acc.userAgent(), acc.dnsProvider) { line ->
                        delivered = true
                        feedLine(line)
                    }
                }
            } else {
                streamGuideLines(source, acc.userAgent(), acc.dnsProvider, ::feedLine)
            }
            val result = replay.finish { p ->
                collector.add(p, EpgSourcePlan.storedKey(index, normalizeChannelId(p.channelId)))
            }
            for (g in replay.guide) {
                val id = normalizeChannelId(g.id)
                if (id.isEmpty()) continue
                guideRows.add(EpgGuideChannelRow(EpgSourcePlan.storedKey(index, id), id, g.names.firstOrNull() ?: g.id, index))
            }
            return result
        } finally {
            replay.dispose()
        }
    }

    /**
     * now/next for one channel, read from the stored guide by the channel's TVG id — the pre-B10
     * join, kept for callers that only know an id.
     */
    suspend fun nowNext(acc: XtreamAccount, tvgId: String, limit: Int = 4): List<XtreamProgram> {
        val key = normalizeChannelId(tvgId)
        if (key.isEmpty()) return emptyList()
        val now = TraktPlatformClock.nowEpochMs()
        val rows = IptvContentDb.epgAround(partitionOf(acc), key, now, limit)
        return selectNowNext(rows, now)
    }

    /**
     * The store rung for one channel by STREAM id (B10): the ingest's match (provider id, then the
     * cleaned name), else the provider's raw id for a playlist ingested before the map existed.
     * [] when the channel has no stored guide.
     */
    suspend fun storedNowNext(acc: XtreamAccount, streamId: Int, limit: Int = 4): List<XtreamProgram> {
        val partition = partitionOf(acc)
        val key = EpgGuideKeyPolicy.resolve(
            manualGuideId = null,
            keyForGuideId = { null },
            mappedKey = IptvContentDb.epgGuideKey(partition, streamId),
            providerId = providerIdFor(acc, streamId),
        ) ?: return emptyList()
        val now = TraktPlatformClock.nowEpochMs()
        return selectNowNext(IptvContentDb.epgAround(partition, key, now, limit), now)
    }

    /**
     * The MANUAL rung (F14): the guide channel the active profile picked for this channel, or null
     * when there is no pick (null falls through the ladder; a pick is never second-guessed).
     */
    suspend fun manualNowNext(acc: XtreamAccount, streamId: Int, limit: Int = 4): List<XtreamProgram>? {
        val picks = EpgOverrides.forPlaylist(acc.id)
        if (picks.isEmpty()) return null
        val entity = entityIdFor(acc, streamId) ?: return null
        val guideId = picks[entity] ?: return null
        val partition = partitionOf(acc)
        val key = IptvContentDb.epgGuideKeyForGuideId(partition, guideId) ?: return null
        val now = TraktPlatformClock.nowEpochMs()
        return selectNowNext(IptvContentDb.epgAround(partition, key, now, limit), now)
    }

    /** The guide channels this playlist's sources offer, for the manual picker (F14). */
    internal suspend fun guideChannels(acc: XtreamAccount, query: String, limit: Int = 200): List<EpgGuideChannelRow> =
        IptvContentDb.epgGuideChannels(partitionOf(acc), query, limit)

    /** The last ingest's coverage census (B10), or null before the first matched ingest. */
    internal suspend fun census(acc: XtreamAccount): EpgCensusRow? = IptvContentDb.epgCensus(partitionOf(acc))

    /** canon-v1 entity id of one live channel — the key a manual pick is stored under. */
    suspend fun entityIdFor(acc: XtreamAccount, streamId: Int): String? =
        if (acc.sourceType == SOURCE_TYPE_XTREAM) XtreamMatchIndex.liveEntityIdFor(acc.id, streamId)
        else IptvContentDb.channelRow(acc.id, streamId)?.let {
            com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(acc.id, it.name, it.tvgId)
        }

    private suspend fun providerIdFor(acc: XtreamAccount, streamId: Int): String? =
        if (acc.sourceType == SOURCE_TYPE_XTREAM) XtreamMatchIndex.liveEpgIdFor(acc.id, streamId)
        else IptvContentDb.channelRow(acc.id, streamId)?.tvgId

    /**
     * Where this playlist's XMLTV lane is stored. Stalker's own bulk guide swaps the playlist's main
     * partition, so an explicit EPG URL on a Stalker playlist gets its own; Xtream/M3U keep the main
     * one (every existing reader, catch-up and sports search included, reads it).
     */
    fun partitionOf(acc: XtreamAccount): String =
        if (acc.sourceType == SOURCE_TYPE_STALKER) acc.id + IptvContentDb.XMLTV_PARTITION_SUFFIX else acc.id

    /** The lineup the matcher maps, from whichever store owns it. */
    private suspend fun lineupFor(acc: XtreamAccount): List<GuideChannelMatcher.LineupChannel> =
        if (acc.sourceType == SOURCE_TYPE_XTREAM) {
            XtreamMatchIndex.liveLineup(acc.id).map { GuideChannelMatcher.LineupChannel(it.sid, it.name, it.epgId) }
        } else {
            IptvContentDb.liveLineup(acc.id).map { (sid, name, tvg) -> GuideChannelMatcher.LineupChannel(sid, name, tvg) }
        }

    /**
     * The playlist's guide sources in priority order (F14, [EpgSourcePlan]): every URL the user
     * typed, then the Xtream account's own derived `xmltv.php`, then the M3U `url-tvg` list.
     *
     * The derived rung is what makes the whole-guide lane real for Xtream. Before it, resolveSource
     * answered null for every Xtream playlist — so ensureEpg no-opped, nothing was ever stored, and
     * the guide had no choice but to ask the panel per channel forever. `xmltv.php` is the standard
     * Xtream guide route (same creds and host as player_api.php); a panel that does not serve it
     * fails the fetch once and the ladder falls through to the per-channel rung, i.e. old behaviour.
     */
    internal suspend fun resolveSources(acc: XtreamAccount): List<EpgSource> =
        EpgSourcePlan.plan(
            explicit = acc.epgUrl,
            derivedXtream = derivedXmltvUrl(acc),
            urlTvg = if (acc.sourceType == SOURCE_TYPE_XTREAM) null else IptvContentDb.ingestMeta(acc.id)?.epgUrl,
        )

    /** The first (highest-priority) source, or null — kept for callers/tests of the single-source era. */
    internal suspend fun resolveSource(acc: XtreamAccount): EpgSource? = resolveSources(acc).firstOrNull()

    /**
     * `{base}/xmltv.php?username=…&password=…` for an Xtream account, else null. Pure and internal
     * so the URL shape is pinned by a test rather than by a live panel. Credentials are encoded —
     * panels do issue passwords containing `&` and `+`.
     */
    internal fun derivedXmltvUrl(acc: XtreamAccount): String? {
        if (acc.sourceType != SOURCE_TYPE_XTREAM) return null
        val base = acc.baseUrl.trim().trimEnd('/').ifEmpty { return null }
        if (acc.username.isBlank() || acc.password.isBlank()) return null
        return "$base/xmltv.php?username=${acc.username.urlEncoded()}&password=${acc.password.urlEncoded()}"
    }

    private fun String.urlEncoded(): String = buildString(length) {
        for (c in this@urlEncoded) {
            if (c.isLetterOrDigit() || c in "-_.~") append(c)
            else for (b in c.toString().encodeToByteArray()) {
                append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    suspend fun clear(acc: XtreamAccount) = partitionOf(acc).let { IptvContentDb.beginEpg(it); IptvContentDb.finishEpg(it, 0) }

    // --- internals ---------------------------------------------------------------

    /** Streams the guide's lines. Network only in P2 — a `file://` EPG source is a later upgrade. */
    private suspend fun streamGuideLines(source: EpgSource, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) {
        httpStreamLines(source.url, userAgent, dnsProvider, onLine = onLine)
    }

    private class EpgCollector(private val playlistId: String) {
        private val buf = ArrayList<EpgProgrammeRow>(CHUNK)
        private val covered = HashSet<String>()
        var count = 0; private set

        /** Distinct channels that actually got rows — coverage, which every EPG report is about. */
        val channelsCovered: Int get() = covered.size

        /** [key] = the stored channel key ([EpgSourcePlan.storedKey] over the NORMALIZED id). */
        fun add(p: XmltvProgramme, key: String) {
            covered.add(key)
            buf.add(EpgProgrammeRow(key, p.startMs, p.endMs, p.title, p.desc))
            count++
            if (buf.size >= CHUNK) flush()
        }

        fun finish() = flush()

        private fun flush() {
            if (buf.isEmpty()) return
            runBlocking { IptvContentDb.insertEpgChunk(playlistId, buf) }
            buf.clear()
        }
    }

    private fun isStale(builtAtMs: Long): Boolean {
        if (builtAtMs <= 0) return false
        return TraktPlatformClock.nowEpochMs() - builtAtMs > REFRESH_TTL_MS
    }

    private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    private fun XtreamAccount.userAgent(): String = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
}

enum class EpgSourceKind { EXPLICIT, URL_TVG, XTREAM_DERIVED }

/** A resolved EPG source: the URL plus where it came from (for logging/UX). */
data class EpgSource(val url: String, val kind: EpgSourceKind)

/**
 * Maps EPG rows (start-ordered, all with end > now) to the now/next [XtreamProgram] list. Pure so the
 * now/next selection is unit-tested without the DB. The first row is "now-playing" only when the
 * current instant actually falls inside its window (start <= now < end) — otherwise the channel is
 * between programmes and the earliest upcoming one is next, with nothing marked now-playing.
 */
internal fun selectNowNext(rows: List<EpgProgrammeRow>, nowMs: Long): List<XtreamProgram> =
    rows.mapIndexed { index, r ->
        XtreamProgram(
            title = r.title,
            description = r.desc.orEmpty(),
            startMs = r.startMs,
            endMs = r.endMs,
            nowPlaying = index == 0 && r.startMs <= nowMs && nowMs < r.endMs,
        )
    }

/**
 * Whether a whole-guide fetch may run again after a failure. A failed ingest writes no meta row, so
 * without this every hub tile's [XmltvClient.ensureEpg] re-downloaded the whole (often 50-100 MB)
 * guide after one timeout or HTTP error. TV has always had the same 1h backoff.
 */
internal object XmltvFailureBackoff {
    const val WINDOW_MS: Long = 60L * 60 * 1000

    fun allows(lastFailedMs: Long?, nowMs: Long): Boolean =
        lastFailedMs == null || nowMs - lastFailedMs >= WINDOW_MS
}
