package com.nuvio.app.core.telemetry.internal

import kotlinx.atomicfu.atomic
import kotlin.time.TimeSource

/**
 * A tiny spin lock for the flight recorder. Critical sections are a few array operations, so spinning is
 * cheap, and unlike a monitor it can be TRIED with a deadline: the crash handler must never block behind a
 * thread that holds the ring (that thread may be the one that crashed).
 */
internal class TelemetryLock {
    private val held = atomic(false)

    inline fun <T> withLock(block: () -> T): T {
        while (!held.compareAndSet(false, true)) { /* spin: sections are microseconds */ }
        try { return block() } finally { held.value = false }
    }

    /** Runs [block] if the lock is obtained within [timeoutMs], else returns null without running it. */
    fun <T : Any> tryWithLock(timeoutMs: Long, block: () -> T): T? {
        val start = TimeSource.Monotonic.markNow()
        while (!held.compareAndSet(false, true)) {
            if (start.elapsedNow().inWholeMilliseconds >= timeoutMs) return null
        }
        try { return block() } finally { held.value = false }
    }
}
