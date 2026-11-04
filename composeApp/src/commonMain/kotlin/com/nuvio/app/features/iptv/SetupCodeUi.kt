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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.provider_setup_adding
import nuvio.composeapp.generated.resources.provider_setup_add_to_device
import nuvio.composeapp.generated.resources.provider_setup_added_device_toast
import nuvio.composeapp.generated.resources.provider_setup_preview_device
import nuvio.composeapp.generated.resources.provider_setup_preview_device_note
import nuvio.composeapp.generated.resources.provider_setup_add_to
import nuvio.composeapp.generated.resources.provider_setup_added_other_profile_toast
import nuvio.composeapp.generated.resources.provider_setup_added_toast
import nuvio.composeapp.generated.resources.provider_setup_already_toast
import nuvio.composeapp.generated.resources.provider_setup_cancel
import nuvio.composeapp.generated.resources.provider_setup_code_help
import nuvio.composeapp.generated.resources.provider_setup_code_label
import nuvio.composeapp.generated.resources.provider_setup_code_placeholder
import nuvio.composeapp.generated.resources.provider_setup_continue
import nuvio.composeapp.generated.resources.provider_setup_enter_other_code
import nuvio.composeapp.generated.resources.provider_setup_paste
import nuvio.composeapp.generated.resources.provider_setup_preview_account
import nuvio.composeapp.generated.resources.provider_setup_preview_addons
import nuvio.composeapp.generated.resources.provider_setup_preview_label
import nuvio.composeapp.generated.resources.provider_setup_preview_loading
import nuvio.composeapp.generated.resources.provider_setup_preview_playlists
import nuvio.composeapp.generated.resources.provider_setup_preview_profile
import nuvio.composeapp.generated.resources.provider_setup_section_title
import nuvio.composeapp.generated.resources.provider_setup_nothing_added_toast
import nuvio.composeapp.generated.resources.provider_setup_skipped_login_toast
import nuvio.composeapp.generated.resources.provider_setup_skipped_url_toast
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SetupCodeMessage.text(): String = stringResource(resource())

@Composable
internal fun SetupCodeProblem.text(): String = SetupCodeOutcome.fromProblem(this).message!!.text()

@Composable
private fun sourceTypeLabel(sourceType: String): String = stringResource(sourceTypeResource(sourceType))

/**
 * The Code chip's content on the Add Playlist page: the Setup Code field (grouped as typed), Paste and
 * Continue. Continue holds the code in memory and opens the preview — or, without a real account, sends
 * the person to sign in first. All decisions live in [SetupCodeController]; this only renders them.
 */
