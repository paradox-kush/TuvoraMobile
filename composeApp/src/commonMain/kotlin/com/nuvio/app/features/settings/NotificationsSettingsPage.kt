package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.notifications.AlertCap
import com.nuvio.app.features.notifications.AlertClock
import com.nuvio.app.features.notifications.AlertTimingMode
import com.nuvio.app.features.notifications.AwayReminder
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_alerts_away_days
import nuvio.composeapp.generated.resources.settings_alerts_away_description
import nuvio.composeapp.generated.resources.settings_alerts_away_off
import nuvio.composeapp.generated.resources.settings_alerts_away_title
import nuvio.composeapp.generated.resources.settings_alerts_section_away
import nuvio.composeapp.generated.resources.settings_alerts_cap_description
import nuvio.composeapp.generated.resources.settings_alerts_cap_one_week
import nuvio.composeapp.generated.resources.settings_alerts_cap_per_day
import nuvio.composeapp.generated.resources.settings_alerts_cap_three_week
import nuvio.composeapp.generated.resources.settings_alerts_cap_title
import nuvio.composeapp.generated.resources.settings_alerts_denied_body
import nuvio.composeapp.generated.resources.settings_alerts_denied_button
import nuvio.composeapp.generated.resources.settings_alerts_denied_title
import nuvio.composeapp.generated.resources.settings_alerts_less_often
import nuvio.composeapp.generated.resources.settings_alerts_learned_footer
import nuvio.composeapp.generated.resources.settings_alerts_learned_learning
import nuvio.composeapp.generated.resources.settings_alerts_learned_learning_again
import nuvio.composeapp.generated.resources.settings_alerts_learned_ready
import nuvio.composeapp.generated.resources.settings_alerts_mode_learned
import nuvio.composeapp.generated.resources.settings_alerts_mode_set_time
import nuvio.composeapp.generated.resources.settings_alerts_prompt_description
import nuvio.composeapp.generated.resources.settings_alerts_prompt_title
import nuvio.composeapp.generated.resources.settings_alerts_quiet_conflict
import nuvio.composeapp.generated.resources.settings_alerts_quiet_description
import nuvio.composeapp.generated.resources.settings_alerts_quiet_from
import nuvio.composeapp.generated.resources.settings_alerts_quiet_title
import nuvio.composeapp.generated.resources.settings_alerts_quiet_until
import nuvio.composeapp.generated.resources.settings_alerts_reset
import nuvio.composeapp.generated.resources.settings_alerts_section_often
import nuvio.composeapp.generated.resources.settings_alerts_section_quiet
import nuvio.composeapp.generated.resources.settings_alerts_section_when
import nuvio.composeapp.generated.resources.settings_alerts_set_time_label
import nuvio.composeapp.generated.resources.settings_alerts_shows_count
import nuvio.composeapp.generated.resources.settings_alerts_shows_description
import nuvio.composeapp.generated.resources.settings_alerts_shows_empty
import nuvio.composeapp.generated.resources.settings_alerts_shows_title
import nuvio.composeapp.generated.resources.settings_alerts_undo
import nuvio.composeapp.generated.resources.settings_notifications_disabled_in_app
import nuvio.composeapp.generated.resources.settings_notifications_episode_release_alerts
import nuvio.composeapp.generated.resources.settings_notifications_episode_release_alerts_description
import nuvio.composeapp.generated.resources.settings_notifications_permission_disabled
import nuvio.composeapp.generated.resources.settings_notifications_scheduled_count
import nuvio.composeapp.generated.resources.settings_notifications_section_alerts
import nuvio.composeapp.generated.resources.settings_notifications_section_test
import nuvio.composeapp.generated.resources.settings_notifications_send_test
import nuvio.composeapp.generated.resources.settings_notifications_sending_test
import nuvio.composeapp.generated.resources.settings_notifications_test_for_title
import nuvio.composeapp.generated.resources.settings_notifications_test_requires_saved_show
import nuvio.composeapp.generated.resources.settings_notifications_test_title
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the Notifications page can do, as plain functions. The page itself does no I/O and names no repository:
 * [Default] is the one place the repository is bound (thin Composables).
 */
