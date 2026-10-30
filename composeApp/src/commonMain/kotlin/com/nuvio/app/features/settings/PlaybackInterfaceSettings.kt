package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.app.features.player.LiveBufferPolicy
import com.nuvio.app.features.player.isPictureInPictureBlockedBySystem
import com.nuvio.app.features.player.openPictureInPictureSystemSettings
import com.nuvio.app.features.player.platformSupportsPictureInPicture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackInterfaceSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    var showHoldToSpeedValueDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-probed on every RESUME rather than remembered once: the user can leave to system settings,
    // flip the permission, and come straight back to this page.
    var pictureInPictureBlocked by remember { mutableStateOf(isPictureInPictureBlockedBySystem()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pictureInPictureBlocked = isPictureInPictureBlockedBySystem()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_page_interface),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_legacy_layout),
                    description = stringResource(Res.string.settings_playback_legacy_layout_description),
                    checked = settings.useLegacyPlayerLayout,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setUseLegacyPlayerLayout,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_loading_overlay),
                    description = stringResource(Res.string.settings_playback_show_loading_overlay_description),
                    checked = settings.showLoadingOverlay,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.playback_show_loading_status),
                    description = stringResource(Res.string.playback_show_loading_status_sub),
                    checked = settings.showPlayerLoadingStatus,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowPlayerLoadingStatus,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_pause_overlay),
                    description = stringResource(Res.string.settings_playback_pause_overlay_description),
                    checked = settings.pauseOverlayEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPauseOverlayEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_parental_guide),
                    description = stringResource(Res.string.settings_playback_parental_guide_description),
                    checked = settings.showParentalGuide,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowParentalGuide,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_stream_info),
                    description = stringResource(Res.string.settings_playback_stream_info_description),
                    checked = settings.showStreamInfo,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowStreamInfo,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_remember_preferences),
                    description = stringResource(Res.string.settings_playback_remember_preferences_description),
                    checked = settings.rememberPlayerPreferences,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setRememberPlayerPreferences,
                )
                SettingsGroupDivider(isTablet = isTablet)
                // F13: live IPTV buffer length. Slider over the offered choices; 0 = Auto.
                val liveBufferIndex = LiveBufferPolicy.CHOICES_SECONDS
                    .indexOf(settings.liveBufferSeconds).coerceAtLeast(0)
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_live_buffer),
                    value = liveBufferIndex,
                    valueText = { index ->
                        val seconds = LiveBufferPolicy.CHOICES_SECONDS[index.coerceIn(0, LiveBufferPolicy.CHOICES_SECONDS.lastIndex)]
                        if (seconds == LiveBufferPolicy.AUTO) {
                            stringResource(Res.string.settings_playback_live_buffer_auto)
                        } else {
                            stringResource(Res.string.settings_playback_live_buffer_seconds, seconds)
                        }
                    },
                    valueRange = 0..LiveBufferPolicy.CHOICES_SECONDS.lastIndex,
                    step = 1,
                    isTablet = isTablet,
                    onValueChange = { index ->
                        PlayerSettingsRepository.setLiveBufferSeconds(LiveBufferPolicy.CHOICES_SECONDS[index])
                    },
                )
                // PiP only where the OS actually has it. A switch that silently does nothing is worse
                // than no switch.
                if (platformSupportsPictureInPicture()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_picture_in_picture),
                        description = stringResource(Res.string.settings_playback_picture_in_picture_description),
                        checked = settings.pictureInPictureEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setPictureInPictureEnabled,
                    )
                    // The per-app PiP permission can be revoked in system settings, after which
                    // entering PiP silently no-ops. Say so, and offer the fix.
                    if (settings.pictureInPictureEnabled && pictureInPictureBlocked) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_playback_picture_in_picture_blocked),
                            description = stringResource(Res.string.settings_playback_picture_in_picture_blocked_description),
                            isTablet = isTablet,
                            onClick = { openPictureInPictureSystemSettings() },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_touch_gestures),
                    description = stringResource(Res.string.settings_playback_touch_gestures_description),
                    checked = settings.touchGesturesEnabled,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setTouchGesturesEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_hold_to_speed),
                    description = stringResource(Res.string.settings_playback_hold_to_speed_description),
                    checked = settings.holdToSpeedEnabled,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setHoldToSpeedEnabled,
                )
                if (settings.holdToSpeedEnabled && !settings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_hold_speed),
                        description = formatPlaybackSpeedLabel(settings.holdToSpeedValue),
                        isTablet = isTablet,
                        onClick = { showHoldToSpeedValueDialog = true },
                    )
                }
                if (!isIos) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_exo_chapters),
                        description = stringResource(Res.string.settings_playback_exo_chapters_description),
                        checked = settings.androidExoChaptersEnabled,
                        enabled = !settings.externalPlayerEnabled && settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setAndroidExoChaptersEnabled,
                    )
                }
            }
        }
    }

    if (showHoldToSpeedValueDialog) {
        HoldToSpeedValueDialog(
            selectedSpeed = settings.holdToSpeedValue,
            onSpeedSelected = { speed ->
                PlayerSettingsRepository.setHoldToSpeedValue(speed)
                showHoldToSpeedValueDialog = false
            },
            onDismiss = { showHoldToSpeedValueDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HoldToSpeedValueDialog(
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_hold_speed),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { speed ->
                val isSelected = speed == selectedSpeed
                DialogOption(
                    text = formatPlaybackSpeedLabel(speed),
                    selected = isSelected,
                    onClick = { onSpeedSelected(speed) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}
