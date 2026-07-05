package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection

/**
 * Xtream IPTV accounts page. Single paste field: the user pastes a portal/M3U URL,
 * we verify it live, then save. KMP twin of NuvioTV's XtreamSettingsScreen.
 */
internal fun LazyListScope.xtreamSettingsContent(
    isTablet: Boolean,
    state: XtreamUiState,
) {
    item {
        var showAddDialog by remember { mutableStateOf(false) }
        var actionsFor by remember { mutableStateOf<XtreamAccount?>(null) }
        var editFor by remember { mutableStateOf<XtreamAccount?>(null) }
        val indexingAccounts by XtreamTmdbResolver.indexing.collectAsStateWithLifecycle()

        SettingsSection(title = "IPTV (Xtream Codes)", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Add IPTV account",
                    description = "Paste your portal or M3U URL",
                    isTablet = isTablet,
                    onClick = { showAddDialog = true },
                )
                state.accounts.forEach { account ->
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = account.name,
                        description = account.baseUrl +
                            (if (account.id in indexingAccounts) "  •  preparing catalog…" else "") +
                            (if (account.enabled) "" else "  •  disabled"),
                        isTablet = isTablet,
                        onClick = { actionsFor = account },
                    )
                }
            }
        }

        if (showAddDialog) {
            XtreamAddDialog(
                state = state,
                onSubmitUrl = { url -> XtreamRepository.addFromUrl(url, name = null) { ok -> if (ok) showAddDialog = false } },
                onSubmitManual = { server, user, pass, name ->
                    XtreamRepository.addManual(server, user, pass, name) { ok -> if (ok) showAddDialog = false }
                },
                onDismiss = {
                    XtreamRepository.clearError()
                    showAddDialog = false
                },
            )
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
                            editFor = account
                        }) { Text("Edit URL / credentials") }
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

        editFor?.let { account ->
            XtreamAddDialog(
                state = state,
                initial = account,
                onSubmitUrl = { url ->
                    XtreamRepository.editFromUrl(account.id, url) { ok -> if (ok) editFor = null }
                },
                onSubmitManual = { server, user, pass, name ->
                    XtreamRepository.editManual(account.id, server, user, pass, name) { ok -> if (ok) editFor = null }
                },
                onDismiss = {
                    XtreamRepository.clearError()
                    editFor = null
                },
            )
        }
    }
}

/** Live account status pulled from player_api user_info: state, connections, and expiry. */
@Composable
private fun XtreamAccountDetails(account: XtreamAccount) {
    var info by remember(account.id) { mutableStateOf<XtreamAccountInfo?>(null) }
    var loading by remember(account.id) { mutableStateOf(true) }
    LaunchedEffect(account.id) {
        loading = true
        info = XtreamClient.accountInfo(account).getOrNull()
        loading = false
    }
    Column {
        Text(account.baseUrl, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val i = info
        when {
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

@Composable
private fun XtreamAddDialog(
    state: XtreamUiState,
    onSubmitUrl: (String) -> Unit,
    onSubmitManual: (server: String, user: String, pass: String, name: String?) -> Unit,
    onDismiss: () -> Unit,
    initial: XtreamAccount? = null,
) {
    var manualMode by remember { mutableStateOf(initial != null) }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }

    val canSubmit = if (manualMode) {
        server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    } else {
        url.isNotBlank()
    }
    val submit = {
        if (canSubmit && !state.isValidating) {
            if (manualMode) onSubmitManual(server.trim(), user.trim(), pass.trim(), name.trim().ifEmpty { null })
            else onSubmitUrl(url.trim())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "Edit IPTV account" else "Add IPTV account") },
        text = {
            Column {
                // mode toggle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !manualMode,
                        onClick = { manualMode = false },
                        label = { Text("Paste link") },
                    )
                    FilterChip(
                        selected = manualMode,
                        onClick = { manualMode = true },
                        label = { Text("Enter details") },
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (manualMode) {
                    OutlinedTextField(
                        value = server, onValueChange = { server = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://host:port") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = user, onValueChange = { user = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Username") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Name (optional)") },
                    )
                } else {
                    Text(
                        text = "Paste your portal or M3U URL — it contains your username & password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("http://host:port/get.php?username=…&password=…") },
                    )
                }

                state.error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = canSubmit && !state.isValidating) {
                Text(if (state.isValidating) "Verifying…" else if (initial != null) "Save" else "Add")
                if (state.isValidating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