internal class NotificationsSettingsActions(
    val setEnabled: (Boolean) -> Unit,
    val openSystemSettings: () -> Unit,
    val setTimingMode: (AlertTimingMode) -> Unit,
    val setSetTimeMinute: (Int) -> Unit,
    val resetLearnedTime: () -> Unit,
    val setQuietEnabled: (Boolean) -> Unit,
    val setQuietRange: (startMinute: Int, endMinute: Int) -> Unit,
    val setCap: (AlertCap) -> Unit,
    val setAwayReminder: (AwayReminder) -> Unit,
    val undoSendingLessOften: () -> Unit,
    val setAskAtEndOfEpisode: (Boolean) -> Unit,
    val setShowAlert: (contentType: String, contentId: String, title: String, on: Boolean) -> Unit,
    val sendTest: () -> Unit,
) {
    companion object {
        val Default = NotificationsSettingsActions(
            setEnabled = EpisodeReleaseNotificationsRepository::setEnabled,
            openSystemSettings = EpisodeReleaseNotificationsRepository::openSystemNotificationSettings,
            setTimingMode = EpisodeReleaseNotificationsRepository::setTimingMode,
            setSetTimeMinute = EpisodeReleaseNotificationsRepository::setSetTimeMinute,
            resetLearnedTime = EpisodeReleaseNotificationsRepository::resetLearnedTime,
            setQuietEnabled = EpisodeReleaseNotificationsRepository::setQuietHoursEnabled,
            setQuietRange = EpisodeReleaseNotificationsRepository::setQuietHoursRange,
            setCap = EpisodeReleaseNotificationsRepository::setCap,
            setAwayReminder = EpisodeReleaseNotificationsRepository::setAwayReminder,
            undoSendingLessOften = EpisodeReleaseNotificationsRepository::undoSendingLessOften,
            setAskAtEndOfEpisode = { ask -> EpisodeReleaseNotificationsRepository.setDontAskAgain(!ask) },
            setShowAlert = { type, id, title, on -> EpisodeReleaseNotificationsRepository.setShowAlert(type, id, title, on) },
            sendTest = EpisodeReleaseNotificationsRepository::sendTestNotification,
        )
    }
}

