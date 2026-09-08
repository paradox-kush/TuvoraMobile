package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberHomeSkeletonBrush
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toMetaPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.compose_iptv_hub_add_provider
import nuvio.composeapp.generated.resources.compose_iptv_hub_empty_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_empty_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_epg_next
import nuvio.composeapp.generated.resources.compose_iptv_hub_epg_no_information
import nuvio.composeapp.generated.resources.compose_iptv_hub_blocked_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_blocked_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_error_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_error_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_refused_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_favorites
import nuvio.composeapp.generated.resources.compose_iptv_hub_no_provider_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_no_provider_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_playlist_fallback
import nuvio.composeapp.generated.resources.compose_iptv_hub_playlists_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_recent
import nuvio.composeapp.generated.resources.compose_iptv_hub_section_live
import nuvio.composeapp.generated.resources.compose_livetv_pinned_channel
import nuvio.composeapp.generated.resources.compose_settings_page_iptv_add_playlist
import nuvio.composeapp.generated.resources.library_other
import nuvio.composeapp.generated.resources.home_view_all
import nuvio.composeapp.generated.resources.media_movies
import nuvio.composeapp.generated.resources.media_series
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Top-level IPTV browse surface: an account selector, Movies/Series section chips, and lazily
 * loaded category rows of posters. Posters open Nuvio's native detail (via the Xtream meta
 * short-circuit) and play through the normal streams -> player pipeline. Live TV is its own
 * guide (P5).
 *
 * Visually this is home's shelf system: NuvioShelfSection rows with the responsive section
 * padding, poster cards sized by the user's poster-style setting, shimmer skeletons while
 * loading, and the same safe bottom inset so the floating nav pill never covers the last row.
 */
