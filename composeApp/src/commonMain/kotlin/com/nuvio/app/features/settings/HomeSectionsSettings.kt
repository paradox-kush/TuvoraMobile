package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeSectionEntry
import com.nuvio.app.features.home.HomeSectionIds
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.home_section_catalogs
import nuvio.composeapp.generated.resources.home_section_catalogs_sub
import nuvio.composeapp.generated.resources.home_section_continue_watching
import nuvio.composeapp.generated.resources.home_section_continue_watching_sub
import nuvio.composeapp.generated.resources.home_section_hero
import nuvio.composeapp.generated.resources.home_section_hero_sub
import nuvio.composeapp.generated.resources.home_section_live_recents
import nuvio.composeapp.generated.resources.home_section_live_recents_sub
import nuvio.composeapp.generated.resources.home_section_new_episodes
import nuvio.composeapp.generated.resources.home_section_new_episodes_sub
import nuvio.composeapp.generated.resources.home_section_sports
import nuvio.composeapp.generated.resources.home_section_sports_sub
import nuvio.composeapp.generated.resources.home_section_upcoming
import nuvio.composeapp.generated.resources.home_section_upcoming_sub
import nuvio.composeapp.generated.resources.settings_home_order_hint
import nuvio.composeapp.generated.resources.settings_home_order_title
import nuvio.composeapp.generated.resources.settings_homescreen_reorder
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Settings -> Home layout: one list of Home's sections. Drag the handle to reorder, the switch to hide,
 * "Reset to Default" to go back to the device default order. The catalog and collection rows are one
 * entry here (their own order is the list below it).
 */
@Composable
internal fun HomeSectionsOrderSection(isTablet: Boolean) {
    val sections = HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle().value.sections
    SettingsSection(
        title = stringResource(Res.string.settings_home_order_title),
        isTablet = isTablet,
        actions = {
            NuvioActionLabel(
                text = stringResource(Res.string.action_reset),
                onClick = HomeCatalogSettingsRepository::resetHomeSections,
            )
        },
    ) {
        val tokens = MaterialTheme.nuvio
        Text(
            text = stringResource(Res.string.settings_home_order_hint),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val hapticFeedback = LocalHapticFeedback.current
        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState = lazyListState) { from, to ->
            val moved = sections.getOrNull(from.index) ?: return@rememberReorderableLazyListState
            HomeCatalogSettingsRepository.moveHomeSection(moved.id, to.index)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        SettingsGroup(isTablet = isTablet) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                state = lazyListState,
            ) {
                itemsIndexed(sections, key = { _, entry -> entry.id }) { index, entry ->
                    ReorderableItem(reorderableState, key = entry.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                        Surface(shadowElevation = elevation) {
                            Column {
                                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                                HomeSectionRow(
                                    entry = entry,
                                    isTablet = isTablet,
                                    onEnabledChange = { HomeCatalogSettingsRepository.setHomeSectionEnabled(entry.id, it) },
                                    dragHandleScope = this@ReorderableItem,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionRow(
    entry: HomeSectionEntry,
    isTablet: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    dragHandleScope: sh.calvin.reorderable.ReorderableCollectionItemScope,
) {
    val tokens = MaterialTheme.nuvio
    val hapticFeedback = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(homeSectionTitle(entry.id)),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(homeSectionSubtitle(entry.id)),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = entry.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.colors.onAccent,
                    checkedTrackColor = tokens.colors.accent,
                    uncheckedThumbColor = tokens.colors.textMuted,
                    uncheckedTrackColor = tokens.colors.borderDefault,
                ),
            )
            IconButton(
                modifier = with(dragHandleScope) {
                    Modifier.draggableHandle(
                        onDragStarted = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = { hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    )
                },
                onClick = {},
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(Res.string.settings_homescreen_reorder),
                    tint = tokens.colors.textMuted,
                )
            }
        }
    }
}

private fun homeSectionTitle(id: String) = when (id) {
    HomeSectionIds.HERO -> Res.string.home_section_hero
    HomeSectionIds.CONTINUE_WATCHING -> Res.string.home_section_continue_watching
    HomeSectionIds.LIVE_RECENTS -> Res.string.home_section_live_recents
    HomeSectionIds.NEW_EPISODES -> Res.string.home_section_new_episodes
    HomeSectionIds.SPORTS -> Res.string.home_section_sports
    HomeSectionIds.UPCOMING -> Res.string.home_section_upcoming
    else -> Res.string.home_section_catalogs
}

private fun homeSectionSubtitle(id: String) = when (id) {
    HomeSectionIds.HERO -> Res.string.home_section_hero_sub
    HomeSectionIds.CONTINUE_WATCHING -> Res.string.home_section_continue_watching_sub
    HomeSectionIds.LIVE_RECENTS -> Res.string.home_section_live_recents_sub
    HomeSectionIds.NEW_EPISODES -> Res.string.home_section_new_episodes_sub
    HomeSectionIds.SPORTS -> Res.string.home_section_sports_sub
    HomeSectionIds.UPCOMING -> Res.string.home_section_upcoming_sub
    else -> Res.string.home_section_catalogs_sub
}
