package com.nuvio.app.features.recordings.internal.ui

import androidx.compose.runtime.Composable
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.features.livetv.liveClockLabel
import com.nuvio.app.features.livetv.liveDayLabel
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.RecordingRow
import com.nuvio.app.features.recordings.api.RecordingStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.recordings_problem_cancelled
import nuvio.composeapp.generated.resources.recordings_problem_live_pending
import nuvio.composeapp.generated.resources.recordings_problem_missed
import nuvio.composeapp.generated.resources.recordings_problem_network
import nuvio.composeapp.generated.resources.recordings_problem_no_catch_up
import nuvio.composeapp.generated.resources.recordings_problem_no_space
import nuvio.composeapp.generated.resources.recordings_problem_provider_refused
import nuvio.composeapp.generated.resources.recordings_problem_stalled
import nuvio.composeapp.generated.resources.recordings_problem_unknown
import nuvio.composeapp.generated.resources.recordings_problem_unsupported_format
import nuvio.composeapp.generated.resources.recordings_status_cancelled
import nuvio.composeapp.generated.resources.recordings_status_done
import nuvio.composeapp.generated.resources.recordings_status_failed
import nuvio.composeapp.generated.resources.recordings_status_recording
import nuvio.composeapp.generated.resources.recordings_status_retrying
import nuvio.composeapp.generated.resources.recordings_status_scheduled
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The plain-words explanation of why a recording did not happen. Exhaustive: a new problem fails the build here. */
internal fun recordingProblemRes(problem: RecordingProblem?): StringResource = when (problem) {
    RecordingProblem.NO_SPACE -> Res.string.recordings_problem_no_space
    RecordingProblem.NO_CATCH_UP -> Res.string.recordings_problem_no_catch_up
    RecordingProblem.PROVIDER_REFUSED -> Res.string.recordings_problem_provider_refused
    RecordingProblem.NETWORK -> Res.string.recordings_problem_network
    RecordingProblem.STALLED -> Res.string.recordings_problem_stalled
    RecordingProblem.MISSED -> Res.string.recordings_problem_missed
    RecordingProblem.CANCELLED_BY_USER -> Res.string.recordings_problem_cancelled
    RecordingProblem.UNSUPPORTED_FORMAT -> Res.string.recordings_problem_unsupported_format
    RecordingProblem.LIVE_CAPTURE_PENDING -> Res.string.recordings_problem_live_pending
    RecordingProblem.UNKNOWN, null -> Res.string.recordings_problem_unknown
}

/** "News · S01E02", or just the title. */
internal fun recordingHeadline(row: RecordingRow): String =
    if (row.episode.isNullOrBlank()) row.title else "${row.title} · ${row.episode}"

/** "Channel · Today 21:00–22:00". */
internal fun recordingWhen(row: RecordingRow): String =
    "${row.channelName} · ${liveDayLabel(row.programmeStartMs)} ${liveClockLabel(row.programmeStartMs)}–${liveClockLabel(row.programmeEndMs)}"

@Composable
internal fun recordingStatusLine(row: RecordingRow): String = when (row.status) {
    RecordingStatus.SCHEDULED ->
        stringResource(if (row.retryPending) Res.string.recordings_status_retrying else Res.string.recordings_status_scheduled)
    RecordingStatus.RECORDING -> stringResource(Res.string.recordings_status_recording, formatRecordingBytes(row.sizeBytes))
    RecordingStatus.DONE -> stringResource(Res.string.recordings_status_done, formatRecordingBytes(row.sizeBytes))
    RecordingStatus.FAILED -> stringResource(Res.string.recordings_status_failed)
    RecordingStatus.CANCELLED -> stringResource(Res.string.recordings_status_cancelled)
}

internal fun formatRecordingBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
