package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the screen renders. BoundedLoad semantics: every load ends Loaded / Empty / Failed, and Failed is never Empty. */
internal sealed interface GameDetailUiState {
    /** In flight; ends at [deadlineAtMs] whatever the work does (the controller enforces it). */
    data class Loading(val startedAtMs: Long, val deadlineAtMs: Long) : GameDetailUiState
    data class Loaded(val detail: GameDetail, val watch: WatchUi, val staleLabel: String?) : GameDetailUiState
    data class Failed(val timedOut: Boolean) : GameDetailUiState
    /** No data at all -> the caller falls back to today's match sheet. */
    data object Empty : GameDetailUiState
}

/**
 * The game screen's state holder (thin composables render [state]; no I/O in the UI). Polling is driven ONLY by
 * [GameDetailRefreshPolicy] and runs only inside [run], which the screen starts under
 * `repeatOnLifecycle(RESUMED)` — so it is lifecycle-bound by construction and cancel-safe.
 *
 * A failed first load waits for [retry] (an event), never a timer. A failed refresh after a good load keeps the last
 * good screen (the 'Updated Ns ago' chip tells the truth) and backs off.
 */
internal class GameDetailController(
    private val ref: GameRef,
    private val source: GameDetailSource,
    private val watch: GameWatchPort,
    private val config: () -> GameDetailRefreshPolicy.Config,
    private val nowMs: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val loadDeadlineMs: Long = 20_000L,
) {
    private val _state = MutableStateFlow<GameDetailUiState>(GameDetailUiState.Loading(nowMs(), nowMs() + loadDeadlineMs))
    val state: StateFlow<GameDetailUiState> = _state.asStateFlow()

    private var lastEventMs: Long? = null
    private var lastSummaryMs: Long? = null
    private var failures = 0
    private var lookup: WatchLookup? = null
    private var lookupDone = false
    private var retryRequested = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** First load (+ the watch lookup). Tests and one-shot callers; [run] does the lookup off the critical path. */
    suspend fun start() {
        loadFirst()
        ensureLookup()
    }

    fun retry() {
        retryRequested = true
        wake.trySend(Unit)
    }

    suspend fun retryWatch() {
        lookupDone = false
        lookup = null
        ensureLookup()
    }

    fun refreshWatch() {
        val s = _state.value as? GameDetailUiState.Loaded ?: return
        _state.value = s.copy(watch = watchUi(s.detail))
    }

    fun refreshChip() {
        val s = _state.value as? GameDetailUiState.Loaded ?: return
        _state.value = s.copy(staleLabel = chip(s.detail))
    }

    /** Runs until cancelled. Call inside `repeatOnLifecycle(RESUMED)`. */
    suspend fun run() = coroutineScope {
        launch { watch.nowWatching.collect { refreshWatch() } }
        if (_state.value is GameDetailUiState.Loading) loadFirst()
        while (true) {
            if (!lookupDone && _state.value is GameDetailUiState.Loaded) {
                lookupDone = true
                launch { lookup = safeLookup(); refreshWatch() }
            }
            val wait = step()
            if (wait == null) wake.receive() else sleep(wait)
        }
    }

    /** One decision: fetch what the policy says is due. Returns ms until the next look, or null when idle (nothing to do until [retry]). */
    suspend fun step(): Long? {
        val s = _state.value
        if (s is GameDetailUiState.Failed || s is GameDetailUiState.Empty) {
            if (!retryRequested) return null
            retryRequested = false
            _state.value = GameDetailUiState.Loading(nowMs(), nowMs() + loadDeadlineMs)
            loadFirst()
            return planWait()
        }
        if (s is GameDetailUiState.Loading) {
            loadFirst()
            return planWait()
        }
        val loaded = s as GameDetailUiState.Loaded
        val plan = plan(loaded.detail)
        if (plan.fetchEvent || plan.fetchSummary) {
            val t = nowMs()
            if (plan.fetchEvent) lastEventMs = t
            if (plan.fetchSummary) lastSummaryMs = t
            val r = withTimeoutOrNull(loadDeadlineMs) { source.tick(plan.fetchEvent, plan.fetchSummary) }
            if (r is DetailLoad.Loaded) {
                failures = 0
                publish(r.detail)
            } else {
                failures++
            }
        }
        refreshChip()
        return planWait()
    }

    // ---- internals ---------------------------------------------------------------------------------------

    private suspend fun loadFirst() {
        val r = withTimeoutOrNull(loadDeadlineMs) { source.tick(true, true) } ?: DetailLoad.Failed(timedOut = true)
        when (r) {
            is DetailLoad.Loaded -> {
                failures = 0
                lastEventMs = nowMs(); lastSummaryMs = nowMs()
                publish(r.detail)
            }
            DetailLoad.Empty -> _state.value = GameDetailUiState.Empty
            is DetailLoad.Failed -> _state.value = GameDetailUiState.Failed(r.timedOut)
        }
    }

    private fun publish(detail: GameDetail) {
        _state.value = GameDetailUiState.Loaded(detail, watchUi(detail), chip(detail))
    }

    private suspend fun ensureLookup() {
        if (lookupDone) return
        if ((_state.value as? GameDetailUiState.Loaded) == null) return
        lookupDone = true
        lookup = safeLookup()
        refreshWatch()
    }

    private suspend fun safeLookup(): WatchLookup = try {
        watch.lookup(ref)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        WatchLookup.Failed
    }

    private fun watchUi(d: GameDetail): WatchUi = WatchBlockPolicy.decide(watch.nowWatching.value, lookup, d.state)

    private fun chip(d: GameDetail): String? =
        if (d.state == GameState.LIVE) GameDetailUiPolicy.staleLabel(d.asOf, nowMs()) else null

    private fun plan(d: GameDetail) =
        GameDetailRefreshPolicy.plan(d.state, ref.startsAtMs, nowMs(), lastEventMs, lastSummaryMs, failures, config())

    /** Wake at least every 5 s while a live chip needs to stay honest. */
    private fun planWait(): Long? {
        val s = _state.value as? GameDetailUiState.Loaded ?: return null
        val next = plan(s.detail).nextCheckInMs ?: return null
        return next.coerceIn(1L, CHIP_TICK_MS)
    }

    private companion object {
        const val CHIP_TICK_MS = 5_000L
    }
}
