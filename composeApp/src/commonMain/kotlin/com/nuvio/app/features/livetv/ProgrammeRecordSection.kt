package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSheetActionButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.recordings.api.ConfirmationKind
import com.nuvio.app.features.recordings.api.ProgrammeRecordPolicy
import com.nuvio.app.features.recordings.api.RecordOffer
import com.nuvio.app.features.recordings.api.RecordUnavailable
import com.nuvio.app.features.recordings.api.RecordingsPort
import com.nuvio.app.features.recordings.api.ScheduleConfirmation
import com.nuvio.app.features.recordings.api.SeriesOffer
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.recordings_action_allow_exact
import nuvio.composeapp.generated.resources.recordings_action_cancel
import nuvio.composeapp.generated.resources.recordings_action_record
import nuvio.composeapp.generated.resources.recordings_action_record_series
import nuvio.composeapp.generated.resources.recordings_series_guide_failed
import nuvio.composeapp.generated.resources.recordings_action_view
import nuvio.composeapp.generated.resources.recordings_already_saved
import nuvio.composeapp.generated.resources.recordings_confirm_already
import nuvio.composeapp.generated.resources.recordings_confirm_cannot
import nuvio.composeapp.generated.resources.recordings_confirm_clash
import nuvio.composeapp.generated.resources.recordings_confirm_space_tight
import nuvio.composeapp.generated.resources.recordings_confirm_late
import nuvio.composeapp.generated.resources.recordings_confirm_next_open
import nuvio.composeapp.generated.resources.recordings_confirm_series_nothing
import nuvio.composeapp.generated.resources.recordings_confirm_set
import nuvio.composeapp.generated.resources.recordings_prepare_failed
import nuvio.composeapp.generated.resources.recordings_series_active
import nuvio.composeapp.generated.resources.recordings_action_stop_series
import nuvio.composeapp.generated.resources.recordings_unavailable_bad_times
import nuvio.composeapp.generated.resources.recordings_unavailable_needs_catch_up
import nuvio.composeapp.generated.resources.recordings_unavailable_over
import nuvio.composeapp.generated.resources.recordings_working
import nuvio.composeapp.generated.resources.recordings_confirm_series_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Record / Record series / Cancel recording part of the programme sheet. The decisions are
 * [ProgrammeRecordPolicy] and [ScheduleConfirmationPolicy]; the I/O is [ProgrammeRecordingController].
 */
