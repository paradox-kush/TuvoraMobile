package com.nuvio.app.features.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_source_timed_out
import org.jetbrains.compose.resources.getString

/** A provider lane ran past its deadline and was ended with a Failed outcome. */
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
 * What it fixes: `runCatchingUnlessCancelled` re-throws every [CancellationException]. Add-on,
 * IPTV-match and plugin lanes use it, and their providers throw cancellations that are not the
 * caller leaving (`fetchAddonResponseText`'s "Source choices changed", an inner `withTimeout`).
 * Re-thrown, the child ended quietly, the completion was never sent and the collector waited
 * forever (B226 family). The media-server lane ([com.nuvio.app.features.servers.ServerStreamSource.load])
 * already turned a foreign cancellation into a Failed group, so for servers this adds only the deadline.
 *
 * Rule: only the CALLER's cancellation propagates; everything else becomes a failure value.
 * A [deadlineMs] of null bounds nothing and only converts foreign cancellation — used by plugin
 * scrapers, whose runtime owns a 60 s budget that starts after its TMDB lookup, its 10-slot queue
 * and the search-pause wait, none of which a lane deadline may cut.
 */
internal object SourceLaneGuard {
    /** Matches the 60 s whole-call HTTP limit; legitimately slow media-server lookups (15-20 s) fit inside it. */
    const val LANE_DEADLINE_MS = 60_000L

    private class Held<T>(val value: T)

    suspend fun <T> run(deadlineMs: Long? = LANE_DEADLINE_MS, block: suspend () -> T): Result<T> {
        val caller = currentCoroutineContext()
        return try {
            if (deadlineMs == null) {
                Result.success(block())
            } else {
                // withTimeoutOrNull swallows only OUR timeout; an inner TimeoutCancellationException
                // propagates and is reported as the provider's own interruption, not as this deadline.
                val held = withTimeoutOrNull(deadlineMs) { Held(block()) }
                if (held == null) {
                    caller.ensureActive()
                    Result.failure(SourceLaneTimeoutException(deadlineMs))
                } else {
                    Result.success(held.value)
                }
            }
        } catch (error: CancellationException) {
            caller.ensureActive() // the caller left: propagate
            Result.failure(SourceLaneInterruptedException(error))
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}

/** User-facing text for a lane failure: the localized timeout line for a deadline, else the provider's message. */
internal suspend fun Throwable.laneFailureMessage(): String? =
    if (this is SourceLaneTimeoutException) getString(Res.string.streams_source_timed_out) else message
