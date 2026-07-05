package com.nuvio.app.features.iptv.match

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.tmdb.TmdbTitleBundle
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class XtreamMatch(val item: IndexedItem, val via: String)

/**
 * Resolves a TMDB id to a concrete Xtream stream/series id for one account.
 *
 * Three tiers, cheapest first (validated against live panels — see match test campaign):
 *  1. bulk-list `tmdb` field (XUI panels ship it for ~90% of items) — zero API calls
 *  2. verified-mapping cache (local mirror of the Supabase-synced table) — zero API calls
 *  3. normalized-name probes over the SQLite index, then verify candidates via
 *     get_vod_info / get_series_info (~1 call), caching the outcome — including misses
 *     (negative rows), so unavailable titles don't re-scan on every play.
 */
internal object XtreamTmdbResolver {
    private val log = Logger.withTag("XtreamTmdbResolver")

    // Staleness ceiling before a re-fetch. The refresh is an incremental diff (unchanged
    // titles validate by fingerprint; only new/renamed re-index), so it's cheap on-device —
    // the 72h window just bounds how often the full catalog JSON is re-downloaded.
    private const val INDEX_TTL_MS = 72 * 60 * 60 * 1000L
    private const val BUILD_BACKOFF_MS = 60 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_VERIFY_CALLS = 3

    private val buildLock = Mutex()
    private val inFlightBuilds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lastFailedBuildMs = mutableMapOf<String, Long>()

    // account ids whose catalog index is currently building — drives the
    // "preparing catalog…" status on the IPTV settings rows
    private val indexingCounts = mutableMapOf<String, Int>()
    private val _indexing = MutableStateFlow<Set<String>>(emptySet())
    val indexing: StateFlow<Set<String>> = _indexing.asStateFlow()

    // index builds outlive the stream request that triggered them: a 175k-item catalog
    // takes ~a minute on-device, and users navigate away — cancelling the request must
    // not kill (and backoff-poison) the build
    private val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun now() = TraktPlatformClock.nowEpochMs()

    /**
     * Fire-and-forget index warm-up (account add, app start, sync-in) so the first
     * resolve/search doesn't pay the full-catalog download on demand — on budget
     * devices that's minutes, which reads as "finding the movie takes forever".
     */
    fun warmUp(accounts: List<XtreamAccount>, startDelayMs: Long = 0L) {
        accounts.filter { it.enabled }.forEach { acc ->
            buildScope.launch {
                if (startDelayMs > 0) delay(startDelayMs)
                ensureIndexed(acc, MatchKind.MOVIE)
                ensureIndexed(acc, MatchKind.SERIES)
            }
        }
    }

