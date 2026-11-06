package com.nuvio.app.features.recordings.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioSheetActionButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.recordings.api.CaptureMode
import com.nuvio.app.features.recordings.api.RecordingAction
import com.nuvio.app.features.recordings.api.RecordingActionsPolicy
import com.nuvio.app.features.recordings.api.RecordingRow
import com.nuvio.app.features.recordings.api.RecordingStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.recordings_action_cancel
import nuvio.composeapp.generated.resources.recordings_action_delete_confirm
import nuvio.composeapp.generated.resources.recordings_action_delete_keep
import nuvio.composeapp.generated.resources.recordings_details_what_happened
import nuvio.composeapp.generated.resources.recordings_file_missing
import nuvio.composeapp.generated.resources.recordings_saving_catch_up
import nuvio.composeapp.generated.resources.recordings_saving_live
import org.jetbrains.compose.resources.stringResource

/**
 * The details of one recording: what it is, why it failed (in plain words), and the actions that make sense
 * for its state ([RecordingActionsPolicy]). Delete asks inline, in the sheet, not in a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingDetailsSheet(
    row: RecordingRow,
    fileAvailable: Boolean,
    onDismiss: () -> Unit,
    onAction: (RecordingAction) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmingDelete by remember(row.id) { mutableStateOf(false) }
    val actions = RecordingActionsPolicy.actionsFor(row, fileAvailable)
    val failedLike = row.status == RecordingStatus.FAILED || row.status == RecordingStatus.CANCELLED

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NuvioTokens.Space.s20)
                .padding(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Text(
                text = recordingWhen(row).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            Text(
                text = recordingHeadline(row),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = recordingStatusLine(row),
                style = MaterialTheme.typography.labelMedium,
                color = if (row.status == RecordingStatus.FAILED) colors.danger else colors.textSecondary,
            )
            row.description?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Text(
                text = stringResource(if (row.mode == CaptureMode.CATCH_UP) Res.string.recordings_saving_catch_up else Res.string.recordings_saving_live),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (failedLike) {
                Text(
                    text = stringResource(Res.string.recordings_details_what_happened),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(recordingProblemRes(row.problem)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                )
            }
            if (row.status == RecordingStatus.DONE && !fileAvailable) {
                Text(
                    text = stringResource(Res.string.recordings_file_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.danger,
                )
            }

            if (confirmingDelete) {
                Text(
                    text = stringResource(Res.string.recordings_action_delete_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                    NuvioSheetActionButton(
                        icon = Icons.Rounded.Delete,
                        label = stringResource(Res.string.action_delete),
                        background = colors.danger,
                        contentColor = Color.White,
                        onClick = { onAction(RecordingAction.DELETE) },
                        modifier = Modifier.weight(1f),
                    )
                    NuvioSheetActionButton(
                        icon = Icons.Rounded.Close,
                        label = stringResource(Res.string.recordings_action_delete_keep),
                        background = Color.Transparent,
                        contentColor = colors.textPrimary,
                        border = colors.borderSubtle,
                        onClick = { confirmingDelete = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                    actions.forEachIndexed { index, action ->
                        val filled = index == 0 && action != RecordingAction.DELETE
                        val (icon, labelRes) = when (action) {
                            RecordingAction.PLAY -> Icons.Rounded.PlayArrow to Res.string.action_play
                            RecordingAction.RETRY -> Icons.Rounded.Refresh to Res.string.action_retry
                            RecordingAction.CANCEL -> Icons.Rounded.Close to Res.string.recordings_action_cancel
                            RecordingAction.DELETE -> Icons.Rounded.Delete to Res.string.action_delete
                        }
                        NuvioSheetActionButton(
                            icon = icon,
                            label = stringResource(labelRes),
                            background = if (filled) colors.accent else Color.Transparent,
                            contentColor = if (filled) colors.onAccent else colors.textPrimary,
                            border = if (filled) null else colors.borderSubtle,
                            onClick = { if (action == RecordingAction.DELETE) confirmingDelete = true else onAction(action) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
