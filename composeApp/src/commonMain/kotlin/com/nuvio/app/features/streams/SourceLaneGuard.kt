package com.nuvio.app.features.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** A provider lane ran past [SourceLaneGuard.LANE_DEADLINE_MS] and was ended with a Failed outcome. */
internal class SourceLaneTimeoutException(val deadlineMs: Long) :
    Exception("Source did not answer within ${deadlineMs / 1000}s")

/** A provider threw a CancellationException of its own (stale scope, epoch change, inner timeout) while the list was still wanted. */
internal class SourceLaneInterruptedException(cause: Throwable) :
    Exception(cause.message ?: "Source request was interrupted", cause)

/**
 * One provider's lane in a title's merged source list (add-on, plugin scraper, media server, IPTV
 * playlist). Every lane MUST end in a published group — Loaded, Empty or Failed — because the
 * aggregators count completions (`repeat(totalTasks) { receive() }`) and settle the whole list
 * only when every lane has reported.
 *
 * Before this guard a lane could end without reporting: a provider that threw a
 * [CancellationException] that was not the caller leaving (`fetchAddonResponseText`'s "Source
 * choices changed", a stale-generation "Server scope changed", an inner `withTimeout`) was
 * re-thrown by `runCatchingUnlessCancelled`, which ended only that child quietly — the completion
 * was never sent, the collector waited forever and the whole list stayed on its spinner (B226,
 * B153/B154 family). And a provider that never answered held the list open with no deadline.
 *
 * Rule: only the CALLER's cancellation propagates; everything else becomes a failure value.
 */
internal object SourceLaneGuard {
    /** Matches the 60 s whole-call HTTP limit; legitimately slow media-server lookups (15-20 s) fit inside it. */
    const val LANE_DEADLINE_MS = 60_000L

    suspend fun <T> run(deadlineMs: Long = LANE_DEADLINE_MS, block: suspend () -> T): Result<T> {
        val caller = currentCoroutineContext()
        return try {
            Result.success(withTimeout(deadlineMs) { block() })
        } catch (error: TimeoutCancellationException) {
            caller.ensureActive()
            Result.failure(SourceLaneTimeoutException(deadlineMs))
        } catch (error: CancellationException) {
            caller.ensureActive() // the caller left: propagate
            Result.failure(SourceLaneInterruptedException(error))
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
