package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.features.iptv.CONTENT_TYPE_LIVE
import com.nuvio.app.features.iptv.CONTENT_TYPE_MOVIES
import com.nuvio.app.features.iptv.CONTENT_TYPE_SERIES
import com.nuvio.app.features.iptv.IptvClient
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamSearchIndex
import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.CatalogCategory
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.CatalogChannel
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenItem
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.NamedCategory
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads and edits a playlist's hidden channels and groups (F02). A seam so the controller tests without I/O. */
internal interface IptvHiddenItemsSource {
    suspend fun load(account: XtreamAccount): List<HiddenItem>
    fun unhide(account: XtreamAccount, item: HiddenItem)
    fun hideGroup(account: XtreamAccount, contentType: String, categoryName: String)
}

internal object IptvHiddenItems : IptvHiddenItemsSource {

    private val TYPES = listOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_MOVIES, CONTENT_TYPE_SERIES)

    /**
     * The overlay stores only hashed keys, so the list is rebuilt from the playlist's catalog: the
     * channels are read only when some channel is hidden, the categories only when some category is.
     */
    override suspend fun load(account: XtreamAccount): List<HiddenItem> {
        val overlay = IptvOverlayStore.snapshot(ProfileRepository.activeProfileId)
        val channels = if (overlay.channels.values.any { it.hidden }) {
            runCatching { XtreamSearchIndex.liveChannelsFor(account) }.getOrDefault(emptyList())
                .map { CatalogChannel(IptvIdentity.entityId(account.id, it.name, it.epgChannelId), it.name) }
        } else {
            emptyList()
        }
        val categories = if (overlay.categories.values.any { it.hidden }) {
            TYPES.flatMap { type ->
                categoryNames(account, type).map { CatalogCategory(type, IptvIdentity.categoryKey(account.id, type, it.name), it.name) }
            }
        } else {
            emptyList()
        }
        return IptvHiddenItemsPolicy.hiddenItems(channels, categories, overlay)
    }

    override fun unhide(account: XtreamAccount, item: HiddenItem) = when (item.kind) {
        HiddenKind.GROUP -> IptvOverlayRepository.setCategoryHidden(account.id, item.contentType, item.key, hidden = false)
        HiddenKind.CHANNEL -> IptvOverlayRepository.setChannelHidden(item.key, account.id, hidden = false)
    }

    override fun hideGroup(account: XtreamAccount, contentType: String, categoryName: String) =
        IptvOverlayRepository.setCategoryHidden(
            account.id, contentType, IptvIdentity.categoryKey(account.id, contentType, categoryName), hidden = true,
        )

    /** UX130: takes back [hideGroup] for the toast's Undo (same key the hide wrote). */
    fun unhideGroup(account: XtreamAccount, contentType: String, categoryName: String) =
        unhide(
            account,
            HiddenItem(
                HiddenKind.GROUP, contentType,
                IptvIdentity.categoryKey(account.id, contentType, categoryName), categoryName,
            ),
        )

    /** A playlist's category names for [contentType]: the local catalog for Xtream, else one category request. */
    suspend fun categoryNames(account: XtreamAccount, contentType: String): List<NamedCategory> {
        val kind = when (contentType) {
            CONTENT_TYPE_LIVE -> MatchKind.LIVE
            CONTENT_TYPE_MOVIES -> MatchKind.MOVIE
            else -> MatchKind.SERIES
        }
        if (account.sourceType == SOURCE_TYPE_XTREAM) {
            val stored = runCatching { XtreamMatchIndex.categoriesFor(account.id, kind) }.getOrDefault(emptyList())
            if (stored.isNotEmpty()) return stored.map { NamedCategory(it.first, it.second) }
        }
        val client = IptvClient.forAccount(account)
        return runCatching {
            when (kind) {
                MatchKind.LIVE -> client.liveCategories(account)
                MatchKind.MOVIE -> client.vodCategories(account)
                MatchKind.SERIES -> client.seriesCategories(account)
            }.getOrNull()
        }.getOrNull().orEmpty().map { NamedCategory(it.id, it.name) }
    }
}

internal data class HiddenItemsUiState(
    /** Entered only through [com.nuvio.app.features.iptv.BoundedLoad]: a failed load is Failed (with Retry), never "nothing hidden". */
    val load: com.nuvio.app.features.iptv.LoadStatus = com.nuvio.app.features.iptv.LoadStatus.Idle,
    val items: List<HiddenItem> = emptyList(),
) {
    val loading: Boolean get() = load is com.nuvio.app.features.iptv.LoadStatus.Loading
    val failed: Boolean get() = load is com.nuvio.app.features.iptv.LoadStatus.Failed
}

/** Screen-scoped state holder for the "Hidden channels & groups" list (Rule 4: the dialog only renders it). */
internal class IptvHiddenItemsController(
    private val scope: CoroutineScope,
    private val source: IptvHiddenItemsSource = IptvHiddenItems,
) {
    private val mutableState = MutableStateFlow(HiddenItemsUiState())
    val state: StateFlow<HiddenItemsUiState> = mutableState.asStateFlow()

    fun open(account: XtreamAccount) {
        mutableState.value = HiddenItemsUiState(load = com.nuvio.app.features.iptv.BoundedLoad.begin(com.nuvio.app.features.iptv.LoadSurface.SETTINGS))
        scope.launch {
            val outcome = com.nuvio.app.features.iptv.BoundedLoad.run(
                com.nuvio.app.features.iptv.LoadSurface.SETTINGS,
                isEmpty = { it.isEmpty() },
                report = mapOf("row" to "hidden_items"),
            ) { source.load(account) }
            mutableState.value = HiddenItemsUiState(load = outcome.status, items = outcome.valueOrNull().orEmpty())
        }
    }

    fun unhide(account: XtreamAccount, item: HiddenItem) {
        source.unhide(account, item)
        mutableState.update { it.copy(items = it.items - item) }
    }

    fun hideGroup(account: XtreamAccount, contentType: String, categoryName: String) =
        source.hideGroup(account, contentType, categoryName)
}