@Composable
internal fun SetupCodeEntrySection(isTablet: Boolean, onOpenPreview: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val controller = SetupCodeController.shared
    val ui by controller.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val submit = {
        // Signed in or not: a code can always be looked at (B205). Adding to this device needs no account.
        when (controller.onContinue()) {
            ContinueResult.OPEN_PREVIEW -> onOpenPreview()
            ContinueResult.REJECTED -> Unit
        }
    }
    // The field owns its text so fast typing never races the state flow (a lagging copy written back
    // into the field would drop or reorder characters). It starts from what the controller holds (a
    // linked code) and the controller is told after every edit and paste.
    var field by remember { mutableStateOf(TextFieldValue(ui.typed, TextRange(ui.typed.length))) }
    // The code is cleared (redeemed, cancelled, expired) while this page stays in the back stack: the
    // field must not keep showing it.
    LaunchedEffect(ui.typed) {
        if (ui.typed.isEmpty() && field.text.isNotEmpty()) field = TextFieldValue("")
    }
    val setField = { text: String ->
        val grouped = SetupCode.liveFormat(text)
        field = TextFieldValue(grouped, TextRange(grouped.length))
        controller.onTyped(grouped)
    }
    SettingsSection(title = stringResource(Res.string.provider_setup_section_title), isTablet = isTablet) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(Res.string.provider_setup_code_help),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            OutlinedTextField(
                value = field,
                onValueChange = { edited -> setField(edited.text) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = ui.typedProblem != null,
                label = { Text(stringResource(Res.string.provider_setup_code_label)) },
                placeholder = { Text(stringResource(Res.string.provider_setup_code_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    TextButton(onClick = { clipboard.getText()?.text?.let { setField(SetupCode.extractFromLink(it) ?: it) } }) {
                        Text(stringResource(Res.string.provider_setup_paste), color = tokens.colors.accent)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    // A setup code is a one-time secret: no autocorrect, and the password keyboard type tells the
                    // keyboard not to learn it (security L7). The text itself stays visible.
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.colors.borderFocus.copy(alpha = tokens.opacity.strong),
                    unfocusedBorderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                    focusedContainerColor = tokens.colors.surface,
                    unfocusedContainerColor = tokens.colors.surface,
                    focusedLabelColor = tokens.colors.textSecondary,
                    unfocusedLabelColor = tokens.colors.textMuted,
                    focusedTextColor = tokens.colors.textPrimary,
                    unfocusedTextColor = tokens.colors.textPrimary,
                    cursorColor = tokens.colors.accent,
                ),
            )
            ui.typedProblem?.let {
                Text(text = it.text(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.provider_setup_continue),
                enabled = ui.typed.isNotBlank(),
                onClick = { submit() },
            )
        }
    }
}

/**
 * The preview page ("Setup from your provider"): what the held code would add, which profile gets it,
 * and the gold "Add to <profile>". Refusals (expired, used, network, rate limit) are plain sentences with
 * a way forward. The code itself is never shown, logged or put in a saved route argument.
 */
internal fun LazyListScope.xtreamSetupPreviewContent(
    isTablet: Boolean,
    onCancelled: () -> Unit,
    onCompleted: (SetupCompletion) -> Unit,
) {
    item {
        val controller = SetupCodeController.shared
        val ui by controller.state.collectAsStateWithLifecycle()
        val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
        val auth by AuthRepository.state.collectAsStateWithLifecycle()
        // Keyed on the held-code generation: a link (or a new Continue) while this page is open restarts the load.
        LaunchedEffect(ui.holdGeneration) { controller.loadPreview() }

        val completion = ui.completed
        if (completion != null) {
            val toast = when (completion.outcome) {
                CompletionKind.ALREADY_IN_ACCOUNT -> stringResource(Res.string.provider_setup_already_toast, completion.providerName)
                CompletionKind.NOTHING_MISSING_LOGIN -> stringResource(Res.string.provider_setup_skipped_login_toast, completion.providerName)
                CompletionKind.NOTHING_INVALID_URL -> stringResource(Res.string.provider_setup_skipped_url_toast, completion.providerName)
                CompletionKind.NOTHING_ADDED -> stringResource(Res.string.provider_setup_nothing_added_toast)
                CompletionKind.ADDED_ON_DEVICE ->
                    stringResource(Res.string.provider_setup_added_device_toast, completion.providerName)
                CompletionKind.ADDED ->
                    if (completion.profileIndex != profileState.activeProfile?.profileIndex) {
                        stringResource(
                            Res.string.provider_setup_added_other_profile_toast, completion.providerName,
                            profileState.profiles.firstOrNull { it.profileIndex == completion.profileIndex }?.name.orEmpty(),
                        )
                    } else {
                        stringResource(Res.string.provider_setup_added_toast, completion.providerName)
                    }
            }
            LaunchedEffect(completion) {
                NuvioToastController.show(toast)
                onCompleted(completion)
                controller.finish()
            }
            return@item
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            val preview = ui.preview
            val outcome = ui.previewOutcome
            when {
                ui.previewLoading || (outcome == null && controller.hasHeldCode()) -> PreviewLoadingCard()
                preview != null -> PreviewReady(
                    isTablet = isTablet,
                    preview = preview,
                    ui = ui,
                    profileState = profileState,
                    accountEmail = (auth as? AuthState.Authenticated)?.email,
                    onDevice = accountKindOf(auth) != AccountKind.REAL,
                    onSelectProfile = controller::selectProfile,
                    onConfirm = controller::confirm,
                    onCancel = { controller.cancel(); onCancelled() },
                )
                else -> PreviewRefusedCard(
                    outcome = outcome ?: SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY),
                    onRetry = { controller.loadPreview(force = true) },
                    onOtherCode = { controller.cancel(); onCancelled() },
                )
            }
        }
    }
}

@Composable
private fun PreviewLoadingCard() {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            CircularProgressIndicator(modifier = Modifier.size(tokens.icons.lg), strokeWidth = tokens.borders.medium, color = tokens.colors.accent)
            Text(
                text = stringResource(Res.string.provider_setup_preview_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PreviewRefusedCard(outcome: SetupCodeOutcome, onRetry: () -> Unit, onOtherCode: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val retryable = outcome is SetupCodeOutcome.Network || outcome is SetupCodeOutcome.RateLimited
    NuvioSurfaceCard {
        Text(
            text = outcome.message?.text().orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
        if (outcome is SetupCodeOutcome.Expired) {
            Spacer(Modifier.height(NuvioTokens.Space.s12))
            ProviderContactButtons(outcome.support.links())
        }
        Spacer(Modifier.height(NuvioTokens.Space.s16))
        if (retryable) {
            NuvioPrimaryButton(text = stringResource(Res.string.action_retry), onClick = onRetry)
            TextButton(onClick = onOtherCode, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.provider_setup_enter_other_code), color = tokens.colors.accent)
            }
        } else {
            NuvioPrimaryButton(text = stringResource(Res.string.provider_setup_enter_other_code), onClick = onOtherCode)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PreviewReady(
    isTablet: Boolean,
    preview: SetupPreview,
    ui: SetupCodeUiState,
    profileState: com.nuvio.app.features.profiles.ProfileState,
    accountEmail: String?,
    /** No real account: the playlists go to this device only, and a later sign-in can claim them. */
    onDevice: Boolean,
    onSelectProfile: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val contacts = preview.support.links()
    val selected = profileState.profiles.firstOrNull { it.profileIndex == ui.selectedProfileIndex }
        ?: profileState.activeProfile
    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.provider_setup_preview_label),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.accent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s6))
        Text(text = preview.providerName, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        if (preview.packageName.isNotBlank()) {
            Text(text = preview.packageName, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
        if (contacts.isNotEmpty()) {
            Spacer(Modifier.height(NuvioTokens.Space.s14))
            ProviderContactButtons(contacts)
        }
    }

    SettingsSection(title = stringResource(Res.string.provider_setup_preview_playlists), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            preview.playlists.forEachIndexed { index, playlist ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                PreviewRow(title = playlist.name, description = sourceTypeLabel(playlist.sourceType), isTablet = isTablet)
            }
            // Store builds never show add-ons (AddonSourcePolicy); the redeem still adds them server-side.
            if (AppFeaturePolicy.addonsEnabled && preview.addons.isNotEmpty()) {
                if (preview.playlists.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
                PreviewRow(
                    title = stringResource(Res.string.provider_setup_preview_addons),
                    description = preview.addons.joinToString(", "),
                    isTablet = isTablet,
                )
            }
        }
    }

    if (onDevice) {
        // B205: no account. Nothing to pick: the playlists land on this device, and the line says how to keep them.
        SettingsGroup(isTablet = isTablet) {
            PreviewRow(
                title = stringResource(Res.string.provider_setup_preview_device),
                description = stringResource(Res.string.provider_setup_preview_device_note),
                isTablet = isTablet,
            )
        }
    } else {
        SettingsSection(title = stringResource(Res.string.provider_setup_preview_profile), isTablet = isTablet) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                if (profileState.profiles.size > 1) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        profileState.profiles.forEach { profile ->
                            FilterChip(
                                selected = profile.profileIndex == selected?.profileIndex,
                                onClick = { onSelectProfile(profile.profileIndex) },
                                label = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = profile.profileIndex == selected?.profileIndex,
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
                }
                SettingsGroup(isTablet = isTablet) {
                    PreviewRow(
                        title = stringResource(Res.string.provider_setup_preview_account),
                        description = accountEmail,
                        isTablet = isTablet,
                    )
                }
            }
        }
    }

    ui.redeemRefusal?.let { refusal ->
        Text(text = refusal.message?.text().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
        if (refusal is SetupCodeOutcome.Expired) ProviderContactButtons(refusal.support.links())
    }

    val dead = ui.redeemRefusal is SetupCodeOutcome.Unusable || ui.redeemRefusal is SetupCodeOutcome.Expired
    NuvioPrimaryButton(
        text = if (ui.redeeming) {
            stringResource(Res.string.provider_setup_adding)
        } else {
            if (onDevice) stringResource(Res.string.provider_setup_add_to_device)
            else stringResource(Res.string.provider_setup_add_to, selected?.name.orEmpty())
        },
        enabled = !ui.redeeming && !dead,
        onClick = onConfirm,
    )
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), enabled = !ui.redeeming) {
        Text(stringResource(Res.string.provider_setup_cancel), color = tokens.colors.textSecondary)
    }
    Spacer(Modifier.height(FLOATING_BAR_CLEARANCE))
}

/** A read-only row in the settings-group style (title over a muted description), no tap target. */
@Composable
private fun PreviewRow(title: String, description: String?, isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 16.dp else 14.dp,
            ),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.Medium)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }
}
