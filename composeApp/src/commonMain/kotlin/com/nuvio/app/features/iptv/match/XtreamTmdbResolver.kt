package com.nuvio.app.features.iptv.match

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.CONTENT_TYPE_LIVE
import com.nuvio.app.features.iptv.CONTENT_TYPE_MOVIES
import com.nuvio.app.features.iptv.CONTENT_TYPE_SERIES
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.iptv.typeEnabled
import com.nuvio.app.features.tmdb.TmdbTitleBundle
import com.nuvio.app.core.journal.JournalOutcome
import com.nuvio.app.core.journal.StartupJournal
import com.nuvio.app.core.util.Guarded
import com.nuvio.app.core.util.containTask
import com.nuvio.app.core.util.guarded
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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

    /** Serializes catalog builds across every account — see the use site in [ensureIndexed]. */
    private val buildSlot = Semaphore(1)

    private fun now() = TraktPlatformClock.nowEpochMs()

    /**
     * Fire-and-forget index warm-up (account add, app start, sync-in) so the first
     * resolve/search doesn't pay the full-catalog download on demand — on budget
     * devices that's minutes, which reads as "finding the movie takes forever".
     */
    fun warmUp(accounts: List<XtreamAccount>, startDelayMs: Long = 0L) {
        // Recovery safe mode: the app is crash-looping before it reaches an interactive screen, so
        // withhold ALL optional index warm-ups this run (the heaviest cold-start allocation). The
        // per-account durable backoff in ensureIndexed still applies once out of safe mode.
        if (StartupJournal.isSafeMode) return
        // Xtream only (mirrors NuvioTV): M3U/Stalker have no player_api bulk lists to index —
        // a warm-up for them just burns a failed fetch into the build backoff.
        accounts.filter { it.enabled && it.sourceType == SOURCE_TYPE_XTREAM }.forEach { acc ->
            buildScope.launch {
                if (startDelayMs > 0) delay(startDelayMs)
                // Contain every failure at the task boundary — this is a bare launch on a
                // SupervisorJob scope with no CoroutineExceptionHandler, so an uncaught throwable
                // here crashes the process (JVM/Android) or aborts (Native). ensureIndexed is
                // contracted never to throw, but a foreign throw from an early index-state read must
                // degrade to a skipped warm-up, never a fatal cold-start loop. containTask re-raises
                // CancellationException so structured cancellation of buildScope still works.
                containTask(onError = { log.w(it) { "warm-up index build failed for ${acc.name}" } }) {
                    // Respect the playlist's content types. Xtream's get_vod_streams is the single
                    // largest fetch the app makes (~15 MB on a 60k-title panel, whole catalog in ONE
                    // response — no paging), so a user who turned Movies off was still paying in full.
                    if (acc.typeEnabled(CONTENT_TYPE_LIVE)) ensureIndexed(acc, MatchKind.LIVE)
                    if (acc.typeEnabled(CONTENT_TYPE_MOVIES)) ensureIndexed(acc, MatchKind.MOVIE)
                    if (acc.typeEnabled(CONTENT_TYPE_SERIES)) ensureIndexed(acc, MatchKind.SERIES)
                }
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
            } else if (now() - cached.updatedAtMs < NEGATIVE_TTL_MS &&
                cached.updatedAtMs > XtreamMatchIndex.lastAddedAt(provider, kind)
            ) {
                // Fresh "not on this provider" — and the catalog hasn't gained titles since the
                // verdict. A panel that added items (daily on real providers) falsifies every
                // older miss, so those fall through to a re-match instead of hiding new content
                // for NEGATIVE_TTL_MS (measured: a title the panel added 3 days after the synced
                // verdict stayed invisible on every device).
                return null
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
        // best candidate whose panel tmdb id disagreed but whose year+exact-name confirmed
        // (junk-id panels) — accepted only after the whole verify budget fails to CONFIRM
        var provisional: XtreamMatch? = null
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
                when (decision) {
                    VerifyVerdict.CONFIRMED -> {
                        log.d { "matched tmdb=$tmdbId via=${probe.via} sid=${cand.sid} '${cand.name}'" }
                        XtreamMatchIndex.putMapping(acc.id, kind, tmdbId, cand.sid, cand.name)
                        XtreamMatchSyncService.triggerPush(acc.id)
                        return XtreamMatch(cand, probe.via)
                    }
                    // probes run best-first, so the first provisional is the best-ranked one
                    VerifyVerdict.PROVISIONAL -> if (provisional == null) {
                        provisional = XtreamMatch(cand, "${probe.via}+id-mismatch")
                    }
                    VerifyVerdict.REJECTED -> {}
                }
            }
            if (verifyCalls >= MAX_VERIFY_CALLS) break
        }
        provisional?.let {
            log.d { "matched tmdb=$tmdbId via=${it.via} sid=${it.item.sid} '${it.item.name}' (panel id overridden by exact name+year)" }
            XtreamMatchIndex.putMapping(acc.id, kind, tmdbId, it.item.sid, it.item.name)
            XtreamMatchSyncService.triggerPush(acc.id)
            return it
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
            MatchKind.LIVE -> VerifySignal(null, null)   // live rows are never TMDB-verified
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
     *  - a matching panel tmdb id confirms outright
     *  - a MISMATCHING panel id rejects — unless the panel's own year signal confirms the
     *    target exactly on an exact-tier primary/original name hit, which downgrades to
     *    PROVISIONAL instead: junk id feeds exist (a live panel ships the constant tmdb_id
     *    1111 for its entire catalog), and there the id contradicts the panel's own
     *    metadata. A provisional candidate is used only when nothing CONFIRMS, so panels
     *    with real ids behave exactly as before whenever a genuine match exists.
     *  - else best year signal (info year, then name year): exact tiers get ±1, inexact
     *    tiers (trunc/skeleton/nodigit/off-year) demand an exact year — "Wanted" 2008 vs
     *    2009 are different films, which is also why the provisional demands a year
     *    distance of exactly 0, never ±1
     *  - no signal at all: only exact-tier primary/original matches pass (an alt-title hit
     *    with nothing to confirm it is how O11CE's alt title "11" once matched an
     *    unrelated show — alt titles can't ride the provisional either)
     */
    internal enum class VerifyVerdict { CONFIRMED, PROVISIONAL, REJECTED }

    internal fun verifyDecision(
        signal: VerifySignal,
        targetTmdb: Int,
        targetYear: Int?,
        nameYear: Int?,
        exactTier: Boolean,
        via: String,
    ): VerifyVerdict {
        val exactPrimary = exactTier && (via.startsWith("primary") || via.startsWith("original"))
        val year = signal.year ?: nameYear
        signal.tmdb?.let {
            if (it == targetTmdb) return VerifyVerdict.CONFIRMED
            return if (exactPrimary && year != null && targetYear != null && yearDistance(year, targetYear) == 0) {
                VerifyVerdict.PROVISIONAL
            } else {
                VerifyVerdict.REJECTED
            }
        }
        if (year != null && targetYear != null) {
            val d = yearDistance(year, targetYear)
            return if (if (exactTier) d <= 1 else d == 0) VerifyVerdict.CONFIRMED else VerifyVerdict.REJECTED
        }
        return if (exactPrimary) VerifyVerdict.CONFIRMED else VerifyVerdict.REJECTED
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
        // The index-state read is a SQLite query BEFORE the build's own try/catch, on the caller's
        // coroutine (a warm-up launch, a resolve() call, or a headless refresh worker). A
        // locked/corrupt handle must not escape ensureIndexed — its contract is "never throws;
        // resolve degrades to whatever index exists". Degrade to a skipped build this cycle. A null
        // value (no index yet) is preserved via Guarded.Ok; only a THROW returns early.
        val existing = when (
            val g = guarded(onError = {
                log.w(it) { "index-state read failed for ${acc.name} ${kind.slug}; skipping build this cycle" }
            }) { XtreamMatchIndex.builtAt(acc.id, kind) }
        ) {
            is Guarded.Ok -> g.value
            Guarded.Failed -> return
        }
        if (existing != null && now() - existing < INDEX_TTL_MS) return

        // Durable backoff (survives an OS kill that bypasses the catch below): if a prior run began
        // this build and never recorded an outcome, or an expected failure is still within its
        // window, defer rather than repeat a build that may have killed the process. Off the
        // buildLock so its small file I/O never blocks other ops. See StartupJournal.
        if (StartupJournal.shouldDefer(StartupJournal.OP_INDEX_BUILD, key)) return

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
                // Attempt-before-work: record the attempt durably BEFORE the heavy build, so an OS
                // kill mid-build (which bypasses the catch) is detected on the next run and deferred.
                // A null token means the attempt could not be persisted — withhold the build this run.
                val token = StartupJournal.beginAttempt(StartupJournal.OP_INDEX_BUILD, key)
                if (token == null) {
                    buildLock.withLock {
                        inFlightBuilds.remove(key)
                        markIndexingLocked(acc.id, -1)
                    }
                    deferred.complete(Unit)
                    return@launch
                }
                try {
                    val stats = buildSlot.withPermit {
                        // One catalog build at a time across ALL accounts, and STREAMED: each
                        // parsed row goes straight into the SQLite session and is then garbage,
                        // so a build peaks at one 5k flush-chunk instead of the whole catalog
                        // (~40-50 MB of IndexedItem on a 175k panel — the allocation that used
                        // to OOM low-RAM devices right after a playlist was added).
                        // The category list rides along (P7): stored on success so the hub can
                        // serve section rows without a per-session network fetch.
                        val cats = when (kind) {
                            MatchKind.MOVIE -> XtreamClient.vodCategories(acc)
                            MatchKind.SERIES -> XtreamClient.seriesCategories(acc)
                            MatchKind.LIVE -> XtreamClient.liveCategories(acc)
                        }.getOrNull()
                        val session = XtreamMatchIndex.beginSync(acc.id, kind)
                        val fetched = when (kind) {
                            MatchKind.MOVIE -> XtreamClient.vodIndexItemsInto(acc, session::accept).getOrThrow()
                            MatchKind.SERIES -> XtreamClient.seriesIndexItemsInto(acc, session::accept).getOrThrow()
                            MatchKind.LIVE -> XtreamClient.liveIndexItemsInto(acc, session::accept).getOrThrow()
                        }
                        // An empty list where we previously indexed content is a panel glitch, not
                        // a real catalog — fail into the 1h backoff instead of re-fetching every
                        // resolve. Checked BEFORE finish() so built_at stays untouched.
                        check(fetched > 0 || XtreamMatchIndex.builtAt(acc.id, kind) == null) {
                            "panel returned an empty ${kind.slug} list"
                        }
                        val st = session.finish()
                        cats?.let { XtreamMatchIndex.replaceCategories(acc.id, kind, it.map { c -> c.id to c.name }) }
                        st
                    }
                    log.i { "synced ${kind.slug} index for ${acc.name}: +${stats.added} ~${stats.changed} -${stats.removed} (${stats.total} total)" }
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.COMPLETED)
                    buildLock.withLock { lastFailedBuildMs.remove(key) }
                } catch (c: CancellationException) {
                    // Preserve cancellation — do NOT swallow it. Record it as a non-failure so it is
                    // not mistaken for an abnormal death next run, then re-raise.
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.CANCELLED)
                    throw c
                } catch (t: Throwable) {
                    // OutOfMemoryError is JVM-only and cannot be named from commonMain. Keep the
                    // useful distinction without making iOS/native compilation target-dependent.
                    if (t::class.simpleName == "OutOfMemoryError") {
                        log.e(t) { "index build ran out of memory for ${acc.name} ${kind.slug}" }
                    } else {
                        log.w(t) { "index build failed for ${acc.name} ${kind.slug}" }
                    }
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.EXPECTED_FAILURE)
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
        if (n <= 0) {
            indexingCounts.remove(accountId)
            // The account's last in-flight build just ended (or failed) — drop the running count so
            // the next build starts from zero instead of continuing yesterday's total.
            XtreamMatchIndex.clearBuildProgress(accountId)
        } else {
            indexingCounts[accountId] = n
        }
        _indexing.value = indexingCounts.keys.toSet()
    }
}
