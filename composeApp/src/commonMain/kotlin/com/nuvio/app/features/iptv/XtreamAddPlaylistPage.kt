package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Lock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_setup_chip_code
import nuvio.composeapp.generated.resources.provider_edit_type_locked_note
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import nuvio.composeapp.generated.resources.provider_edit_banner_message
import nuvio.composeapp.generated.resources.provider_edit_banner_title
import nuvio.composeapp.generated.resources.provider_edit_locked_field
import nuvio.composeapp.generated.resources.provider_edit_locked_note
import nuvio.composeapp.generated.resources.provider_edit_name_label
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsSwitchRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSecretTextField

/**
 * Which playlist the "Add Playlist" form is editing — set right before navigating to
 * SettingsPage.IptvAddPlaylist. Mirrors XtreamContentPage. `editId == null` => add mode.
 */
internal object XtreamAddPage {
    var editId: String? = null
        private set

    val isEdit: Boolean get() = editId != null

    /** Set by the empty IPTV screen's "I have a setup code": the next Add Playlist opens on the Code chip. */
    var startWithCode: Boolean = false
        private set

    /** Opens add mode on the Code chip. */
    fun openAddWithCode() {
        editId = null
        startWithCode = true
    }

    /** The page has read the one-shot request. */
    fun consumeStartWithCode(): Boolean = startWithCode.also { startWithCode = false }

    /** Fresh "Add Playlist". */
    fun openAdd() {
        editId = null
        startWithCode = false
    }

    /** Edit an existing playlist (prefills from state). */
    fun openEdit(id: String) {
        editId = id
    }
}

/**
 * The four playlist source types from the reference design. All are functional: [XTREAM], [URL] (M3U)
 * and [FILE] (local M3U) landed in P1/P2; [STALKER] (MAG/Ministra portal) is P4. A disabled type
 * renders with a "· Soon" suffix and can't be selected — flip [enabled] as each field-set lands.
 */
internal enum class XtreamSourceType(val label: String, val enabled: Boolean) {
    URL("URL", enabled = true),
    FILE("File", enabled = true),
    XTREAM("Xtream", enabled = true),
    STALKER("Stalker", enabled = true),
    /** Step 2: a provider's setup code. Not a connection form — it opens the setup preview. */
    CODE("Code", enabled = true),
}

// DNS-over-HTTPS providers offered per playlist (Android only — iOS can't do per-playlist DNS).
// key = the persisted XtreamAccount.dnsProvider value; label = what the tile shows.
private val DNS_PROVIDERS = listOf(
    "system" to "System",
    "cloudflare" to "Cloudflare",
    "google" to "Google",
    "mullvad" to "Mullvad",
    "quad9" to "Swiss",
    "dnssb" to "DNS.SB",
)

// Auto-refresh choices (hours). 0 = off; 24 is the product default. Persisted as autoRefreshHours.
private val AUTO_REFRESH_OPTIONS = listOf(
    0 to "Off",
    6 to "Every 6 hours",
    12 to "Every 12 hours",
    24 to "Every 24 hours",
    48 to "Every 48 hours",
    72 to "Every 72 hours",
)

private fun autoRefreshLabel(hours: Int): String =
    AUTO_REFRESH_OPTIONS.firstOrNull { it.first == hours }?.second ?: "Every $hours hours"

/**
 * "Add Playlist" / "Edit Playlist" form. A settings sub-page (parentPage = Iptv) rather than a
 * dialog — the form is too tall for an AlertDialog. Built entirely from the settings design system
 * (SettingsSection/SettingsGroup, marigold FilterChips, SettingsSecretTextField, NuvioDropdownChip,
 * NuvioPrimaryButton). Xtream is the only functional source type in P1; the other tiles are disabled.
 * [onDone] pops back to the playlist list on a successful save.
 */