@Composable
fun XtreamHubScreen(
    onPosterClick: (MetaPreview) -> Unit,
    onPlayLiveChannel: (String) -> Unit,
    onFavoriteLiveChannel: (String) -> Unit,
    onAddProvider: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
) {
    val state by XtreamHubRepository.uiState.collectAsStateWithLifecycle()
    val localLibraryItems by LibraryRepository.localItems.collectAsStateWithLifecycle()
    val liveRecents by XtreamLiveRecents.recents.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        XtreamHubRepository.ensureLoaded()
        LibraryRepository.ensureLoaded()
        XtreamLiveRecents.ensureLoaded()
    }

    // Only a LOADED empty list means "no playlists": the first composition happens before the
    // LaunchedEffect above runs ensureLoaded, and painting the add-a-playlist state over a
    // populated hub for that beat read as data loss (field-reported flash).
    if (state.accounts.isEmpty()) {
        if (state.accountsLoaded) {
            XtreamHubNoPlaylistState(onAddProvider = onAddProvider, modifier = modifier)
        }
        return
    }

    val isLive = state.section == XtreamHubSection.LIVE
    val onTileClick: (MetaPreview) -> Unit = if (isLive) {
        { meta -> onPlayLiveChannel(meta.id) }
    } else {
        onPosterClick
    }
    val onTileLongClick: ((MetaPreview) -> Unit)? = if (isLive) {
        { meta -> onFavoriteLiveChannel(meta.id) }
    } else {
        null
    }
    val epgMap by XtreamHubRepository.epg.collectAsStateWithLifecycle()

    // Playlist-manager enforcement, all display-level (caches stay intact): disabled content
    // types lose their section chip; category selections filter the visible rows.
    val account = state.accounts.firstOrNull { it.id == state.selectedAccountId }
    val enabledSections = XtreamHubSection.entries.filter { account?.typeEnabled(it.contentKey) != false }
    val visibleCategories = if (account == null) state.categories else {
        state.categories.filter { account.allowsCategory(state.section.contentKey, it.id) }
    }
    // A category only collapses once it's confirmed empty; unloaded ones stay (as shimmer rows).
    // Filtering ahead of the LazyColumn keeps the listGap arrangement from stacking gaps for
    // collapsed rows.
    val renderableCategories = visibleCategories.filterNot { it.loaded && it.items.isEmpty() }
    val favoriteTitle = stringResource(Res.string.compose_iptv_hub_favorites)
    val recentTitle = stringResource(Res.string.compose_iptv_hub_recent)
    // Both rails are scoped to the SELECTED account: the stores keep one flat profile-wide list
    // across every playlist, and these rails sit inside one provider's hub.
    val accountPrefix = state.selectedAccountId?.let { XtreamItemRegistry.accountPrefix(it) }
    val liveSpecialCategories = remember(isLive, localLibraryItems, liveRecents, favoriteTitle, recentTitle, accountPrefix) {
        if (!isLive || accountPrefix == null) {
            emptyList()
        } else {
            buildList {
                localLibraryItems
                    .filter { XtreamItemRegistry.isLiveId(it.id) && it.id.startsWith(accountPrefix) }
                    .map { it.toMetaPreview() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        add(XtreamHubCategory(SPECIAL_FAVORITES_ID, favoriteTitle, items, loaded = true))
                    }
                liveRecents
                    .filter { it.contentId.startsWith(accountPrefix) }
                    .map { it.toMetaPreview() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        add(XtreamHubCategory(SPECIAL_RECENT_ID, recentTitle, items, loaded = true))
                    }
            }
        }
    }
    val displayedCategories = liveSpecialCategories + renderableCategories
    var openCategoryId by remember(state.selectedAccountId, state.section) { mutableStateOf<String?>(null) }
    val openCategory = openCategoryId?.let { id -> displayedCategories.firstOrNull { it.id == id } }

    val tokens = MaterialTheme.nuvio
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        // In the wide/tablet layout the app's floating top nav bar overlays the top of the content,
        // which would hide this fixed section-chip header — pad it down to clear the bar.
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = tabletTopInset)) {
            if (openCategory != null) {
                XtreamHubCategoryPage(
                    category = openCategory,
                    live = isLive,
                    epg = if (isLive) epgMap else emptyMap(),
                    sectionPadding = sectionPadding,
                    onBack = { openCategoryId = null },
                    onPosterClick = onTileClick,
                    onPosterLongClick = onTileLongClick,
                    loadFromRepository = !openCategory.id.startsWith(SPECIAL_CATEGORY_PREFIX),
                )
                return@Column
            }

            XtreamHubHeader(
                accounts = state.accounts,
                selectedAccountId = state.selectedAccountId,
                sections = enabledSections,
                section = state.section,
                horizontalPadding = sectionPadding,
                onSelectAccount = { XtreamHubRepository.selectAccount(it) },
                onSelectSection = { XtreamHubRepository.selectSection(it) },
                onAddProvider = onAddProvider,
            )

            val failure = state.loadError
            when {
                state.loadingCategories -> XtreamHubSkeleton(
                    live = isLive,
                    sectionPadding = sectionPadding,
                )

                failure != null -> {
                    // A WAF block and a portal refusal are NOT "the portal is down" — say which.
                    val title = when (failure.kind) {
                        IptvLoadFailurePolicy.Kind.BLOCKED_BY_PROVIDER -> stringResource(Res.string.compose_iptv_hub_blocked_title)
                        IptvLoadFailurePolicy.Kind.REFUSED -> stringResource(Res.string.compose_iptv_hub_refused_title)
                        IptvLoadFailurePolicy.Kind.UNREACHABLE -> stringResource(Res.string.compose_iptv_hub_error_title)
                    }
                    val generic = stringResource(Res.string.compose_iptv_hub_error_message)
                    val blocked = stringResource(Res.string.compose_iptv_hub_blocked_message, failure.status ?: 0)
                    val message = when (failure.kind) {
                        IptvLoadFailurePolicy.Kind.BLOCKED_BY_PROVIDER -> blocked
                        // The portal's own reason, already worded with its remedy. A refusal that
                        // arrived without text still beats spinning, so fall back to the generic.
                        IptvLoadFailurePolicy.Kind.REFUSED -> failure.portalText ?: generic
                        IptvLoadFailurePolicy.Kind.UNREACHABLE -> generic
                    }
                    XtreamHubMessageCard(
                        title = title,
                        // The breadcrumb rides the message rather than a new card slot: it is then
                        // guaranteed to be in the same screenshot as the error, and the shared
                        // HomeEmptyStateCard vocabulary stays untouched.
                        message = "$message\n\n${failure.detail}",
                        actionLabel = stringResource(Res.string.action_retry),
                        onAction = { XtreamHubRepository.retryCategories() },
                        sectionPadding = sectionPadding,
                    )
                }

                displayedCategories.isEmpty() -> XtreamHubMessageCard(
                    title = stringResource(Res.string.compose_iptv_hub_empty_title),
                    message = stringResource(Res.string.compose_iptv_hub_empty_message),
                    actionLabel = stringResource(Res.string.compose_settings_page_iptv_add_playlist),
                    onAction = onAddProvider,
                    sectionPadding = sectionPadding,
                )

                else -> {
                    // Xtream panels ship real-world duplicate category ids — duplicate-safe keys.
                    val keyedCategories = displayedCategories.withDuplicateSafeLazyKeys { it.id }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Same rhythm and insets as home's NuvioScreen: listGap between shelves and
                        // the nav-aware safe bottom padding (the fixed 24dp before let the floating
                        // nav pill cover the last row).
                        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                        contentPadding = PaddingValues(
                            top = NuvioTokens.Space.s4,
                            bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom),
                        ),
                    ) {
                        itemsIndexed(
                            items = keyedCategories,
                            key = { _, entry -> entry.lazyKey },
                        ) { index, entry ->
                            val category = entry.value
                            // Keyed on the account/section too: a category id that survives a
                            // section switch would otherwise keep the effect from re-running.
                            if (!category.id.startsWith(SPECIAL_CATEGORY_PREFIX)) {
                                LaunchedEffect(state.selectedAccountId, state.section, category.id) {
                                    XtreamHubRepository.loadCategory(category.id)
                                    // Give the next few rows a head start so they land with boxes and
                                    // names instead of shimmer. The repository caps how many of these
                                    // can actually be in flight and drops the rest.
                                    for (offset in 1..CATEGORY_PREFETCH_LOOKAHEAD) {
                                        val next = keyedCategories.getOrNull(index + offset) ?: break
                                        if (!next.value.id.startsWith(SPECIAL_CATEGORY_PREFIX)) {
                                            XtreamHubRepository.prefetchCategory(next.value.id)
                                        }
                                    }
                                }
                            }
                            XtreamHubCategoryRow(
                                category = category,
                                live = isLive,
                                epg = if (isLive) epgMap else emptyMap(),
                                sectionPadding = sectionPadding,
                                onPosterClick = onTileClick,
                                onPosterLongClick = onTileLongClick,
                                onViewAll = { openCategoryId = category.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- header chrome ---------------------------------------------------------------

@Composable
private fun XtreamHubHeader(
    accounts: List<XtreamAccount>,
    selectedAccountId: String?,
    sections: List<XtreamHubSection>,
    section: XtreamHubSection,
    horizontalPadding: Dp,
    onSelectAccount: (String) -> Unit,
    onSelectSection: (XtreamHubSection) -> Unit,
    onAddProvider: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        sections.forEach { s ->
            XtreamHubSectionChip(
                label = stringResource(s.labelRes),
                selected = section == s,
                onClick = { onSelectSection(s) },
            )
        }
        Spacer(Modifier.weight(1f))
        // Always a dropdown so it's obvious you can switch playlists (and it lists all of them,
        // plus an "Add Playlist" entry so a second provider is one tap away).
        XtreamAccountDropdown(accounts, selectedAccountId, onSelectAccount, onAddProvider)
    }
}

private val XtreamHubSection.labelRes: StringResource
    get() = when (this) {
        XtreamHubSection.LIVE -> Res.string.compose_iptv_hub_section_live
        XtreamHubSection.MOVIES -> Res.string.media_movies
        XtreamHubSection.SERIES -> Res.string.media_series
    }

/** Section switch in the app's pill-chip vocabulary (nav pills, tablet top bar). */
@Composable
private fun XtreamHubSectionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(if (selected) tokens.colors.overlaySelected else tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(
                horizontal = tokens.components.chipHorizontalPadding,
                vertical = tokens.components.chipVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun XtreamAccountDropdown(
    accounts: List<XtreamAccount>,
    selectedAccountId: String?,
    onSelectAccount: (String) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    val fallbackLabel = stringResource(Res.string.compose_iptv_hub_playlist_fallback)
    val addPlaylistLabel = stringResource(Res.string.compose_settings_page_iptv_add_playlist)
    val selectedName = accounts.firstOrNull { it.id == selectedAccountId }?.name
        ?: accounts.firstOrNull()?.name
        ?: fallbackLabel
    NuvioDropdownChip(
        title = stringResource(Res.string.compose_iptv_hub_playlists_title),
        label = selectedName,
        selectedKey = selectedAccountId,
        options = accounts.map { NuvioDropdownOption(key = it.id, label = it.name) } +
            NuvioDropdownOption(key = ADD_PLAYLIST_OPTION_KEY, label = addPlaylistLabel),
        onSelected = { option ->
            if (option.key == ADD_PLAYLIST_OPTION_KEY) onAddPlaylist() else onSelectAccount(option.key)
        },
    )
}

// --- category shelves ------------------------------------------------------------

@Composable
private fun XtreamHubCategoryRow(
    category: XtreamHubCategory,
    live: Boolean,
    epg: Map<String, ChannelEpg>,
    sectionPadding: Dp,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onViewAll: () -> Unit,
) {
    val title = category.name.ifBlank { stringResource(Res.string.library_other) }
    if (category.items.isEmpty()) {
        // Loading or not-yet-loaded: real title, shimmer tiles with the resolved tiles'
        // exact silhouette so nothing jumps when the row lands.
        val brush = rememberHomeSkeletonBrush()
        NuvioShelfSection(
            title = title,
            entries = remember { (0 until PLACEHOLDER_TILE_COUNT).toList() },
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { it },
        ) {
            XtreamHubTilePlaceholder(live = live, brush = brush)
        }
    } else {
        NuvioShelfSection(
            title = title,
            entries = category.items,
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            onViewAllClick = onViewAll,
            endContent = { XtreamHubViewAllCard(live = live, onClick = onViewAll) },
            key = { it.id },
        ) { item ->
            // Item-5 window append: composing the LAST loaded tile of a longer category pulls
            // the next window in — endless-scroll inside the row, no whole-category List ever.
            if (category.hasMore && item === category.items.last()) {
                LaunchedEffect(category.id, category.items.size) { XtreamHubRepository.loadMore(category.id) }
            }
            if (live) {
                // Live channel: card + now/next EPG line, fetched lazily as it appears.
                LaunchedEffect(item.id) { XtreamHubRepository.ensureEpg(item.id) }
                XtreamLiveChannelTile(
                    item = item,
                    epg = epg[item.id],
                    onClick = { onPosterClick(item) },
                    onLongClick = onPosterLongClick?.let { cb -> { cb(item) } },
                )
            } else {
                // No width override: the card sizes itself from the user's poster-style
                // setting (and follows catalog landscape mode like home's rows do).
                HomePosterCard(
                    item = item,
                    onClick = { onPosterClick(item) },
                    onLongClick = onPosterLongClick?.let { cb -> { cb(item) } },
                )
            }
        }
    }
}

@Composable
private fun XtreamHubViewAllCard(live: Boolean, onClick: () -> Unit) {
    val style = rememberPosterCardStyleUiState()
    NuvioPosterCard(
        title = stringResource(Res.string.home_view_all),
        imageUrl = null,
        shape = if (live || style.catalogLandscapeModeEnabled) {
            NuvioPosterShape.Landscape
        } else {
            NuvioPosterShape.Poster
        },
        showTitleBelow = false,
        onClick = onClick,
    )
}

@Composable
private fun XtreamHubCategoryPage(
    category: XtreamHubCategory,
    live: Boolean,
    epg: Map<String, ChannelEpg>,
    sectionPadding: Dp,
    onBack: () -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    loadFromRepository: Boolean = true,
) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    val landscape = live || rememberPosterCardStyleUiState().catalogLandscapeModeEnabled
    val title = category.name.ifBlank { stringResource(Res.string.library_other) }

    if (loadFromRepository) {
        LaunchedEffect(category.id) { XtreamHubRepository.loadCategory(category.id) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.nuvio.colors.textPrimary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.nuvio.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = remember(maxWidth, landscape) {
                xtreamCategoryGridColumns(maxWidth, landscape)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sectionPadding,
                    end = sectionPadding,
                    bottom = nuvioSafeBottomPadding(MaterialTheme.nuvio.spacing.screenBottom),
                ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap),
            ) {
            gridItemsIndexed(
                items = category.items,
                key = { _, item -> item.id },
            ) { index, item ->
                if (category.hasMore && index == category.items.lastIndex) {
                    LaunchedEffect(category.id, category.items.size) {
                        XtreamHubRepository.loadMore(category.id)
                    }
                }
                if (live) {
                    LaunchedEffect(item.id) { XtreamHubRepository.ensureEpg(item.id) }
                    XtreamLiveChannelTile(
                        item = item,
                        epg = epg[item.id],
                        compact = true,
                        onClick = { onPosterClick(item) },
                        onLongClick = onPosterLongClick?.let { callback -> { callback(item) } },
                    )
                } else {
                    HomePosterCard(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                        onClick = { onPosterClick(item) },
                        onLongClick = onPosterLongClick?.let { callback -> { callback(item) } },
                    )
                }
            }
            }
        }
    }
}

private fun xtreamCategoryGridColumns(width: Dp, landscape: Boolean): Int =
    if (landscape) {
        when {
            width >= 1400.dp -> 6
            width >= 1200.dp -> 5
            width >= 840.dp -> 4
            width >= 600.dp -> 3
            else -> 2
        }
    } else {
        when {
            width >= 1400.dp -> 8
            width >= 1200.dp -> 7
            width >= 1000.dp -> 6
            width >= 840.dp -> 5
            width >= 600.dp -> 4
            else -> 3
        }
    }

@Composable
private fun XtreamLiveChannelTile(
    item: MetaPreview,
    epg: ChannelEpg?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    compact: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    // The card style's natural landscape width — not a hardcoded tile size.
    val tileWidth = landscapePosterWidth(rememberPosterCardStyleUiState().widthDp)
    Column(modifier = if (compact) Modifier.fillMaxWidth() else Modifier.width(tileWidth)) {
        Box {
            HomePosterCard(
                item = item,
                modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                useLandscapeBackdropMode = true,
                compact = compact,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // Personalization pin marker — a corner badge on the card artwork, the house pattern for a
            // per-card status marker (mirrors NuvioPosterWatchedOverlay). Top-START so it never collides
            // with the watched badge (top-END). A pin already floats the channel up its category; this is
            // the visible cue. LIVE-only: item.pinned is set on live rows in XtreamHubRepository.
            if (item.pinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(NuvioTokens.Space.s6)
                        .size(NuvioTokens.Icon.md)
                        .clip(tokens.shapes.avatar)
                        .background(tokens.colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(Res.string.compose_livetv_pinned_channel),
                        tint = tokens.colors.onAccent,
                        modifier = Modifier.size(NuvioTokens.Icon.xs),
                    )
                }
            }
        }
        Text(
            text = epg?.now ?: stringResource(Res.string.compose_iptv_hub_epg_no_information),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NuvioTokens.Space.s2, start = NuvioTokens.Space.s2, end = NuvioTokens.Space.s2),
        )
        epg?.next?.let { next ->
            Text(
                text = stringResource(Res.string.compose_iptv_hub_epg_next, next),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted.copy(alpha = tokens.opacity.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s2),
            )
        }
    }
}

// --- loading / empty / error states ----------------------------------------------

/** Initial category-list load: home-style shimmer shelves instead of a full-screen spinner. */
@Composable
private fun XtreamHubSkeleton(
    live: Boolean,
    sectionPadding: Dp,
) {
    val tokens = MaterialTheme.nuvio
    val brush = rememberHomeSkeletonBrush()
    Column(
        modifier = Modifier.fillMaxSize().padding(top = NuvioTokens.Space.s4),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
    ) {
        repeat(SKELETON_SHELF_COUNT) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                // Title placeholder (same spec as HomeSkeletonRow).
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .background(brush),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                    repeat(PLACEHOLDER_TILE_COUNT) {
                        XtreamHubTilePlaceholder(live = live, brush = brush)
                    }
                }
            }
        }
    }
}

