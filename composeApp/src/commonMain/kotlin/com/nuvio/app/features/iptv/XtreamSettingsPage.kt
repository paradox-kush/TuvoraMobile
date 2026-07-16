package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection

/**
 * Xtream IPTV accounts page. "Add Playlist" opens the full form sub-page (SettingsPage.IptvAddPlaylist);
 * tapping a saved account opens an actions dialog (Edit -> the same form in edit mode, Content, Enable,
 * Remove). KMP twin of NuvioTV's XtreamSettingsScreen.
 */
internal fun LazyListScope.xtreamSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onAddPlaylist: () -> Unit = {},
    onEditPlaylist: (XtreamAccount) -> Unit = {},
    onOpenContent: (XtreamAccount) -> Unit = {},
) {
    item {
        var actionsFor by remember { mutableStateOf<XtreamAccount?>(null) }

        SettingsSection(title = "IPTV (Xtream Codes)", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Add Playlist",
                    description = "Xtream account, EPG, DNS & auto-refresh",
                    isTablet = isTablet,
                    onClick = onAddPlaylist,
                )
                state.accounts.forEach { account ->
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = account.name,
                        description = account.baseUrl + if (account.enabled) "" else "  •  disabled",
                        isTablet = isTablet,
                        onClick = { actionsFor = account },
                    )
                }
            }
        }

        actionsFor?.let { account ->
            AlertDialog(
                onDismissRequest = { actionsFor = null },
                title = { Text(account.name) },
                text = {
                    Column {
                        XtreamAccountDetails(account)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            actionsFor = null
                            onEditPlaylist(account)
                        }) { Text("Edit playlist") }
                        TextButton(onClick = {
                            actionsFor = null
                            onOpenContent(account)
                        }) { Text("Content & Categories") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        XtreamRepository.setEnabled(account.id, !account.enabled)
                        actionsFor = null
                    }) { Text(if (account.enabled) "Disable" else "Enable") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        XtreamRepository.remove(account.id)
                        actionsFor = null
                    }) { Text("Remove") }
                },
            )
        }
    }
}

/** Live account status pulled from the source's own panel API: state, connections, and expiry. */
@Composable
private fun XtreamAccountDetails(account: XtreamAccount) {
    var info by remember(account.id) { mutableStateOf<XtreamAccountInfo?>(null) }
    var loading by remember(account.id) { mutableStateOf(true) }
    // M3U has no panel to ask — skip the fetch instead of showing a phantom "couldn't reach".
    val hasPanel = !account.sourceType.isM3u()
    LaunchedEffect(account.id) {
        if (!hasPanel) { loading = false; return@LaunchedEffect }
        loading = true
        info = IptvClient.forAccount(account).accountInfo(account).getOrNull()
        loading = false
    }
    Column {
        Text(account.baseUrl, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val i = info
        when {
            !hasPanel -> {}
            loading -> Text("Loading account details…", style = MaterialTheme.typography.bodySmall)
            i == null -> Text("Couldn't reach the panel for account details.", style = MaterialTheme.typography.bodySmall)
            else -> {
                val statusLabel = (i.status?.replaceFirstChar { it.uppercase() } ?: "Unknown") + if (i.isTrial) " · Trial" else ""
                AccountDetailLine("Status", statusLabel)
                if (i.maxConnections != null) {
                    AccountDetailLine("Connections", "${i.activeConnections ?: 0} / ${i.maxConnections} active")
                }
                i.expiresAtEpochSec?.let { sec ->
                    AccountDetailLine("Expires", if (sec == 0L) "Never" else formatEpochDate(sec))
                }
                // Stalker portals report expiry as free text, not an epoch.
                i.expiresText?.let { AccountDetailLine("Expires", it) }
            }
        }
    }
}

@Composable
private fun AccountDetailLine(label: String, value: String) {
    Spacer(Modifier.height(2.dp))
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Epoch seconds -> "YYYY-MM-DD" without a date library (Hinnant's days->civil algorithm). */
private fun formatEpochDate(epochSec: Long): String {
    val z = epochSec / 86400L + 719468L
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
