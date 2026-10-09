package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Drives the IPTV hub. Category lists (per account + section) are cached in memory so switching
 * sections/accounts is instant — no reload flash — and category items are fetched lazily, for the
 * rows scrolled into view plus a small bounded lookahead ([prefetchCategory]) so the next rows
 * arrive filled in rather than as shimmer. On launch it kicks a THROTTLED background prefetch of
 * every section's category list so the first switch is already warm; the throttle (a monotonic
 * mark) means rapidly re-foregrounding the app won't hammer the panel.
 */
/**
 * Endless-scroll window merge with a hard loop guard. The category row re-triggers loadMore on
 * every items.size change, so a window that returns rows ALREADY loaded (a stale index, or a tied
 * ORDER BY overlapping its pages) would append dupes, grow size, re-trigger, and spin forever —
 * the "category rotating on a loop" bug. Only NEW ids extend the list, and a window that adds
 * nothing new ends paging (hasMore=false) regardless of what the fetch claimed.
 */
internal fun <T> mergePagedWindow(existing: List<T>, more: List<T>, hasMore: Boolean, id: (T) -> String): Pair<List<T>, Boolean> {
    val seen = existing.mapTo(HashSet(existing.size), id)
    val fresh = more.filter { id(it) !in seen }
    return (existing + fresh) to (hasMore && fresh.isNotEmpty())
}

object XtreamHubRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(XtreamHubUiState())
    val uiState: StateFlow<XtreamHubUiState> = _uiState.asStateFlow()

    // Now/next EPG per live channel, kept separate from uiState so a per-channel fetch doesn't
    // recompose the whole hub. Fetched lazily as channel tiles scroll into view.
    private val _epg = MutableStateFlow<Map<String, ChannelEpg>>(emptyMap())
    val epg: StateFlow<Map<String, ChannelEpg>> = _epg.asStateFlow()
    private val epgFetched = mutableSetOf<String>()

    /**
     * Bumped every time new guide data lands ([onGuideDataChanged]). Open guide surfaces (docked
     * guide, hub tiles) watch it to re-ask rows that answered empty before the data arrived — see
     * [GuideDataRefreshPolicy].
     */
    private val _guideDataGeneration = MutableStateFlow(0L)
    val guideDataGeneration: StateFlow<Long> = _guideDataGeneration.asStateFlow()

    // (accountId, section) -> category list, each carrying its own lazily-loaded items.
    // Guarded by categoryLock: several item fetches run at once and every one of them rewrites
    // this map from a background dispatcher.
    private val categoryLock = SynchronizedObject()
    private val cache = mutableMapOf<Pair<String, XtreamHubSection>, List<XtreamHubCategory>>()

    init {
        // Lazily enriched posters (PosterEnricher) patch loaded rows in place — the DB row is
        // already updated, this just repaints cards that are on screen right now.
        scope.launch {
            com.nuvio.app.features.iptv.match.PosterEnricher.updates.collect { applyPosterUpdate(it) }
        }
    }
    private var lastPrefetchMark: TimeMark? = null
    private val REFRESH_TTL = 6.hours

    // --- category-item fetch bounds ---------------------------------------------------
    // A single category can be tens of MB of JSON on a real panel, so the row lookahead must never
    // turn into a fan-out: at most MAX_CONCURRENT_CATEGORY_LOADS responses are ever being parsed at
    // once, and best-effort prefetches are dropped as soon as MAX_OUTSTANDING_CATEGORY_LOADS
    // fetches are already claimed (which also makes prefetch back off by itself while the user
    // flings and the visible rows are hogging the pipe).
    /**
     * Rows per category window (item 5). A category is loaded window-by-window — first window on
     * row-compose, the next appended when the row nears its end — instead of materializing a
     * 10k-item category as one List (the reason "M3U has a DB" still bloated the heap).
     */
    private const val PAGE_SIZE = 400

    private const val MAX_CONCURRENT_CATEGORY_LOADS = 3
    private const val MAX_OUTSTANDING_CATEGORY_LOADS = 6
    private val categoryLoadGate = Semaphore(MAX_CONCURRENT_CATEGORY_LOADS)

    /**
     * How many categories keep their loaded items in memory at once.
     *
     * [cache] used to only ever grow: nothing but a profile switch or a playlist edit emptied it,
     * so a session that browsed several sections across two playlists retained every item it had
     * ever seen — and each of those items is retained a second time by [XtreamItemRegistry].
     * Past this cap the least-recently-loaded category drops its items and reloads if the user
     * scrolls back to it, which is a cheap category fetch (or a local DB read for M3U).
     */
    private const val MAX_LOADED_CATEGORIES = 40
    private val loadedOrder = ArrayDeque<CategoryKey>()

    /**
     * Categories with a fetch claimed (queued or running). This — not XtreamHubCategory.loading —
     * is the single-flight guard: the lookahead means several rows ask for the same category, and a
     * category-list refresh rebuilds the list with `loading` cleared, so that flag alone can be
     * lost mid-fetch.
     */
    private val inFlightCategories = mutableSetOf<CategoryKey>()

    /**
     * Live category-load jobs, keyed by the account that owns them.
     *
     * Measured on an S24 (HubTrace, 2026-08-16): at the moment of a playlist switch the OLD
     * provider had `inFlight=36..40` category loads holding the shared 3-permit gate, so the new
     * provider's first rows waited 5.4-6.2 s just for a permit before any network happened. The
     * jobs are cancellable and nobody is looking at their results, so a switch cancels them —
     * StreamVault's named-job discipline (guideFallbackJob?.cancel()) applied to this gate.
     */
    private val categoryJobs = mutableMapOf<String, MutableList<kotlinx.coroutines.Job>>()

    private data class CategoryKey(
        val accountId: String,
        val section: XtreamHubSection,
        val categoryId: String,
    )

    /** Sync accounts, show the current section (from cache if warm), and prefetch the rest. */
    private var overlayObserving = false
    @Volatile private var overlaySnapshot = com.nuvio.app.features.iptv.overlay.OverlaySnapshot()
    /** Re-apply the overlay to the current section when a hide/reorder edit lands (native or synced). */
    private fun observeOverlay() {
        if (overlayObserving) return
        overlayObserving = true
        com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.ensureLoaded()
        scope.launch {
            com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.uiState.collect {
                overlaySnapshot = it
                // Group membership or a channel hide may have changed: re-resolve groups when next shown.
                synchronized(categoryLock) { groupItems.clear() }
                val st = _uiState.value
                val acc = st.selectedAccountId ?: return@collect
                showSection(acc, st.section)
            }
        }
    }

    fun ensureLoaded() {
        observeOverlay()
        XtreamRepository.ensureLoaded()
        // Warm the canonical-EPG mirror (12h TTL, no-op when fresh) — it backs the hub's
        // now/next whenever the panel's own EPG is missing.
        scope.launch { com.nuvio.app.features.epg.EpgMirrorRepository.ensureFresh() }
        val all = XtreamRepository.uiState.value.accounts
        val accounts = all.filter { it.enabled }
        val current = _uiState.value
        // Fix 1 (sticky provider): a fresh hub state (cold start, or right after resetForProfile)
        // restores the last on-screen provider + tab; a live in-memory selection is the truth.
        val remembered = if (current.selectedAccountId == null) {
            parseHubSelection(XtreamAccountStorage.loadHubSelectionJson(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId))
        } else null
        val selected = resolveStickyAccount(current.selectedAccountId, remembered?.accountId, all)
        val wanted = remembered?.let { resolveStickySection(it.section, current.section) } ?: current.section
        val section = clampSection(accounts.firstOrNull { it.id == selected }, wanted)
        _uiState.update { it.copy(accounts = accounts, accountsLoaded = true, selectedAccountId = selected, section = section) }
        if (selected != null) {
            showSection(selected, section)
            maybePrefetch(selected)
        }
    }

    /**
     * Keeps the hub in step with the playlist store while it is on screen — the screen runs this
     * under `repeatOnLifecycle(RESUMED)`, so it never works for a tab nobody is looking at.
     *
     * The root tab host keeps a visited tab composed, so a one-shot load on first composition went
     * stale: a playlist added in Settings stayed invisible here until an app restart, and a removal
     * (which wipes this state via [resetForProfile]) left a blank tab. This re-syncs on entry and on
     * every change to the store's playlist list or a reset of the hub, and [hubNeedsAccountSync]
     * keeps an unchanged emission a no-op. Event-driven, local-only: it never polls or fetches by
     * itself — [ensureLoaded] only fetches what a newly shown provider needs.
     */
    suspend fun followAccounts() {
        combine(
            XtreamRepository.uiState.map { it.accounts }.distinctUntilChanged(),
            _uiState.map { it.accountsLoaded }.distinctUntilChanged(),
        ) { playlists, _ -> playlists }
            .collect { playlists -> if (hubNeedsAccountSync(_uiState.value, playlists)) ensureLoaded() }
    }

    fun selectAccount(accountId: String) {
        if (_uiState.value.selectedAccountId == accountId) return
        // Whatever browse work the OLD provider still has queued belongs to a screen the user just
        // left — drop it rather than let it drain ahead of the new provider (which may share the
        // same throttled host; measured on-device: Xtream posters waiting minutes behind an
        // abandoned Stalker scroll backlog).
        com.nuvio.app.features.iptv.stalker.StalkerPlaybackTraffic.onProviderSwitched()
        com.nuvio.app.core.diag.HubTrace.log("hub", "selectAccount") { "to=$accountId" }
        // The old provider's queued poster fetches and in-flight category loads are work for a
        // screen the user just left — and they hold the very gates the new provider now needs.
        com.nuvio.app.features.iptv.match.PosterEnricher.onProviderSwitched(accountId)
        cancelCategoryJobsExcept(accountId)
        val section = clampSection(accountFor(accountId), _uiState.value.section)
        _uiState.update { it.copy(selectedAccountId = accountId, section = section) }
        rememberSelection()
        showSection(accountId, section)
        maybePrefetch(accountId)
    }

    fun selectSection(section: XtreamHubSection) {
        if (_uiState.value.section == section) return
        val accountId = _uiState.value.selectedAccountId ?: return
        _uiState.update { it.copy(section = section) }
        rememberSelection()
        showSection(accountId, section)
    }

    /** Fix 1: persist the on-screen provider + tab (device-local, per profile) for the next entry. */
    private fun rememberSelection() {
        val st = _uiState.value
        XtreamAccountStorage.saveHubSelectionJson(
            com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
            encodeHubSelection(XtreamHubSelection(accountId = st.selectedAccountId, section = st.section.name)),
        )
    }

    /** Re-fetch the current section's category list after a failed load (error-card retry). */
    fun retryCategories() {
        val state = _uiState.value
        val accountId = state.selectedAccountId ?: return
        // User-driven retry: clear the panel breaker FIRST (WP6) so it can never fast-fail the
        // very attempt the user just asked for.
        accountFor(accountId)?.let { IptvPanelGuard.resetForAccount(it) }
        showSection(accountId, state.section)
    }

    /** Show cached categories instantly, else fetch the (cheap) category list. */
    private fun showSection(accountId: String, section: XtreamHubSection) {
        if (accountFor(accountId)?.typeEnabled(section.contentKey) == false) {
            // Disabled content type: never fetched, nothing shown.
            _uiState.update { it.copy(categories = emptyList(), loadingCategories = false, loadError = null) }
            return
        }
        val cached = cachedCategories(accountId, section)
        if (cached != null) {
            // Serve the cache, but STILL apply the personalization overlay (the cache holds the raw
            // provider list; hiding/reordering a category must show without a re-fetch).
            _uiState.update { it.copy(categories = applyCategoryOverlay(accountId, section.contentKey, cached), loadingCategories = false, loadError = null) }
            return
        }
        _uiState.update { it.copy(categories = emptyList(), loadingCategories = true, loadError = null) }
        scope.launch { fetchCategoryList(accountId, section) }
    }

    /**
     * Apply the personalization overlay to a hub category list: hidden categories removed, pinned/
     * reordered, renamed (keyed on the durable category identity). Degrades to the raw list if the
     * overlay store is momentarily unavailable. Custom-group rows are a follow-on (they need member
     * loading); the guide already carries channel-level hide/pin/order.
     */
    private fun applyCategoryOverlay(accountId: String, contentType: String, cats: List<XtreamHubCategory>): List<XtreamHubCategory> {
        val overlaid = applyCategoryOverlayToRows(accountId, contentType, cats)
        if (contentType != CONTENT_TYPE_LIVE) return overlaid
        // F10: cleaned channel names when the user opted in for this playlist — applied BEFORE the
        // channel overlay so an explicit rename still wins.
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }
        val shown = if (account?.cleanChannelNames == true) {
            overlaid.map { cat -> cat.copy(items = cat.items.map { it.copy(name = account.displayChannelName(it.name)) }) }
        } else {
            overlaid
        }
        // UX73 + B108: the cache holds each row's RAW provider window, and the channel overlay (hide,
        // rename, pin marker + float) is applied here, every time rows are shown. So an edit made
        // after a row loaded (from the guide, here, or the website) and its undo (un-hide, un-pin,
        // un-rename) show at once, with no re-fetch.
        val channels = overlaySnapshot.channels
        if (channels.isEmpty()) return shown
        val entities = synchronized(categoryLock) { liveEntityIds.toMap() }
        val policy = com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy
        return shown.map { cat ->
            val kept = if (isCustomGroupRow(cat.id)) {
                // A custom group's row keeps the group's own order and names; only hides apply.
                policy.visibleInHub(cat.items, channels) { entities[it.id] }
            } else {
                policy.hubRow(
                    items = cat.items,
                    overlay = channels,
                    entityOf = { entities[it.id] },
                    withName = { card, newName -> card.copy(name = newName) },
                    withPinned = { card -> card.copy(pinned = true) },
                )
            }
            if (kept === cat.items) cat else cat.copy(items = kept)
        }
    }

    /**
     * Live card id -> its canon-v1 entity id, recorded as rows load (a card's name may already carry
     * a rename, so the identity can't be re-derived from it). Lets a hide made after a row loaded
     * drop the card, and lets a long-press on a card hide it.
     */
    private val liveEntityIds = mutableMapOf<String, String>()

    private fun rememberLiveEntities(previewIds: List<String>, entityIds: List<String>) {
        synchronized(categoryLock) { previewIds.zip(entityIds).forEach { (id, entity) -> liveEntityIds[id] = entity } }
    }

    /** UX36: the hide target for a live card in the hub, or null when its identity isn't known here. */
    internal fun hideTargetFor(contentId: String): com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy.HideTarget? {
        val entity = synchronized(categoryLock) { liveEntityIds[contentId] }
        return com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy.hideTarget(
            entity, XtreamItemRegistry.parseId(contentId)?.accountId,
        )
    }

    private fun applyCategoryOverlayToRows(accountId: String, contentType: String, cats: List<XtreamHubCategory>): List<XtreamHubCategory> {
        val overlay = overlaySnapshot.categories
        // F02: custom groups are rows of channels, listed above the provider categories of Live TV.
        // (No editor makes movie or series groups.)
        val groups = if (contentType == CONTENT_TYPE_LIVE) {
            com.nuvio.app.features.iptv.overlay.IptvCustomGroupPolicy.groupsFor(accountId, contentType, overlaySnapshot.groups)
        } else {
            emptyList()
        }
        if (overlay.isEmpty() && groups.isEmpty()) return cats
        val tagged = cats.mapIndexed { i, c ->
            com.nuvio.app.features.iptv.overlay.IptvCategoryOverlayPolicy.TaggedCategory(
                com.nuvio.app.features.iptv.identity.IptvIdentity.categoryKey(accountId, contentType, c.name), i, c.id, c.name,
            )
        }
        val displayed = com.nuvio.app.features.iptv.overlay.IptvCategoryOverlayPolicy.displayed(tagged, overlay, groups)
        val byId = cats.associateBy { it.id }
        return displayed.mapNotNull { d ->
            if (d.custom) {
                val rowId = com.nuvio.app.features.iptv.overlay.IptvCustomGroupPolicy.rowId(d.id)
                val items = groupItems[accountId to rowId]
                XtreamHubCategory(rowId, d.name, items = items.orEmpty(), loaded = items != null, hasMore = false)
            } else {
                byId[d.id]?.copy(name = d.name)
            }
        }
    }

    /** accountId to group row id -> the group's resolved channels; dropped whenever the overlay changes. */
    private val groupItems = mutableMapOf<Pair<String, String>, List<MetaPreview>>()
    private val groupLoads = mutableSetOf<Pair<String, String>>()
    /** The lineup keyed by entity id, rebuilt only when the account's channel list object changes. */
    private var entityIndex: Triple<String, List<XtreamChannel>, Map<String, XtreamChannel>>? = null

    /** F02: resolve a custom group's members against the playlist's lineup, then repaint the section. */
    private fun loadGroupRow(accountId: String, rowId: String) {
        val key = accountId to rowId
        val claimed = synchronized(categoryLock) { key !in groupItems && groupLoads.add(key) }
        if (!claimed) return
        scope.launch {
            try {
                val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return@launch
                val groupId = com.nuvio.app.features.iptv.overlay.IptvCustomGroupPolicy.groupIdOf(rowId) ?: return@launch
                val group = overlaySnapshot.groups.firstOrNull { it.id == groupId } ?: return@launch
                val channels = XtreamSearchIndex.liveChannelsFor(account)
                val index = entityIndex?.takeIf { it.first == accountId && it.second === channels }?.third
                    ?: channels.associateBy {
                        com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(account.id, it.name, it.epgChannelId)
                    }.also { entityIndex = Triple(accountId, channels, it) }
                val members = com.nuvio.app.features.iptv.overlay.IptvCustomGroupPolicy.members(group, index, overlaySnapshot.channels)
                members.forEach { XtreamItemRegistry.registerChannel(account.id, it) }
                val previews = members.map { it.toMetaPreview(account.id) }
                rememberLiveEntities(
                    previews.map { it.id },
                    members.map { com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(account.id, it.name, it.epgChannelId) },
                )
                synchronized(categoryLock) { groupItems[key] = previews.distinctBy { it.id } }
                val st = _uiState.value
                if (st.selectedAccountId == accountId) showSection(accountId, st.section)
            } finally {
                synchronized(categoryLock) { groupLoads.remove(key) }
            }
        }
    }

    /** True for a custom group's row (it is managed on the website, not hidden from here). */
    fun isCustomGroupRow(categoryId: String): Boolean =
        com.nuvio.app.features.iptv.overlay.IptvCustomGroupPolicy.groupIdOf(categoryId) != null

    /**
     * A fetched LIVE window of the hub, as the row cache keeps it: RAW (provider order, provider
     * names, nothing hidden, no pin marker). The channel overlay is applied when rows are shown
     * ([applyCategoryOverlay] -> IptvChannelQuickActionsPolicy.hubRow), so an un-pin, un-rename or
     * un-hide made after the row loaded shows without a re-fetch (B108, K9). The raw window also
     * keeps the next window's offset equal to the raw provider count the paging indexes.
     * Records each card's canon-v1 identity on the way through; [entityIds] is parallel to
     * [previews] (same order/length).
     */
    private fun rawLiveWindow(entityIds: List<String>, previews: List<MetaPreview>): List<MetaPreview> {
        rememberLiveEntities(previews.map { it.id }, entityIds)
        return previews
    }

    private suspend fun fetchCategoryList(accountId: String, section: XtreamHubSection) {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return
        // Xtream reads its section rows from the local catalog once it's built (P7, item 4) —
        // no per-session category fetch. Index absent (first run): fall through to the live
        // API below and kick the build so the NEXT visit is local.
        if (account.sourceType == SOURCE_TYPE_XTREAM) {
            val stored = runCatching { XtreamMatchIndex.categoriesFor(account.id, section.matchKind) }.getOrDefault(emptyList())
            if (stored.isNotEmpty()) {
                val merged = synchronized(categoryLock) {
                    val previous = cache[accountId to section].orEmpty().associateBy { it.id }
                    val next = stored.map { (id, name) ->
                        val old = previous[id]
                        XtreamHubCategory(id, name, items = old?.items ?: emptyList(), loaded = old?.loaded ?: false, hasMore = old?.hasMore ?: false)
                    }
                    cache[accountId to section] = next
                    next
                }
                val shown = applyCategoryOverlay(account.id, section.contentKey, merged)
                if (isCurrent(accountId, section)) {
                    _uiState.update { it.copy(categories = shown, loadingCategories = false, loadError = null) }
                }
                return
            }
            XtreamTmdbResolver.warmUp(listOf(account))
        }
        val client = IptvClient.forAccount(account)   // xtream -> XtreamClient, m3u_url -> M3UClient
        val outcome = when (section) {
            XtreamHubSection.LIVE -> client.liveCategories(account)
            XtreamHubSection.MOVIES -> client.vodCategories(account)
            XtreamHubSection.SERIES -> client.seriesCategories(account)
        }
        val fresh = outcome.getOrNull() ?: run {
            // Failed fetch: keep any warm cache, but if there's none the section would otherwise spin
            // forever — surface an error so the user knows the portal is unreachable, not just slow.
            // The throwable is CLASSIFIED rather than discarded: a WAF block and a portal that
            // refused this device are both reachable portals, and saying otherwise sends the viewer
            // to debug their provider's uptime instead of the thing that is actually wrong.
            if (isCurrent(accountId, section) && cachedCategories(accountId, section) == null) {
                val failure = IptvLoadFailurePolicy.classify(
                    outcome.exceptionOrNull(),
                    // The server that actually failed last (Step 0.3b) — with backups that is not always the main one.
                    host = IptvPanelGuard.panelOriginUrlOf(account, PlaylistServerFailover.lastFailedServerUrl(account) ?: account.baseUrl),
                )
                _uiState.update { it.copy(loadingCategories = false, loadError = failure) }
            }
            return
        }
        // Merge: carry over already-loaded items for categories that still exist.
        val merged = synchronized(categoryLock) {
            val previous = cache[accountId to section].orEmpty().associateBy { it.id }
            val next = fresh.map { cat ->
                val old = previous[cat.id]
                XtreamHubCategory(cat.id, cat.name, items = old?.items ?: emptyList(), loaded = old?.loaded ?: false)
            }
            cache[accountId to section] = next
            next
        }
        val shown = applyCategoryOverlay(account.id, section.contentKey, merged)
        if (isCurrent(accountId, section)) {
            _uiState.update { it.copy(categories = shown, loadingCategories = false, loadError = null) }
        }
    }

    /** Lazily fetch one category's items (called when its row first composes). */
    fun loadCategory(categoryId: String) {
        requestCategory(categoryId, prefetch = false)
    }

    /**
     * Warm a category the user hasn't scrolled to yet, so its row lands with real posters and names
     * instead of shimmer. Best-effort by design: dropped whenever enough fetches are already
     * outstanding, so flinging through hundreds of categories can never queue unbounded work.
     */
    fun prefetchCategory(categoryId: String) {
        requestCategory(categoryId, prefetch = true)
    }

    private fun requestCategory(categoryId: String, prefetch: Boolean) {
        val state = _uiState.value
        val accountId = state.selectedAccountId ?: return
        val section = state.section
        if (isCustomGroupRow(categoryId)) {
            loadGroupRow(accountId, categoryId)
            return
        }
        val category = cachedCategories(accountId, section)?.firstOrNull { it.id == categoryId } ?: return
        if (category.loaded) return
        val key = CategoryKey(accountId, section, categoryId)
        // Claim the fetch atomically: a visible row always gets one, a prefetch only while the pipe
        // has room.
        val claimed = synchronized(categoryLock) {
            when {
                key in inFlightCategories -> false
                prefetch && inFlightCategories.size >= MAX_OUTSTANDING_CATEGORY_LOADS -> false
                else -> inFlightCategories.add(key)
            }
        }
        if (!claimed) return
        updateCategory(accountId, section, categoryId) { it.copy(loading = true) }
        com.nuvio.app.core.diag.HubTrace.log("category", "claimed") { "cat=$categoryId prefetch=$prefetch inFlight=${inFlightCategories.size}" }
        val job = scope.launch {
            var completed = false
            val tClaim = com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs()
            try {
                categoryLoadGate.withPermit {
                    com.nuvio.app.core.diag.HubTrace.log("category", "gotPermit") {
                        "cat=$categoryId waited=${com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - tClaim}ms"
                    }
                    val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }
                    val client = account?.let { IptvClient.forAccount(it) }
                    val (items, hasMore) = if (account == null || client == null) emptyList<MetaPreview>() to false
                    else fetchWindow(account, section, categoryId, offset = 0, prefetch = prefetch)
                    com.nuvio.app.core.diag.HubTrace.log("category", "fetched") {
                        "cat=$categoryId n=${items.size} total=${com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - tClaim}ms"
                    }
                    // Dedup the initial window by id BEFORE it becomes Lazy-list keys: a provider
                    // can return the same stream_id twice in one page, and a duplicate Compose `key`
                    // is a hard crash (a message-less SIGABRT on iOS, `Key … already used` on Android).
                    // Appended pages are already deduped by mergePagedWindow; the initial window was the gap.
                    updateCategory(accountId, section, categoryId) { it.copy(items = items.distinctBy { it.id }, loaded = true, loading = false, hasMore = hasMore) }
                    noteLoadedAndEvict(key)
                    completed = true
                }
            } finally {
                synchronized(categoryLock) { inFlightCategories.remove(key) }
                // Never strand a row as permanently "loading" if the fetch was cancelled.
                if (!completed) updateCategory(accountId, section, categoryId) { it.copy(loading = false) }
            }
        }
        synchronized(categoryLock) { categoryJobs.getOrPut(accountId) { mutableListOf() }.add(job) }
        job.invokeOnCompletion { synchronized(categoryLock) { categoryJobs[accountId]?.remove(job) } }
    }

    /**
     * Cancels category loads belonging to providers other than [keepAccountId].
     *
     * They hold the shared category gate and nobody is waiting on their results; leaving them to
     * finish is what made a switched-to playlist wait ~5.5 s for its first permit.
     */
    private fun cancelCategoryJobsExcept(keepAccountId: String) {
        val doomed = synchronized(categoryLock) {
            val others = categoryJobs.filterKeys { it != keepAccountId }
            others.keys.forEach { categoryJobs.remove(it) }
            others.values.flatten()
        }
        doomed.forEach { it.cancel() }
        com.nuvio.app.core.diag.HubTrace.log("category", "cancelledOld") { "keep=$keepAccountId n=${doomed.size}" }
    }

    /**
     * Appends the next window to an already-loaded category (item 5) — called by the row when it
     * nears its end. Single-flight per key, best-effort.
     */
    fun loadMore(categoryId: String) {
        val state = _uiState.value
        val accountId = state.selectedAccountId ?: return
        val section = state.section
        val category = cachedCategories(accountId, section)?.firstOrNull { it.id == categoryId } ?: return
        if (!category.loaded || !category.hasMore || category.loading) return
        val key = CategoryKey(accountId, section, categoryId)
        val claimed = synchronized(categoryLock) {
            if (key in inFlightCategories) false else inFlightCategories.add(key)
        }
        if (!claimed) return
        scope.launch {
            try {
                val account = accountFor(accountId) ?: return@launch
                val offset = cachedCategories(accountId, section)
                    ?.firstOrNull { it.id == categoryId }?.items?.size ?: return@launch
                val (more, hasMore) = fetchWindow(account, section, categoryId, offset)
                updateCategory(accountId, section, categoryId) { cat ->
                    val (items, more2) = mergePagedWindow(cat.items, more, hasMore) { it.id }
                    cat.copy(items = items, hasMore = more2)
                }
            } finally {
                synchronized(categoryLock) { inFlightCategories.remove(key) }
            }
        }
    }

    /**
     * One window of a category's items, per source (item 4+5), registered as a batch:
     *  - Xtream w/ built catalog: local index window; stream URLs rebuilt from creds.
     *  - Xtream first-run (no index yet): the old full network fetch, once, kept to one window.
     *  - M3U + the Stalker live lineup: paged reads over IptvContentDb.
     *  - Stalker VOD/series: the portal's bounded page (70) — the protocol's own window.
     */
    private suspend fun fetchWindow(
        account: XtreamAccount,
        section: XtreamHubSection,
        categoryId: String,
        offset: Int,
        prefetch: Boolean = false,
    ): Pair<List<MetaPreview>, Boolean> {
        val accountId = account.id
        when (account.sourceType) {
            SOURCE_TYPE_XTREAM -> {
                val kind = section.matchKind
                if (XtreamMatchIndex.builtAt(accountId, kind) != null) {
                    val rows = XtreamMatchIndex.itemsFor(accountId, kind, categoryId, offset, PAGE_SIZE + 1)
                    val page = rows.take(PAGE_SIZE)
                    // Panels that ship no icons in the bulk list (or rows indexed before artwork
                    // was known) get filled lazily: ask get_vod_info per null row, in list order,
                    // while the user is on this window. Results land in the DB + patch in via
                    // the PosterEnricher.updates collector below.
                    page.filter { it.poster == null }.map { it.sid }
                        .takeIf { it.isNotEmpty() }
                        ?.let { com.nuvio.app.features.iptv.match.PosterEnricher.enqueue(account, kind, it, prioritize = !prefetch) }
                    val resolved = page.map { r ->
                        when (section) {
                            XtreamHubSection.LIVE -> XtreamResolvedItem(
                                XtreamItemRegistry.liveId(accountId, r.sid), accountId, XtreamKind.LIVE,
                                r.name, XtreamClient.liveStreamUrl(account, r.sid), logo = r.poster, streamType = "live",
                            )
                            XtreamHubSection.MOVIES -> XtreamResolvedItem(
                                XtreamItemRegistry.vodId(accountId, r.sid), accountId, XtreamKind.VOD,
                                r.name, XtreamClient.movieStreamUrl(account, r.sid, r.ext ?: "mp4"), poster = r.poster,
                            )
                            XtreamHubSection.SERIES -> XtreamResolvedItem(
                                XtreamItemRegistry.seriesId(accountId, r.sid), accountId, XtreamKind.SERIES,
                                r.name, null, poster = r.poster,
                            )
                        }
                    }
                    XtreamItemRegistry.registerAll(resolved)
                    val previews = resolved.map { it.toMetaPreview() }
                    // LIVE hub rows: record each card's identity for the display-time overlay; page + previews are 1:1.
                    val shown = if (section == XtreamHubSection.LIVE) {
                        val entityIds = page.map {
                            com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(accountId, it.name, it.epgId)
                        }
                        rawLiveWindow(entityIds, previews)
                    } else previews
                    return shown to (rows.size > PAGE_SIZE)
                }
                // No index yet (first run): the old whole-category fetch — the build is warming.
                // Keep only the first window of it: registering a 10k-item category (rows +
                // registry + Compose state) was a first-launch heap spike, and the index that is
                // building right now serves the full set with real paging once categories reload
                // (eviction, section switch, or next visit).
                if (offset > 0) return emptyList<MetaPreview>() to false
                val client = IptvClient.forAccount(account)
                return when (section) {
                    XtreamHubSection.LIVE -> client.liveChannels(account, categoryId).getOrDefault(emptyList()).take(PAGE_SIZE).let { rows ->
                        XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedChannel(accountId, it) })
                        val entityIds = rows.map {
                            com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(accountId, it.name, it.epgChannelId)
                        }
                        rawLiveWindow(entityIds, rows.map { it.toMetaPreview(accountId) })
                    }
                    XtreamHubSection.MOVIES -> client.vodMovies(account, categoryId).getOrDefault(emptyList()).take(PAGE_SIZE).let { rows ->
                        XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedMovie(accountId, it) })
                        rows.map { it.toMetaPreview(accountId) }
                    }
                    XtreamHubSection.SERIES -> client.series(account, categoryId).getOrDefault(emptyList()).take(PAGE_SIZE).let { rows ->
                        XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedSeries(accountId, it) })
                        rows.map { it.toMetaPreview(accountId) }
                    }
                } to false
            }
            SOURCE_TYPE_STALKER -> {
                if (section == XtreamHubSection.LIVE) {
                    val rows = com.nuvio.app.features.iptv.stalker.StalkerClient
                        .liveChannelsPage(account, categoryId, offset, PAGE_SIZE + 1)
                    val page = rows.take(PAGE_SIZE)
                    XtreamItemRegistry.registerAll(page.map { XtreamItemRegistry.resolvedChannel(accountId, it) })
                    val entityIds = page.map {
                        com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(accountId, it.name, it.epgChannelId)
                    }
                    return rawLiveWindow(entityIds, page.map { it.toMetaPreview(accountId) }) to (rows.size > PAGE_SIZE)
                }
                if (offset > 0) return emptyList<MetaPreview>() to false
                val client = IptvClient.forAccount(account)
                return when (section) {
                    XtreamHubSection.MOVIES -> client.vodMovies(account, categoryId).getOrDefault(emptyList()).let { rows ->
                        XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedMovie(accountId, it) })
                        rows.map { it.toMetaPreview(accountId) }
                    }
                    else -> client.series(account, categoryId).getOrDefault(emptyList()).let { rows ->
                        XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedSeries(accountId, it) })
                        rows.map { it.toMetaPreview(accountId) }
                    }
                } to false
            }
            else -> {
                // M3U: ensure the catalog, then a paged indexed read.
                M3UClient.ensureIngested(account)
                return when (section) {
                    XtreamHubSection.LIVE -> M3UClient.liveChannelsPage(account, categoryId, offset, PAGE_SIZE + 1).let { rows ->
                        val page = rows.take(PAGE_SIZE)
                        XtreamItemRegistry.registerAll(page.map { XtreamItemRegistry.resolvedChannel(accountId, it) })
                        val entityIds = page.map {
                            com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(accountId, it.name, it.epgChannelId)
                        }
                        rawLiveWindow(entityIds, page.map { it.toMetaPreview(accountId) }) to (rows.size > PAGE_SIZE)
                    }
                    XtreamHubSection.MOVIES -> M3UClient.vodMoviesPage(account, categoryId, offset, PAGE_SIZE + 1).let { rows ->
                        val page = rows.take(PAGE_SIZE)
                        XtreamItemRegistry.registerAll(page.map { XtreamItemRegistry.resolvedMovie(accountId, it) })
                        page.map { it.toMetaPreview(accountId) } to (rows.size > PAGE_SIZE)
                    }
                    XtreamHubSection.SERIES -> M3UClient.seriesPage(account, categoryId, offset, PAGE_SIZE + 1).let { rows ->
                        val page = rows.take(PAGE_SIZE)
                        XtreamItemRegistry.registerAll(page.map { XtreamItemRegistry.resolvedSeries(accountId, it) })
                        page.map { it.toMetaPreview(accountId) } to (rows.size > PAGE_SIZE)
                    }
                }
            }
        }
    }

    /** Background-refresh every section's category list on launch, throttled to once per TTL. */
    private fun maybePrefetch(accountId: String) {
        val mark = lastPrefetchMark
        if (mark != null && mark.elapsedNow() < REFRESH_TTL) return
        lastPrefetchMark = TimeSource.Monotonic.markNow()
        scope.launch {
            val account = accountFor(accountId)
            for (section in XtreamHubSection.entries) {
                if (account?.typeEnabled(section.contentKey) == false) continue  // disabled type: skip fetch
                fetchCategoryList(accountId, section)
            }
        }
    }

    /** Keep the shown section one the account actually has enabled. */
    private fun clampSection(account: XtreamAccount?, wanted: XtreamHubSection): XtreamHubSection {
        if (account == null || account.typeEnabled(wanted.contentKey)) return wanted
        return XtreamHubSection.entries.firstOrNull { account.typeEnabled(it.contentKey) } ?: wanted
    }

    private val XtreamHubSection.matchKind: MatchKind
        get() = when (this) {
            XtreamHubSection.LIVE -> MatchKind.LIVE
            XtreamHubSection.MOVIES -> MatchKind.MOVIE
            XtreamHubSection.SERIES -> MatchKind.SERIES
        }

    private fun accountFor(accountId: String?): XtreamAccount? =
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }

    /**
     * Lazily fetch now/next EPG for a live channel (called when its tile scrolls into view).
     *
     * Through [TileEpgQueue], never a bare launch: each tile used to hold its place in line
     * forever, so a hard scroll left the tiles on screen waiting ~30 s behind hundreds that were
     * long gone (S24 field report; STBEmu on the same portal navigates instantly because it only
     * ever asks for what a MAG shows). Newest-first + a hard cap keeps the on-screen tiles at the
     * front; an evicted tile releases its once-only mark so a revisit still fetches it.
     */
    /**
     * New guide data has landed (an XMLTV ingest or a mirror sync finished).
     *
     * Both the once-only mark and the queue's cooldown are verdicts about a world that no longer
     * exists, and the once-only mark is the binding one: a tile that asked before the guide
     * arrived answered empty, and `epgFetched` then refused to ask again for the rest of the
     * session — the data sitting on disk beside it. Measured on the emulator (2026-08-18): on a
     * cold playlist two tiles asked 1s apart, the later one joined the in-flight ingest and got
     * its programmes, the earlier one was stuck on "No information" through a tab switch.
     *
     * Clearing both means the next time a tile is asked for it actually resolves; bumping
     * [guideDataGeneration] is what asks the rows already on screen again (the visible self-heal).
     */
    fun onGuideDataChanged() {
        epgFetched.clear()
        TileEpgQueue.invalidate()
        _guideDataGeneration.update { it + 1 }
    }

    fun ensureEpg(contentId: String) {
        if (!epgFetched.add(contentId)) return
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return
        if (parsed.kind != XtreamKind.LIVE) return
        val streamId = parsed.id.toIntOrNull() ?: return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return
        // Warm this account's OWN whole guide (xmltv.php) so the store rung has something to serve
        // and the per-channel asks stop. Fire-and-forget on the ingest's own scope, throttled by a
        // 12h TTL inside — calling it per tile is cheap and idempotent.
        com.nuvio.app.features.iptv.epg.XmltvClient.warm(account)
        // Stalker warms its lineup + the ONE bulk get_epg_info on the client's own scope (no-op for
        // Xtream/M3U — the xmltv line above is their warm). Without this the bulk rides this
        // cancellable tile queue and only completes once scrolling stops. See StalkerClient.warm.
        IptvClient.forAccount(account).warm(account)
        com.nuvio.app.core.diag.HubTrace.log("tileEpg", "enqueue") { contentId }
        TileEpgQueue.enqueue(contentId, onEvicted = { epgFetched.remove(contentId) }) {
            val t0 = com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs()
            // ONE resolution path for the whole app: the tiles resolve exactly as the guide does.
            //
            // This used to be `shortEpg().ifEmpty { mirror }` — the provider-first fallback the
            // ladder was built to replace, still running on the highest-traffic surface. Its one
            // measured failure mode is that present-but-garbage beats absent: wa12's skewed rows
            // (every epoch a zone-offset out, nothing bracketing now) are non-empty, so `ifEmpty`
            // never reached the mirror and the tiles showed the wrong programme instead of the
            // right one. The ladder's sanity gate sends exactly that shape to the mirror, and its
            // session memory stops a mirror-fed channel re-asking the panel on every scroll past.
            val listings = com.nuvio.app.features.iptv.EpgSourceLadder.resolveAndRemember(
                memory = com.nuvio.app.features.iptv.EpgSourceLadder.sessionMemory,
                accountId = account.id,
                streamId = streamId,
                nowMs = t0,
                // F14: the user's own pick of guide channel for this channel, if any.
                manual = EpgSourceLadder.ManualResolver { _, sid, _ ->
                    runCatching { com.nuvio.app.features.iptv.epg.XmltvClient.manualNowNext(account, sid) }.getOrNull()
                },
                // The account's own guide, ingested once into SQLite. Zero network per channel —
                // this is the rung that makes a guide fling cost nothing. Null-safe: an account
                // with no stored guide (no xmltv.php, ingest not run yet) simply answers empty and
                // the ladder falls through to the per-channel ask exactly as before. B10: joined by
                // the ingest's channel map (provider id, then cleaned name), not by the raw id alone.
                store = {
                    runCatching {
                        com.nuvio.app.features.iptv.epg.XmltvClient.storedNowNext(account, streamId)
                    }.getOrDefault(emptyList())
                },
                // null = the ask FAILED. Collapsing that into emptyList() told the ladder
                // "this panel has no EPG for this channel", which is a coverage claim a timeout
                // cannot support — see EpgSourceLadder.Source.UNAVAILABLE.
                provider = {
                    runCatching {
                        IptvClient.forAccount(account).shortEpg(account, streamId).getOrNull()
                    }.getOrNull()
                },
                mirror = {
                    runCatching {
                        com.nuvio.app.features.epg.EpgMirrorRepository
                            .nowNextProgrammes(account.id, streamId, t0)
                    }.getOrDefault(emptyList())
                },
            ).programmes
            com.nuvio.app.core.diag.HubTrace.log("tileEpg", "fetched") {
                "id=$contentId took=${com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - t0}ms n=${listings.size}"
            }
            // The return value is the queue's cooldown signal: a channel no rung could answer for
            // must not be re-asked on every scroll past (TileEpgAdmission).
            if (listings.isEmpty()) return@enqueue false
            val nowIndex = listings.indexOfFirst { it.nowPlaying }.takeIf { it >= 0 } ?: 0
            val now = listings.getOrNull(nowIndex)?.title?.ifBlank { null }
            val next = listings.getOrNull(nowIndex + 1)?.title?.ifBlank { null }
            if (now != null || next != null) {
                _epg.update { it + (contentId to ChannelEpg(now = now, next = next)) }
            }
            true
        }
    }

    /**
     * Marks [key] most-recently-loaded and drops the items of anything past [MAX_LOADED_CATEGORIES].
     * The eviction happens OUTSIDE the lock because [updateCategory] takes it itself.
     */
    private fun noteLoadedAndEvict(key: CategoryKey) {
        val evicted = synchronized(categoryLock) {
            loadedOrder.remove(key)
            loadedOrder.addLast(key)
            val out = ArrayList<CategoryKey>()
            while (loadedOrder.size > MAX_LOADED_CATEGORIES) out.add(loadedOrder.removeFirst())
            out
        }
        for (k in evicted) {
            updateCategory(k.accountId, k.section, k.categoryId) {
                it.copy(items = emptyList(), loaded = false, loading = false)
            }
        }
    }

    fun resetForProfile() {
        synchronized(categoryLock) { cache.clear(); loadedOrder.clear(); groupItems.clear() }
        entityIndex = null
        lastPrefetchMark = null
        epgFetched.clear()
        _epg.value = emptyMap()
        _uiState.value = XtreamHubUiState()
    }

    /**
     * Repaints one lazily-enriched poster on every loaded copy of its row. The index row is
     * already written by PosterEnricher; this touches the in-memory copies: the registry
     * (detail/play path), the category cache (rows on the backstack), and the visible state.
     */
    private fun applyPosterUpdate(u: com.nuvio.app.features.iptv.match.PosterEnricher.PosterUpdate) {
        val section = when (u.kind) {
            MatchKind.MOVIE -> XtreamHubSection.MOVIES
            MatchKind.SERIES -> XtreamHubSection.SERIES
            MatchKind.LIVE -> return
        }
        val cardId = when (section) {
            XtreamHubSection.MOVIES -> XtreamItemRegistry.vodId(u.accountId, u.sid)
            else -> XtreamItemRegistry.seriesId(u.accountId, u.sid)
        }
        XtreamItemRegistry.get(cardId)?.let { XtreamItemRegistry.register(it.copy(poster = u.poster)) }
        val key = u.accountId to section
        val updated = synchronized(categoryLock) {
            val current = cache[key] ?: return
            var touched = false
            val next = current.map { cat ->
                if (cat.items.none { it.id == cardId && it.poster == null }) cat
                else {
                    touched = true
                    cat.copy(items = cat.items.map { if (it.id == cardId) it.copy(poster = u.poster) else it })
                }
            }
            if (!touched) return
            cache[key] = next
            next
        }
        if (isCurrent(u.accountId, section)) _uiState.update { it.copy(categories = applyCategoryOverlay(u.accountId, section.contentKey, updated)) }
    }

    /**
     * F02: hide a provider category of the current section from this device (synced like a website
     * hide). Keyed on the provider's own name from the raw cache, never a renamed display title.
     * Returns the hidden category's name, or null when [categoryId] is not a provider category here.
     */
    fun hideCategory(categoryId: String): String? {
        val st = _uiState.value
        val accountId = st.selectedAccountId ?: return null
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return null
        val raw = cachedCategories(accountId, st.section)?.firstOrNull { it.id == categoryId } ?: return null
        com.nuvio.app.features.iptv.overlay.IptvHiddenItems.hideGroup(account, st.section.contentKey, raw.name)
        return raw.name
    }

    private fun isCurrent(accountId: String, section: XtreamHubSection): Boolean =
        _uiState.value.selectedAccountId == accountId && _uiState.value.section == section

    private fun cachedCategories(accountId: String, section: XtreamHubSection): List<XtreamHubCategory>? =
        synchronized(categoryLock) { cache[accountId to section] }

    private fun updateCategory(
        accountId: String,
        section: XtreamHubSection,
        categoryId: String,
        transform: (XtreamHubCategory) -> XtreamHubCategory,
    ) {
        val key = accountId to section
        val updated = synchronized(categoryLock) {
            val current = cache[key]
            if (current == null) {
                null
            } else {
                val next = current.map { if (it.id == categoryId) transform(it) else it }
                cache[key] = next
                next
            }
        } ?: return
        if (isCurrent(accountId, section)) _uiState.update { it.copy(categories = applyCategoryOverlay(accountId, section.contentKey, updated)) }
    }
}
