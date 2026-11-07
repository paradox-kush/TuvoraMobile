package com.nuvio.app.features.sportsdetail.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One shared, long-lived load per key — for work that is expensive to restart (channel/guide matching, TV listings, EPG probes).
 *
 *  - The work runs in [scope] (the owner's, NOT the caller's): a caller that times out, is cancelled, or leaves the screen only
 *    stops WAITING; the load continues and the next caller joins it or reads its result.
 *  - Single-flight: concurrent callers for a key share one load.
 *  - Bounded: the load ends within [overallDeadlineMs], yielding [onDeadline] (a failure is surfaced, never an empty value).
 *  - A finished result is kept for [ttlMs] when [shouldCache] says so (failures are not cached: the next caller starts fresh).
 *  - A throwing load reaches its callers and never wedges the key.
 */
class SingleFlight<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val ttlMs: Long,
    private val nowMs: () -> Long,
    private val overallDeadlineMs: Long,
    private val onDeadline: V,
    private val shouldCache: (V) -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val inFlight = HashMap<K, Deferred<V>>()
    private val done = HashMap<K, Pair<Long, V>>()

    suspend fun get(key: K, work: suspend () -> V): V {
        val deferred: Deferred<V> = mutex.withLock {
            done[key]?.let { (at, v) -> if (nowMs() - at < ttlMs) return v else done.remove(key) }
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        val v = withTimeoutOrNull(overallDeadlineMs) { work() } ?: onDeadline
                        mutex.withLock { if (shouldCache(v)) done[key] = nowMs() to v }
                        v
                    } finally {
                        mutex.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        // Cancelling or timing out HERE cancels only this wait: the Deferred belongs to [scope].
        return deferred.await()
    }

    /** Forget a key's remembered result (the next [get] reloads). An in-flight load is left alone. */
    suspend fun invalidate(key: K) { mutex.withLock { done.remove(key) } }
}
