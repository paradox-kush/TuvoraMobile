package com.nuvio.app

import android.app.Application
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTier
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.internal.AndroidTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Implements [SingletonImageLoader.Factory] (upstream) so Coil's singleton — with the custom-poster
 * fallback interceptor and the fork's IPv4-first fetcher and memory-tier cap — exists before the first
 * image request; the composable factory in App.kt is skipped on Android (platformProvidesImageLoader).
 */
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: Context): ImageLoader = buildAppImageLoader(context)


    private val analyticsConsentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Public client-side key — safe to ship in the binary.
        const val POSTHOG_PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
        const val POSTHOG_HOST = "https://us.i.posthog.com"
    }

    override fun onCreate() {
        super.onCreate()
        // Telemetry v2 FIRST, before DI: the crash handler must exist for a crash during startup. It uses
        // no coroutines or DI; the SDK is configured later (configurePostHog) once consent is loaded.
        SentrySettingsStorage.initialize(this)
        AndroidTelemetry.start(this)
        // Consent must be available before feature registration constructs RecSettingsImpl,
        // including a headless process start with no MainActivity startup tasks.
        initializeAndroidProcessStorage(this)
        // Feature-contribution bootstrap (once per process — see FeatureWiring.kt). Must run
        // before anything reads a port — including the memory tier resolved just below.
        registerFeatureContributions()
        // Startup journal — application-scoped so it is ready on EVERY process start, including a
        // headless WorkManager cold start with no Activity (where MainActivity's runStartup never
        // runs). initialize() is idempotent (just sets the storage dir); decideStartupMode() is
        // DECISION ONLY (sweeps + advances the one run, decides safe mode) and opens NO UI attempt,
        // so a worker-only launch cannot inflate the crash-loop counter. The UI-launch attempt is
        // still opened later, in MainActivity.onCreate (markUiLaunchStarted). Before this fix a
        // headless IptvRefreshWorker read isSafeMode with the store uninitialised and the mode
        // undecided → fail-open false, bypassing the safe-mode skip.
        com.nuvio.app.core.journal.StartupJournalStore.initialize(this)
        com.nuvio.app.core.journal.StartupJournal.decideStartupMode()
        // Resolve the app-wide memory tier once, before anything sizes a cache from it. The OS's
        // own words (ActivityManager.isLowRamDevice / memoryClass) feed the neutral policy; a null
        // ActivityManager never happens in practice and falls to the bigger cache, as before.
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryTier = if (activityManager == null) MemoryTier.HIGH
            else MemoryTierPolicy.androidTier(activityManager.isLowRamDevice, activityManager.memoryClass)
        MemoryPortAccess.current().setBaseTier(memoryTier)
        SentrySettingsStorage.initialize(this)
        SentrySettingsRepository.ensureLoaded()
        val crashReportsEnabled = SentrySettingsRepository.enabled.value

        // PostHog setup (and the crash handler re-wrap on top of the SDK's) lives in core/telemetry/internal.
        AndroidTelemetry.configurePostHog(this, crashReportsEnabled)
        // Breadcrumbs persist locally so AppExitReporter can attribute a process death on the
        // NEXT launch; their live-event side goes through the sink above and is consent-gated
        // by the SDK like every other capture.
        com.nuvio.app.core.analytics.Breadcrumbs.crashWriter =
            object : com.nuvio.app.core.analytics.Breadcrumbs.CrashWriter {
                override fun onScreen(name: String) {
                    AppExitReporter.recordRoute(this@NuvioApplication, name)
                }

                override fun onPlaybackStarted(kind: String, engine: String, surface: String) {
                    AppExitReporter.recordPlaybackStarted(this@NuvioApplication, kind, engine, surface)
                }

                override fun onPlaybackStopped() {
                    AppExitReporter.recordPlaybackStopped(this@NuvioApplication)
                }
            }
        if (crashReportsEnabled) {
            AppExitReporter.reportPendingExits(this)
        }
        analyticsConsentScope.launch {
            SentrySettingsRepository.enabled.collect { enabled ->
                AndroidTelemetry.setOptIn(enabled)
            }
        }
        // A crash record left by the previous run goes out once, off Main, with a deadline.
        analyticsConsentScope.launch { TelemetryAccess.current().sendPendingCrash() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Since Android 14 only these two constants fire (the rest died in 14, formally
        // deprecated in 15) — both mean the UI left the screen: drop every registered
        // cache. Truth is on disk; the windows repopulate on return.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            MemoryPortAccess.current().trimCaches()
        }
        TelemetryAccess.current().record(
            DiagEntry(System.currentTimeMillis(), DiagArea.MEMORY, "trim_memory", mapOf("trim_level" to level)),
        )
        AppExitReporter.recordMemorySnapshot(this, "trim_memory", level)
    }
}