internal fun LazyListScope.xtreamAddPlaylistContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onDone: () -> Unit,
    onOpenSetupPreview: () -> Unit = {},
) {
    item {
        val editing = XtreamAddPage.editId?.let { id -> state.accounts.firstOrNull { it.id == id } }
        // Step 2: a managed playlist's Edit is a name-only screen with the rest locked.
        val managedInfo = editing?.let { ManagedInfoRepository.infoFor(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId, it.id) }
        if (editing != null && managedInfo != null) {
            ManagedEditContent(isTablet = isTablet, state = state, account = editing, info = managedInfo, onDone = onDone)
            return@item
        }
        val startOnCode = remember { XtreamAddPage.consumeStartWithCode() }

        // Prefilled once per target playlist (add mode -> blank). Fields are plain remembered state.
        val editingIsM3uUrl = editing?.sourceType == SOURCE_TYPE_M3U_URL
        val editingIsM3uFile = editing?.sourceType == SOURCE_TYPE_M3U_FILE
        val editingIsStalker = editing?.sourceType == SOURCE_TYPE_STALKER
        var sourceType by remember(editing?.id) {
            mutableStateOf(
                when {
                    startOnCode && editing == null -> XtreamSourceType.CODE
                    editingIsM3uUrl -> XtreamSourceType.URL
                    editingIsM3uFile -> XtreamSourceType.FILE
                    editingIsStalker -> XtreamSourceType.STALKER
                    else -> XtreamSourceType.XTREAM
                }
            )
        }
        var server by remember(editing?.id) { mutableStateOf(if (editingIsM3uUrl || editingIsM3uFile) "" else editing?.baseUrl ?: "") }
        var username by remember(editing?.id) { mutableStateOf(editing?.username ?: "") }
        var password by remember(editing?.id) { mutableStateOf(editing?.password ?: "") }
        var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
        var epgUrl by remember(editing?.id) { mutableStateOf(editing?.epgUrl ?: "") }
        var dnsProvider by remember(editing?.id) { mutableStateOf(editing?.dnsProvider ?: "system") }
        var autoRefreshHours by remember(editing?.id) { mutableStateOf(editing?.autoRefreshHours ?: 24) }
        // M3U-URL source fields (baseUrl carries the M3U URL for m3u_url accounts).
        var m3uUrl by remember(editing?.id) { mutableStateOf(if (editingIsM3uUrl) editing?.baseUrl ?: "" else "") }
        var userAgent by remember(editing?.id) { mutableStateOf(editing?.userAgent ?: "") }
        // M3U-FILE source: the picked document (null until a pick) + the display file name (prefilled
        // from the edited account so its "re-import" state and name survive without a new pick).
        var pickedFile by remember(editing?.id) { mutableStateOf<PickedM3UFile?>(null) }
        var pickedFileName by remember(editing?.id) { mutableStateOf(editing?.fileName) }
        // Stalker (MAG/Ministra): portal base reuses `server`; MAC is required (pre-seeded with the
        // common IPTV OUI prefix); serial/deviceId/login are optional overrides for strict portals.
        var mac by remember(editing?.id) { mutableStateOf(editing?.macAddress?.takeIf { it.isNotBlank() } ?: "00:1A:79:") }
        var stalkerUser by remember(editing?.id) { mutableStateOf(editing?.stalkerUsername ?: "") }
        var stalkerPass by remember(editing?.id) { mutableStateOf(editing?.stalkerPassword ?: "") }
        var serial by remember(editing?.id) { mutableStateOf(editing?.serialNumber ?: "") }
        var deviceId by remember(editing?.id) { mutableStateOf(editing?.deviceId ?: "") }
        var sendDeviceId by remember(editing?.id) { mutableStateOf(editing?.sendDeviceId ?: true) }
        // F46: the rest of a real box's identity (all optional, blank = derived / preset).
        var deviceId2 by remember(editing?.id) { mutableStateOf(editing?.deviceId2 ?: "") }
        var signature by remember(editing?.id) { mutableStateOf(editing?.signature ?: "") }
        var stbModel by remember(editing?.id) { mutableStateOf(editing?.stbModel ?: "") }
        var hwVersion by remember(editing?.id) { mutableStateOf(editing?.hwVersion ?: "") }
        // Step 0.3: backup server rows as typed (validated on save; hidden for M3U files).
        var backupRows by remember(editing?.id) { mutableStateOf(editing?.backupUrls ?: emptyList()) }
        // A file playlist synced from another device has a fileName but no local copy here.
        val fileMissingOnThisDevice = editingIsM3uFile && editing != null && !M3UFileStore.hasLocalCopy(editing)

        // UX88: the sections share ONE lazy item, so the list's own item spacing never applied and
        // each title sat flush against the previous section's helper text. Space them like the
        // settings list spaces its items.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap),
        ) {
            SourceTypeSection(
                isTablet = isTablet,
                selected = sourceType,
                // The type is decided when a playlist is added: in edit mode no chip can be selected.
                editing = XtreamAddPage.isEdit,
                onSelected = { sourceType = it },
            )

            if (sourceType == XtreamSourceType.CODE) {
                SetupCodeEntrySection(isTablet = isTablet, onOpenPreview = onOpenSetupPreview)
                return@Column
            }

            when (sourceType) {
                XtreamSourceType.XTREAM -> XtreamFieldsSection(
                    isTablet = isTablet,
                    server = server,
                    onServerChange = { input ->
                        // Xtream = portal + username + password. Pasting a full get.php URL into Server URL
                        // auto-fills user/pass; otherwise it's just the portal URL.
                        val parsed = parseXtreamAccount(input)
                        if (parsed != null) {
                            server = parsed.baseUrl
                            username = parsed.username
                            password = parsed.password
                            if (name.isBlank()) name = parsed.name
                        } else {
                            server = input
                        }
                    },
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    userAgent = userAgent,
                    onUserAgentChange = { userAgent = it },
                    name = name,
                    onNameChange = { name = it },
                )
                XtreamSourceType.URL -> M3UFieldsSection(
                    isTablet = isTablet,
                    m3uUrl = m3uUrl,
                    onM3uUrlChange = { m3uUrl = it },
                    userAgent = userAgent,
                    onUserAgentChange = { userAgent = it },
                    name = name,
                    onNameChange = { name = it },
                )
                XtreamSourceType.FILE -> M3UFileFieldsSection(
                    isTablet = isTablet,
                    pickedFileName = pickedFileName,
                    fileMissingOnThisDevice = fileMissingOnThisDevice && pickedFile == null,
                    onChooseFile = {
                        pickM3UFile { picked ->
                            if (picked != null) {
                                pickedFile = picked
                                pickedFileName = picked.fileName
                                if (name.isBlank()) name = picked.fileName.substringBeforeLast('.')
                            }
                        }
                    },
                    userAgent = userAgent,
                    onUserAgentChange = { userAgent = it },
                    name = name,
                    onNameChange = { name = it },
                )
                XtreamSourceType.CODE -> Unit
                XtreamSourceType.STALKER -> StalkerFieldsSection(
                    isTablet = isTablet,
                    portalUrl = server,
                    onPortalUrlChange = { server = it },
                    mac = mac,
                    onMacChange = { mac = it },
                    serial = serial,
                    onSerialChange = { serial = it },
                    deviceId = deviceId,
                    onDeviceIdChange = { deviceId = it },
                    sendDeviceId = sendDeviceId,
                    onSendDeviceIdChange = { sendDeviceId = it },
                    deviceId2 = deviceId2,
                    onDeviceId2Change = { deviceId2 = it },
                    signature = signature,
                    onSignatureChange = { signature = it },
                    stbModel = stbModel,
                    onStbModelChange = { stbModel = it },
                    hwVersion = hwVersion,
                    onHwVersionChange = { hwVersion = it },
                    stalkerUser = stalkerUser,
                    onStalkerUserChange = { stalkerUser = it },
                    stalkerPass = stalkerPass,
                    onStalkerPassChange = { stalkerPass = it },
                    name = name,
                    onNameChange = { name = it },
                )
            }

            EpgUrlSection(
                isTablet = isTablet,
                epgUrl = epgUrl,
                onEpgUrlChange = { epgUrl = it },
            )

            DnsProviderSection(
                isTablet = isTablet,
                selected = dnsProvider,
                onSelected = { dnsProvider = it },
            )

            AutoRefreshSection(
                isTablet = isTablet,
                selectedHours = autoRefreshHours,
                onSelected = { autoRefreshHours = it },
            )

            val resolvedSourceType = when (sourceType) {
                XtreamSourceType.URL -> SOURCE_TYPE_M3U_URL
                XtreamSourceType.FILE -> SOURCE_TYPE_M3U_FILE
                XtreamSourceType.STALKER -> SOURCE_TYPE_STALKER
                else -> SOURCE_TYPE_XTREAM
            }
            val backupCheck = BackupServerValidation.validate(
                resolvedSourceType,
                if (sourceType == XtreamSourceType.URL) m3uUrl else server,
                backupRows,
            )
            if (BackupServerValidation.supportsBackups(resolvedSourceType)) {
                BackupServersSection(
                    isTablet = isTablet,
                    rows = backupRows,
                    problems = backupCheck.problems.associate { it.index to it.problem },
                    schemeOnlyRows = BackupServerValidation.schemeOnlyDifferences(
                        resolvedSourceType,
                        if (sourceType == XtreamSourceType.URL) m3uUrl else server,
                        backupRows,
                    ),
                    placeholder = if (sourceType == XtreamSourceType.URL) "http://other-host/playlist.m3u" else "http://other-host:port",
                    onRowsChange = { backupRows = it },
                )
            }

            SaveSection(
                isTablet = isTablet,
                state = state,
                isEdit = XtreamAddPage.isEdit,
                canSave = sourceType.enabled && backupCheck.ok && when (sourceType) {
                    XtreamSourceType.URL -> m3uUrl.isNotBlank()
                    // A file playlist can save when a new file was picked, OR (edit) an on-device copy exists.
                    XtreamSourceType.FILE -> pickedFile != null || (editingIsM3uFile && !fileMissingOnThisDevice)
                    // Stalker auths by MAC, not creds — a portal URL + MAC is enough.
                    XtreamSourceType.STALKER -> server.isNotBlank() && mac.isNotBlank()
                    else -> server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                },
                onSave = {
                    val input = XtreamFormInput(
                        serverUrl = server,
                        username = username,
                        password = password,
                        name = name.trim().ifEmpty { null },
                        epgUrl = epgUrl.trim().ifEmpty { null },
                        dnsProvider = dnsProvider,
                        autoRefreshHours = autoRefreshHours,
                        sourceType = resolvedSourceType,
                        m3uUrl = m3uUrl,
                        userAgent = userAgent.trim().ifEmpty { null },
                        fileName = pickedFileName,
                        pickedFile = pickedFile,
                        macAddress = mac.trim(),
                        stalkerUsername = stalkerUser.trim().ifEmpty { null },
                        stalkerPassword = stalkerPass.trim().ifEmpty { null },
                        serialNumber = serial.trim().ifEmpty { null },
                        deviceId = deviceId.trim().ifEmpty { null },
                        sendDeviceId = sendDeviceId,
                        deviceId2 = deviceId2.trim().ifEmpty { null },
                        signature = signature.trim().ifEmpty { null },
                        stbModel = stbModel.trim().ifEmpty { null },
                        hwVersion = hwVersion.trim().ifEmpty { null },
                        backupUrls = backupRows,
                    )
                    val editId = XtreamAddPage.editId
                    if (editId != null) {
                        XtreamRepository.editFromForm(editId, input) { ok -> if (ok) onDone() }
                    } else {
                        XtreamRepository.addFromForm(input) { ok -> if (ok) onDone() }
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SourceTypeSection(
    isTablet: Boolean,
    selected: XtreamSourceType,
    editing: Boolean,
    onSelected: (XtreamSourceType) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val selectable = ManagedPlaylistPolicy.sourceTypeSelectable(editing)
    SettingsSection(title = "Source Type", isTablet = isTablet) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            // A code is only ever entered when adding; an existing playlist has no "Code" type to show.
            XtreamSourceType.entries.filter { !editing || it != XtreamSourceType.CODE }.forEach { type ->
                FilterChip(
                    selected = selected == type,
                    enabled = type.enabled && selectable,
                    onClick = { if (type.enabled && selectable) onSelected(type) },
                    label = {
                        Text(
                            text = if (type.enabled) {
                                if (type == XtreamSourceType.CODE) stringResource(Res.string.provider_setup_chip_code) else type.label
                            } else "${type.label} · Soon",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = type.enabled,
                        selected = selected == type,
                        borderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                        selectedBorderColor = tokens.colors.accent.copy(alpha = tokens.opacity.strong),
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
                        selectedLabelColor = tokens.colors.textPrimary,
                        labelColor = tokens.colors.textSecondary,
                        disabledLabelColor = tokens.colors.textDisabled,
                        // Locked in edit mode, not greyed out: the chosen type still reads as chosen.
                        disabledSelectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
                    ),
                )
            }
        }
        if (editing) {
            Spacer(Modifier.height(NuvioTokens.Space.s8))
            Text(
                text = stringResource(Res.string.provider_edit_type_locked_note),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun XtreamFieldsSection(
    isTablet: Boolean,
    server: String,
    onServerChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Xtream Account", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            FormOutlinedField(
                value = server,
                onValueChange = onServerChange,
                label = "Server URL",
                placeholder = "portal, e.g. http://host:port",
            )
            FormOutlinedField(
                value = username,
                onValueChange = onUsernameChange,
                label = "Username",
            )
            SettingsSecretTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                modifier = Modifier.fillMaxWidth(),
            )
            FormOutlinedField(
                value = userAgent,
                onValueChange = onUserAgentChange,
                label = "User-Agent (optional)",
                placeholder = "e.g. VLC/3.0.20 LibVLC/3.0.20",
            )
            Text(
                text = "Only if streams won't play: some providers' firewalls block unknown apps. " +
                    "Set the User-Agent of a player they allow (e.g. VLC) to match it.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            FormOutlinedField(
                value = name,
                onValueChange = onNameChange,
                label = "Name (optional)",
            )
        }
    }
}

@Composable
private fun StalkerFieldsSection(
    isTablet: Boolean,
    portalUrl: String,
    onPortalUrlChange: (String) -> Unit,
    mac: String,
    onMacChange: (String) -> Unit,
    serial: String,
    onSerialChange: (String) -> Unit,
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    sendDeviceId: Boolean,
    onSendDeviceIdChange: (Boolean) -> Unit,
    deviceId2: String,
    onDeviceId2Change: (String) -> Unit,
    signature: String,
    onSignatureChange: (String) -> Unit,
    stbModel: String,
    onStbModelChange: (String) -> Unit,
    hwVersion: String,
    onHwVersionChange: (String) -> Unit,
    stalkerUser: String,
    onStalkerUserChange: (String) -> Unit,
    stalkerPass: String,
    onStalkerPassChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Stalker Portal", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            FormOutlinedField(
                value = portalUrl,
                onValueChange = onPortalUrlChange,
                label = "Portal URL",
                placeholder = "http://host:port/c/",
            )
            FormOutlinedField(
                value = mac,
                onValueChange = onMacChange,
                label = "MAC Address",
                placeholder = "00:1A:79:xx:xx:xx",
            )
            FormOutlinedField(
                value = name,
                onValueChange = onNameChange,
                label = "Name (optional)",
            )
            Text(
                text = "Stalker portals sign in with the MAC address — no username or password. The " +
                    "device serial and IDs are derived from the MAC and Tuvora presents itself as a " +
                    "MAG250; only fill in the fields below if your provider (or your old box) gave you " +
                    "specific values. Leave them empty otherwise.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            FormOutlinedField(
                value = serial,
                onValueChange = onSerialChange,
                label = "Serial number (optional)",
            )
            FormOutlinedField(
                value = deviceId,
                onValueChange = onDeviceIdChange,
                label = "Device ID (optional)",
            )
            FormOutlinedField(
                value = deviceId2,
                onValueChange = onDeviceId2Change,
                label = "Device ID 2 (optional)",
            )
            SettingsSwitchRow(
                title = "Send device signature",
                description = "Sends the device signature when signing in to the portal. Turn off only if your provider requires it. Device IDs are still sent.",
                checked = sendDeviceId,
                isTablet = isTablet,
                onCheckedChange = onSendDeviceIdChange,
            )
            FormOutlinedField(
                value = signature,
                onValueChange = onSignatureChange,
                label = "Signature (optional)",
            )
            FormOutlinedField(
                value = stbModel,
                onValueChange = onStbModelChange,
                label = "STB model (optional)",
                placeholder = "MAG250",
            )
            FormOutlinedField(
                value = hwVersion,
                onValueChange = onHwVersionChange,
                label = "Hardware version (optional)",
                placeholder = "1.7-BD-00",
            )
            FormOutlinedField(
                value = stalkerUser,
                onValueChange = onStalkerUserChange,
                label = "Portal username (optional)",
            )
            SettingsSecretTextField(
                value = stalkerPass,
                onValueChange = onStalkerPassChange,
                label = "Portal password (optional)",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun M3UFieldsSection(
    isTablet: Boolean,
    m3uUrl: String,
    onM3uUrlChange: (String) -> Unit,
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "M3U Playlist", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            FormOutlinedField(
                value = m3uUrl,
                onValueChange = onM3uUrlChange,
                label = "M3U URL",
                placeholder = "http://host:port/get.php?username=…&type=m3u_plus",
            )
            FormOutlinedField(
                value = userAgent,
                onValueChange = onUserAgentChange,
                label = "User-Agent (optional)",
                placeholder = "e.g. VLC/3.0.20 LibVLC/3.0.20",
            )
            FormOutlinedField(
                value = name,
                onValueChange = onNameChange,
                label = "Name (optional)",
            )
            Text(
                text = "The playlist is downloaded and indexed on save. Large playlists can take a moment.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun M3UFileFieldsSection(
    isTablet: Boolean,
    pickedFileName: String?,
    fileMissingOnThisDevice: Boolean,
    onChooseFile: () -> Unit,
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "M3U File", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            NuvioPrimaryButton(
                text = if (pickedFileName != null) "Choose a different file" else "Choose file",
                onClick = onChooseFile,
            )
            when {
                fileMissingOnThisDevice -> Text(
                    // A file playlist synced from another device: fileName is known but the bytes aren't here.
                    text = "“${pickedFileName ?: "This playlist"}” was added on another device. Choose the file again to import it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
                pickedFileName != null -> Text(
                    text = "Selected: $pickedFileName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
            FormOutlinedField(
                value = userAgent,
                onValueChange = onUserAgentChange,
                label = "User-Agent (optional)",
                placeholder = "e.g. VLC/3.0.20 LibVLC/3.0.20",
            )
            FormOutlinedField(
                value = name,
                onValueChange = onNameChange,
                label = "Name (optional)",
            )
            Text(
                text = "The file is copied into the app and indexed on save, so the original can be moved or deleted. File contents are not synced across devices.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun EpgUrlSection(
    isTablet: Boolean,
    epgUrl: String,
    onEpgUrlChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "EPG URLs (optional)", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            FormOutlinedField(
                value = epgUrl,
                onValueChange = onEpgUrlChange,
                label = "XMLTV EPG URLs",
                placeholder = "https://example.com/guide.xml.gz",
                singleLine = false,
            )
            Text(
                text = "Add one or more XMLTV guides, one per line. The first one wins for each channel; " +
                    "the provider's own guide is still used for every channel these don't cover. " +
                    "Leave blank to use only the provider's guide.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DnsProviderSection(
    isTablet: Boolean,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "DNS Provider", isTablet = isTablet) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                DNS_PROVIDERS.forEach { (key, label) ->
                    FilterChip(
                        selected = selected == key,
                        onClick = { onSelected(key) },
                        label = {
                            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected == key,
                            borderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                            selectedBorderColor = tokens.colors.accent.copy(alpha = tokens.opacity.strong),
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
                            selectedLabelColor = tokens.colors.textPrimary,
                            labelColor = tokens.colors.textSecondary,
                        ),
                    )
                }
            }
            Text(
                text = "Choose a DNS server for resolving this playlist's addresses. Cloudflare or Google can improve connection reliability.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            // UX83: a limitation note only where the setting is actually ignored — never a dev note
            // ("iOS ignores this") shown to Android users, for whom the setting works.
            if (!perPlaylistDnsSupported) {
                Text(
                    text = "Only used by the Android app — this device ignores it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun AutoRefreshSection(
    isTablet: Boolean,
    selectedHours: Int,
    onSelected: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Auto-Refresh", isTablet = isTablet) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            NuvioDropdownChip(
                title = "Auto-Refresh",
                label = autoRefreshLabel(selectedHours),
                selectedKey = selectedHours.toString(),
                options = AUTO_REFRESH_OPTIONS.map { NuvioDropdownOption(it.first.toString(), it.second) },
                onSelected = { option -> option.key.toIntOrNull()?.let(onSelected) },
            )
            Text(
                text = "Periodically check this playlist for new movies and series.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

/**
 * Step 0.3 — "Backup servers": an ordered list of alternate addresses (priority = order) with add /
 * remove / move up-down. Row operations are [BackupServerListEdits]; validation is
 * [BackupServerValidation] (run by the caller — this only renders its verdict per row).
 */
@Composable
private fun BackupServersSection(
    isTablet: Boolean,
    rows: List<String>,
    problems: Map<Int, BackupServerValidation.Problem>,
    schemeOnlyRows: Set<Int>,
    placeholder: String,
    onRowsChange: (List<String>) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Backup servers", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Text(
                text = "Used automatically if the main server doesn't respond.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            rows.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FormOutlinedField(
                        value = value,
                        onValueChange = { onRowsChange(BackupServerListEdits.update(rows, index, it)) },
                        label = "Backup server ${index + 1}",
                        placeholder = placeholder,
                        isError = index in problems,
                        modifier = Modifier.weight(1f),
                    )
                    // UX89: the two reorder arrows stack in one narrow column (they fit the field's
                    // height) instead of taking two full-width buttons, so a long URL stays readable.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { onRowsChange(BackupServerListEdits.moveUp(rows, index)) },
                            enabled = index > 0,
                            modifier = Modifier.size(BACKUP_ARROW_TARGET),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowUp,
                                contentDescription = "Move up",
                                tint = if (index > 0) tokens.colors.textSecondary else tokens.colors.textDisabled,
                            )
                        }
                        IconButton(
                            onClick = { onRowsChange(BackupServerListEdits.moveDown(rows, index)) },
                            enabled = index < rows.lastIndex,
                            modifier = Modifier.size(BACKUP_ARROW_TARGET),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Move down",
                                tint = if (index < rows.lastIndex) tokens.colors.textSecondary else tokens.colors.textDisabled,
                            )
                        }
                    }
                    IconButton(onClick = { onRowsChange(BackupServerListEdits.remove(rows, index)) }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove backup server",
                            tint = tokens.colors.textSecondary,
                        )
                    }
                }
                problems[index]?.let { problem ->
                    Text(
                        text = BackupServerListEdits.message(problem),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.danger,
                    )
                }
                if (index !in problems && index in schemeOnlyRows) {
                    // UX92: valid, but usually the main server typed twice — say so, don't block.
                    Text(
                        text = "Same server as the main one — only http/https differs",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
            if (BackupServerListEdits.canAdd(rows)) {
                TextButton(onClick = { onRowsChange(BackupServerListEdits.add(rows)) }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                    Spacer(Modifier.width(NuvioTokens.Space.s8))
                    Text(text = "Add backup server", color = tokens.colors.accent)
                }
            }
        }
    }
}

/** Two stacked reorder arrows together match the 56dp text field's height. */
private val BACKUP_ARROW_TARGET = 28.dp

@Composable
private fun SaveSection(
    isTablet: Boolean,
    state: XtreamUiState,
    isEdit: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        state.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.danger,
            )
        }
        val saveLabel = when {
            state.isValidating -> "Verifying…"
            isEdit -> "Save changes"
            else -> "Add playlist"
        }
        NuvioPrimaryButton(
            text = saveLabel,
            enabled = canSave && !state.isValidating,
            onClick = onSave,
        )
        if (state.isValidating) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(tokens.icons.md),
                    strokeWidth = tokens.borders.medium,
                    color = tokens.colors.accent,
                )
                Spacer(Modifier.width(NuvioTokens.Space.s8))
                Text(
                    text = "Contacting the provider…",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        // Store policy: the app must state it ships no content of its own.
        Text(
            text = "Tuvora does not provide any channels, movies, or series. All content comes from the playlist you add here, and you must have a valid subscription with that provider.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
    }
}

/** OutlinedTextField pre-styled with the Nuvio settings token colors (marigold focus border). */
@Composable
internal fun FormOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    isError: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = 1,
        maxLines = if (singleLine) 1 else 5,
        isError = isError,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus.copy(alpha = tokens.opacity.strong),
            unfocusedBorderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
            focusedContainerColor = tokens.colors.surface,
            unfocusedContainerColor = tokens.colors.surface,
            disabledContainerColor = tokens.colors.surface,
            focusedLabelColor = tokens.colors.textSecondary,
            unfocusedLabelColor = tokens.colors.textMuted,
            focusedTextColor = tokens.colors.textPrimary,
            unfocusedTextColor = tokens.colors.textPrimary,
            cursorColor = tokens.colors.accent,
        ),
    )
}


/**
 * Edit Playlist for a MANAGED playlist: a banner saying why, the name as the only editable field, and the
 * server/login shown as a locked row (locked, not hidden). The save goes through the normal edit path,
 * which for a managed playlist is a rename built from the pulled account ([ManagedEditPolicy]).
 */
@Composable
private fun ManagedEditContent(
    isTablet: Boolean,
    state: XtreamUiState,
    account: XtreamAccount,
    info: ManagedInfo,
    onDone: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var name by remember(account.id) { mutableStateOf(account.name) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap),
    ) {
        com.nuvio.app.core.ui.NuvioSurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(tokens.icons.lg),
                )
                Column {
                    Text(
                        text = stringResource(Res.string.provider_edit_banner_title, info.providerName),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                    )
                    Text(
                        text = stringResource(Res.string.provider_edit_banner_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
        SettingsSection(title = stringResource(Res.string.provider_edit_name_label), isTablet = isTablet) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s2),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                FormOutlinedField(value = name, onValueChange = { name = it }, label = stringResource(Res.string.provider_edit_name_label))
                LockedFieldRow(
                    label = stringResource(Res.string.provider_edit_locked_field),
                    value = stringResource(Res.string.provider_edit_locked_note),
                )
            }
        }
        SaveSection(
            isTablet = isTablet,
            state = state,
            isEdit = true,
            canSave = name.isNotBlank(),
            onSave = {
                val input = XtreamFormInput(
                    serverUrl = account.baseUrl, username = account.username, password = account.password, name = name,
                    epgUrl = account.epgUrl, dnsProvider = account.dnsProvider, autoRefreshHours = account.autoRefreshHours,
                    sourceType = account.sourceType,
                )
                XtreamRepository.editFromForm(account.id, input) { ok -> if (ok) onDone() }
            },
        )
    }
}

/** A dashed, non-interactive field stand-in for something the person cannot change here. */
@Composable
private fun LockedFieldRow(label: String, value: String) {
    val tokens = MaterialTheme.nuvio
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .androidx_drawDashedBorder(tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium), dash)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material.icons.Icons.Rounded.Lock,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(tokens.icons.md),
        )
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }
}

private fun Modifier.androidx_drawDashedBorder(color: androidx.compose.ui.graphics.Color, effect: androidx.compose.ui.graphics.PathEffect): Modifier =
    this.drawBehind {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = effect)
        drawRoundRect(color = color, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
    }