/**
 * Shimmer silhouette of one tile, mirroring the real card column (image + optional title line +
 * trailing zero-height box with 6dp gaps — see NuvioPosterCard) plus the live tiles' EPG line,
 * all sized from the user's poster style so pending and resolved rows are the same height.
 */
@Composable
private fun XtreamHubTilePlaceholder(
    live: Boolean,
    brush: Brush,
) {
    val style = rememberPosterCardStyleUiState()
    val landscape = live || style.catalogLandscapeModeEnabled
    val cardWidth = if (landscape) landscapePosterWidth(style.widthDp) else style.widthDp.dp
    val cardHeight = if (landscape) landscapePosterHeightForWidth(cardWidth) else style.heightDp.dp
    val cardShape = RoundedCornerShape(style.cornerRadiusDp.dp)
    Column(modifier = Modifier.width(cardWidth)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(cardShape)
                    .background(brush),
            )
            if (!style.hideLabelsEnabled) {
                // A blank Text with the real title style keeps exactly one line of its height.
                Text(
                    text = " ",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .background(brush),
                )
            }
            Box(modifier = Modifier.height(NuvioTokens.Space.none))
        }
        if (live) {
            Text(
                text = " ",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s2, start = NuvioTokens.Space.s2, end = NuvioTokens.Space.s2)
                    .fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
                    .background(brush),
            )
        }
    }
}