internal fun LazyListScope.notificationsSettingsContent(
    isTablet: Boolean,
    uiState: EpisodeReleaseNotificationsUiState,
    actions: NotificationsSettingsActions = NotificationsSettingsActions.Default,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_notifications_section_alerts),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (uiState.permissionDenied) {
                    PermissionDeniedPanel(isTablet = isTablet, onOpenSettings = actions.openSystemSettings)
                } else {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_notifications_episode_release_alerts),
                        description = stringResource(Res.string.settings_notifications_episode_release_alerts_description),
                        checked = uiState.isEnabled,
                        enabled = !uiState.isLoading,
                        isTablet = isTablet,
                        onCheckedChange = actions.setEnabled,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                ShowsWithAlerts(isTablet = isTablet, uiState = uiState, actions = actions)
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_alerts_prompt_title),
                    description = stringResource(Res.string.settings_alerts_prompt_description),
                    checked = !uiState.preferences.dontAskAgain,
                    isTablet = isTablet,
                    onCheckedChange = actions.setAskAtEndOfEpisode,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_alerts_section_when),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                WhenToNotify(isTablet = isTablet, uiState = uiState, actions = actions)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_alerts_section_quiet),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_alerts_quiet_title),
                    description = stringResource(Res.string.settings_alerts_quiet_description),
                    checked = uiState.preferences.quietHours.enabled,
                    isTablet = isTablet,
                    onCheckedChange = actions.setQuietEnabled,
                )
                if (uiState.preferences.quietHours.enabled) {
                    val quiet = uiState.preferences.quietHours
                    SettingsGroupDivider(isTablet = isTablet)
                    TimeStepperRow(
                        label = stringResource(Res.string.settings_alerts_quiet_from),
                        minute = quiet.startMinute,
                        isTablet = isTablet,
                        onChange = { actions.setQuietRange(it, quiet.endMinute) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    TimeStepperRow(
                        label = stringResource(Res.string.settings_alerts_quiet_until),
                        minute = quiet.endMinute,
                        isTablet = isTablet,
                        onChange = { actions.setQuietRange(quiet.startMinute, it) },
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_alerts_section_often),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChipRow(
                    title = stringResource(Res.string.settings_alerts_cap_title),
                    description = stringResource(Res.string.settings_alerts_cap_description),
                    isTablet = isTablet,
                ) {
                    AlertCap.entries.forEach { cap ->
                        Chip(
                            label = stringResource(cap.labelRes()),
                            selected = uiState.preferences.cap == cap,
                            onClick = { actions.setCap(cap) },
                        )
                    }
                }
                if (uiState.sendingLessOften) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SendingLessOftenRow(isTablet = isTablet, onUndo = actions.undoSendingLessOften)
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_alerts_section_away),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChipRow(
                    title = stringResource(Res.string.settings_alerts_away_title),
                    description = stringResource(Res.string.settings_alerts_away_description),
                    isTablet = isTablet,
                ) {
                    AwayReminder.entries.forEach { choice ->
                        Chip(
                            label = if (choice == AwayReminder.Off) {
                                stringResource(Res.string.settings_alerts_away_off)
                            } else {
                                stringResource(Res.string.settings_alerts_away_days, choice.days)
                            },
                            selected = uiState.awayReminder == choice,
                            onClick = { actions.setAwayReminder(choice) },
                        )
                    }
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_notifications_section_test),
            isTablet = isTablet,
        ) {
            NotificationTestCard(
                isTablet = isTablet,
                uiState = uiState,
                onSendTest = actions.sendTest,
            )
        }
    }
}

private fun AlertCap.labelRes() = when (this) {
    AlertCap.PerDay -> Res.string.settings_alerts_cap_per_day
    AlertCap.ThreePerWeek -> Res.string.settings_alerts_cap_three_week
    AlertCap.OnePerWeek -> Res.string.settings_alerts_cap_one_week
}

/** The refused-permission state: says so plainly and offers the phone's settings, instead of a switch that cannot work. */
@Composable
private fun PermissionDeniedPanel(isTablet: Boolean, onOpenSettings: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_alerts_denied_title),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(Res.string.settings_alerts_denied_body),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(stringResource(Res.string.settings_alerts_denied_button))
        }
    }
}

@Composable
private fun ShowsWithAlerts(
    isTablet: Boolean,
    uiState: EpisodeReleaseNotificationsUiState,
    actions: NotificationsSettingsActions,
) {
    var expanded by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_alerts_shows_title),
        description = stringResource(Res.string.settings_alerts_shows_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = stringResource(Res.string.settings_alerts_shows_count, uiState.showsOnCount, uiState.shows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        },
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        if (uiState.shows.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_alerts_shows_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
            )
        }
        uiState.shows.forEach { show ->
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = show.title,
                checked = show.on,
                isTablet = isTablet,
                onCheckedChange = { on -> actions.setShowAlert(show.contentType, show.contentId, show.title, on) },
            )
        }
    }
}

