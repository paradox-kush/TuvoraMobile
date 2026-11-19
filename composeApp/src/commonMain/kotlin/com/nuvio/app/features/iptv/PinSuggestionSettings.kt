package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.pin_suggest_clear_action
import nuvio.composeapp.generated.resources.pin_suggest_clear_confirm_description
import nuvio.composeapp.generated.resources.pin_suggest_clear_description
import nuvio.composeapp.generated.resources.pin_suggest_clear_done
import nuvio.composeapp.generated.resources.pin_suggest_clear_title
import nuvio.composeapp.generated.resources.pin_suggest_settings_footnote
import nuvio.composeapp.generated.resources.pin_suggest_settings_switch
import nuvio.composeapp.generated.resources.pin_suggest_settings_switch_description
import nuvio.composeapp.generated.resources.pin_suggest_settings_title
import org.jetbrains.compose.resources.stringResource

/**
 * IPTV settings, "Suggestions": the switch for pin suggestions and "Clear play counts". Clearing confirms on its own row
 * (tap Clear, then Clear again within a few seconds), with no popup. The play counts it clears live on this device only.
 */
@Composable
internal fun PinSuggestionSettingsSection(isTablet: Boolean) {
    val state by PinSuggestions.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PinSuggestions.store.ensureLoaded() }
    var armed by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    // The confirmation only lasts a moment, then the row is as it was; "Cleared" likewise.
    LaunchedEffect(armed) {
        if (armed) {
            delay(CLEAR_CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    LaunchedEffect(cleared) {
        if (cleared) {
            delay(CLEARED_SHOWN_MS)
            cleared = false
        }
    }
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = stringResource(Res.string.pin_suggest_settings_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.pin_suggest_settings_switch),
                description = stringResource(Res.string.pin_suggest_settings_switch_description),
                checked = state.enabled,
                isTablet = isTablet,
                onCheckedChange = { PinSuggestions.store.setEnabled(it) },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.pin_suggest_clear_title),
                description = stringResource(
                    if (armed) Res.string.pin_suggest_clear_confirm_description else Res.string.pin_suggest_clear_description,
                ),
                isTablet = isTablet,
                trailingContent = {
                    ClearLabel(
                        text = stringResource(if (cleared) Res.string.pin_suggest_clear_done else Res.string.pin_suggest_clear_action),
                        armed = armed,
                    )
                },
                onClick = {
                    if (armed) {
                        PinSuggestions.store.clearPlayCounts()
                        armed = false
                        cleared = true
                    } else {
                        cleared = false
                        armed = true
                    }
                },
            )
        }
        Text(
            text = stringResource(Res.string.pin_suggest_settings_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = NuvioTokens.Radius.card, vertical = NuvioTokens.Space.s6),
        )
    }
}

@Composable
private fun RowScope.ClearLabel(text: String, armed: Boolean) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (armed) tokens.colors.danger else tokens.colors.accent,
    )
}

private const val CLEAR_CONFIRM_WINDOW_MS = 5_000L
private const val CLEARED_SHOWN_MS = 3_000L
