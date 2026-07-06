package com.nuvio.app.features.iptv

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import androidx.compose.material.icons.Icons as MaterialIcons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LiveTv

/**
 * Top-level IPTV browse surface: an account selector, Movies/Series section chips, and lazily
 * loaded category rows of posters. Posters open Nuvio's native detail (via the Xtream meta
 * short-circuit) and play through the normal streams -> player pipeline. Live TV is its own
 * guide (P5).
 */
@Composable
fun XtreamHubScreen(
    onPosterClick: (MetaPreview) -> Unit,
    onPlayLiveChannel: (String) -> Unit,
    onFavoriteLiveChannel: (String) -> Unit,
    onAddProvider: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by XtreamHubRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { XtreamHubRepository.ensureLoaded() }

    if (state.accounts.isEmpty()) {
        XtreamHubEmptyState(onAddProvider = onAddProvider, modifier = modifier)
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // In the wide/tablet layout the app's floating top nav bar overlays the top of the content,
        // which would hide this fixed section-chip header — pad it down to clear the bar.
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = tabletTopInset)) {
        XtreamHubHeader(
            accounts = state.accounts,
            selectedAccountId = state.selectedAccountId,
            sections = enabledSections,
            section = state.section,
            onSelectAccount = { XtreamHubRepository.selectAccount(it) },
            onSelectSection = { XtreamHubRepository.selectSection(it) },
            onAddProvider = onAddProvider,
        )

        when {
            state.loadingCategories -> CenteredProgress()
            visibleCategories.isEmpty() -> CenteredMessage("Nothing here yet")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
            ) {
                items(visibleCategories, key = { it.id }) { category ->
                    LaunchedEffect(category.id) { XtreamHubRepository.loadCategory(category.id) }
                    // Title always shows; only collapse a category once it's confirmed empty.
                    if (!(category.loaded && category.items.isEmpty())) {
                        XtreamHubCategoryRow(
                            category = category,
                            landscape = isLive,
                            epg = if (isLive) epgMap else emptyMap(),
                            onPosterClick = onTileClick,
                            onPosterLongClick = onTileLongClick,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun XtreamHubHeader(
    accounts: List<XtreamAccount>,
    selectedAccountId: String?,
    sections: List<XtreamHubSection>,
    section: XtreamHubSection,
    onSelectAccount: (String) -> Unit,
    onSelectSection: (XtreamHubSection) -> Unit,
    onAddProvider: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        sections.forEach { s ->
            FilterChip(
                selected = section == s,
                onClick = { onSelectSection(s) },
                label = {
                    Text(
                        when (s) {
                            XtreamHubSection.LIVE -> "Live TV"
                            XtreamHubSection.MOVIES -> "Movies"
                            XtreamHubSection.SERIES -> "Series"
                        }
                    )
                },
            )
        }
        Spacer(Modifier.weight(1f))
        // Always a dropdown so it's obvious you can switch playlists (and it lists all of them,
        // plus an "Add playlist" entry so a second provider is one tap away).
        XtreamAccountDropdown(accounts, selectedAccountId, onSelectAccount, onAddProvider)
    }
}

@Composable
private fun XtreamAccountDropdown(
    accounts: List<XtreamAccount>,
    selectedAccountId: String?,
    onSelectAccount: (String) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: accounts.firstOrNull()?.name ?: "Playlist"
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selectedName, maxLines = 1)
            Icon(MaterialIcons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        expanded = false
                        onSelectAccount(account.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add playlist") },
                leadingIcon = { Icon(MaterialIcons.Filled.Add, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddPlaylist()
                },
            )
        }
    }
}

@Composable
private fun XtreamHubCategoryRow(
    category: XtreamHubCategory,
    landscape: Boolean,
    epg: Map<String, ChannelEpg>,
    onPosterClick: (MetaPreview) -> Unit,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    val tileWidth = if (landscape) CHANNEL_WIDTH else POSTER_WIDTH
    val rowHeight = if (landscape) CHANNEL_ROW_HEIGHT else POSTER_HEIGHT
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s8)) {
        Text(
            text = category.name.ifBlank { "Other" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
        )
        if (category.items.isEmpty()) {
            // Loading or not-yet-loaded: hold the row height with a spinner so titles don't jump.
            Box(Modifier.fillMaxWidth().height(rowHeight), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                items(category.items, key = { it.id }) { item ->
                    if (landscape) {
                        // Live channel: card + now/next EPG line, fetched lazily as it appears.
                        LaunchedEffect(item.id) { XtreamHubRepository.ensureEpg(item.id) }
                        XtreamLiveChannelTile(
                            item = item,
                            epg = epg[item.id],
                            width = tileWidth,
                            onClick = { onPosterClick(item) },
                            onLongClick = onPosterLongClick?.let { cb -> { cb(item) } },
                        )
                    } else {
                        HomePosterCard(
                            item = item,
                            modifier = Modifier.width(tileWidth),
                            onClick = { onPosterClick(item) },
                            onLongClick = onPosterLongClick?.let { cb -> { cb(item) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XtreamLiveChannelTile(
    item: MetaPreview,
    epg: ChannelEpg?,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.width(width)) {
        HomePosterCard(
            item = item,
            modifier = Modifier.width(width),
            useLandscapeBackdropMode = true,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        Text(
            text = epg?.now ?: "No information",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NuvioTokens.Space.s2, start = NuvioTokens.Space.s2, end = NuvioTokens.Space.s2),
        )
        epg?.next?.let { next ->
            Text(
                text = "Next: $next",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s2),
            )
        }
    }
}

@Composable
private fun XtreamHubEmptyState(onAddProvider: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().statusBarsPadding().padding(NuvioTokens.Space.s24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MaterialIcons.Filled.LiveTv, contentDescription = null, modifier = Modifier.width(48.dp).height(48.dp))
        Spacer(Modifier.height(NuvioTokens.Space.s12))
        Text("No IPTV provider yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(NuvioTokens.Space.s6))
        Text(
            "Add an Xtream Codes account to browse Live TV, Movies and Series.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s16))
        TextButton(onClick = onAddProvider) { Text("Add IPTV provider") }
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val TABLET_TOP_BAR_INSET = 72.dp
private val POSTER_WIDTH = 120.dp
private val POSTER_HEIGHT = 180.dp
private val CHANNEL_WIDTH = 160.dp
private val CHANNEL_ROW_HEIGHT = 120.dp
