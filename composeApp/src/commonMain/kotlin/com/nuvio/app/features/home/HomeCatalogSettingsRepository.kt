package com.nuvio.app.features.home

import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.core.contracts.ContributedRowDeclaration
import com.nuvio.app.core.contracts.HomeSectionContributorRegistry
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
    /** A row a source (a media server) contributes: orderable / hideable / renamable, never a hero source, device-local. */
    val isContributed: Boolean = false,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

data class HomeCatalogSettingsUiState(
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    // Recently-watched live channels on the home screen (inside Continue Watching). Live channels have no
    // resume position, so mobile/desktop surface them as a "recents" row; off keeps home VOD-only (TV
    // never shows them). Default true = current behaviour.
    val showLiveOnHome: Boolean = true,
    val items: List<HomeCatalogSettingsItem> = emptyList(),
    /** The stored `home_sections` value (null: never customised). Kept so a write can start from it. */
    val homeSections: HomeSectionsConfig? = null,
    /** This profile has had Continue Watching items or recent live channels (drives the default order). */
    val hasWatchHistory: Boolean = false,
    /** Home sections this build renders, in order, hidden ones included (the settings list). */
    val sections: List<HomeSectionEntry> = HomeSectionOrderPolicy.resolve(null, HomeSectionContext(hasWatchHistory = false)),
) {
    /** Ids Home draws, top to bottom ([HomeSectionOrderPolicy]). */
    val visibleSectionIds: List<String>
        get() = sections.filter { it.enabled }.map { it.id }

    val signature: String
        get() = buildString {
            append(heroEnabled)
            append('|')
            append(showCatalogType)
            append('|')
            append(hideUnreleasedContent)
            append('|')
            append(showLiveOnHome)
            append('|')
            append(sections.joinToString(separator = ",") { "${it.id}:${it.enabled}" })
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}"
                }
            )
        }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val heroSourceEnabled: Boolean,
    val order: Int,
)

internal data class HomeCatalogSettingsSnapshot(
    val heroEnabled: Boolean,
    val showCatalogType: Boolean,
    val hideUnreleasedContent: Boolean,
    val preferences: Map<String, HomeCatalogPreference>,
)

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val showLiveOnHome: Boolean = true,
    val items: List<StoredHomeCatalogPreference> = emptyList(),
    val homeSections: HomeSectionsConfig? = null,
    // Device-local: set once this profile has Continue Watching items or recent live channels, so the
    // returning-user default order holds from the first frame after a restart (before that data loads).
    val hasWatchHistory: Boolean = false,
)

object HomeCatalogSettingsRepository {
    const val HERO_SOURCE_SELECTION_LIMIT = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var collectionDefinitions: List<CollectionCatalogDefinition> = emptyList()
    private var contributedDefinitions: List<ContributedRowDeclaration> = emptyList()
    private var lastCatalogSync: CatalogSyncInput? = null
    private var lastCollectionSync: Pair<List<Collection>, String>? = null
    private val preferencesRef = atomic<Map<String, StoredHomeCatalogPreference>>(emptyMap())
    private var preferences: Map<String, StoredHomeCatalogPreference>
        get() = preferencesRef.value
        set(value) {
            preferencesRef.value = value
        }
    private var heroEnabled = true
    private var showCatalogType = true
    private var hideUnreleasedContent = false
    private var showLiveOnHome = true
    private var homeSections: HomeSectionsConfig? = null
    private var hasWatchHistory = false

