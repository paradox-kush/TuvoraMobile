package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_confirm_detach_button
import nuvio.composeapp.generated.resources.provider_confirm_detach_message
import nuvio.composeapp.generated.resources.provider_confirm_detach_title
import nuvio.composeapp.generated.resources.provider_confirm_remove_button
import nuvio.composeapp.generated.resources.provider_confirm_remove_extra
import nuvio.composeapp.generated.resources.provider_confirm_remove_message
import nuvio.composeapp.generated.resources.provider_confirm_remove_title
import nuvio.composeapp.generated.resources.provider_details_channels
import nuvio.composeapp.generated.resources.provider_details_connections
import nuvio.composeapp.generated.resources.provider_details_day_left
import nuvio.composeapp.generated.resources.provider_details_days_left
import nuvio.composeapp.generated.resources.provider_details_detach
import nuvio.composeapp.generated.resources.provider_details_detach_failed
import nuvio.composeapp.generated.resources.provider_details_detached_toast
import nuvio.composeapp.generated.resources.provider_details_edit
import nuvio.composeapp.generated.resources.provider_details_edit_hint
import nuvio.composeapp.generated.resources.provider_details_edit_hint_managed
import nuvio.composeapp.generated.resources.provider_details_enabled
import nuvio.composeapp.generated.resources.provider_details_enabled_hint
import nuvio.composeapp.generated.resources.provider_details_expired
import nuvio.composeapp.generated.resources.provider_details_expiry_check_failed
import nuvio.composeapp.generated.resources.provider_details_expiry_not_reported
import nuvio.composeapp.generated.resources.provider_details_gone
import nuvio.composeapp.generated.resources.provider_details_locked_title
import nuvio.composeapp.generated.resources.provider_details_locked_value
import nuvio.composeapp.generated.resources.provider_details_movies
import nuvio.composeapp.generated.resources.provider_details_never_expires
import nuvio.composeapp.generated.resources.provider_details_refresh_title
import nuvio.composeapp.generated.resources.provider_details_refresh_hint
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_now
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_minutes
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_hours
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_days
import nuvio.composeapp.generated.resources.provider_details_refresh_running
import nuvio.composeapp.generated.resources.provider_details_refresh_slow
import nuvio.composeapp.generated.resources.provider_details_refresh_failed
import nuvio.composeapp.generated.resources.provider_details_refresh_partial
import nuvio.composeapp.generated.resources.provider_details_refresh_cooldown
import nuvio.composeapp.generated.resources.provider_details_refresh_reload_on_open
import nuvio.composeapp.generated.resources.provider_details_refresh_needs_file
import nuvio.composeapp.generated.resources.provider_details_remove
import nuvio.composeapp.generated.resources.provider_details_rematch_started
import nuvio.composeapp.generated.resources.provider_details_ribbon
import nuvio.composeapp.generated.resources.provider_details_ribbon_updated
import nuvio.composeapp.generated.resources.provider_details_series
import nuvio.composeapp.generated.resources.provider_details_status
import nuvio.composeapp.generated.resources.provider_details_status_loading
import nuvio.composeapp.generated.resources.provider_details_status_none
import nuvio.composeapp.generated.resources.provider_details_status_unreachable
import nuvio.composeapp.generated.resources.provider_details_tab_content
import nuvio.composeapp.generated.resources.provider_details_tab_manage
import nuvio.composeapp.generated.resources.provider_details_tab_overview
import nuvio.composeapp.generated.resources.provider_details_tile_categories
import nuvio.composeapp.generated.resources.provider_details_tile_categories_hint
import nuvio.composeapp.generated.resources.provider_details_tile_hidden
import nuvio.composeapp.generated.resources.provider_details_tile_hidden_hint
import nuvio.composeapp.generated.resources.provider_details_tile_rematch
import nuvio.composeapp.generated.resources.provider_details_tile_rematch_hint
import org.jetbrains.compose.resources.stringResource

/**
 * Which playlist the details page shows — set right before navigating to SettingsPage.IptvPlaylistDetails
 * (a plain var, like XtreamContentPage; the page bounces back if a process restart lost it).
 */
