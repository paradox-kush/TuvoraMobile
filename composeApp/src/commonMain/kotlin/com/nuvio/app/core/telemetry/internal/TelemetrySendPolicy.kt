package com.nuvio.app.core.telemetry.internal

/** What happens to a routine event. Decided by [TelemetrySendPolicy], applied by the service. */
internal enum class SendDecision { SEND, RECORD_ONLY, COUNT }

/**
 * Error-only sending (design §3): success-path steps stay local or become counts in `session_health`;
 * anything that signals a problem is sent now. Pure, so the cuts are testable without a network.
 * Unknown events default to SEND: a new event is never silently swallowed.
 */
internal object TelemetrySendPolicy {
    const val SCREEN_EVENT = "\$screen"

    private val countedAlways = setOf("playback_started", "iptv_index_build")
    private val countedUnlessFailed = setOf("iptv_load", "epg_ingest", "epg_resolve", "epg_mapping")
    private val failureOutcomes = setOf("failed", "error", "timeout", "unavailable", "fail", "failure")

    /** Exit reasons worth a single event each; the rest (cached/background kills) become a count. */
    private const val USER_VISIBLE_IMPORTANCE_MAX = 270

    private val individualExitReasons = setOf(
        "crash", "native_crash", "anr", "initialization_failure", "user_requested_crash",
    )

    fun decide(event: String, props: Map<String, Any>): SendDecision = when {
        event == SCREEN_EVENT -> SendDecision.RECORD_ONLY
        event in countedAlways -> SendDecision.COUNT
        event in countedUnlessFailed -> if (isFailure(props)) SendDecision.SEND else SendDecision.COUNT
        event == "app_exit" -> decideExit(props)
        else -> SendDecision.SEND
    }

    private val errorMarkers = listOf("fail", "freeze", "error", "crash", "timeout", "anr", "exception", "gave_up", "warning")

    /** Error events carry the recorder's last entries (`diag_trail`); routine SEND events do not. */
    fun attachesTrail(event: String, props: Map<String, Any>): Boolean =
        errorMarkers.any { it in event.lowercase() } || isFailure(props)

    fun isFailure(props: Map<String, Any>): Boolean {
        val outcome = (props["outcome"] as? String)?.lowercase()
        if (outcome != null) return outcome in failureOutcomes
        // epg_resolve has no outcome: a non-zero `unavailable` is the failure signal.
        return (props["unavailable"] as? Int ?: 0) > 0
    }

    private fun decideExit(props: Map<String, Any>): SendDecision {
        val reason = (props["reason"] as? String)?.lowercase() ?: (props["exit_reason"] as? String)?.lowercase()
        if (reason == null) return SendDecision.SEND
        if (reason in individualExitReasons) return SendDecision.SEND
        // Android passes the raw ActivityManager importance (<= 270 is user-visible); other hosts pass a name.
        val raw = props["importance"]
        val foreground = when (raw) {
            is Int -> raw in 1..USER_VISIBLE_IMPORTANCE_MAX
            is String -> raw.lowercase().let { it.contains("foreground") || it.contains("perceptible") || it.contains("visible") }
            else -> false
        }
        return if (foreground) SendDecision.SEND else SendDecision.COUNT
    }
}
