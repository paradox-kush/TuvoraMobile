package com.nuvio.app.features.iptv

/**
 * How opening an M3U replay ends. The open reads the channel's stored line and catch-up from the content
 * database; like every other IPTV load it runs under [BoundedLoad] (the live-resolve deadline, 20 s) and
 * ends in one of three outcomes, so the guide shows a replay, a "no recording" notice, or a failure with
 * Retry, and never waits forever. A failure is never "unavailable".
 */
sealed interface M3uReplayOpen {
    data class Ready(val request: M3uReplayWalk.Request) : M3uReplayOpen

    /** This channel has no catch-up to build a link from (or the playlist is gone). */
    data object Unavailable : M3uReplayOpen

    /** The open threw or hit its deadline ([timedOut]); the viewer can retry. */
    data class Failed(val timedOut: Boolean) : M3uReplayOpen

    companion object {
        suspend fun run(sourceType: String, build: suspend () -> M3uReplayWalk.Request?): M3uReplayOpen =
            when (
                val outcome = BoundedLoad.run<M3uReplayWalk.Request?>(
                    surface = LoadSurface.LIVE_RESOLVE,
                    isEmpty = { it == null },
                    report = mapOf("source_type" to sourceType, "kind" to "catchup"),
                    work = build,
                )
            ) {
                is LoadOutcome.Loaded -> outcome.value?.let(::Ready) ?: Unavailable
                is LoadOutcome.Empty -> Unavailable
                is LoadOutcome.Failed -> Failed(outcome.timedOut)
            }
    }
}

/**
 * Fences the results of replay opens. An open runs detached under [BoundedLoad], so a slow one can finish
 * after its deadline, after Retry, or after the viewer chose another programme. Each open takes a [next]
 * ticket; only the latest one is [isCurrent], and the screen checks it before publishing anything (and
 * hands it to [M3uReplayWalk.begin] as `admit`). Invalidate on exit with another [next].
 */
class ReplayOpenFence {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var generation = 0L

    fun next(): Long = kotlinx.atomicfu.locks.synchronized(lock) { ++generation }

    fun isCurrent(ticket: Long): Boolean = kotlinx.atomicfu.locks.synchronized(lock) { ticket == generation }
}