internal object XtreamPlaylistDetailsPage {
    var accountId: String? = null
        private set

    fun open(id: String) {
        accountId = id
    }
}

private enum class DetailsTab { OVERVIEW, CONTENT, MANAGE }

/**
 * The playlist details screen, for EVERY playlist: a header card (ribbon only when managed; days left and
 * its thin gold bar only when the provider reports an expiry; connections; round contact buttons only for
 * the contacts the provider set), then three one-purpose tabs. Replaces the old actions dialog. Detach and
 * Remove are behind the type-to-confirm dialog ([DestructiveConfirmDialog]).
 */
internal fun LazyListScope.xtreamPlaylistDetailsContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onEdit: (XtreamAccount) -> Unit,
    onOpenContent: (XtreamAccount) -> Unit,
    onLeave: () -> Unit,
) {
    item {
        val account = XtreamPlaylistDetailsPage.accountId?.let { id -> state.accounts.firstOrNull { it.id == id } }
        if (account == null) {
            DetailsGoneCard(isTablet)
            return@item
        }
        val controller = remember { PlaylistDetailsController() }
        val liveState by controller.live.collectAsStateWithLifecycle()
        // UX179: "Refresh now". The controller owns the work and the cooldown lives in the engine; this only renders it.
        val refreshScope = rememberCoroutineScope()
        val refreshController = remember(account.id, refreshScope) { IptvManualRefreshController(refreshScope) }
        val refreshState by refreshController.state.collectAsStateWithLifecycle()
        LaunchedEffect(account.id) { refreshController.load(account) }
        // The wait ends at the check's own deadline even if the panel never answers (BoundedLoad).
        val liveStatus = rememberEffectiveLoadStatus(liveState.load)
        val live = if (liveStatus != liveState.load) liveState.copy(load = liveStatus) else liveState
        val managedMap by ManagedInfoRepository.state.collectAsStateWithLifecycle()
        val profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
        val info = managedMap[profileId]?.get(account.id) ?: ManagedInfoRepository.infoFor(profileId, account.id)
        LaunchedEffect(account.id) { controller.load(account) }

        val activeServers by XtreamRepository.activeServers.collectAsStateWithLifecycle()
        val model = ManagedDetailsModel.build(
            account = account,
            info = info,
            accountInfo = live.info,
            counts = live.counts,
            nowEpochSec = TraktPlatformClock.nowEpochMs() / 1000,
            addressLine = (ServerFailoverPolicy.backupLabel(activeServers[account.id] ?: 0)
                ?: PlaylistAddress.hostOnly(account.baseUrl)),
            // Asked and not answered: say so, instead of letting "no answer" read as "the provider has no expiry".
            panelCheckFailed = live.hasPanel && !live.loading && live.info == null,
        )

        var tab by rememberSaveable(account.id) { mutableStateOf(DetailsTab.OVERVIEW.name) }
        var confirming by remember(account.id) { mutableStateOf<DestructiveAction?>(null) }
        var detaching by remember(account.id) { mutableStateOf(false) }
        var hiddenFor by remember { mutableStateOf<XtreamAccount?>(null) }
        val hiddenScope = rememberCoroutineScope()
        val hiddenController = remember(hiddenScope) { com.nuvio.app.features.iptv.overlay.IptvHiddenItemsController(hiddenScope) }
        val hiddenState by hiddenController.state.collectAsStateWithLifecycle()
        val detachedToast = info?.let { stringResource(Res.string.provider_details_detached_toast, it.providerName) }
        val detachFailed = stringResource(Res.string.provider_details_detach_failed)
        val rematchStarted = stringResource(Res.string.provider_details_rematch_started)

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            DetailsHeaderCard(model = model, live = live)
            DetailsTabBar(selected = DetailsTab.valueOf(tab), onSelect = { tab = it.name })
            when (DetailsTab.valueOf(tab)) {
                DetailsTab.OVERVIEW -> OverviewTab(isTablet, model, live)
                DetailsTab.CONTENT -> ContentTab(
                    isTablet = isTablet,
                    showRematch = DetailsAction.REMATCH in model.groups.flatMap { it.actions },
                    onCategories = { onOpenContent(account) },
                    onHidden = { hiddenFor = account; hiddenController.open(account) },
                    onRematch = { controller.rematch(account); NuvioToastController.show(rematchStarted) },
                )
                DetailsTab.MANAGE -> ManageTab(
                    isTablet = isTablet,
                    model = model,
                    onToggle = { XtreamRepository.setEnabled(account.id, it) },
                    onEdit = { onEdit(account) },
                    refresh = refreshState,
                    onRefresh = { refreshController.start(account) },
                    onDetach = { confirming = DestructiveAction.DETACH },
                    onRemove = { confirming = DestructiveAction.REMOVE },
                )
            }
            // The floating tab bar draws over the end of the list: leave room so Remove is fully reachable.
            Spacer(Modifier.height(FLOATING_BAR_CLEARANCE))
        }

        hiddenFor?.let { target ->
            IptvHiddenItemsDialog(
                playlistName = PlaylistAddress.displayName(target.name),
                state = hiddenState,
                onUnhide = { hiddenController.unhide(target, it) },
                onRetry = { hiddenController.open(target) },
                onDismiss = { hiddenFor = null },
            )
        }

        confirming?.let { action ->
            val provider = info?.providerName
            val shownName = PlaylistAddress.displayName(account.name)
            val copy = DestructiveConfirmPolicy.copy(action, shownName, provider, managed = info != null)
            DestructiveConfirmDialog(
                action = action,
                copy = localizedCopy(action, copy, shownName, provider, managed = info != null),
                confirmLabel = stringResource(
                    if (action == DestructiveAction.DETACH) Res.string.provider_confirm_detach_button else Res.string.provider_confirm_remove_button,
                ),
                busy = detaching,
                onDismiss = { confirming = null },
                onConfirm = {
                    when (action) {
                        DestructiveAction.REMOVE -> {
                            confirming = null
                            XtreamRepository.remove(account.id)
                            onLeave()
                        }
                        DestructiveAction.DETACH -> {
                            detaching = true
                            // Not on this page's scope: leaving mid-detach must not cancel the refresh + pull
                            // that follow the RPC (the playlist would stay shown as managed until the next sync).
                            ManagedPlaylistActions.shared.detachInBackground(account.id) { ok ->
                                detaching = false
                                confirming = null
                                NuvioToastController.show(if (ok) detachedToast.orEmpty() else detachFailed)
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun localizedCopy(
    action: DestructiveAction,
    english: DestructiveConfirmCopy,
    playlistName: String,
    provider: String?,
    managed: Boolean,
): DestructiveConfirmCopy {
    val who = provider?.takeIf { it.isNotBlank() } ?: english.title.removePrefix("Detach from ").removeSuffix("?")
    return when (action) {
        DestructiveAction.DETACH -> DestructiveConfirmCopy(
            title = stringResource(Res.string.provider_confirm_detach_title, who),
            message = stringResource(Res.string.provider_confirm_detach_message, who),
        )
        DestructiveAction.REMOVE -> DestructiveConfirmCopy(
            title = stringResource(Res.string.provider_confirm_remove_title, playlistName),
            message = stringResource(Res.string.provider_confirm_remove_message),
            extra = if (managed) stringResource(Res.string.provider_confirm_remove_extra, who) else null,
        )
    }
}

@Composable
private fun DetailsGoneCard(isTablet: Boolean) {
    SettingsGroup(isTablet = isTablet) {
        Text(
            text = stringResource(Res.string.provider_details_gone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            modifier = Modifier.padding(
                horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
            ),
        )
    }
}

@Composable
private fun DetailsHeaderCard(model: ManagedDetailsModel, live: PlaylistDetailsLive) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard {
        model.managedBy?.let {
            val updated = PlaylistAddress.isoDate(model.serviceUpdatedAt)
            val ribbon = if (updated != null) {
                stringResource(Res.string.provider_details_ribbon_updated, model.providerName.orEmpty(), updated)
            } else {
                stringResource(Res.string.provider_details_ribbon, model.providerName.orEmpty())
            }
            Surface(shape = RoundedCornerShape(NuvioTokens.Radius.full), color = tokens.colors.accent.copy(alpha = tokens.opacity.selected)) {
                Row(
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(tokens.icons.sm))
                    Text(text = ribbon, style = MaterialTheme.typography.labelMedium, color = tokens.colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(NuvioTokens.Space.s12))
        }
        Text(text = model.name, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        model.addressLine?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }

        val expiry = expiryText(model.expiry, live)
        val connections = model.connections
        if (expiry != null || connections != null) {
            Spacer(Modifier.height(NuvioTokens.Space.s16))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16)) {
                if (expiry != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = expiry, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                        (model.expiry as? ExpiryDisplay.DaysLeft)?.fraction?.let { ThinBar(it) }
                    }
                }
                if (connections != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.provider_details_connections, connections.active, connections.max),
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textPrimary,
                        )
                        if (connections.max <= MAX_DOTS) ConnectionDots(connections)
                    }
                }
            }
        }
        if (model.contacts.isNotEmpty()) {
            Spacer(Modifier.height(NuvioTokens.Space.s16))
            ProviderContactButtons(model.contacts)
        }
    }
}

private const val MAX_DOTS = 8

/** Extra room below a screen's last control for the floating bottom bar. */
internal val FLOATING_BAR_CLEARANCE = 56.dp

@Composable
private fun expiryText(expiry: ExpiryDisplay, live: PlaylistDetailsLive): String? = when (expiry) {
    is ExpiryDisplay.DaysLeft -> if (expiry.days == 1) stringResource(Res.string.provider_details_day_left) else stringResource(Res.string.provider_details_days_left, expiry.days)
    ExpiryDisplay.Expired -> stringResource(Res.string.provider_details_expired)
    ExpiryDisplay.NeverExpires -> stringResource(Res.string.provider_details_never_expires)
    is ExpiryDisplay.Text -> expiry.text
    // While the panel has not answered there is nothing to say yet; once it has (or it has no panel), say so.
    ExpiryDisplay.NotReported -> if (live.loading) null else stringResource(Res.string.provider_details_expiry_not_reported)
    ExpiryDisplay.CheckFailed -> stringResource(Res.string.provider_details_expiry_check_failed)
}

/** The thin gold bar under "N days left": the last 30 days of the subscription. Drawn only when there is an expiry. */
@Composable
private fun ThinBar(fraction: Float) {
    val tokens = MaterialTheme.nuvio
    Spacer(Modifier.height(NuvioTokens.Space.s8))
    Box(
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(NuvioTokens.Radius.full))
            .background(tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp)
                .clip(RoundedCornerShape(NuvioTokens.Radius.full)).background(tokens.colors.accent),
        )
    }
}