@Composable
private fun WhenToNotify(
    isTablet: Boolean,
    uiState: EpisodeReleaseNotificationsUiState,
    actions: NotificationsSettingsActions,
) {
    val tokens = MaterialTheme.nuvio
    val prefs = uiState.preferences
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 16.dp else 14.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                label = stringResource(Res.string.settings_alerts_mode_learned),
                selected = prefs.timingMode == AlertTimingMode.Learned,
                onClick = { actions.setTimingMode(AlertTimingMode.Learned) },
            )
            Chip(
                label = stringResource(Res.string.settings_alerts_mode_set_time),
                selected = prefs.timingMode == AlertTimingMode.SetTime,
                onClick = { actions.setTimingMode(AlertTimingMode.SetTime) },
            )
        }
        when (prefs.timingMode) {
            AlertTimingMode.Learned -> {
                val usual = uiState.usualMinute
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            usual != null -> stringResource(Res.string.settings_alerts_learned_ready, AlertClock.words(usual))
                            uiState.learningAgain -> stringResource(Res.string.settings_alerts_learned_learning_again)
                            else -> stringResource(Res.string.settings_alerts_learned_learning)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                    )
                    if (usual != null || uiState.habitSessions > 0) {
                        Text(
                            text = stringResource(Res.string.settings_alerts_reset),
                            modifier = Modifier.clickable(onClick = actions.resetLearnedTime).padding(8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.colors.accent,
                        )
                    }
                }
                uiState.quietConflict?.let { conflict ->
                    Text(
                        text = stringResource(
                            Res.string.settings_alerts_quiet_conflict,
                            AlertClock.words(conflict.usualMinute),
                            AlertClock.hhmm(conflict.movedToMinute),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.accent,
                    )
                }
                Text(
                    text = stringResource(Res.string.settings_alerts_learned_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            AlertTimingMode.SetTime -> {
                TimeStepperRow(
                    label = stringResource(Res.string.settings_alerts_set_time_label),
                    minute = prefs.setTimeMinute,
                    isTablet = isTablet,
                    horizontalPadding = 0.dp,
                    onChange = actions.setSetTimeMinute,
                )
            }
        }
    }
}

@Composable
private fun SendingLessOftenRow(isTablet: Boolean, onUndo: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.settings_alerts_less_often),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Text(
            text = stringResource(Res.string.settings_alerts_undo),
            modifier = Modifier.clickable(onClick = onUndo).padding(8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.accent,
        )
    }
}

/** A time of day with minus and plus buttons in half-hour steps: no dialog, nothing to dismiss. */
@Composable
private fun TimeStepperRow(
    label: String,
    minute: Int,
    isTablet: Boolean,
    onChange: (Int) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = if (isTablet) 20.dp else 16.dp,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = { onChange(AlertClock.step(minute, -1)) }) {
            Icon(Icons.Default.Remove, contentDescription = null, tint = tokens.colors.textPrimary)
        }
        Text(
            text = AlertClock.hhmm(minute),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = { onChange(AlertClock.step(minute, 1)) }) {
            Icon(Icons.Default.Add, contentDescription = null, tint = tokens.colors.textPrimary)
        }
    }
}

@Composable
private fun NotificationTestCard(
    isTablet: Boolean,
    uiState: EpisodeReleaseNotificationsUiState,
    onSendTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_notifications_test_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = uiState.testTargetTitle?.let { title ->
                        stringResource(Res.string.settings_notifications_test_for_title, title)
                    } ?: stringResource(Res.string.settings_notifications_test_requires_saved_show),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (uiState.isEnabled) {
                        stringResource(Res.string.settings_notifications_scheduled_count, uiState.scheduledCount)
                    } else {
                        stringResource(Res.string.settings_notifications_disabled_in_app)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onSendTest,
                enabled = !uiState.isSendingTest && !uiState.isLoading && uiState.testTargetTitle != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    if (uiState.isSendingTest) {
                        stringResource(Res.string.settings_notifications_sending_test)
                    } else {
                        stringResource(Res.string.settings_notifications_send_test)
                    },
                )
            }

            uiState.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!uiState.permissionGranted && !uiState.permissionDenied) {
                Text(
                    text = stringResource(Res.string.settings_notifications_permission_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
