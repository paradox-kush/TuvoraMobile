package com.nuvio.app.features.iptv

import com.nuvio.app.core.analytics.AnalyticsSink
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * THE loading rule for IPTV and Live TV (repo-root CLAUDE.md, "every loading state has a deadline and a
 * terminal outcome").
 *
 * Why it exists: "Live TV spins forever" was fixed three times, one path at a time — B76 (Stalker lineup on
 * iOS), 10-08 (direct-source autoplay never settling), 10-09 (a channel that failed to open reconnecting
 * forever) — and came back a fourth time as a hub skeleton waiting on a provider that trickled bytes. Each
 * fix closed one path; the cause stayed: any loading flag could be switched on with nothing guaranteeing it
 * ever switched off, and a failure was often read as "empty".
 *
 * The invariants, all enforced here (and by ArchitectureTest R7, which forbids entering a loading state
 * anywhere else in features/iptv and features/livetv):
 *  1. Every load ends: [LoadStatus.Loaded], [LoadStatus.Empty] or [LoadStatus.Failed]. A failure is never Empty.
 *  2. A load that makes no progress for its surface's [LoadSurface.stallMs] ends as Failed(timedOut) — a stall
 *     deadline, not a total one, so a big but healthy playlist import keeps going while it reports progress.
 *  3. [LoadStatus.Loading] carries its own deadline, so the UI ends the wait at it even if the work never
 *     returns (a blocking call that ignores cancellation) — see [effectiveAt].
 *  4. Every page-level outcome is reported (`iptv_load`), so a regression shows up in PostHog within a day.
 */
enum class LoadSurface(val key: String, val stallMs: Long, val reportsEveryOutcome: Boolean) {
    /** The IPTV page's category list (for M3U/Stalker this is the playlist import itself). */
    HUB_CATEGORIES("hub_categories", 25_000L, reportsEveryOutcome = true),
    /** One category row on the IPTV page. Only failures are reported (a visit loads dozens). */
    HUB_ROW("hub_row", 20_000L, reportsEveryOutcome = false),
    /** The docked Live TV screen resolving a channel's stream URL (Stalker create_link etc.). Every zap
     *  resolves, so only failures are reported; a started channel is already `playback_started`. */
    LIVE_RESOLVE("live_resolve", 20_000L, reportsEveryOutcome = false),
    /** The IPTV hub's Movies/Series search inside one playlist. Every query would report, so only failures do. */
    HUB_SEARCH("hub_search", 20_000L, reportsEveryOutcome = false),
    /** IPTV settings pages: hidden items, setup codes, content categories. */
    SETTINGS("settings", 25_000L, reportsEveryOutcome = false),
}

sealed interface LoadStatus {
    data object Idle : LoadStatus

    /** In flight. Shown as failed at [deadlineAtMs] whatever the work does ([effectiveAt]). Built only by [BoundedLoad]. */
    class Loading internal constructor(val startedAtMs: Long, val deadlineAtMs: Long) : LoadStatus {
        override fun equals(other: Any?): Boolean =
            other is Loading && other.startedAtMs == startedAtMs && other.deadlineAtMs == deadlineAtMs
        override fun hashCode(): Int = 31 * startedAtMs.hashCode() + deadlineAtMs.hashCode()
        override fun toString(): String = "Loading(startedAtMs=$startedAtMs, deadlineAtMs=$deadlineAtMs)"
    }

    data object Loaded : LoadStatus
    data object Empty : LoadStatus

    /** [timedOut] = the deadline passed (stalled provider), as opposed to a request that failed outright. */
    data class Failed(val timedOut: Boolean) : LoadStatus
}

sealed interface LoadOutcome<out T> {
    val status: LoadStatus

    data class Loaded<T>(val value: T) : LoadOutcome<T> {
        override val status: LoadStatus get() = LoadStatus.Loaded
    }

    data class Empty<T>(val value: T) : LoadOutcome<T> {
        override val status: LoadStatus get() = LoadStatus.Empty
    }

    data class Failed(val error: Throwable?, val timedOut: Boolean) : LoadOutcome<Nothing> {
        override val status: LoadStatus get() = LoadStatus.Failed(timedOut)
    }

    fun valueOrNull(): T? = when (this) {
        is Loaded -> value
        is Empty -> value
        is Failed -> null
    }
}

/** A load that hit its stall deadline. Classified like any unreachable provider (IptvLoadFailurePolicy). */
class IptvLoadTimeoutException(surface: LoadSurface, stallMs: Long) :
    RuntimeException("timed out after ${stallMs / 1000}s without progress (${surface.key})")

object BoundedLoad {
    /** Test seam: shortens every stall deadline so a test can watch a load end without waiting 25 s. */
    internal var stallOverrideMsForTests: Long? = null

    /** Test seam: the clock deadlines are measured on. */
    internal var clock: () -> Long = { TraktPlatformClock.nowEpochMs() }

    private val reportedFailures = mutableMapOf<LoadSurface, Int>()
    private const val MAX_FAILURE_REPORTS_PER_SURFACE = 10

    internal fun resetReportsForTests() = reportedFailures.clear()

    fun stallMs(surface: LoadSurface): Long = stallOverrideMsForTests ?: surface.stallMs

    /** The status to publish when a load starts. */
    fun begin(surface: LoadSurface, nowMs: Long = clock()): LoadStatus.Loading =
        LoadStatus.Loading(startedAtMs = nowMs, deadlineAtMs = nowMs + stallMs(surface))

    /** Progress pushes a Loading status's deadline out; any other status is returned unchanged. */
    fun progressed(status: LoadStatus, surface: LoadSurface, nowMs: Long = clock()): LoadStatus =
        if (status is LoadStatus.Loading) LoadStatus.Loading(status.startedAtMs, nowMs + stallMs(surface)) else status

    /** What the UI shows at [nowMs]: a Loading past its deadline is Failed(timedOut), whatever the work is doing. */
    fun effectiveAt(status: LoadStatus, nowMs: Long): LoadStatus =
        if (status is LoadStatus.Loading && nowMs >= status.deadlineAtMs) LoadStatus.Failed(timedOut = true) else status

    /**
     * Runs [work] under [surface]'s stall deadline. Each emission of [progress] restarts the deadline and is
     * passed to [onProgress] (publish the extended [LoadStatus.Loading] from there). On a timeout the work is
     * cancelled unless [cancelOnTimeout] is false — a playlist import keeps running in the background and the
     * caller picks its result up when it lands.
     */
    suspend fun <T> run(
        surface: LoadSurface,
        progress: Flow<*>? = null,
        onProgress: ((LoadStatus.Loading) -> Unit)? = null,
        isEmpty: (T) -> Boolean = { false },
        cancelOnTimeout: Boolean = true,
        report: Map<String, Any> = emptyMap(),
        work: suspend () -> T,
    ): LoadOutcome<T> {
        val stall = stallMs(surface)
        val startedAt = clock()
        var progressTicks = 0L
        var timedOut = false
        // The work runs on the CALLER's dispatcher (same thread semantics as calling it directly, and a test
        // scheduler still drives it) but is NOT the caller's child: the deadline returns without waiting for
        // it, even when the work is a blocking call that ignores cancellation (it is cancelled, not awaited).
        val detached = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val pending = detached.async { runCatching { work() } }
        val outcome: LoadOutcome<T> = try {
            coroutineScope {
                val watcher = progress?.let { flow ->
                    launch {
                        flow.collect {
                            progressTicks++
                            onProgress?.invoke(LoadStatus.Loading(startedAt, clock() + stall))
                        }
                    }
                }
                // Windows of [stall] on the coroutine timer (virtual under a test scheduler, never the wall
                // clock): a window with no answer and no progress ends the load. Worst case a stall is caught
                // within two windows of the last progress.
                var settled: LoadOutcome<T>? = null
                while (settled == null) {
                    val seen = progressTicks
                    val result = withTimeoutOrNull(stall) { pending.await() }
                    if (result == null) {
                        if (progressTicks != seen) continue
                        timedOut = true
                        settled = LoadOutcome.Failed(IptvLoadTimeoutException(surface, stall), timedOut = true)
                        break
                    }
                    settled = result.fold(
                        onSuccess = { value -> if (isEmpty(value)) LoadOutcome.Empty(value) else LoadOutcome.Loaded(value) },
                        // The work runs in its own scope, so even a CancellationException from it (its own
                        // inner timeout) is the work failing, never this caller being cancelled.
                        onFailure = { error -> LoadOutcome.Failed(error, timedOut = false) },
                    )
                }
                watcher?.cancel()
                checkNotNull(settled)
            }
        } finally {
            // Caller gone (switched playlist, left the screen): nothing will read the work, so stop it.
            // Deadline passed: stop it too, unless it is an import the caller will pick up when it lands.
            if (!pending.isCompleted && (cancelOnTimeout || !timedOut)) pending.cancel()
        }
        reportOutcome(surface, outcome, clock() - startedAt, report)
        return outcome
    }

    private fun reportOutcome(surface: LoadSurface, outcome: LoadOutcome<*>, durationMs: Long, extra: Map<String, Any>) {
        val failed = outcome is LoadOutcome.Failed
        if (!surface.reportsEveryOutcome) {
            if (!failed) return
            val sent = reportedFailures[surface] ?: 0
            if (sent >= MAX_FAILURE_REPORTS_PER_SURFACE) return
            reportedFailures[surface] = sent + 1
        }
        val label = when (outcome) {
            is LoadOutcome.Loaded -> "loaded"
            is LoadOutcome.Empty -> "empty"
            is LoadOutcome.Failed -> if (outcome.timedOut) "timeout" else "failed"
        }
        val properties = buildMap<String, Any> {
            put("surface", surface.key)
            put("outcome", label)
            put("duration_ms", durationMs)
            // The exception TYPE only: messages can carry hosts or credentials.
            (outcome as? LoadOutcome.Failed)?.error?.let { put("error_type", it::class.simpleName ?: "unknown") }
            putAll(extra)
        }
        runCatching { AnalyticsSink.capture("iptv_load", properties) }
    }
}

/**
 * Rows arriving for a playlist import (an M3U download, a Stalker lineup). Each tick restarts the import's
 * stall deadline, so a big but healthy import is never cut off; [completed] tells the IPTV page that an
 * import it gave up waiting on has landed, so it shows the result without the viewer doing anything.
 */
object IptvImportProgress {
    private val ticks = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _completed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    val completed: kotlinx.coroutines.flow.SharedFlow<String> = _completed

    /** Cheap and thread-safe; call it per chunk (or every few thousand lines), never per byte. */
    fun tick(accountId: String) {
        ticks.update { it + (accountId to ((it[accountId] ?: 0L) + 1L)) }
    }

    fun finished(accountId: String) {
        _completed.tryEmit(accountId)
    }

    /** Emits once per tick for [accountId] after subscription (the current count is skipped). */
    fun of(accountId: String): Flow<Long> =
        ticks.map { it[accountId] ?: 0L }.distinctUntilChanged().drop(1)
}
