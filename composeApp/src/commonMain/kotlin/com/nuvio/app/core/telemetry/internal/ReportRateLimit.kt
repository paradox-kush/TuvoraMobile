package com.nuvio.app.core.telemetry.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One "Send report" per [minIntervalMs] (2 min). Only a successful acquire starts the window. */
internal class ReportRateLimit(private val minIntervalMs: Long = 120_000L) {
    private val lock = SynchronizedObject()
    private var lastAtMs: Long? = null

    /** Returns 0 when a report may go now (and starts the window), else the milliseconds left to wait. */
    fun tryAcquire(nowMs: Long): Long = synchronized(lock) {
        val last = lastAtMs
        if (last != null && nowMs >= last && nowMs - last < minIntervalMs) {
            minIntervalMs - (nowMs - last)
        } else {
            lastAtMs = nowMs
            0L
        }
    }

    /** A report that failed to send must not burn the window. */
    fun release() = synchronized(lock) { lastAtMs = null }

    companion object {
        fun secondsLeft(remainingMs: Long): Int = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
    }
}
