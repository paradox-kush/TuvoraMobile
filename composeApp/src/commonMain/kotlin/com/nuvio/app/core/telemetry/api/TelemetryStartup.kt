package com.nuvio.app.core.telemetry.api

import com.nuvio.app.core.telemetry.internal.TelemetryRuntime

/** Entry points for hosts and composition roots. Everything else goes through [TelemetryPort]. */
object TelemetryStartup {
    /** Publishes the running service as the app's [TelemetryPort]. No-op port stays if the platform never started one. */
    fun registerPort() {
        TelemetryRuntime.service?.let { TelemetryAccess.register(it) }
    }

    /**
     * First-run funnel start: call once per process, early (the logic-feature registration does), before anything
     * creates user state. [otherExistingSignals] answers "does this install already hold a playlist, add-on or watch
     * history?" and is asked only on the first launch of a build that has the funnel.
     */
    fun startFirstRunFunnel(otherExistingSignals: () -> Boolean) {
        TelemetryRuntime.service?.startFirstRunFunnel(otherExistingSignals)
    }

    /** Crash hook body for platform uncaught-exception handlers (iOS `setUnhandledExceptionHook`). Never throws. */
    fun onUncaught(threadName: String, error: Throwable) = TelemetryRuntime.onUncaught(threadName, error)
}
