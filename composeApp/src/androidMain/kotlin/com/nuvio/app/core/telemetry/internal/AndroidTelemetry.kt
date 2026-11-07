package com.nuvio.app.core.telemetry.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

/**
 * Android start-up for Telemetry v2. [start] runs FIRST in `Application.onCreate` (before DI) so a crash during
 * startup still leaves a record. The uncaught-exception handler uses no coroutines, DI or blocking locks, never
 * throws, and always hands the crash on to whatever handler was installed before it (PostHog's, then the system's).
 */
object AndroidTelemetry {
    private val lastHandled = AtomicReference<Throwable?>(null)
    private var started = false

    fun start(app: Application) {
        if (started) return
        started = true
        TelemetryRuntime.start(AndroidTelemetryHost(app))
        installCrashHandler()
        trackForeground(app)
    }

    /**
     * Install (or re-install on top of the SDK's own handler, so ours runs outermost and writes the record
     * before the SDK's best-effort flush). Safe to call more than once: one throwable is recorded once.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (lastHandled.getAndSet(error) !== error) {
                TelemetryRuntime.onUncaught(thread.name, error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** PostHog setup lives with the host so the SDK is named in one place. */
    fun configurePostHog(app: Application, crashReportsEnabled: Boolean) {
        AndroidPostHogSetup.setup(app, crashReportsEnabled)
        // Re-wrap now that the SDK has installed its handler, then register super properties (needs a set-up SDK).
        installCrashHandler()
        TelemetryRuntime.service?.onSdkReady()
    }

    fun setOptIn(enabled: Boolean) = AndroidPostHogSetup.setOptIn(enabled)

    private fun trackForeground(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) TelemetryRuntime.service?.onForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (started > 0 && --started == 0 && !activity.isChangingConfigurations) {
                    TelemetryRuntime.service?.onBackground()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
