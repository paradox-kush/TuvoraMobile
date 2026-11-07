package com.nuvio.app.core.telemetry.internal

/** App-private file storage for the telemetry directory (`files/telemetry/`). Implemented per platform. */
internal interface TelemetryFileStore {
    fun read(name: String): String?

    /** Temp file + rename: a crash mid-write leaves the previous file or none, never half a file. */
    fun writeAtomic(name: String, text: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun delete(name: String)
}

/**
 * The platform facts and the SDK door. Android implements it over `PostHog`, iOS over the Swift-registered sink,
 * tests over fakes. Nothing in this interface may throw into the caller's flow except through the documented return.
 */
internal interface TelemetryHost {
    val appVersion: String
    val store: TelemetryFileStore

    fun nowMs(): Long

    /** Uniform random int in `0 until bound` from a secure source. */
    fun randomBelow(bound: Int): Int

    /** The raw support code persisted for this install, or null when none is stored yet. */
    fun loadSupportCode(): String?
    fun saveSupportCode(code: String)

    /** The user's "Crash & usage reports" switch (the SDK is also opted out when it is off). */
    fun consentEnabled(): Boolean

    /** Hands an event to the SDK. [timestampMs] keeps the original event time (a crash is not stamped at send time). */
    fun capture(event: String, props: Map<String, Any>, timestampMs: Long?)
    fun captureException(error: Throwable, props: Map<String, Any>)
    fun flush()

    /** Properties attached to every later event (`support_code`, hardware). */
    fun registerSuperProperties(props: Map<String, Any>)

    /** Hardware facts. May be slow (codec probes): call off Main. */
    fun hardwareProfile(): Map<String, Any>

    /** Current memory in MB: `java_mb`, `java_max_mb`, `native_mb`, `pss_mb`, `ram_free_mb` as available. */
    fun memorySample(): Map<String, Long>
}
