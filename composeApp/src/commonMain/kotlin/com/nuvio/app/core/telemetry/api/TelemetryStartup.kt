package com.nuvio.app.core.telemetry.api

import com.nuvio.app.core.telemetry.internal.TelemetryRuntime

/** Entry points for hosts and composition roots. Everything else goes through [TelemetryPort]. */
object TelemetryStartup {
    /** Publishes the running service as the app's [TelemetryPort]. No-op port stays if the platform never started one. */
    fun registerPort() {
        TelemetryRuntime.service?.let { TelemetryAccess.register(it) }
    }

    /** Crash hook body for platform uncaught-exception handlers (iOS `setUnhandledExceptionHook`). Never throws. */
    fun onUncaught(threadName: String, error: Throwable) = TelemetryRuntime.onUncaught(threadName, error)
}
