package com.nuvio.app.core.telemetry.internal

import kotlinx.atomicfu.atomic

/**
 * Process-wide holder for the live [TelemetryService]. The platform entry point calls [start] FIRST in app start,
 * before DI, so the crash handler it installs can write a record for a crash that happens during startup.
 */
internal object TelemetryRuntime {
    private val current = atomic<TelemetryService?>(null)

    val service: TelemetryService? get() = current.value

    /** Idempotent: a second call returns the running service. */
    fun start(host: TelemetryHost): TelemetryService {
        current.value?.let { return it }
        val created = TelemetryService(host)
        if (!current.compareAndSet(null, created)) return current.value!!
        created.start()
        return created
    }

    /** The crash hook body. No coroutines, no DI, never throws. */
    fun onUncaught(threadName: String, error: Throwable) {
        try { current.value?.handleCrash(threadName, error) } catch (_: Throwable) { }
    }

    internal fun resetForTest() { current.value = null }
}
