package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * B64 phase 3 — executes [M3uIdRekey] for one M3U playlist of the current profile, once its catalog is
 * built under the login-free ids ([M3U_ID_SCHEME]). Runs after every such ingest and costs nothing
 * when there is nothing to move:
 *
 *  1. DRY RUN: collect the saved content ids under the playlist (library incl. live favourites, watch
 *     progress, watched marks, recent channels) — none → done, no catalog read;
 *  2. page through the catalog (channels, movies, episodes) building a plan restricted to those ids
 *     (memory = the user's saved items, never the 100k-line catalog);
 *  3. log the counts per store, then apply through each store's own synced re-key (delete old id +
 *     upsert new, as Step 0's key adoption already does).
 *
 * Idempotent (see [M3uIdRekey]) — so no marker: a second run finds nothing to move. It also normalizes
 * legacy ids another (not yet updated) device pushed in the meantime.
 *
 * Device pass T1: it also re-runs whenever the saved ids (or the playlists) change — a remote pull can
 * bring an old-id row back after the re-key, which used to stay as a duplicate Continue Watching card
 * until the next ingest. [M3uIdRekeyLedger] keeps those re-runs free: only ids not yet checked against
 * the current catalog cost a catalog read. Local, in-memory work only (no network of its own).
 */
internal object M3uIdRekeyRunner {

    private val log = Logger.withTag("M3uIdRekey")
    private const val PAGE = 2_000
    private val mutex = Mutex()
    private val ledger = M3uIdRekeyLedger()   // guarded by [mutex]
    private val watching = atomic(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.w(e) { "M3U id re-key failed" } },
    )

    /**
     * Phase 4 (normalize): on a profile load, run for every M3U playlist whose catalog is already on the
     * new ids — this also moves legacy ids another, not-yet-updated phone/desktop pushed in the meantime
     * (a playlist still on the old ids is handled by its rebuild instead).
     */
    fun scheduleForCurrentProfile() {
        watchSavedIds()
        scope.launch {
            mutex.withLock { ledger.clear() }
            sweep()
        }
    }

    suspend fun run(acc: XtreamAccount) = mutex.withLock { runLocked(acc) }

    /** After [acc]'s catalog was (re)built: every saved id is checked against it again. */
    suspend fun runAfterIngest(acc: XtreamAccount) = mutex.withLock {
        ledger.forget(acc.id)
        runLocked(acc)
    }

    /** Every M3U playlist of the profile whose catalog is on the new ids (one still on the old ids waits for its rebuild). */
    private suspend fun sweep() {
        for (acc in XtreamRepository.uiState.value.accounts) {
            if (acc.sourceType != SOURCE_TYPE_M3U_URL && acc.sourceType != SOURCE_TYPE_M3U_FILE) continue
            if ((IptvContentDb.ingestMeta(acc.id)?.idScheme ?: 0) < M3U_ID_SCHEME) continue
            run(acc)
        }
    }

    /**
     * T1: one process-wide observer (never stacked) — a sweep whenever the set of saved IPTV ids or the
     * playlists change (a remote pull, a profile's stores finishing their load). Debounced; an unchanged
     * set (playback progress ticking) does nothing, and a sweep with nothing new is a no-op ([ledger]).
     */
    @OptIn(FlowPreview::class)
    private fun watchSavedIds() {
        if (!watching.compareAndSet(expect = false, update = true)) return
        scope.launch {
            savedIptvIds().distinctUntilChanged().debounce(SWEEP_DEBOUNCE_MS).collect { sweep() }
        }
    }

    private fun savedIptvIds(): Flow<Pair<List<String>, Set<String>>> = combine(
        XtreamRepository.uiState,
        LibraryRepository.localItems,
        WatchProgressRepository.uiState,
        WatchedRepository.uiState,
        XtreamLiveRecents.recents,
    ) { playlists, library, progress, watched, recents ->
        val ids = HashSet<String>()
        library.forEach { ids += it.id }
        progress.entries.forEach { ids += it.videoId; ids += it.parentMetaId }
        watched.items.forEach { ids += it.id; it.videoId?.let { v -> ids += v } }
        recents.forEach { ids += it.contentId }
        playlists.accounts.map { it.id } to ids.filterTo(HashSet()) { XtreamItemRegistry.isXtreamId(it) }
    }

    private suspend fun runLocked(acc: XtreamAccount) {
        val prefix = XtreamItemRegistry.accountPrefix(acc.id)
        // 1. Dry run — what is saved under this playlist (no store is written: every rewrite says "leave").
        val wanted = HashSet<String>()
        LibraryRepository.rekeyItems { item -> if (item.id.startsWith(prefix)) wanted += item.id; null }
        WatchProgressRepository.rekeyEntries { e ->
            if (e.videoId.startsWith(prefix)) wanted += e.videoId
            if (e.parentMetaId.startsWith(prefix)) wanted += e.parentMetaId
            null
        }
        WatchedRepository.rekeyItems({ w ->
            if (w.id.startsWith(prefix)) wanted += w.id
            w.videoId?.takeIf { it.startsWith(prefix) }?.let { wanted += it }
            null
        })
        XtreamLiveRecents.rekeyIds { id -> if (id.startsWith(prefix)) wanted += id; null }
        PinSuggestions.store.rekeyIds { id -> if (id.startsWith(prefix)) wanted += id; null }
        if (wanted.isEmpty()) return
        // T1: only ids not yet checked against this catalog need it read (none = a free re-run).
        val toCheck = ledger.toCheck(acc.id, wanted)
        if (toCheck.isEmpty()) return

        // 2. Plan from the catalog, restricted to what is saved.
        val login = if (acc.sourceType == SOURCE_TYPE_M3U_FILE) null else M3uIdentity.loginOf(acc.baseUrl)
        val renames = LinkedHashMap<String, String>()
        val promoted = LinkedHashMap<String, M3uIdRekey.Promoted>()
        fun absorb(plan: M3uIdRekey.Plan) {
            plan.renames.forEach { (old, new) -> if (old in toCheck) renames[old] = new }
            plan.promoted.forEach { (old, p) -> if (old in toCheck) promoted[old] = p }
        }
        for (table in listOf("channels", "vod")) {
            var offset = 0
            while (true) {
                val urls = IptvContentDb.pageUrls(acc.id, table, offset, PAGE)
                if (urls.isEmpty()) break
                absorb(
                    if (table == "channels") M3uIdRekey.plan(acc.id, login, urls, emptyList(), emptyList())
                    else M3uIdRekey.plan(acc.id, login, emptyList(), urls, emptyList()),
                )
                offset += urls.size
            }
        }
        var offset = 0
        while (true) {
            val refs = IptvContentDb.pageEpisodeRefs(acc.id, offset, PAGE)
            if (refs.isEmpty()) break
            absorb(
                M3uIdRekey.plan(
                    acc.id, login, emptyList(), emptyList(),
                    refs.map { M3uIdRekey.EpisodeLine(it.url, it.seriesSid, it.season, it.episode, it.seriesName) },
                ),
            )
            offset += refs.size
        }
        // A promoted line's old movie id is only promoted when no current movie owns it (plan-local rule);
        // across pages the movie pass ran first, so a renamed movie id is never also promoted.
        renames.keys.forEach { promoted.remove(it) }
        val plan = M3uIdRekey.Plan(renames, promoted)
        ledger.settle(acc.id, wanted, plan)
        if (plan.isEmpty) return

        // 3. Counts first, then the synced writes.
        log.i { "B64 re-key playlist=${acc.name}: saved=${wanted.size} renames=${renames.size} promoted=${promoted.size}" }
        val library = LibraryRepository.rekeyItems(plan::library)
        val progress = WatchProgressRepository.rekeyEntries(plan::progress)
        // Series ids never change on the phone (only promoted movies join a series), so the
        // fully-watched series keys need no rewrite.
        val watched = WatchedRepository.rekeyItems(plan::watched)
        val recents = XtreamLiveRecents.rekeyIds(plan::contentId)
        PinSuggestions.store.rekeyIds(plan::contentId)
        log.i { "B64 re-key applied playlist=${acc.name}: library=$library progress=$progress watched=$watched recents=$recents" }
    }
}

private const val SWEEP_DEBOUNCE_MS = 1_000L

/** B64: the M3U catalog item-id scheme this build writes (1 = raw-URL ids; 2 = login-free ids + D2 series). */
internal const val M3U_ID_SCHEME = 2
