package com.tuvora.tvos.player

import kotlinx.coroutines.*

/** A manual retry resolves once; duplicate taps coalesce and disposal cancels the result. */
internal class TvVodSourceRetry<T : Any>(private val scope: CoroutineScope,
    private val resolve: suspend () -> T?, private val reopen: (T, Long, Boolean) -> Unit,
    private val failed: () -> Unit) {
    private var job: Job? = null
    val isResolving: Boolean get() = job?.isActive == true
    fun retry(positionMs: Long, paused: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val fresh = try { withTimeoutOrNull(120_000) { resolve() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            ensureActive()
            if (fresh == null) failed() else reopen(fresh, positionMs.coerceAtLeast(0), paused)
        }
    }
    fun cancel() { job?.cancel(); job = null }
}
