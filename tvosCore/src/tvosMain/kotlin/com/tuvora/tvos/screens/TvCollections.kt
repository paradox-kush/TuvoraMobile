package com.tuvora.tvos.screens

import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.FolderDetailRepository
import com.nuvio.app.features.collection.FolderDetailUiState
import com.nuvio.app.features.collection.FolderTab
import com.nuvio.app.features.collection.FolderViewMode
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Collections on Apple TV: the Home rows (catalogs and the profile's collections, placed by
 * TvHomeCollectionsPolicy) and a collection folder's lists. Reads the phone's own repositories:
 * CollectionRepository (synced from the account by SyncManager / CollectionSyncService - no polling
 * here), HomeCatalogSettingsRepository (per-profile order and visibility) and FolderDetailRepository
 * (loads a folder's catalogs once per open; more pages only as the user scrolls).
 *
 * `enableSmokeSample()` (launch argument `-smokeCollections`) swaps in sample collections built in
 * memory from the Home rows already loaded - nothing is written to the repositories, the disk or the
 * account, so the simulator's real profile is never touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TvCollections {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private val smoke = MutableStateFlow(false)
    /** The open sample folder (collection id to folder id) and its selected tab, when smoke-testing. */
    private val smokeTarget = MutableStateFlow<Pair<String, String>?>(null)
    private val smokeTab = MutableStateFlow(0)

    private val _entries = MutableStateFlow<List<TvHomeEntry>>(emptyList())
    /** Home rows in display order: each entry is a catalog section or a collection. */
    val entries: StateFlow<List<TvHomeEntry>> = _entries.asStateFlow()

    /** The open folder (FolderDetailRepository's state, or the in-memory smoke sample). */
    val folder: StateFlow<FolderDetailUiState> =
        combine(FolderDetailRepository.uiState, smokeTarget, smokeTab, HomeRepository.uiState) { real, target, tab, rows ->
            target?.let { (c, f) -> smokeFolderState(c, f, smokeRows(rows.sections)).let { it.copy(selectedTabIndex = tab.coerceIn(0, (it.tabs.size - 1).coerceAtLeast(0))) } } ?: real
        }.stateIn(scope, SharingStarted.Eagerly, FolderDetailUiState())

    private val collections = smoke.flatMapLatest { sample ->
        if (sample) HomeRepository.uiState.map { smokeCollections(smokeRows(it.sections)) } else CollectionRepository.collections
    }

    fun start() {
        if (started) return
        started = true
        CollectionRepository.initialize()
        // HomeScreen.kt ScreenActivityEffect(activeProfileId, collections): give every collection its
        // entry in the Home catalog settings (local; the settings sync owns any upload).
        scope.launch {
            CollectionRepository.collections.collect { if (!smoke.value) HomeCatalogSettingsRepository.syncCollections(it) }
        }
        scope.launch {
            combine(HomeRepository.uiState, HomeCatalogSettingsRepository.uiState, collections) { rows, settings, list ->
                TvHomeCollectionsPolicy.entries(settings.items, if (smoke.value) smokeRows(rows.sections) else rows.sections, list)
            }.collect { _entries.value = it }
        }
    }

    fun enableSmokeSample() { smoke.value = true }

    /** Opens a folder; FolderDetailRepository fetches its sources once and keeps them while it stays open. */
    fun openFolder(collectionId: String, folderId: String) {
        if (smoke.value && collectionId.startsWith(SMOKE_PREFIX)) {
            smokeTab.value = 0
            smokeTarget.value = collectionId to folderId
            return
        }
        smokeTarget.value = null
        FolderDetailRepository.initialize(collectionId, folderId)
    }

    fun selectTab(index: Int) {
        if (smokeTarget.value != null) smokeTab.value = index else FolderDetailRepository.selectTab(index)
    }

    /** Next page of the selected tab, when the user nears the end of the grid. */
    fun loadMore() { if (smokeTarget.value == null) FolderDetailRepository.loadMoreSelectedTab() }

    // region Smoke sample (in memory only)

    private const val SMOKE_PREFIX = "smoke-"

    /**
     * A source-less profile (a fresh simulator guest: the App Store build has no add-ons) has no Home rows to build
     * the sample from, which left every sample folder empty and the remote with nothing to focus. The real rows win
     * when there are any; otherwise two sample rows of well-known titles (real IMDb ids, so their details resolve).
     */
    private fun smokeRows(sections: List<HomeCatalogSection>): List<HomeCatalogSection> {
        if (sections.isNotEmpty()) return sections
        fun row(key: String, title: String, vararg titles: Pair<String, String>) = HomeCatalogSection(
            key = "smoke-row-$key", title = title, subtitle = "Sample", addonName = "Sample",
            target = CatalogTarget.Addon(manifestUrl = "smoke://sample", contentType = "movie", catalogId = key),
            items = titles.map { (id, name) -> MetaPreview(id = id, type = "movie", name = name) },
        )
        return listOf(
            row("classics", "Sample classics", "tt0111161" to "The Shawshank Redemption", "tt0068646" to "The Godfather",
                "tt0071562" to "The Godfather Part II", "tt0468569" to "The Dark Knight", "tt0050083" to "12 Angry Men"),
            row("modern", "Sample modern", "tt1375666" to "Inception", "tt0816692" to "Interstellar",
                "tt0133093" to "The Matrix", "tt0109830" to "Forrest Gump", "tt0137523" to "Fight Club"),
        )
    }

    private fun smokeCollections(sections: List<HomeCatalogSection>): List<Collection> {
        fun cover(i: Int, wide: Boolean): String? = sections.getOrNull(i % sections.size.coerceAtLeast(1))?.items?.firstOrNull()?.let {
            if (wide) it.banner ?: it.poster else it.poster
        }
        fun title(i: Int, fallback: String) = sections.getOrNull(i % sections.size.coerceAtLeast(1))?.title ?: fallback
        val streaming = Collection(
            id = "${SMOKE_PREFIX}streaming", title = "Streaming services", pinToTop = true, viewMode = FolderViewMode.TABBED_GRID.name,
            folders = List(3) { i -> CollectionFolder(id = "s$i", title = title(i, "Folder ${i + 1}"), coverImageUrl = cover(i, wide = true), tileShape = "landscape") } +
                CollectionFolder(id = "s3", title = "Popcorn picks", coverEmoji = "🍿", tileShape = "landscape"),
        )
        val moods = Collection(
            id = "${SMOKE_PREFIX}moods", title = "Moods", viewMode = FolderViewMode.ROWS.name,
            folders = List(3) { i -> CollectionFolder(id = "m$i", title = title(i + 1, "Mood ${i + 1}"), coverImageUrl = cover(i + 1, wide = false)) } +
                CollectionFolder(id = "m3", title = "Square tile", tileShape = "square"),
        )
        return listOf(streaming, moods)
    }

    private fun smokeFolderState(collectionId: String, folderId: String, sections: List<HomeCatalogSection>): FolderDetailUiState {
        val collection = smokeCollections(sections).firstOrNull { it.id == collectionId }
        val folder = collection?.folders?.firstOrNull { it.id == folderId } ?: return FolderDetailUiState(isLoading = false)
        val index = folder.id.drop(1).toIntOrNull() ?: 0
        val sources = listOfNotNull(sections.getOrNull(index % sections.size.coerceAtLeast(1)), sections.getOrNull((index + 1) % sections.size.coerceAtLeast(1)))
            .distinctBy { it.key }
        val tabs = sources.map { FolderTab(label = it.title, typeLabel = it.subtitle, type = it.target.contentType, items = it.items, isLoading = false) }
        val all = FolderTab(label = "All", isAllTab = true, isLoading = false, items = roundRobin(tabs.map { it.items }))
        val withAll = if (collection.showAllTab && tabs.size > 1) listOf(all) + tabs else tabs
        return FolderDetailUiState(
            folder = folder, collectionTitle = collection.title, viewMode = collection.folderViewMode,
            tabs = withAll, isLoading = false, showAllTab = withAll.firstOrNull()?.isAllTab == true,
        )
    }

    private fun roundRobin(lists: List<List<MetaPreview>>): List<MetaPreview> {
        val seen = HashSet<String>()
        return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            .filter { seen.add(it.stableKey()) }
    }

    // endregion
}
