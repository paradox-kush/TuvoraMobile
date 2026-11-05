package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.nuvio.app.core.contracts.DetailsReminderControl
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.membershipTitle
import com.nuvio.app.features.tracking.trackingMembershipDestinations
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.calendar_bell_off
import nuvio.composeapp.generated.resources.compose_tracking_list_picker_loading
import nuvio.composeapp.generated.resources.compose_tracking_list_picker_subtitle
import org.jetbrains.compose.resources.stringResource

/** One row of the + menu: a library list to join, or the Calendar's new-episode reminder switch. */
sealed interface TrackingPickerRow {
    val key: String
    data class ListRow(val tab: TrackingLibraryTab) : TrackingPickerRow {
        override val key: String get() = tab.key
    }
    data class ReminderRow(val on: Boolean) : TrackingPickerRow {
        override val key: String get() = REMINDER_ROW_KEY
    }
}

const val REMINDER_ROW_KEY = "calendar:reminder"

/** The lists first, then the reminder switch when the Calendar can follow this title ([reminder] non-null). */
fun trackingPickerRows(
    destinations: List<TrackingLibraryTab>,
    reminder: DetailsReminderControl?,
): List<TrackingPickerRow> =
    destinations.map { TrackingPickerRow.ListRow(it) } +
        listOfNotNull(reminder?.let { TrackingPickerRow.ReminderRow(it.on) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingListPickerDialog(
    visible: Boolean,
    title: String,
    reminder: DetailsReminderControl? = null,
    tabs: List<TrackingLibraryTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    errorMessage: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val tokens = MaterialTheme.nuvio
    val destinations = trackingMembershipDestinations(tabs)
    val rows = trackingPickerRows(destinations, reminder)

    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Text(
            text = stringResource(Res.string.compose_tracking_list_picker_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }

        if (isPending && rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s40),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                ) {
                    NuvioLoadingIndicator(
                        modifier = Modifier.size(tokens.icons.lg),
                    )
                    Text(
                        text = stringResource(Res.string.compose_tracking_list_picker_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s40),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                items(items = rows, key = { it.key }) { row ->
                    when (row) {
                        is TrackingPickerRow.ListRow -> DialogOption(
                            text = row.tab.membershipTitle(),
                            selected = membership[row.tab.key] == true,
                            enabled = !isPending,
                            role = Role.Checkbox,
                            onClick = { onToggle(row.tab.key) },
                        )
                        // Applies immediately (not part of Save), exactly as the old standalone bell did.
                        is TrackingPickerRow.ReminderRow -> DialogOption(
                            text = stringResource(Res.string.calendar_bell_off),
                            selected = row.on,
                            role = Role.Switch,
                            onClick = { reminder?.toggle?.invoke() },
                        )
                    }
                }
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                enabled = !isPending,
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = onSave,
                style = DialogButtonStyle.Primary,
                loading = isPending,
            )
        }
    }
}
