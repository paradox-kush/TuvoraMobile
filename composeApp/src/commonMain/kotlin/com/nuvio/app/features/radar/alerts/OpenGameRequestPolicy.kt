package com.nuvio.app.features.radar.alerts

/**
 * A sports alert's "Open game" request to the Sports hub (review round 2): honoured only within [TTL_MS] of the tap,
 * and dropped once the hub's fixture load has FINISHED without the game (not merely "is not loading at this
 * instant", which is also true before a load starts). Pure, so the hub's wait is a loop over observations.
 */
object OpenGameRequestPolicy {
    const val TTL_MS: Long = 15_000L

    fun isFresh(atMs: Long, nowMs: Long): Boolean = nowMs - atMs <= TTL_MS

    fun deadlineMs(atMs: Long): Long = atMs + TTL_MS

    enum class Outcome { Wait, Open, Drop }

    /** Feed it every hub state while the request waits. */
    class Waiter {
        private var sawLoading = false

        fun observe(found: Boolean, loading: Boolean): Outcome {
            if (found) return Outcome.Open
            if (loading) {
                sawLoading = true
                return Outcome.Wait
            }
            return if (sawLoading) Outcome.Drop else Outcome.Wait
        }
    }
}
