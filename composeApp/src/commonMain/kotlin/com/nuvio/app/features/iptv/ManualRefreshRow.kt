package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsNavigationRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_details_refresh_title
import nuvio.composeapp.generated.resources.provider_details_refresh_hint
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_now
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_minutes
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_hours
import nuvio.composeapp.generated.resources.provider_details_refresh_updated_days
import nuvio.composeapp.generated.resources.provider_details_refresh_running
import nuvio.composeapp.generated.resources.provider_details_refresh_failed
import nuvio.composeapp.generated.resources.provider_details_refresh_partial
import nuvio.composeapp.generated.resources.provider_details_refresh_cooldown
import nuvio.composeapp.generated.resources.provider_details_refresh_reload_on_open
import nuvio.composeapp.generated.resources.provider_details_refresh_needs_file
import nuvio.composeapp.generated.resources.provider_details_refresh_slow
import org.jetbrains.compose.resources.stringResource

/**
 * UX179: "Refresh now" with its status line. The line always says what happened: running, updated N ago, or why nothing
 * happened (cooldown, Stalker reloads on open, no local file) — and a failure says so and offers another try.
 */
@Composable
internal fun RefreshNowRow(state: ManualRefreshUiState, isTablet: Boolean, onRefresh: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    // The wait ends at the load's own deadline even if the work never returns (BoundedLoad).
    val status = rememberEffectiveLoadStatus(state.load)
    val running = status is LoadStatus.Loading
    val description = when {
        running -> stringResource(Res.string.provider_details_refresh_running)
        // A stall deadline is "outcome unknown", not "failed": the rebuild keeps going behind the screen.
        status is LoadStatus.Failed && status.timedOut -> stringResource(Res.string.provider_details_refresh_slow)
        status is LoadStatus.Failed -> stringResource(
            if (state.result == ManualRefreshResult.PARTIAL) Res.string.provider_details_refresh_partial else Res.string.provider_details_refresh_failed,
        )
        state.result == ManualRefreshResult.COOLDOWN -> stringResource(Res.string.provider_details_refresh_cooldown)
        state.result == ManualRefreshResult.RELOAD_ON_OPEN -> stringResource(Res.string.provider_details_refresh_reload_on_open)
        state.result == ManualRefreshResult.NEEDS_LOCAL_FILE -> stringResource(Res.string.provider_details_refresh_needs_file)
        else -> when (val age = state.age) {
            null -> stringResource(Res.string.provider_details_refresh_hint)
            else -> when (age.unit) {
                IptvManualRefreshPolicy.AgeUnit.JUST_NOW -> stringResource(Res.string.provider_details_refresh_updated_now)
                IptvManualRefreshPolicy.AgeUnit.MINUTES -> stringResource(Res.string.provider_details_refresh_updated_minutes, age.count.toString())
                IptvManualRefreshPolicy.AgeUnit.HOURS -> stringResource(Res.string.provider_details_refresh_updated_hours, age.count.toString())
                IptvManualRefreshPolicy.AgeUnit.DAYS -> stringResource(Res.string.provider_details_refresh_updated_days, age.count.toString())
            }
        }
    }
    SettingsNavigationRow(
        title = stringResource(Res.string.provider_details_refresh_title),
        description = description,
        isTablet = isTablet,
        enabled = !running,
        trailingContent = {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(tokens.icons.md),
                    strokeWidth = tokens.borders.medium,
                    color = tokens.colors.accent,
                )
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(tokens.icons.md))
            }
        },
        onClick = onRefresh,
    )
}

