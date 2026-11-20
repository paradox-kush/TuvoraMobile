package com.nuvio.app.core.analytics

import com.nuvio.app.core.telemetry.api.TelemetryAccess

/**
 * Turns [DecodeHealthPolicy] findings into `playback_decode_health` events, through the telemetry PORT
 * ([TelemetryAccess], never an SDK): an error-class event, so it is sent with the flight-recorder trail.
 *
 * One reporter per player session (the cap of [DecodeHealthPolicy.MAX_EVENTS_PER_SESSION] is per
 * reporter); [onSample] starts a new tune whenever the tune key changes. Reporting only: it returns
 * nothing a caller could act on.
 */
class DecodeHealthReporter(
    /** `android`, `ios` or `tvos`. */
    private val platform: String,
    private val report: (String, Map<String, Any>) -> Unit = TelemetryAccess::reportError,
    maxPerSession: Int = DecodeHealthPolicy.MAX_EVENTS_PER_SESSION,
) {
    private val tracker = DecodeHealthPolicy.Tracker(maxPerSession)
    private var tuneKey: String? = UNSET

    val eventsThisSession: Int get() = tracker.eventsThisSession

    /**
     * Evaluates one sample. [shape] is read only when something is about to be reported, so the healthy
     * path never builds it. [tune] names the channel tune (a content id, never a URL): a change starts a
     * new tune with fresh once-per-issue dedupe.
     */
    fun onSample(
        tune: String?,
        sample: DecodeHealthPolicy.Sample,
        surface: String,
        shape: () -> DecodeHealthPolicy.StreamShape?,
    ) {
        if (tune != tuneKey) {
            tuneKey = tune
            tracker.onTune()
        }
        val findings = tracker.onSample(sample)
        if (findings.isEmpty()) return
        val resolved = runCatching(shape).getOrNull()
        for (finding in findings) {
            report(DecodeHealthPolicy.EVENT, DecodeHealthPolicy.properties(finding, resolved, surface, platform))
        }
    }

    /** A deliberate interruption (display-mode switch): see [DecodeHealthPolicy.Tracker.onDisruption]. */
    fun onDisruption() = tracker.onDisruption()

    private companion object {
        /** Distinct from every real key (including null), so the first sample starts a tune. */
        const val UNSET = "\u0000unset"
    }
}