    suspend fun resolve(acc: XtreamAccount, kind: MatchKind, tmdbId: Int, titles: TmdbTitleBundle): XtreamMatch? {
        ensureIndexed(acc, kind)
        val provider = acc.id
        val indexExists = XtreamMatchIndex.builtAt(provider, kind) != null

        // tier 1: the panel told us outright
        XtreamMatchIndex.byTmdb(provider, kind, tmdbId).minByOrNull { rankDistance(it.year, titles.year) }?.let {
            return XtreamMatch(it, "id")
        }

        // tier 2: previously verified (possibly on another device, via Supabase)
        XtreamMatchSyncService.pullOnce(provider)
        XtreamMatchIndex.cachedMapping(provider, kind, tmdbId)?.let { cached ->
            if (cached.sid != null) {
                XtreamMatchIndex.item(provider, kind, cached.sid)?.let { return XtreamMatch(it, "cache") }
                // Item not in the local index. If this provider isn't indexed on THIS device,
                // trust the synced mapping (another device verified it) and resolve straight
                // from the sid — that's the whole point of cross-device sync: a match made on
                // the TV must play on the phone without re-downloading a huge catalog. Only
                // treat a missing sid as stale-vanished when we actually have the index to check.
                if (!indexExists) {
                    return XtreamMatch(IndexedItem(cached.sid, cached.matchedName ?: "", null, tmdbId, null), "cache-synced")
                }
                // else: sid vanished from a built catalog — fall through to re-match
            } else if (now() - cached.updatedAtMs < NEGATIVE_TTL_MS) {
                return null // fresh "not on this provider"
            }
        }

        // tier 3: name matching + verification
        val variants = buildList {
            titles.primary?.let { add(TitleVariant(it, "primary")) }
            titles.original?.takeIf { it != titles.primary }?.let { add(TitleVariant(it, "original")) }
            titles.alternatives.forEach { add(TitleVariant(it, "alt")) }
        }
        if (variants.isEmpty()) return null

        var verifyCalls = 0
        for (probe in TitleNormalizer.probesFor(variants)) {
            val bucket = XtreamMatchIndex.probe(acc.id, kind, probe.key)
            if (bucket.isEmpty()) continue
            // year is a ranking signal, not a gate: panels ship garbage years (epoch 1970
            // defaults), so off-year candidates still get verified — just later and never
            // auto-accepted without a confirming signal.
            val ordered = bucket.sortedBy { rankDistance(it.year, titles.year) }
            for (cand in ordered) {
                if (verifyCalls >= MAX_VERIFY_CALLS) break
                val inYear = cand.year == null || titles.year == null || yearDistance(cand.year, titles.year) <= 1
                val signal = fetchVerifySignal(acc, kind, cand).also { verifyCalls++ }
                val decision = verifyDecision(
                    signal = signal,
                    targetTmdb = tmdbId,
                    targetYear = titles.year,
                    nameYear = cand.year,
                    exactTier = probe.exactTier && inYear,
                    via = probe.via,
                )
                if (decision) {
                    log.d { "matched tmdb=$tmdbId via=${probe.via} sid=${cand.sid} '${cand.name}'" }
                    XtreamMatchIndex.putMapping(acc.id, kind, tmdbId, cand.sid, cand.name)
                    XtreamMatchSyncService.triggerPush(acc.id)
                    return XtreamMatch(cand, probe.via)
                }
            }
            if (verifyCalls >= MAX_VERIFY_CALLS) break
        }

        // only cache "not on this provider" when we actually had an index to search —
        // a failed/missing index must not poison the negative cache for 7 days
        if (indexExists) {
            XtreamMatchIndex.putMapping(acc.id, kind, tmdbId, sid = null, matchedName = null)
            XtreamMatchSyncService.triggerPush(acc.id)
        }
        return null
    }

    /** What the panel's info endpoint can tell us about a candidate. */
    internal data class VerifySignal(val tmdb: Int?, val year: Int?)

    private suspend fun fetchVerifySignal(acc: XtreamAccount, kind: MatchKind, cand: IndexedItem): VerifySignal =
        when (kind) {
            MatchKind.MOVIE -> XtreamClient.vodInfo(acc, cand.sid).getOrNull()
                ?.let { VerifySignal(it.tmdbId, it.releaseDate?.take(4)?.toIntOrNull()) }
                ?: VerifySignal(null, null)
            MatchKind.SERIES -> XtreamClient.seriesInfo(acc, cand.sid).getOrNull()
                ?.let { VerifySignal(it.tmdbId, it.releaseDate?.take(4)?.toIntOrNull()) }
                ?: VerifySignal(null, null)
        }

    /**
     * The acceptance rules distilled from the live-panel campaign — pure so the test suite
     * can hammer them:
     *  - panel tmdb id decides outright when present (equality or rejection)
     *  - else best year signal (info year, then name year): exact tiers get ±1, inexact
     *    tiers (trunc/skeleton/nodigit/off-year) demand an exact year
     *  - no signal at all: only exact-tier primary/original matches pass ("Wanted" 2008 vs
     *    2009 are different films; an alt-title hit with nothing to confirm it is how
     *    O11CE's alt title "11" once matched an unrelated show)
     */
    internal fun verifyDecision(
        signal: VerifySignal,
        targetTmdb: Int,
        targetYear: Int?,
        nameYear: Int?,
        exactTier: Boolean,
        via: String,
    ): Boolean {
        signal.tmdb?.let { return it == targetTmdb }
        val year = signal.year ?: nameYear
        if (year != null && targetYear != null) {
            val d = yearDistance(year, targetYear)
            return if (exactTier) d <= 1 else d == 0
        }
        return exactTier && (via.startsWith("primary") || via.startsWith("original"))
    }

