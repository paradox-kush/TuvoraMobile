package com.nuvio.app.features.iptv

import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the "Refresh now" row shows. [result] is the last finished outcome (null until one has finished). */
internal data class ManualRefreshUiState(
    /** Entered only through [BoundedLoad]: Loaded or Failed always ends, and a failure is never "done". */
    val load: LoadStatus = LoadStatus.Idle,
    val result: ManualRefreshResult? = null,
    /** When this playlist's catalog was last rebuilt (null = unknown). Read when the screen opens and after each refresh. */
    val lastRefreshAtMs: Long? = null,
    /** The wall-clock time this state was published; the "Updated N ago" line is measured against it. */
    val publishedAtMs: Long = 0L,
) {
    val refreshing: Boolean get() = load is LoadStatus.Loading
    val age: IptvManualRefreshPolicy.Age? get() = IptvManualRefreshPolicy.age(lastRefreshAtMs, publishedAtMs)
}

/**
 * Screen-scoped state holder for "Refresh now" on one playlist (UX179): the screen only renders it. One refresh at a
 * time; the per-playlist cooldown lives in the engine ([IptvRefreshScheduler.refreshNow]) so every screen shares it.
 */
internal class IptvManualRefreshController(
    private val scope: CoroutineScope,
    private val refresh: suspend (XtreamAccount) -> ManualRefreshResult = { IptvRefreshScheduler.refreshNow(it) },
    private val lastRefreshAt: suspend (XtreamAccount) -> Long? = { IptvRefreshScheduler.lastRefreshAt(it) },
    private val nowMs: () -> Long = { TraktPlatformClock.nowEpochMs() },
) {
    private val mutableState = MutableStateFlow(ManualRefreshUiState())
    val state: StateFlow<ManualRefreshUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Reads the last-refreshed time for the "Updated N ago" line (a failed read just leaves it unknown). */
    fun load(account: XtreamAccount) {
        scope.launch {
            val at = readLastRefresh(account)
            val current = mutableState.value
            // A refresh that started (or finished) while this read was in flight already carries the newer time.
            if (current.refreshing || current.result != null) return@launch
            mutableState.value = current.copy(lastRefreshAtMs = at, publishedAtMs = nowMs())
        }
    }

    /** A failed read leaves the time unknown; cancellation is control flow and is never swallowed. */
    private suspend fun readLastRefresh(account: XtreamAccount): Long? = try {
        lastRefreshAt(account)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    fun start(account: XtreamAccount) {
        if (mutableState.value.refreshing) return
        val known = mutableState.value.lastRefreshAtMs
        mutableState.value = ManualRefreshUiState(load = BoundedLoad.begin(LoadSurface.PLAYLIST_REFRESH), result = null, lastRefreshAtMs = known, publishedAtMs = nowMs())
        job = scope.launch {
            val outcome = BoundedLoad.run(
                LoadSurface.PLAYLIST_REFRESH,
                progress = IptvImportProgress.of(account.id),
                onProgress = { mutableState.value = mutableState.value.copy(load = it) },
                // The rebuild is real work the viewer asked for: past the stall deadline the screen stops waiting, it keeps going.
                cancelOnTimeout = false,
                report = mapOf("source" to account.sourceType),
            ) { refresh(account) }
            val result = (outcome as? LoadOutcome.Loaded)?.value ?: (outcome as? LoadOutcome.Empty)?.value
            val at = readLastRefresh(account) ?: known
            mutableState.value = when {
                outcome is LoadOutcome.Failed -> ManualRefreshUiState(load = outcome.status, result = ManualRefreshResult.FAILED, lastRefreshAtMs = at, publishedAtMs = nowMs())
                // Only REFRESHED is a success; PARTIAL / FAILED are failures the viewer can retry, never "done".
                result == ManualRefreshResult.PARTIAL || result == ManualRefreshResult.FAILED ->
                    ManualRefreshUiState(load = LoadStatus.Failed(timedOut = false), result = result, lastRefreshAtMs = at, publishedAtMs = nowMs())
                else -> ManualRefreshUiState(load = LoadStatus.Loaded, result = result, lastRefreshAtMs = at, publishedAtMs = nowMs())
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
