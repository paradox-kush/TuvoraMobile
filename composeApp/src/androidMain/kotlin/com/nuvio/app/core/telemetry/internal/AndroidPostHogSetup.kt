package com.nuvio.app.core.telemetry.internal

import android.app.Application
import com.nuvio.app.core.analytics.PostHogPrivacy
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.logs.PostHogBeforeSendLog

/** PostHog SDK configuration for Android. Lives here so the SDK is named only inside `core/telemetry/internal`. */
internal object AndroidPostHogSetup {
    // Public client-side key — safe to ship in the binary.
    private const val PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
    private const val HOST = "https://us.i.posthog.com"

    fun setup(app: Application, crashReportsEnabled: Boolean) {
        val config = PostHogAndroidConfig(apiKey = PROJECT_TOKEN, host = HOST).apply {
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
        PostHogAndroid.setup(app, config)
        PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
        if (crashReportsEnabled) PostHog.optIn() else PostHog.optOut()
    }

    fun setOptIn(enabled: Boolean) {
        if (enabled) PostHog.optIn() else PostHog.optOut()
    }
}
