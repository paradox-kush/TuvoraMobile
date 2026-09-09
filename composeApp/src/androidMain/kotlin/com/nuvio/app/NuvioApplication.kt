package com.nuvio.app

import android.app.Application
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTier
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.logs.PostHogBeforeSendLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NuvioApplication : Application() {

    private val analyticsConsentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Public client-side key — safe to ship in the binary.
        const val POSTHOG_PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
        const val POSTHOG_HOST = "https://us.i.posthog.com"
    }

    override fun onCreate() {
        super.onCreate()
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

        val config = PostHogAndroidConfig(
            apiKey = POSTHOG_PROJECT_TOKEN,
            host = POSTHOG_HOST
        ).apply {
            // Capture uncaught exceptions as $exception events (where the app breaks).
            errorTrackingConfig.autoCapture = true
            optOut = !crashReportsEnabled
            captureApplicationLifecycleEvents = false
            captureScreenViews = false
            sendFeatureFlagEvent = false
            preloadFeatureFlags = false
            surveys = false
            captureDeepLinks = false
            sessionReplay = false
            sessionReplayConfig.screenshot = false
            sessionReplayConfig.captureLogcat = false
            tracingHeaders = emptyList()
            logs.addBeforeSend(PostHogBeforeSendLog { null })
            addBeforeSend(PostHogBeforeSend { event ->
                if (PostHogPrivacy.shouldDropEvent(event.event)) null
                else event.copy(
                    properties = PostHogPrivacy.sanitize(event.properties.orEmpty()).toMutableMap(),
                )
            })
            // Upload queued events quickly after launch: a crash queued by the previous
            // run must ship before the user navigates back into whatever crashed
            // (the default 30s starved uploads during crash-loops).
            flushIntervalSeconds = 10
        }
        PostHogAndroid.setup(this, config)
        // Lets shared code capture without depending on an Android-only SDK.
        com.nuvio.app.core.analytics.AnalyticsSink.register { event, properties ->
            PostHog.capture(event, properties = properties)
        }
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
        PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
        if (crashReportsEnabled) {
            PostHog.optIn()
            AppExitReporter.reportPendingExits(this)
        } else {
            PostHog.optOut()
        }
        analyticsConsentScope.launch {
            SentrySettingsRepository.enabled.collect { enabled ->
                if (enabled) PostHog.optIn() else PostHog.optOut()
            }
        }
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
        AppExitReporter.recordMemorySnapshot(this, "trim_memory", level)
    }
}
