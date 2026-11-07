package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagEntry

/**
 * The on-device rolling log: the last [capacity] sanitized entries, one owner, one lock. Snapshots copy under the lock
 * (Kotlin/Native turns an unguarded shared list into a SIGSEGV). [tail] is the crash-handler read: it tries the lock
 * with a short timeout and returns null rather than wait behind a thread that may be the one that is crashing.
 */
internal class FlightRecorder(private val capacity: Int = 200) {
    private val lock = TelemetryLock()
    private val ring = ArrayDeque<DiagEntry>(capacity)
    private var dirty = false
    private var lastPersistAtMs = Long.MIN_VALUE

    /** Sanitizes then appends. Entries that fail the allow-list are dropped. */
    fun record(entry: DiagEntry) {
        val clean = DiagTrailPolicy.sanitize(entry) ?: return
        lock.withLock {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(clean)
            dirty = true
        }
    }

    fun snapshot(): List<DiagEntry> = lock.withLock { ring.toList() }

    /** Newest [n] entries, or null if the lock could not be had inside [timeoutMs]. For the crash path only. */
    fun tail(n: Int, timeoutMs: Long = 50L): List<DiagEntry>? =
        lock.tryWithLock(timeoutMs) { ring.toList().takeLast(n) }

    /**
     * True when the ring changed since the last persist and at least [minIntervalMs] passed (or [force], on every
     * error). Marks it persisted. Delta-shaped: an idle app writes nothing.
     */
    fun shouldPersist(nowMs: Long, minIntervalMs: Long = 30_000L, force: Boolean = false): Boolean = lock.withLock {
        if (!dirty) return@withLock false
        if (!force && lastPersistAtMs != Long.MIN_VALUE && nowMs - lastPersistAtMs < minIntervalMs) return@withLock false
        dirty = false
        lastPersistAtMs = nowMs
        true
    }

    /** Re-arms the dirty flag after a failed write so the next tick retries. */
    fun markDirty() = lock.withLock { dirty = true }

    fun clear() = lock.withLock { ring.clear(); dirty = false }
}