    fun onProfileChanged() {
        hasLoaded = false
        preferences = emptyMap()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        showLiveOnHome = true
        homeSections = null
        hasWatchHistory = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        contributedDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        contributedDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        preferences = emptyMap()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        showLiveOnHome = true
        homeSections = null
        hasWatchHistory = false
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun syncCatalogs(addons: List<ManagedAddon>) {
        ensureLoaded()
        val collections = CollectionRepository.collections.value
        val syncInput = CatalogSyncInput(addons, collections, Locale.current.toLanguageTag(), ServerRepository.uiState.value.revision)
        val contributedChanged = refreshContributedDefinitions()
        if (lastCatalogSync == syncInput && !contributedChanged) return
        definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = syncInput
        lastCollectionSync = lastCollectionSync?.takeIf {
            it.first == collections && it.second == syncInput.locale
        }
        if (definitions.isEmpty() && collectionDefinitions.isEmpty() && contributedDefinitions.isEmpty()) {
            publish()
            return
        }
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
    }

    /**
     * Re-reads the rows source contributors (media servers) declare and folds them into the layout settings, so
     * a server row the viewer just switched on (or a server just removed) shows up in / leaves the list at once.
     * A no-op when nothing changed. Device-local: contributed rows' order/visibility never ride the account sync.
     */
    fun syncContributed() {
        ensureLoaded()
        if (!refreshContributedDefinitions()) return
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun syncCollections(collections: List<Collection>) {
        ensureLoaded()
        val syncInput = collections to Locale.current.toLanguageTag()
        val contributedChanged = refreshContributedDefinitions()
        if (lastCollectionSync == syncInput && !contributedChanged) return
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = lastCatalogSync?.takeIf {
            it.collections == collections && it.locale == syncInput.second
        }
        lastCollectionSync = syncInput
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    /** Re-reads the declared contributed rows; true when they differ from what the layout last saw. */
    private fun refreshContributedDefinitions(): Boolean {
        val declared = HomeSectionContributorRegistry.declaredRows()
        if (declared == contributedDefinitions) return false
        contributedDefinitions = declared
        return true
    }

    internal fun snapshot(): HomeCatalogSettingsSnapshot {
        ensureLoaded()
        return HomeCatalogSettingsSnapshot(
            heroEnabled = heroEnabled,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            preferences = preferences.mapValues { (_, value) ->
                HomeCatalogPreference(
                    customTitle = value.customTitle,
                    enabled = value.enabled,
                    heroSourceEnabled = value.heroSourceEnabled,
                    order = value.order,
                )
            },
        )
    }

    fun setHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        heroEnabled = enabled
        val sectionsChanged = mirrorSection(HomeSectionIds.HERO, enabled)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        if (sectionsChanged) HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setShowCatalogType(enabled: Boolean) {
        ensureLoaded()
        if (showCatalogType == enabled) return
        showCatalogType = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHideUnreleasedContent(enabled: Boolean) {
        ensureLoaded()
        if (hideUnreleasedContent == enabled) return
        hideUnreleasedContent = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setShowLiveOnHome(enabled: Boolean) {
        ensureLoaded()
        if (showLiveOnHome == enabled) return
        showLiveOnHome = enabled
        mirrorSection(HomeSectionIds.LIVE_RECENTS, enabled)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    /**
     * Home's sections and their order ([HomeSectionOrderPolicy]). Moving or hiding one writes the full list
     * as customised and syncs it; the standalone switches (hero, Continue Watching, Live TV on Home) follow,
     * so two switches never disagree.
     */
    fun moveHomeSection(
        id: String,
        toIndex: Int,
        supportedIds: Set<String> = HomeSectionIds.ALL,
        heroPinnedFirst: Boolean = false,
    ) {
        ensureLoaded()
        commitHomeSections(
            HomeSectionOrderPolicy.move(homeSections, sectionContext(supportedIds, heroPinnedFirst), legacyToggles(), id, toIndex),
        )
    }

    /** [supportedIds] and [heroPinnedFirst] describe the device (Apple TV renders fewer sections and pins the hero). */
    fun setHomeSectionEnabled(
        id: String,
        enabled: Boolean,
        supportedIds: Set<String> = HomeSectionIds.ALL,
        heroPinnedFirst: Boolean = false,
    ) {
        ensureLoaded()
        commitHomeSections(
            HomeSectionOrderPolicy.setEnabled(homeSections, sectionContext(supportedIds, heroPinnedFirst), legacyToggles(), id, enabled),
        )
    }

    /** Back to the device default order, with the standalone switches back to shown. */
    fun resetHomeSections() {
        ensureLoaded()
        heroEnabled = true
        showLiveOnHome = true
        ContinueWatchingPreferencesRepository.setVisible(true)
        homeSections = HomeSectionOrderPolicy.reset().takeIf { homeSections != null }
        finishHomeSectionsChange()
    }

    /**
     * Called when a standalone switch that has a section (Continue Watching visibility) changes elsewhere:
     * a customised list follows it, an uncustomised one just re-derives.
     */
    fun mirrorLegacyToggle(id: String, enabled: Boolean) {
        ensureLoaded()
        val changed = mirrorSection(id, enabled)
        publish()
        if (changed) {
            persist()
            HomeCatalogSettingsSyncService.triggerPush()
        }
    }

    /** The Home screen reports once the profile has Continue Watching items or recent live channels. */
    fun reportWatchHistory(has: Boolean) {
        ensureLoaded()
        if (!has || hasWatchHistory) return
        hasWatchHistory = true
        publish()
        persist()
    }

    private fun commitHomeSections(updated: HomeSectionsConfig) {
        if (updated == homeSections) return
        homeSections = updated
        HomeSectionOrderPolicy.togglesFrom(updated, legacyToggles())?.let { toggles ->
            heroEnabled = toggles.heroEnabled
            showLiveOnHome = toggles.showLiveOnHome
            ContinueWatchingPreferencesRepository.setVisible(toggles.continueWatchingVisible)
        }
        finishHomeSectionsChange()
    }

    private fun finishHomeSectionsChange() {
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    /** Updates the customised list's flag for [id]; false when nothing was customised or it already agreed. */
    private fun mirrorSection(id: String, enabled: Boolean): Boolean {
        val updated = HomeSectionOrderPolicy.mirrorToggle(
            homeSections, sectionContext(), legacyToggles(), id, enabled,
        ) ?: return false
        homeSections = updated
        return true
    }

    private fun sectionContext(
        supportedIds: Set<String> = HomeSectionIds.ALL,
        heroPinnedFirst: Boolean = false,
    ) = HomeSectionContext(hasWatchHistory, supportedIds, heroPinnedFirst)

    private fun legacyToggles(): HomeSectionLegacyToggles {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        return HomeSectionLegacyToggles(
            heroEnabled = heroEnabled,
            continueWatchingVisible = ContinueWatchingPreferencesRepository.uiState.value.isVisible,
            showLiveOnHome = showLiveOnHome,
        )
    }

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        updatePreference(key, pushRemote = false) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        showLiveOnHome = true
        homeSections = HomeSectionOrderPolicy.reset().takeIf { homeSections != null }
        preferences = emptyMap()
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val allKeys = allOrderedKeys()
        if (allKeys.isEmpty()) return
        if (fromIndex !in allKeys.indices || toIndex !in allKeys.indices) return
        if (fromIndex == toIndex) return
        val orderedKeys = allKeys.toMutableList()
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val parsedPayload = runCatching {
            json.decodeFromString<StoredHomeCatalogSettingsPayload>(payload)
        }.getOrNull()

        if (parsedPayload != null) {
            heroEnabled = parsedPayload.heroEnabled
            showCatalogType = parsedPayload.showCatalogType
            hideUnreleasedContent = parsedPayload.hideUnreleasedContent
            showLiveOnHome = parsedPayload.showLiveOnHome
            homeSections = parsedPayload.homeSections
            hasWatchHistory = parsedPayload.hasWatchHistory
            preferences = parsedPayload.items.associateBy { it.key }
            publish()
            return
        }

        val legacyItems = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = legacyItems.associateBy { it.key }
        publish()
    }

    private fun normalizePreferences() {
        val current = preferences
        data class UnifiedEntry(val key: String, val isCollection: Boolean)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false) }
        val collectionEntries = collectionDefinitions.map { UnifiedEntry(it.key, true) }
        val contributedEntries = contributedDefinitions.map { UnifiedEntry(it.key, false) }
        val allEntries = catalogEntries + contributedEntries + collectionEntries
        val knownKeys = allEntries.mapTo(linkedSetOf(), UnifiedEntry::key)
        val contributedKeys = contributedDefinitions.mapTo(mutableSetOf()) { it.key }
        var nextOrder = (current.values.maxOfOrNull(StoredHomeCatalogPreference::order) ?: -1) + 1

        val orderedEntries = allEntries.mapIndexed { defaultIndex, entry ->
            Triple(
                entry,
                current[entry.key]?.order ?: (nextOrder + defaultIndex),
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<UnifiedEntry, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = current
            .filterKeys { it !in knownKeys }
            .toMutableMap()
        var enabledHeroSourceCount = 0
        orderedEntries.forEach { entry ->
            val stored = current[entry.key]
            val heroSourceEnabled = if (entry.isCollection || entry.key in contributedKeys) {
                false
            } else {
                (stored?.heroSourceEnabled ?: true) &&
                    enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
                enabled = stored?.enabled ?: true,
                heroSourceEnabled = heroSourceEnabled,
                order = stored?.order ?: nextOrder++,
            )
        }
        preferences = normalized.toMap()
    }

    private fun publish() {
        val collectionMap = collectionDefinitions.associateBy { it.key }
        val catalogItems = definitions
            .map { definition ->
                val preference = preferences[definition.key]
                HomeCatalogSettingsItem(
                    key = definition.key,
                    defaultTitle = definition.defaultTitle,
                    addonName = definition.addonName,
                    customTitle = preference?.customTitle.orEmpty(),
                    enabled = preference?.enabled ?: true,
                    heroSourceEnabled = preference?.heroSourceEnabled ?: true,
                    order = preference?.order ?: 0,
                )
            }

        val contributedItems = contributedDefinitions.map { declared ->
            val preference = preferences[declared.key]
            HomeCatalogSettingsItem(
                key = declared.key,
                defaultTitle = declared.title,
                addonName = declared.subtitle,
                customTitle = preference?.customTitle.orEmpty(),
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = false,
                order = preference?.order ?: 0,
                isContributed = true,
            )
        }

        val collectionItems = collectionDefinitions.map { colDef ->
            val preference = preferences[colDef.key]
            HomeCatalogSettingsItem(
                key = colDef.key,
                defaultTitle = colDef.title,
                addonName = colDef.subtitle,
                customTitle = preference?.customTitle.orEmpty(),
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = false,
                order = preference?.order ?: 0,
                isCollection = true,
                collectionId = colDef.collectionId,
                isPinnedToTop = colDef.isPinnedToTop,
            )
        }

        val rowIndex = allOrderedKeys().withIndex().associate { (index, key) -> key to index }
        val items = (catalogItems + contributedItems + collectionItems)
            .sortedBy { rowIndex[it.key] ?: Int.MAX_VALUE }

        _uiState.value = HomeCatalogSettingsUiState(
            heroEnabled = heroEnabled,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            showLiveOnHome = showLiveOnHome,
            items = items,
            homeSections = homeSections,
            hasWatchHistory = hasWatchHistory,
            sections = HomeSectionOrderPolicy.resolve(homeSections, sectionContext(), legacyToggles()),
        )
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                StoredHomeCatalogSettingsPayload(
                    heroEnabled = heroEnabled,
                    showCatalogType = showCatalogType,
                    hideUnreleasedContent = hideUnreleasedContent,
                    showLiveOnHome = showLiveOnHome,
                    items = preferences.values.sortedBy { it.order },
                    homeSections = homeSections,
                    hasWatchHistory = hasWatchHistory,
                ),
            ),
        )
    }

    private fun updatePreference(
        key: String,
        pushRemote: Boolean = true,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: defaultPreferenceForMissingKey(key) ?: return
        val updated = transform(current)
        if (updated == current) return
        preferences = preferences + (key to updated)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        if (pushRemote) {
            HomeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        val catalogKeys = definitions.mapTo(mutableSetOf()) { it.key }
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && itemKey in catalogKeys && preference.heroSourceEnabled
        }
    }

    private fun move(
        key: String,
        direction: Int,
    ) {
        ensureLoaded()
        val orderedKeys = allOrderedKeys().toMutableList()
        if (orderedKeys.isEmpty()) return

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex !in orderedKeys.indices) return

        val movingKey = orderedKeys.removeAt(currentIndex)
        orderedKeys.add(targetIndex, movingKey)

        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences

        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun exportToSyncPayload(): SyncHomeCatalogPayload {
        ensureLoaded()
        val catalogDefinitionsByKey = definitions.associateBy { it.key }
        val collectionDefinitionsByKey = collectionDefinitions.associateBy { it.key }
        val contributedKeys = contributedDefinitions.mapTo(mutableSetOf()) { it.key }
        val items = preferences.values.filterNot { ServerCatalog.isServerKey(it.key) }.sortedBy { it.order }.filter { it.key !in contributedKeys }.map { pref ->
            val catalogDefinition = catalogDefinitionsByKey[pref.key]
            val collectionDefinition = collectionDefinitionsByKey[pref.key]
            val isCollection = collectionDefinition != null || pref.key.startsWith("collection_")
            if (isCollection) {
                SyncCatalogItem(
                    addonId = "",
                    type = "",
                    catalogId = "",
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = true,
                    collectionId = collectionDefinition?.collectionId ?: pref.key.removePrefix("collection_"),
                    key = pref.key,
                )
            } else {
                val legacyParts = pref.key.split(':', limit = 3)
                SyncCatalogItem(
                    addonId = catalogDefinition?.addonIdForSync() ?: legacyParts.getOrElse(0) { "" },
                    type = catalogDefinition?.type ?: legacyParts.getOrElse(1) { "" },
                    catalogId = catalogDefinition?.catalogId ?: legacyParts.getOrElse(2) { "" },
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = false,
                    key = pref.key,
                )
            }
        }
        return SyncHomeCatalogPayload(
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            showLiveOnHome = showLiveOnHome,
            items = items,
            homeSections = homeSections,
        )
    }

    fun applyFromRemote(payload: SyncHomeCatalogPayload) {
        ensureLoaded()
        showCatalogType = payload.showCatalogType
        hideUnreleasedContent = payload.hideUnreleasedContent
        showLiveOnHome = payload.showLiveOnHome
        // Absent on the wire = nobody customised Home yet (or an older app wrote the blob): keep what is here.
        payload.homeSections?.let { remoteSections ->
            homeSections = remoteSections
            HomeSectionOrderPolicy.togglesFrom(remoteSections, legacyToggles())?.let { toggles ->
                heroEnabled = toggles.heroEnabled
                showLiveOnHome = toggles.showLiveOnHome
                ContinueWatchingPreferencesRepository.setVisible(toggles.continueWatchingVisible)
            }
        }
        if (payload.items.isNotEmpty()) {
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            val remotePreferences = payload.items.associate { item ->
                val key = item.preferenceKey()
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
                    enabled = item.enabled,
                    heroSourceEnabled = existingHeroState[key] ?: true,
                    order = item.order,
                )
            }
            val remoteKeys = remotePreferences.keys
            val knownKeys = knownPreferenceKeys()
            val preservedPreferences = preferences.filterKeys { key ->
                key !in remoteKeys && (key in knownKeys || key.requiresExplicitSyncKey())
            }
            preferences = preservedPreferences + remotePreferences
            normalizePreferences()
            enforcePinnedCollectionsAtTop()
        }
        hasLoaded = true
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    /** Rows in display order: stored order with pinned collections first (B81, [HomeRowOrderPolicy]). */
    private fun allOrderedKeys(): List<String> =
        HomeRowOrderPolicy.orderedKeys(
            catalogKeys = definitions.map { it.key } + contributedDefinitions.map { it.key },
            collectionKeys = collectionDefinitions.map { it.key },
            pinnedCollectionKeys = collectionDefinitions.filter { it.isPinnedToTop }.mapTo(mutableSetOf()) { it.key },
            orderOf = { key -> preferences[key]?.order },
        )

    /**
     * Rewrites the stored order to match the display order, so what is persisted and pushed keeps
     * pinned collections first too. Display already applies the pin on every read (B81); this only
     * keeps the stored numbers honest after any path that rewrote them.
     */
    private fun enforcePinnedCollectionsAtTop() {
        if (collectionDefinitions.none { it.isPinnedToTop }) return
        val displayKeys = allOrderedKeys()
        val storedKeys = (definitions.map { it.key } + contributedDefinitions.map { it.key } + collectionDefinitions.map { it.key })
            .sortedBy { key -> preferences[key]?.order ?: Int.MAX_VALUE }
        if (displayKeys == storedKeys) return

        val updatedPreferences = preferences.toMutableMap()
        displayKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences
    }

    private fun defaultPreferenceForMissingKey(key: String): StoredHomeCatalogPreference? {
        val isCollection = collectionDefinitions.any { it.key == key }
        val isCatalog = definitions.any { it.key == key }
        val isContributed = contributedDefinitions.any { it.key == key }
        if (!isCollection && !isCatalog && !isContributed) return null

        return StoredHomeCatalogPreference(
            key = key,
            enabled = true,
            heroSourceEnabled = isCatalog &&
                selectedHeroSourceCount(excludingKey = key) < HERO_SOURCE_SELECTION_LIMIT,
            order = _uiState.value.items.firstOrNull { it.key == key }?.order
                ?: ((preferences.values.maxOfOrNull { it.order } ?: -1) + 1),
        )
    }

    private fun knownPreferenceKeys(): Set<String> =
        definitions.mapTo(mutableSetOf()) { it.key }.also { keys ->
            keys.addAll(collectionDefinitions.map { it.key })
            keys.addAll(contributedDefinitions.map { it.key })
        }

    private fun HomeCatalogDefinition.addonIdForSync(): String {
        val suffix = ":$type:$catalogId"
        return key.removeSuffix(suffix)
    }

    private fun SyncCatalogItem.preferenceKey(): String =
        key.ifBlank {
            if (isCollection) {
                "collection_$collectionId"
            } else {
                "$addonId:$type:$catalogId"
            }
        }

    private fun String.requiresExplicitSyncKey(): Boolean =
        !startsWith("collection_") && count { it == ':' } > 2
}

internal data class CollectionCatalogDefinition(
    val key: String,
    val collectionId: String,
    val title: String,
    val subtitle: String,
    val isPinnedToTop: Boolean,
)

internal fun visibleCollectionsWithUniqueIds(collections: List<Collection>): List<Collection> =
    collections
        .filter { collection -> collection.folders.isNotEmpty() }
        .distinctBy(Collection::id)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    visibleCollectionsWithUniqueIds(collections).map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
        )
    }

private data class CatalogSyncInput(
    val addons: List<ManagedAddon>,
    val collections: List<Collection>,
    val locale: String,
    val serverRevision: Int,
)
