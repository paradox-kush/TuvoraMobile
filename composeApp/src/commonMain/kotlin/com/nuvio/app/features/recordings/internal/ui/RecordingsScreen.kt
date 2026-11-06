package com.nuvio.app.features.recordings.internal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.contracts.RecordingPlayback
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.recordings.api.LocalRecordings
import com.nuvio.app.features.recordings.api.RecordingAction
import com.nuvio.app.features.recordings.api.RecordingRow
import com.nuvio.app.features.recordings.api.RecordingSection
import com.nuvio.app.features.recordings.api.RecordingStatus
import com.nuvio.app.features.recordings.api.RecordingsLoadPolicy
import com.nuvio.app.features.recordings.api.RecordingsPagePolicy
import com.nuvio.app.features.recordings.api.RecordingsView
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.recordings_action_failed
import nuvio.composeapp.generated.resources.recordings_empty_body
import nuvio.composeapp.generated.resources.recordings_empty_title
import nuvio.composeapp.generated.resources.recordings_load_failed
import nuvio.composeapp.generated.resources.recordings_load_timed_out
import nuvio.composeapp.generated.resources.recordings_loading
import nuvio.composeapp.generated.resources.recordings_section_done
import nuvio.composeapp.generated.resources.recordings_section_failed
import nuvio.composeapp.generated.resources.recordings_section_recording
import nuvio.composeapp.generated.resources.recordings_section_scheduled
import nuvio.composeapp.generated.resources.recordings_title
import nuvio.composeapp.generated.resources.recordings_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Recordings page: Scheduled / Recording / Done / Failed. Pure drawing: the state holder
 * ([RecordingsController]) reads, the policies ([RecordingsPagePolicy], [RecordingsLoadPolicy]) decide.
 */
@Composable
internal fun RecordingsScreen(
    onBack: () -> Unit,
    onPlay: (RecordingPlayback) -> Unit,
) {
    val port = LocalRecordings.current
    val scope = rememberCoroutineScope()
    val actionFailedText = stringResource(Res.string.recordings_action_failed)
    val controller = remember(port) {
        port?.let { RecordingsController(it, scope, TraktPlatformClock::nowEpochMs) { NuvioToastController.show(actionFailedText) } }
    }
    LaunchedEffect(controller) { controller?.load() }

    if (controller == null) {
        NuvioScreen {
            stickyHeader { NuvioScreenHeader(title = stringResource(Res.string.recordings_title), onBack = onBack) }
            item { CenteredMessage(stringResource(Res.string.recordings_unavailable)) }
        }
        return
    }

    val loaded by controller.loaded.collectAsStateWithLifecycle()
    val attempt by controller.attempt.collectAsStateWithLifecycle()
    val page by controller.page.collectAsStateWithLifecycle()
    val playable by controller.playableUris.collectAsStateWithLifecycle()

    // The wait ends itself at its deadline even if the read never returns (the UI holds its own backstop).
    var nowMs by remember { mutableStateOf(TraktPlatformClock.nowEpochMs()) }
    val view = RecordingsLoadPolicy.viewAt(loaded, attempt, nowMs)
    LaunchedEffect(view) {
        if (view is RecordingsView.Loading) {
            delay((view.deadlineAtMs - TraktPlatformClock.nowEpochMs()).coerceAtLeast(0L) + 50L)
            nowMs = TraktPlatformClock.nowEpochMs()
        }
    }

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> page.sections.values.flatten().firstOrNull { it.id == id } }
    // A row that is gone (cancelled, deleted) closes its own sheet.
    LaunchedEffect(selectedId, selected, loaded) {
        if (loaded && selectedId != null && selected == null) selectedId = null
    }

    NuvioScreen {
        stickyHeader { NuvioScreenHeader(title = stringResource(Res.string.recordings_title), onBack = onBack) }
        when (view) {
            is RecordingsView.Loading -> item { LoadingBlock() }
            is RecordingsView.Failed -> item { FailedBlock(timedOut = view.timedOut, onRetry = controller::load) }
            RecordingsView.Ready -> if (page.isEmpty) {
                item { EmptyBlock() }
            } else {
                RecordingsPagePolicy.ORDER.forEach { section ->
                    val rows = page.rows(section)
                    if (rows.isNotEmpty()) {
                        item(key = "h:$section") { SectionTitle(sectionTitle(section)) }
                        items(rows, key = { it.id }) { row ->
                            RecordingRowCard(
                                row = row,
                                canPlay = (row.recordingId?.let { playable.containsKey(it) } == true),
                                onOpen = { selectedId = row.id },
                                onPlay = { controller.playbackFor(row)?.let(onPlay) },
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { row ->
        RecordingDetailsSheet(
            row = row,
            fileAvailable = (row.recordingId?.let { playable.containsKey(it) } == true),
            onDismiss = { selectedId = null },
            onAction = { action ->
                when (action) {
                    RecordingAction.PLAY -> controller.playbackFor(row)?.let { selectedId = null; onPlay(it) }
                    RecordingAction.CANCEL -> { controller.cancel(row); selectedId = null }
                    RecordingAction.RETRY -> { controller.retry(row); selectedId = null }
                    RecordingAction.DELETE -> { controller.delete(row); selectedId = null }
                }
            },
        )
    }
}

private fun sectionTitle(section: RecordingSection): StringResource = when (section) {
    RecordingSection.SCHEDULED -> Res.string.recordings_section_scheduled
    RecordingSection.RECORDING -> Res.string.recordings_section_recording
    RecordingSection.DONE -> Res.string.recordings_section_done
    RecordingSection.FAILED -> Res.string.recordings_section_failed
}

@Composable
private fun SectionTitle(res: StringResource) {
    Text(
        text = stringResource(res),
        modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s8),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RecordingRowCard(
    row: RecordingRow,
    canPlay: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6)
            .clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                    Text(
                        text = recordingHeadline(row),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = recordingWhen(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = recordingStatusLine(row),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.status == RecordingStatus.FAILED) colors.danger else colors.textMuted,
                    )
                }
                if (row.status == RecordingStatus.DONE && canPlay) {
                    IconButton(onClick = onPlay) {
                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_play))
                    }
                }
            }
            if (row.status == RecordingStatus.RECORDING) {
                // The total is unknown (the provider's file length is not promised), so the bar is indeterminate.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s40),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        NuvioLoadingIndicator()
        Text(
            text = stringResource(Res.string.recordings_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
    }
}

@Composable
private fun FailedBlock(timedOut: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s24, vertical = NuvioTokens.Space.s32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
    ) {
        Text(
            text = stringResource(if (timedOut) Res.string.recordings_load_timed_out else Res.string.recordings_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        NuvioPrimaryButton(text = stringResource(Res.string.action_retry), onClick = onRetry)
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s24, vertical = NuvioTokens.Space.s40),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Text(
            text = stringResource(Res.string.recordings_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.recordings_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s40), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textMuted, textAlign = TextAlign.Center)
    }
}