    private fun yearDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 0 else if (a > b) a - b else b - a

    /** Verify-order ranking: year-exact candidates first, unknown-year candidates last. */
    private fun rankDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 999 else yearDistance(a, b)

    // --- index freshness -------------------------------------------------------

    /**
     * Builds the SQLite index from the full bulk list when missing or older than 24h.
     * Single-flight per provider+kind; failures back off for an hour. Never throws —
     * resolve degrades to whatever index exists.
     */
    suspend fun ensureIndexed(acc: XtreamAccount, kind: MatchKind) {
        val key = "${acc.id}#${kind.slug}"
        val existing = XtreamMatchIndex.builtAt(acc.id, kind)
        if (existing != null && now() - existing < INDEX_TTL_MS) return

        val (deferred, isOwner) = buildLock.withLock {
            inFlightBuilds[key]?.let { return@withLock it to false }
            // backoff applies with OR without an existing index — a dead panel must not
            // trigger a full-catalog download on every resolve attempt
            if (now() - (lastFailedBuildMs[key] ?: 0) < BUILD_BACKOFF_MS) return
            val d = CompletableDeferred<Unit>()
            inFlightBuilds[key] = d
            markIndexingLocked(acc.id, +1)
            d to true
        }

        if (isOwner) {
            buildScope.launch {
                try {
                    val items = when (kind) {
                        MatchKind.MOVIE -> XtreamClient.vodMovies(acc).getOrThrow().map {
                            IndexedItem(it.streamId, it.name, TitleNormalizer.yearOf(it.name), it.tmdb, it.containerExtension, it.poster)
                        }
                        MatchKind.SERIES -> XtreamClient.series(acc).getOrThrow().map {
                            IndexedItem(it.seriesId, it.name, it.year ?: TitleNormalizer.yearOf(it.name), it.tmdb, null, it.poster)
                        }
                    }
                    // An empty list where we previously indexed content is a panel glitch, not a
                    // real catalog — fail into the 1h backoff instead of re-fetching every resolve.
                    check(items.isNotEmpty() || XtreamMatchIndex.builtAt(acc.id, kind) == null) {
                        "panel returned an empty ${kind.slug} list"
                    }
                    val stats = XtreamMatchIndex.sync(acc.id, kind, items)
                    log.i { "synced ${kind.slug} index for ${acc.name}: +${stats.added} ~${stats.changed} -${stats.removed} (${stats.total} total)" }
                    buildLock.withLock { lastFailedBuildMs.remove(key) }
                } catch (t: Throwable) {
                    log.w(t) { "index build failed for ${acc.name} ${kind.slug}" }
                    buildLock.withLock { lastFailedBuildMs[key] = now() }
                } finally {
                    buildLock.withLock {
                        inFlightBuilds.remove(key)
                        markIndexingLocked(acc.id, -1)
                    }
                    deferred.complete(Unit)
                }
            }
        }
        // A stale-but-present index serves immediately — yesterday's catalog still
        // resolves, and the rebuild lands in the background. Only a MISSING index is
        // worth blocking the caller for.
        if (existing != null) return
        // await is cancellable (the caller's request may die); the build itself is not
        deferred.await()
    }

    /** Callers hold [buildLock]. Tracks per-account in-flight build counts for [indexing]. */
    private fun markIndexingLocked(accountId: String, delta: Int) {
        val n = (indexingCounts[accountId] ?: 0) + delta
        if (n <= 0) indexingCounts.remove(accountId) else indexingCounts[accountId] = n
        _indexing.value = indexingCounts.keys.toSet()
    }
}
