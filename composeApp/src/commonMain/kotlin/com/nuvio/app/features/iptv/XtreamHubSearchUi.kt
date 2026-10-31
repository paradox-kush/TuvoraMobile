package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_close
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_empty_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_empty_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_failed_message
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_failed_title
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_hint
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_movies
import nuvio.composeapp.generated.resources.compose_iptv_hub_search_series
import org.jetbrains.compose.resources.stringResource

/** The search box under the hub header: one playlist, one section (F48). Focuses itself when it opens. */
@Composable
internal fun XtreamHubSearchBar(
    playlistName: String,
    section: XtreamHubSection,
    query: String,
    sectionPadding: Dp,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val label = when (section) {
        XtreamHubSection.SERIES -> stringResource(Res.string.compose_iptv_hub_search_series, playlistName)
        else -> stringResource(Res.string.compose_iptv_hub_search_movies, playlistName)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding, vertical = NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormOutlinedField(
            value = query,
            onValueChange = onQueryChange,
            label = label,
            modifier = Modifier.weight(1f).focusRequester(focus),
            imeAction = ImeAction.Search,
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(Res.string.compose_iptv_hub_search_close),
                tint = MaterialTheme.nuvio.colors.textSecondary,
            )
        }
    }
}

/**
 * What sits under the search box. Every state ends: a hint while the query is too short, the skeleton while
 * asking, a poster grid, "No matches", or a failure with Retry — a failed search never reads as "no matches".
 */
@Composable
internal fun XtreamHubSearchResults(
    playlistName: String,
    search: HubSearchUiState,
    sectionPadding: Dp,
    onPosterClick: (MetaPreview) -> Unit,
    onRetry: () -> Unit,
) {
    // The wait ends at the load's own deadline even if the work never returns (BoundedLoad).
    when (val status = rememberEffectiveLoadStatus(search.load)) {
        LoadStatus.Idle -> XtreamHubMessageCard(
            title = "",
            message = stringResource(Res.string.compose_iptv_hub_search_hint, playlistName),
            sectionPadding = sectionPadding,
        )
        is LoadStatus.Loading -> XtreamHubSkeleton(live = false, sectionPadding = sectionPadding)
        is LoadStatus.Failed -> XtreamHubMessageCard(
            title = stringResource(Res.string.compose_iptv_hub_search_failed_title),
            message = stringResource(Res.string.compose_iptv_hub_search_failed_message, playlistName),
            sectionPadding = sectionPadding,
            actionLabel = stringResource(Res.string.action_retry),
            onAction = onRetry,
        )
        LoadStatus.Empty -> XtreamHubMessageCard(
            title = stringResource(Res.string.compose_iptv_hub_search_empty_title),
            message = stringResource(Res.string.compose_iptv_hub_search_empty_message, playlistName, search.query.trim()),
            sectionPadding = sectionPadding,
        )
        LoadStatus.Loaded -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = remember(maxWidth) { xtreamCategoryGridColumns(maxWidth, landscape = false) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sectionPadding,
                    end = sectionPadding,
                    bottom = nuvioSafeBottomPadding(MaterialTheme.nuvio.spacing.screenBottom),
                ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap),
            ) {
                items(items = search.items, key = { it.id }) { item ->
                    HomePosterCard(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                        onClick = { onPosterClick(item) },
                    )
                }
            }
        }
    }
}