/** Error / empty vocabulary shared with home: NuvioSurfaceCard + primary action. */
@Composable
private fun XtreamHubMessageCard(
    title: String,
    message: String,
    sectionPadding: Dp,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s8),
    ) {
        HomeEmptyStateCard(
            title = title,
            message = message,
            actionLabel = actionLabel,
            onActionClick = onAction,
        )
    }
}

@Composable
private fun XtreamHubNoPlaylistState(onAddProvider: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = tabletTopInset)
                .padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s16),
        ) {
            HomeEmptyStateCard(
                title = stringResource(Res.string.compose_iptv_hub_no_provider_title),
                message = stringResource(Res.string.compose_iptv_hub_no_provider_message),
                actionLabel = stringResource(Res.string.compose_iptv_hub_add_provider),
                onActionClick = onAddProvider,
            )
        }
    }
}

private val TABLET_TOP_BAR_INSET = 72.dp

/**
 * How many rows past the one that just appeared get their items fetched early. Deliberately small:
 * category responses are large, so the win (rows arriving filled in) has to stay cheap. The hard
 * bounds live in XtreamHubRepository, which caps concurrent fetches and drops prefetches once
 * enough are outstanding.
 */
private const val CATEGORY_PREFETCH_LOOKAHEAD = 3
private const val PLACEHOLDER_TILE_COUNT = 6
private const val SKELETON_SHELF_COUNT = 3
private const val ADD_PLAYLIST_OPTION_KEY = "__add_playlist__"
private const val SPECIAL_CATEGORY_PREFIX = "__live_"
private const val SPECIAL_FAVORITES_ID = "${SPECIAL_CATEGORY_PREFIX}favorites__"
private const val SPECIAL_RECENT_ID = "${SPECIAL_CATEGORY_PREFIX}recent__"
