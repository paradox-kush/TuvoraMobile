package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.recordings.api.ListingInfo
import com.nuvio.app.features.recordings.api.ProgrammeRecordPolicy
import com.nuvio.app.features.recordings.api.RecordingChannel
import com.nuvio.app.features.recordings.api.RecordingsPlatform
import com.nuvio.app.features.recordings.api.RecordingsPort
import com.nuvio.app.features.recordings.api.ScheduleConfirmation
import com.nuvio.app.features.recordings.api.ScheduleConfirmationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The channel snapshot a programme sheet needs before it can offer Record. Always ends: Ready or Failed. */
internal sealed interface RecordPrep {
    data object Preparing : RecordPrep
    data class Ready(val channel: RecordingChannel) : RecordPrep
    data object Failed : RecordPrep
}

internal sealed interface RecordWork {
    data object Idle : RecordWork
    data object Working : RecordWork
    data class Confirmed(val confirmation: ScheduleConfirmation) : RecordWork
    data object Failed : RecordWork

    /** The stored guide could not be read, so a series would have been scheduled short. Nothing was scheduled. */
    data object GuideUnreadable : RecordWork
}

/**
 * Orchestrates the programme sheet's Record / Record series / Cancel so the sheet only draws. Reads the
 * channel's facts ([LiveTvData.recordingChannel]), asks the port, and picks the truthful confirmation
 * ([ScheduleConfirmationPolicy]).
 */
internal class ProgrammeRecordingController(
    private val port: RecordingsPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** The stored guide read; a seam so a failing read can be tested. */
    private val historyProgrammes: suspend (channelKey: String, fromMs: Long, toMs: Long) -> List<XtreamProgram> = LiveTvData::historyProgrammes,
) {
    private val _prep = MutableStateFlow<RecordPrep>(RecordPrep.Preparing)
    val prep: StateFlow<RecordPrep> = _prep
    private val _work = MutableStateFlow<RecordWork>(RecordWork.Idle)
    val work: StateFlow<RecordWork> = _work
    private var prepJob: Job? = null

    /** A new programme sheet opened for [channel]. */
    fun open(channel: LiveGuideChannel) {
        prepJob?.cancel()
        _work.value = RecordWork.Idle
        _prep.value = RecordPrep.Preparing
        prepJob = scope.launch {
            val snapshot = try {
                withTimeoutOrNull(PREP_DEADLINE_MS) { LiveTvData.recordingChannel(channel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _prep.value = if (snapshot != null) RecordPrep.Ready(snapshot) else RecordPrep.Failed
        }
    }

    fun record(channel: RecordingChannel, programme: XtreamProgram, description: String?) {
        _work.value = RecordWork.Working
        scope.launch {
            _work.value = try {
                val text = description?.takeIf { it.isNotBlank() } ?: programme.description.takeIf { it.isNotBlank() }
                val result = port.scheduleProgramme(
                    channel = channel, title = programme.title,
                    episode = ProgrammeRecordPolicy.episodeLabel(programme.title, text),
                    description = text, startMs = programme.startMs, endMs = programme.endMs, nowMs = clock(),
                )
                RecordWork.Confirmed(ScheduleConfirmationPolicy.forResult(result) { RecordingsPlatform.takeExactAlarmPromptTurn() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecordWork.Failed
            }
        }
    }

    /** Saves the rule and schedules every airing the stored guide already has. [shown] = what the grid has in memory. */
    fun recordSeries(channel: RecordingChannel, programme: XtreamProgram, shown: List<XtreamProgram>) {
        _work.value = RecordWork.Working
        scope.launch {
            _work.value = try {
                val now = clock()
                val days = channel.catchUp?.days ?: 0
                val stored = when (val read = readStoredGuide {
                    historyProgrammes(channel.key, now - days * DAY_MS, now + SERIES_LOOKAHEAD_MS)
                }) {
                    is StoredGuideRead.Ok -> read.programmes
                    // Never "nothing here": saving the rule with fewer airings than the guide has would look like success.
                    StoredGuideRead.Failed -> {
                        _work.value = RecordWork.GuideUnreadable
                        return@launch
                    }
                }
                val created = port.addSeriesRule(
                    channel = channel, title = programme.title, keepLast = 0,
                    listings = (stored + shown + programme).toListings(), nowMs = now,
                )
                RecordWork.Confirmed(
                    ScheduleConfirmationPolicy.forSeries(created, port.capabilities, port.state.value.exactAllowed) {
                        RecordingsPlatform.takeExactAlarmPromptTurn()
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecordWork.Failed
            }
        }
    }

    /** "Stop series recording": drops the rule and its waiting airings; saved and running recordings stay. */
    fun stopSeries(ruleId: String) {
        scope.launch {
            try {
                port.removeSeriesRule(ruleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _work.value = RecordWork.Failed
            }
        }
    }

    fun cancel(scheduleId: String) {
        scope.launch {
            try {
                port.cancel(scheduleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _work.value = RecordWork.Failed
            }
        }
    }

    /** Opens the system screen where exact alarms are allowed. False when this device has none. */
    fun allowExactAlarms(): Boolean = RecordingsPlatform.openExactAlarmSettings()

    /**
     * Series rules pick up airings the guide gained since: local guide reads only, no network, once per Live TV visit.
     * Idempotent in the port, so a repeat adds nothing.
     */
    fun refreshSeriesRules() {
        scope.launch {
            try {
                val now = clock()
                for (rule in port.state.value.series) {
                    // A rule whose guide cannot be read this visit is skipped; the next visit asks again.
                    val stored = (readStoredGuide { historyProgrammes(rule.channel.key, now, now + SERIES_LOOKAHEAD_MS) }
                        as? StoredGuideRead.Ok)?.programmes ?: continue
                    if (stored.isNotEmpty()) port.expandSeries(rule.id, stored.toListings(), now)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the next visit tries again.
            }
        }
    }

    private fun List<XtreamProgram>.toListings(): List<ListingInfo> =
        distinctBy { it.startMs }.map {
            ListingInfo(title = it.title, description = it.description.takeIf { d -> d.isNotBlank() }, startMs = it.startMs, endMs = it.endMs)
        }

    private companion object {
        const val PREP_DEADLINE_MS = 8_000L
        const val DAY_MS = 24L * 60 * 60_000
        const val SERIES_LOOKAHEAD_MS = 7 * DAY_MS
    }
}