@Composable
private fun ConnectionDots(connections: ConnectionsDisplay) {
    val tokens = MaterialTheme.nuvio
    Spacer(Modifier.height(NuvioTokens.Space.s8))
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
        repeat(connections.max) { index ->
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (index < connections.active) tokens.colors.accent else tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium)),
            )
        }
    }
}

@Composable
private fun DetailsTabBar(selected: DetailsTab, onSelect: (DetailsTab) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
        DetailsTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Surface(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(NuvioTokens.Radius.full)).clickable { onSelect(tab) },
                shape = RoundedCornerShape(NuvioTokens.Radius.full),
                color = if (isSelected) tokens.colors.accent.copy(alpha = tokens.opacity.selected) else tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle),
            ) {
                Box(modifier = Modifier.padding(vertical = NuvioTokens.Space.s12), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            when (tab) {
                                DetailsTab.OVERVIEW -> Res.string.provider_details_tab_overview
                                DetailsTab.CONTENT -> Res.string.provider_details_tab_content
                                DetailsTab.MANAGE -> Res.string.provider_details_tab_manage
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(isTablet: Boolean, model: ManagedDetailsModel, live: PlaylistDetailsLive) {
    val tokens = MaterialTheme.nuvio
    val counts = model.counts
    if (!counts.isEmpty) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
            counts.channels?.let { MetricTile(it, stringResource(Res.string.provider_details_channels), Modifier.weight(1f)) }
            counts.movies?.let { MetricTile(it, stringResource(Res.string.provider_details_movies), Modifier.weight(1f)) }
            counts.series?.let { MetricTile(it, stringResource(Res.string.provider_details_series), Modifier.weight(1f)) }
        }
    }
    SettingsGroup(isTablet = isTablet) {
        val status = when {
            !live.hasPanel -> stringResource(Res.string.provider_details_status_none)
            model.statusText != null -> model.statusText
            live.loading -> stringResource(Res.string.provider_details_status_loading)
            else -> stringResource(Res.string.provider_details_status_unreachable)
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                vertical = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s14,
            ),
        ) {
            Text(
                text = stringResource(Res.string.provider_details_status),
                style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = status.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun MetricTile(value: Int, label: String, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier,
        shape = tokens.shapes.compactCard,
        color = tokens.colors.surface,
        border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
    ) {
        Column(modifier = Modifier.padding(NuvioTokens.Space.s14)) {
            Text(text = value.toString(), style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun ContentTab(
    isTablet: Boolean,
    showRematch: Boolean,
    onCategories: () -> Unit,
    onHidden: () -> Unit,
    onRematch: () -> Unit,
) {
    SettingsGroup(isTablet = isTablet) {
        SettingsNavigationRow(
            title = stringResource(Res.string.provider_details_tile_categories),
            description = stringResource(Res.string.provider_details_tile_categories_hint),
            isTablet = isTablet,
            onClick = onCategories,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.provider_details_tile_hidden),
            description = stringResource(Res.string.provider_details_tile_hidden_hint),
            isTablet = isTablet,
            onClick = onHidden,
        )
        if (showRematch) {
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.provider_details_tile_rematch),
                description = stringResource(Res.string.provider_details_tile_rematch_hint),
                isTablet = isTablet,
                onClick = onRematch,
            )
        }
    }
}

@Composable
private fun ManageTab(
    isTablet: Boolean,
    model: ManagedDetailsModel,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    refresh: ManualRefreshUiState,
    onRefresh: () -> Unit,
    onDetach: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsGroup(isTablet = isTablet) {
        SettingsSwitchRow(
            title = stringResource(Res.string.provider_details_enabled),
            description = stringResource(Res.string.provider_details_enabled_hint),
            checked = model.enabled,
            isTablet = isTablet,
            onCheckedChange = onToggle,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.provider_details_edit),
            description = stringResource(if (model.isManaged) Res.string.provider_details_edit_hint_managed else Res.string.provider_details_edit_hint),
            isTablet = isTablet,
            onClick = onEdit,
        )
        SettingsGroupDivider(isTablet = isTablet)
        RefreshNowRow(state = refresh, isTablet = isTablet, onRefresh = onRefresh)
        if (model.lockedServerLogin) {
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.provider_details_locked_title),
                description = stringResource(Res.string.provider_details_locked_value, model.providerName.orEmpty()),
                isTablet = isTablet,
                // Locked, not hidden: it explains itself instead of being a dead tap.
                enabled = false,
                trailingContent = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(tokens.icons.md)) },
                onClick = {},
            )
        }
    }
    Spacer(Modifier.height(NuvioTokens.Space.s8))
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
        if (model.isManaged) {
            DangerButton(text = stringResource(Res.string.provider_details_detach, model.providerName.orEmpty()), onClick = onDetach)
        }
        DangerButton(text = stringResource(Res.string.provider_details_remove), onClick = onRemove)
    }
}

/** Outlined red button for the destructive area at the bottom of Manage. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
        shape = tokens.shapes.button,
        border = BorderStroke(tokens.borders.thin, tokens.colors.danger.copy(alpha = tokens.opacity.strong)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.danger),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