@Composable
internal fun ProgrammeRecordSection(
    channel: LiveGuideChannel,
    programme: XtreamProgram,
    description: String,
    shownProgrammes: List<XtreamProgram>,
    nowMs: Long,
    port: RecordingsPort,
    controller: ProgrammeRecordingController,
    onViewRecordings: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val state by port.state.collectAsStateWithLifecycle()
    val prep by controller.prep.collectAsStateWithLifecycle()
    val work by controller.work.collectAsStateWithLifecycle()
    LaunchedEffect(channel.contentId, programme.startMs) { controller.open(channel) }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        when (val p = prep) {
            RecordPrep.Preparing -> Unit
            RecordPrep.Failed -> Note(stringResource(Res.string.recordings_prepare_failed))
            is RecordPrep.Ready -> {
                val confirmed = work as? RecordWork.Confirmed
                if (confirmed != null) {
                    ConfirmationBlock(confirmed.confirmation, controller, onViewRecordings)
                    return@Column
                }
                if (work == RecordWork.Working) {
                    Note(stringResource(Res.string.recordings_working))
                    return@Column
                }
                if (work == RecordWork.Failed) Note(stringResource(Res.string.recordings_prepare_failed))
                if (work == RecordWork.GuideUnreadable) Note(stringResource(Res.string.recordings_series_guide_failed))
                val offer = ProgrammeRecordPolicy.offer(p.channel, programme.startMs, programme.endMs, nowMs, port.capabilities, state)
                when (offer) {
                    is RecordOffer.Record -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                            NuvioSheetActionButton(
                                icon = Icons.Filled.FiberManualRecord,
                                label = stringResource(Res.string.recordings_action_record),
                                background = colors.danger,
                                contentColor = Color.White,
                                onClick = { controller.record(p.channel, programme, description) },
                                modifier = Modifier.weight(1f),
                            )
                            if (ProgrammeRecordPolicy.seriesOffer(p.channel, programme.title, port.capabilities, state) == SeriesOffer.Record) {
                                NuvioSheetActionButton(
                                    icon = Icons.Filled.Schedule,
                                    label = stringResource(Res.string.recordings_action_record_series),
                                    background = Color.Transparent,
                                    contentColor = colors.textPrimary,
                                    border = colors.borderSubtle,
                                    onClick = { controller.recordSeries(p.channel, programme, shownProgrammes) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        StopSeriesOffer(ProgrammeRecordPolicy.seriesOffer(p.channel, programme.title, port.capabilities, state), controller)
                    }
                    is RecordOffer.Scheduled -> {
                        NuvioSheetActionButton(
                            icon = Icons.Filled.Close,
                            label = stringResource(Res.string.recordings_action_cancel),
                            background = Color.Transparent,
                            contentColor = colors.textPrimary,
                            border = colors.borderSubtle,
                            onClick = { controller.cancel(offer.scheduleId) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StopSeriesOffer(ProgrammeRecordPolicy.seriesOffer(p.channel, programme.title, port.capabilities, state), controller)
                    }
                    RecordOffer.AlreadySaved -> {
                        Note(stringResource(Res.string.recordings_already_saved))
                        ViewButton(onViewRecordings)
                    }
                    is RecordOffer.Unavailable -> Note(
                        stringResource(
                            when (offer.reason) {
                                RecordUnavailable.BAD_TIMES -> Res.string.recordings_unavailable_bad_times
                                RecordUnavailable.NEEDS_CATCH_UP -> Res.string.recordings_unavailable_needs_catch_up
                                RecordUnavailable.PROGRAMME_OVER -> Res.string.recordings_unavailable_over
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationBlock(c: ScheduleConfirmation, controller: ProgrammeRecordingController, onViewRecordings: () -> Unit) {
    Note(
        when (c.kind) {
            ConfirmationKind.SET ->
                if (c.count > 1) pluralStringResource(Res.plurals.recordings_confirm_series_count, c.count, c.count)
                else stringResource(Res.string.recordings_confirm_set)
            ConfirmationKind.MAY_START_LATE -> stringResource(Res.string.recordings_confirm_late)
            ConfirmationKind.SAVES_ON_NEXT_OPEN -> stringResource(Res.string.recordings_confirm_next_open)
            ConfirmationKind.ALREADY_SCHEDULED -> stringResource(Res.string.recordings_confirm_already)
            ConfirmationKind.CANNOT_RECORD -> stringResource(Res.string.recordings_confirm_cannot)
            ConfirmationKind.SERIES_NOTHING_YET -> stringResource(Res.string.recordings_confirm_series_nothing)
        },
    )
    if (c.spaceTight) Note(stringResource(Res.string.recordings_confirm_space_tight), warn = true)
    if (c.clashTitles.isNotEmpty()) {
        Note(stringResource(Res.string.recordings_confirm_clash, c.clashTitles.joinToString(", ")), warn = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        if (c.offerExactAlarmSettings) {
            NuvioSheetActionButton(
                icon = Icons.Filled.Schedule,
                label = stringResource(Res.string.recordings_action_allow_exact),
                background = MaterialTheme.nuvio.colors.accent,
                contentColor = MaterialTheme.nuvio.colors.onAccent,
                onClick = { controller.allowExactAlarms() },
                modifier = Modifier.weight(1f),
            )
        }
        ViewButton(onViewRecordings, Modifier.weight(1f))
    }
}

@Composable
private fun ViewButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    NuvioSheetActionButton(
        icon = Icons.Filled.VideoLibrary,
        label = stringResource(Res.string.recordings_action_view),
        background = Color.Transparent,
        contentColor = colors.textPrimary,
        border = colors.borderSubtle,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun Note(text: String, warn: Boolean = false) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) colors.danger else colors.textSecondary,
    )
}

/** Where a series rule is already on: the note, and the way to turn it off. */
@Composable
private fun StopSeriesOffer(offer: SeriesOffer, controller: ProgrammeRecordingController) {
    if (offer !is SeriesOffer.Active) return
    val colors = MaterialTheme.nuvio.colors
    Note(stringResource(Res.string.recordings_series_active))
    NuvioSheetActionButton(
        icon = Icons.Filled.Close,
        label = stringResource(Res.string.recordings_action_stop_series),
        background = Color.Transparent,
        contentColor = colors.textPrimary,
        border = colors.borderSubtle,
        onClick = { controller.stopSeries(offer.ruleId) },
        modifier = Modifier.fillMaxWidth(),
    )
}
