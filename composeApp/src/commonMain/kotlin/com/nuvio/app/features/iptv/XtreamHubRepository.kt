package com.nuvio.app.features.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Drives the IPTV hub. Category lists (per account + section) are cached in memory so switching
 * sections/accounts is instant — no reload flash — and category items are fetched lazily, only
 * for the rows actually scrolled into view. On launch it kicks a THROTTLED background prefetch of
 * every section's category list so the first switch is already warm; the throttle (a monotonic
 * mark) means rapidly re-foregrounding the app won't hammer the panel.
 */
object XtreamHubRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(XtreamHubUiState())
    val uiState: StateFlow<XtreamHubUiState> = _uiState.asStateFlow()

    // Now/next EPG per live channel, kept separate from uiState so a per-channel fetch doesn't
    // recompose the whole hub. Fetched lazily as channel tiles scroll into view.
    private val _epg = MutableStateFlow<Map<String, ChannelEpg>>(emptyMap())
    val epg: StateFlow<Map<String, ChannelEpg>> = _epg.asStateFlow()
    private val epgFetched = mutableSetOf<String>()

    // (accountId, section) -> category list, each carrying its own lazily-loaded items.
    private val cache = mutableMapOf<Pair<String, XtreamHubSection>, List<XtreamHubCategory>>()
    private var lastPrefetchMark: TimeMark? = null
    private val REFRESH_TTL = 6.hours

    /** Sync accounts, show the current section (from cache if warm), and prefetch the rest. */
    fun ensureLoaded() {
        XtreamRepository.ensureLoaded()
        // Warm the canonical-EPG mirror (12h TTL, no-op when fresh) — it backs the hub's
        // now/next whenever the panel's own EPG is missing.
        scope.launch { com.nuvio.app.features.epg.EpgMirrorRepository.ensureFresh() }
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        val current = _uiState.value
        val selected = current.selectedAccountId?.takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.firstOrNull()?.id
        val section = clampSection(accounts.firstOrNull { it.id == selected }, current.section)
        _uiState.update { it.copy(accounts = accounts, selectedAccountId = selected, section = section) }
        if (selected != null) {
            showSection(selected, section)
            maybePrefetch(selected)
        }
    }

    fun selectAccount(accountId: String) {
        if (_uiState.value.selectedAccountId == accountId) return
        val section = clampSection(accountFor(accountId), _uiState.value.section)
        _uiState.update { it.copy(selectedAccountId = accountId, section = section) }
        showSection(accountId, section)
        maybePrefetch(accountId)
    }

    fun selectSection(section: XtreamHubSection) {
        if (_uiState.value.section == section) return
        val accountId = _uiState.value.selectedAccountId ?: return
        _uiState.update { it.copy(section = section) }
        showSection(accountId, section)
    }

    /** Show cached categories instantly, else fetch the (cheap) category list. */
    private fun showSection(accountId: String, section: XtreamHubSection) {
        if (accountFor(accountId)?.typeEnabled(section.contentKey) == false) {
            // Disabled content type: never fetched, nothing shown.
            _uiState.update { it.copy(categories = emptyList(), loadingCategories = false) }
            return
        }
        val cached = cache[accountId to section]
        if (cached != null) {
            _uiState.update { it.copy(categories = cached, loadingCategories = false) }
            return
        }
        _uiState.update { it.copy(categories = emptyList(), loadingCategories = true) }
        scope.launch { fetchCategoryList(accountId, section) }
    }

    private suspend fun fetchCategoryList(accountId: String, section: XtreamHubSection) {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return
        val client = IptvClient.forAccount(account)   // xtream -> XtreamClient, m3u_url -> M3UClient
        val fresh = when (section) {
            XtreamHubSection.LIVE -> client.liveCategories(account)
            XtreamHubSection.MOVIES -> client.vodCategories(account)
            XtreamHubSection.SERIES -> client.seriesCategories(account)
        }.getOrNull() ?: return  // keep any existing cache on a failed refresh
        // Merge: carry over already-loaded items for categories that still exist.
        val previous = cache[accountId to section].orEmpty().associateBy { it.id }
        val merged = fresh.map { cat ->
            val old = previous[cat.id]
            XtreamHubCategory(cat.id, cat.name, items = old?.items ?: emptyList(), loaded = old?.loaded ?: false)
        }
        cache[accountId to section] = merged
        if (isCurrent(accountId, section)) {
            _uiState.update { it.copy(categories = merged, loadingCategories = false) }
        }
    }

    /** Lazily fetch one category's items (called when its row first composes). */
    fun loadCategory(categoryId: String) {
        val state = _uiState.value
        val accountId = state.selectedAccountId ?: return
        val section = state.section
        val category = cache[accountId to section]?.firstOrNull { it.id == categoryId } ?: return
        if (category.loaded || category.loading) return
        updateCategory(accountId, section, categoryId) { it.copy(loading = true) }
        scope.launch {
            val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }
            val client = account?.let { IptvClient.forAccount(it) }
            val items = if (account == null || client == null) emptyList() else when (section) {
                XtreamHubSection.LIVE -> client.liveChannels(account, categoryId).getOrDefault(emptyList()).map { ch ->
                    XtreamItemRegistry.registerChannel(accountId, ch); ch.toMetaPreview(accountId)
                }
                XtreamHubSection.MOVIES -> client.vodMovies(account, categoryId).getOrDefault(emptyList()).map { m ->
                    XtreamItemRegistry.registerMovie(accountId, m); m.toMetaPreview(accountId)
                }
                XtreamHubSection.SERIES -> client.series(account, categoryId).getOrDefault(emptyList()).map { s ->
                    XtreamItemRegistry.registerSeries(accountId, s); s.toMetaPreview(accountId)
                }
            }
            updateCategory(accountId, section, categoryId) { it.copy(items = items, loaded = true, loading = false) }
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

    private fun accountFor(accountId: String?): XtreamAccount? =
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }

    /** Lazily fetch now/next EPG for a live channel (called when its tile scrolls into view). */
    fun ensureEpg(contentId: String) {
        if (!epgFetched.add(contentId)) return
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return
        if (parsed.kind != XtreamKind.LIVE) return
        val streamId = parsed.id.toIntOrNull() ?: return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return
        scope.launch {
            // get_short_epg returns current + upcoming, so the nowPlaying (or first) entry is "now".
            // When the panel has nothing (the common case on real panels — Starshare fills 6% of
            // epg_channel_id), fall back to the mirrored canonical EPG via the channel mapping.
            val listings = IptvClient.forAccount(account).shortEpg(account, streamId).getOrDefault(emptyList())
                .ifEmpty {
                    runCatching {
                        com.nuvio.app.features.epg.EpgMirrorRepository
                            .nowNextProgrammes(account.id, streamId, com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs())
                    }.getOrDefault(emptyList())
                }
            if (listings.isEmpty()) return@launch
            val nowIndex = listings.indexOfFirst { it.nowPlaying }.takeIf { it >= 0 } ?: 0
            val now = listings.getOrNull(nowIndex)?.title?.ifBlank { null }
            val next = listings.getOrNull(nowIndex + 1)?.title?.ifBlank { null }
            if (now != null || next != null) {
                _epg.update { it + (contentId to ChannelEpg(now = now, next = next)) }
            }
        }
    }

    fun resetForProfile() {
        cache.clear()
        lastPrefetchMark = null
        epgFetched.clear()
        _epg.value = emptyMap()
        _uiState.value = XtreamHubUiState()
    }

    private fun isCurrent(accountId: String, section: XtreamHubSection): Boolean =
        _uiState.value.selectedAccountId == accountId && _uiState.value.section == section

    private fun updateCategory(
        accountId: String,
        section: XtreamHubSection,
        categoryId: String,
        transform: (XtreamHubCategory) -> XtreamHubCategory,
    ) {
        val key = accountId to section
        val updated = cache[key]?.map { if (it.id == categoryId) transform(it) else it } ?: return
        cache[key] = updated
        if (isCurrent(accountId, section)) _uiState.update { it.copy(categories = updated) }
    }
}
