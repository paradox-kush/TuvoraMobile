package com.nuvio.app.core.telemetry.api

/**
 * Telemetry v2 port. Feature code records and reports through this and never touches a PostHog SDK
 * or `AnalyticsSink.capture` (a Konsist rule enforces it). Names and enums are the contract shared
 * with the TV, Desktop and Apple TV lanes (research/telemetry-flight-recorder-design-2026-11-07.md).
 *
 * No Compose imports here or in `internal`: `:tvosCore` compiles these files.
 */
interface TelemetryPort {
    /** Raw 8-character support code (`K7Q29MPX`). Show it with [displaySupportCode]. */
    val supportCode: String

    /** `K7Q2-9MPX`. */
    fun displaySupportCode(): String

    /** Local-only: lands in the on-device flight recorder and nowhere else until an error or report carries it. */
    fun record(entry: DiagEntry)

    /**
     * An error: sent now (subject to the consent switch), with the recorder's last entries attached as `diag_trail`.
     */
    fun reportError(event: String, props: Map<String, Any>)

    /**
     * A routine event. The send policy decides: SEND (to PostHog), RECORD_ONLY (flight recorder) or COUNT
     * (folded into the next `session_health`). Replaces `AnalyticsSink.capture` at call sites.
     */
    fun emit(event: String, props: Map<String, Any>)

    /** Mirrors an abnormal process exit as an exception so error tracking groups it (host SDK permitting). */
    fun reportException(error: Throwable, props: Map<String, Any>)

    /** One press sends. Every outcome is a distinct [ReportResult]; none is shown as success. */
    suspend fun sendUserReport(
        category: ReportCategory,
        surface: ReportSurface,
        context: ReportContext = ReportContext(),
    ): ReportResult

    /** True when the user's "Crash & usage reports" switch is on (what "Send report" needs). */
    fun consentEnabled(): Boolean

    /** App moved to the background / is exiting: flushes the `session_health` summary and the ring snapshot. */
    fun onBackground()

    /** Host lifecycle: the app came (back) to the foreground; a new health session starts. */
    fun onForeground()

    /** Sends a crash record left by the previous run, at most once. Call off Main after the SDK is set up. */
    suspend fun sendPendingCrash()

    /** Playback produced output: memory sampling runs while this scope is open (and only then). */
    fun onPlaybackScopeOpened()

    /** Playback ended: cancels the memory sampler. */
    fun onPlaybackScopeClosed()

    /** The persisted flight-recorder trail of the PREVIOUS run (for attaching to its abnormal exit), or null. */
    fun previousSessionTrail(): String?
}

/** Short code-only record. [fields] pass the allow-list in `DiagTrailPolicy`; anything else is dropped. */
data class DiagEntry(
    val atMs: Long,
    val area: DiagArea,
    val code: String,
    val fields: Map<String, Any> = emptyMap(),
)

enum class DiagArea { PLAYBACK, IPTV_LOAD, EPG, SCREEN, MEMORY, LIFECYCLE }

enum class ReportCategory { WONT_PLAY, BLACK_SCREEN, FREEZES, APP_CLOSED, OTHER }

enum class ReportSurface { LIVE, VOD, SETTINGS }

/** What the screen knows about the failure it is reporting. Codes only. */
data class ReportContext(
    val failureCode: String? = null,
    val engine: String? = null,
)

sealed interface ReportResult {
    data class Sent(val supportCode: String) : ReportResult
    data object ConsentOff : ReportResult
    data class RateLimited(val retryInSec: Int) : ReportResult
    data object Failed : ReportResult
}

/** Process-wide holder for the port, registered once at process init (`FeatureContributions.kt`). */
object TelemetryAccess {
    private var port: TelemetryPort = NoopTelemetryPort
    fun register(port: TelemetryPort) { this.port = port }
    fun current(): TelemetryPort = port

    /** Function-reference form of [TelemetryPort.emit] for call sites that inject a `(String, Map) -> Unit`. */
    fun emit(event: String, props: Map<String, Any>) = port.emit(event, props)

    /** Tests: back to the no-op port. */
    fun resetForTest() { port = NoopTelemetryPort }
}

private object NoopTelemetryPort : TelemetryPort {
    override val supportCode: String = ""
    override fun displaySupportCode(): String = ""
    override fun record(entry: DiagEntry) {}
    override fun reportError(event: String, props: Map<String, Any>) {}
    override fun emit(event: String, props: Map<String, Any>) {}
    override fun reportException(error: Throwable, props: Map<String, Any>) {}
    override suspend fun sendUserReport(category: ReportCategory, surface: ReportSurface, context: ReportContext) = ReportResult.Failed
    override fun consentEnabled(): Boolean = false
    override fun onBackground() {}
    override fun onForeground() {}
    override suspend fun sendPendingCrash() {}
    override fun onPlaybackScopeOpened() {}
    override fun onPlaybackScopeClosed() {}
    override fun previousSessionTrail(): String? = null
}

/** Maps a failure code to the report category when a screen already knows what failed (no picker on error screens). */
object ReportCategoryInference {
    fun fromFailureCode(code: String?): ReportCategory {
        val c = code?.lowercase().orEmpty()
        return when {
            c.isEmpty() -> ReportCategory.WONT_PLAY
            "black" in c -> ReportCategory.BLACK_SCREEN
            "freeze" in c || "stall" in c || "stuck" in c -> ReportCategory.FREEZES
            else -> ReportCategory.WONT_PLAY
        }
    }
}
