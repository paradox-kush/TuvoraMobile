package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagEntry
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The on-device rolling log: the last [capacity] sanitized entries, one owner, one lock. Snapshots copy under the lock
 * (Kotlin/Native turns an unguarded shared list into a SIGSEGV).
 *
 * The crash handler never takes that lock: every write also publishes an immutable copy of the ring, and [tail]
 * reads that copy lock-free, so a thread that crashed while holding the ring cannot block the handler.
 */
internal class FlightRecorder(private val capacity: Int = 200) {
    private val lock = SynchronizedObject()
    private val ring = ArrayDeque<DiagEntry>(capacity)
    private var dirty = false
    private var lastPersistAtMs = Long.MIN_VALUE
    private val published = atomic<List<DiagEntry>>(emptyList())

    /** Sanitizes then appends. Entries that fail the allow-list are dropped. */
    fun record(entry: DiagEntry) {
        val clean = DiagTrailPolicy.sanitize(entry) ?: return
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(clean)
            dirty = true
            published.value = ring.toList()
        }
    }

    fun snapshot(): List<DiagEntry> = synchronized(lock) { ring.toList() }

    /** Newest [n] entries as of the last write. Lock-free: the crash-handler read. */
    fun tail(n: Int): List<DiagEntry> = published.value.takeLast(n)

    /**
     * True when the ring changed since the last persist and at least [minIntervalMs] passed (or [force], on every
     * error). Marks it persisted. Delta-shaped: an idle app writes nothing.
     */
    fun shouldPersist(nowMs: Long, minIntervalMs: Long = 30_000L, force: Boolean = false): Boolean = synchronized(lock) {
        if (!dirty) return@synchronized false
        if (!force && lastPersistAtMs != Long.MIN_VALUE && nowMs - lastPersistAtMs < minIntervalMs) return@synchronized false
        dirty = false
        lastPersistAtMs = nowMs
        true
    }

    /** Re-arms the dirty flag after a failed write so the next tick retries. */
    fun markDirty() = synchronized(lock) { dirty = true }

    fun clear() = synchronized(lock) { ring.clear(); dirty = false; published.value = emptyList() }
}
