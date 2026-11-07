package com.nuvio.app.core.analytics

/**
 * Where shared code sends analytics events.
 *
 * PostHog is configured per platform and its SDKs are not multiplatform — Android sets up
 * `PostHogAndroid` in `NuvioApplication`, iOS sets up `PostHogSDK` in `iOSApp.init()`. Rather
 * than an expect/actual per platform, each host registers a sink here once at startup and
 * shared code captures through it.
 *
 * Consent is not re-checked here: both SDKs are configured opt-out until the user enables
 * crash reports, so a capture with consent withheld is dropped by the SDK itself.
 *
 * Unregistered (a host that has not called [register], or a test) silently drops events.
 */
object AnalyticsSink {

    private var sink: ((String, Map<String, Any>) -> Unit)? = null
    private var timedSink: ((String, Map<String, Any>, Long) -> Unit)? = null
    private var superPropertySink: ((Map<String, Any>) -> Unit)? = null
    private var flushSink: (() -> Unit)? = null

    /** Called once by each platform host at startup. Safe to call again to replace. */
    fun register(sink: (String, Map<String, Any>) -> Unit) {
        this.sink = sink
    }

    /** Optional: a sink that keeps the event's own time (a crash is stamped when it happened, not when it is sent). */
    fun registerTimed(sink: (String, Map<String, Any>, Long) -> Unit) {
        this.timedSink = sink
    }

    /** Optional: attaches properties to every later event (PostHog super properties). */
    fun registerSuperProperties(sink: (Map<String, Any>) -> Unit) {
        this.superPropertySink = sink
    }

    /**
     * Telemetry v2: only `core/telemetry/internal` may call this (Konsist rule). Feature code goes through
     * `TelemetryPort`, which applies the send policy.
     */
    fun capture(event: String, properties: Map<String, Any>) {
        sink?.invoke(event, properties)
    }

    /** As [capture], stamped [atEpochMs]. Falls back to a plain capture when no timed sink is registered. */
    fun captureAt(event: String, properties: Map<String, Any>, atEpochMs: Long) {
        val timed = timedSink
        if (timed != null) timed(event, properties, atEpochMs) else sink?.invoke(event, properties)
    }

    /** Optional: uploads the SDK's queue now (a report or a recovered crash should not wait for the interval). */
    fun registerFlush(sink: () -> Unit) {
        this.flushSink = sink
    }

    fun flush() {
        flushSink?.invoke()
    }

    fun registerSuperProps(properties: Map<String, Any>) {
        superPropertySink?.invoke(properties)
    }
}
