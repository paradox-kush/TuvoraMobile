package com.nuvio.app.features.iptv

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.trakt.TraktPlatformClock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_managed_row
import nuvio.composeapp.generated.resources.provider_managed_row_day
import nuvio.composeapp.generated.resources.provider_managed_row_days
import nuvio.composeapp.generated.resources.provider_managed_row_expired
import org.jetbrains.compose.resources.stringResource

/**
 * Xtream IPTV accounts page. "Add Playlist" opens the full form sub-page (SettingsPage.IptvAddPlaylist);
 * tapping a saved playlist opens its details screen ([xtreamPlaylistDetailsContent]) — header card plus
 * Overview / Content / Manage — which replaced the old actions dialog. A playlist a provider installed
 * says "Managed by X · N days left" on its row. KMP twin of NuvioTV's XtreamSettingsScreen.
 */
internal fun LazyListScope.xtreamSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onAddPlaylist: () -> Unit = {},
    onOpenPlaylist: (XtreamAccount) -> Unit = {},
) {
    item {
        // A first catalog build runs for minutes on a large panel (~17 on a measured 468k items),
        // and mobile showed NOTHING while it happened — the `indexing` flow existed but had no
        // consumer, so the screen just looked broken. TV has shown a status here all along.
        val indexingAccounts by com.nuvio.app.features.iptv.match.XtreamTmdbResolver.indexing
            .collectAsStateWithLifecycle()
        val indexProgress by com.nuvio.app.features.iptv.match.XtreamMatchIndex.buildProgress
            .collectAsStateWithLifecycle()
        // Step 0.3: playlists currently served by a backup server.
        val activeServers by XtreamRepository.activeServers.collectAsStateWithLifecycle()
        // Step 2: which playlists a provider manages, and what their panels last said about expiry.
        val managedByProfile by ManagedInfoRepository.state.collectAsStateWithLifecycle()
        val managed = managedByProfile[ProfileRepository.activeProfileId] ?: ManagedInfoRepository.forProfile(ProfileRepository.activeProfileId)
        val knownInfo by PlaylistAccountInfoStore.shared.known.collectAsStateWithLifecycle()
        LaunchedEffect(managed.keys, state.accounts) {
            // One panel question per managed playlist per freshness window — enough for "N days left".
            PlaylistAccountInfoStore.shared.refreshManaged(state.accounts, managed.keys)
        }

        // The guide mirror indexes every region it can find, but a household uses a fraction of
        // it (2,035 of 15,397 channels on a measured panel). Unselected regions are never stored,
        // so this trims the on-device index, not just the display.
        var showRegionPicker by remember { mutableStateOf(false) }
        var regionSummary by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(showRegionPicker) {
            if (!showRegionPicker) {
                regionSummary = BoundedLoad.run(LoadSurface.SETTINGS, report = mapOf("row" to "epg_regions")) {
                    com.nuvio.app.features.epg.epgRegionSummary(
                        selected = com.nuvio.app.features.epg.EpgMirrorRepository.selectedRegions(),
                        available = com.nuvio.app.features.epg.EpgMirrorRepository.availableRegions(),
                    )
                }.valueOrNull() ?: "Unavailable"
            }
        }

        SettingsSection(title = "IPTV playlists", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Add Playlist",
                    description = "Xtream, M3U, Stalker or a setup code — with EPG, DNS & auto-refresh",
                    isTablet = isTablet,
                    onClick = onAddPlaylist,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Guide regions",
                    description = regionSummary ?: "Loading…",
                    isTablet = isTablet,
                    onClick = { showRegionPicker = true },
                )
                state.accounts.forEach { account ->
                    SettingsGroupDivider(isTablet = isTablet)
                    val info = managed[account.id]
                    SettingsNavigationRow(
                        title = PlaylistAddress.displayName(account.name),
                        // B60: an edit saved despite a failed provider check says so first.
                        description = state.saveWarnings[account.id]
                            ?: com.nuvio.app.features.iptv.match.indexingStatusLine(
                                isIndexing = account.id in indexingAccounts,
                                progress = indexProgress[account.id],
                            )
                            ?: info?.let { managedRowLine(it, knownInfo[account.id]) }
                            // P4: the address with its login masked — an M3U link's query IS the login.
                            ?: ((ServerFailoverPolicy.backupLabel(activeServers[account.id] ?: 0) ?: PlaylistAddress.masked(account.baseUrl)) +
                                if (account.enabled) "" else "  •  disabled"),
                        isTablet = isTablet,
                        trailingContent = if (info != null) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.nuvio.colors.accent,
                                    modifier = Modifier.size(MaterialTheme.nuvio.icons.md),
                                )
                            }
                        } else null,
                        onClick = { onOpenPlaylist(account) },
                    )
                }
            }
        }

        if (showRegionPicker) {
            com.nuvio.app.features.epg.EpgRegionPickerHost(onDismiss = { showRegionPicker = false })
        }
    }
}

/** "Managed by X · N days left" for a settings row (just "Managed by X" until the panel has answered). */
@Composable
private fun managedRowLine(info: ManagedInfo, panel: XtreamAccountInfo?): String {
    val expiry = panel?.let { ManagedDetailsModel.expiryOf(it, TraktPlatformClock.nowEpochMs() / 1000) }
    return when (expiry) {
        is ExpiryDisplay.DaysLeft ->
            if (expiry.days == 1) stringResource(Res.string.provider_managed_row_day, info.providerName)
            else stringResource(Res.string.provider_managed_row_days, info.providerName, expiry.days)
        ExpiryDisplay.Expired -> stringResource(Res.string.provider_managed_row_expired, info.providerName)
        else -> stringResource(Res.string.provider_managed_row, info.providerName)
    }
}
