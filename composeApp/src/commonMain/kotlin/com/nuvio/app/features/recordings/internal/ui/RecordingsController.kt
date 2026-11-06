package com.nuvio.app.features.recordings.internal.ui

import com.nuvio.app.core.contracts.RecordingPlayback
import com.nuvio.app.features.recordings.api.RecordingRow
import com.nuvio.app.features.recordings.api.RecordingsLoadAttempt
import com.nuvio.app.features.recordings.api.RecordingsLoadPolicy
import com.nuvio.app.features.recordings.api.RecordingsPageModel
import com.nuvio.app.features.recordings.api.RecordingsPagePolicy
import com.nuvio.app.features.recordings.api.RecordingsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The Recordings page's state holder: all the I/O the page does goes through here, so the composables only draw.
 * Loading follows the repo's bounded-load rule: the read has a deadline ([RecordingsLoadPolicy.DEADLINE_MS]) and
 * ends as loaded or failed-with-Retry; a store that cannot be read is never shown as "no recordings".
 */
internal class RecordingsController(
    private val port: RecordingsPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onActionFailed: () -> Unit,
) {
    private val _attempt = MutableStateFlow(RecordingsLoadAttempt(startedAtMs = clock()))
    val attempt: StateFlow<RecordingsLoadAttempt> = _attempt
    private var loadJob: Job? = null

    /** The rows by section. Built off the main thread; the byte counter of a running capture ticks it. */
    val page: StateFlow<RecordingsPageModel> = combine(port.state, port.progress) { state, progress ->
        RecordingsPagePolicy.build(state, progress)
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecordingsPagePolicy.build(port.state.value, port.progress.value))

    val loaded: StateFlow<Boolean> = port.state.map { it.loaded }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), port.state.value.loaded)

    /** recording id -> the file URI of every DONE recording whose file is on disk (checked off the main thread). */
    val playableUris: StateFlow<Map<String, String>> = port.state.map { state ->
        buildMap {
            state.recordings.forEach { r -> port.playableUri(r)?.let { put(r.id, it) } }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Reads the persisted state unless it is already read. Safe to call again as Retry. */
    fun load() {
        if (port.state.value.loaded) return
        loadJob?.cancel()
        _attempt.value = RecordingsLoadAttempt(startedAtMs = clock())
        loadJob = scope.launch {
            try {
                withTimeout(RecordingsLoadPolicy.DEADLINE_MS) { port.start(clock()) }
            } catch (e: TimeoutCancellationException) {
                // The screen ends the wait itself at the deadline (RecordingsLoadPolicy.viewAt).
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _attempt.value = _attempt.value.copy(failed = true)
            }
        }
    }

    fun cancel(row: RecordingRow) = act { row.scheduleId?.let { port.cancel(it) } }

    fun delete(row: RecordingRow) = act { row.recordingId?.let { port.deleteRecording(it) } }

    fun retry(row: RecordingRow) = act { row.recordingId?.let { port.retry(it, clock()) } }

    /** What the player needs for [row], or null when its file is gone. */
    fun playbackFor(row: RecordingRow): RecordingPlayback? {
        val id = row.recordingId ?: return null
        val uri = playableUris.value[id] ?: return null
        return RecordingPlayback(
            recordingId = id,
            title = row.title,
            subtitle = row.episode,
            channelName = row.channelName,
            fileUri = uri,
        )
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onActionFailed()
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
