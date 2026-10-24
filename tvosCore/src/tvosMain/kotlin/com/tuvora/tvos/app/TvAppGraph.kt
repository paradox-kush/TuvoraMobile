package com.tuvora.tvos.app

import com.nuvio.app.core.analytics.AnalyticsSink
import com.nuvio.app.core.analytics.Breadcrumbs
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.core.journal.StartupJournal
import com.nuvio.app.features.common.lifecycle.FeatureRegistry
import com.nuvio.app.registerLogicFeatureContributions
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSProcessInfo

/**
 * Apple TV's composition root and process bootstrap: the tvOS counterpart of FeatureWiring.kt plus
 * the iOS MainViewController bootstrap. Swift calls [start] once at launch, AFTER it has registered
 * the analytics sink and the memory-pressure source (as iOSApp.swift does), and BEFORE any screen
 * reads shared state: several ports throw if read before registration.
 */
object TvAppGraph {
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (!FeatureRegistry.isInitialized) {
            installUnhandledExceptionReporter()
            // The same logic-port list every platform registers (FeatureContributions.kt). The Compose UI
            // slots in FeatureWiring.kt have no Apple TV role.
            registerLogicFeatureContributions()
            FeatureRegistry.markInitialized()
        }
        // Static half of the memory probe; Apple TV HD has 2 GB, the 4K models 3-4 GB. The dynamic half
        // is the DispatchSource memory-pressure source in the Swift app, feeding AppMemory.
        MemoryPortAccess.current().setBaseTier(
            MemoryTierPolicy.iosTier(NSProcessInfo.processInfo.physicalMemory.toLong()),
        )
        StartupJournal.markUiLaunchStarted()
        TvAppLifecycle.start()
        com.tuvora.tvos.screens.TvDeviceSources.start()
    }

    /** SwiftUI calls this on every screen change; the first call also marks the launch interactive. */
    fun screenChanged(name: String) = Breadcrumbs.screenChanged(name)

    /** Kotlin exceptions that escape to the top reach PostHog before the process ends (iOS parity). */
    @OptIn(ExperimentalNativeApi::class)
    private fun installUnhandledExceptionReporter() {
        var previous: ((Throwable) -> Unit)? = null
        previous = setUnhandledExceptionHook { throwable ->
            runCatching {
                AnalyticsSink.capture(
                    "app_uncaught_exception_tvos",
                    mapOf(
                        "exception" to (throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable"),
                        "message" to (throwable.message ?: ""),
                        "stack" to throwable.stackTraceToString().take(4000),
                    ),
                )
            }
            previous?.invoke(throwable)
        }
    }
}
