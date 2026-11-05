package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.livetv.pause.LivePauseFailure
import com.nuvio.app.features.recordings.api.RecordingProblem

/**
 * How a live capture's failure reads as a recording problem, and when a quiet end is an early one. Pure.
 * The capture loop cannot tell a dead network from a dead provider, so [online] (the device's own view) decides:
 * a stall or refusal with no network is the network, and costs no attempt.
 */
internal object RecordingLiveOutcomePolicy {
    data class Mapped(val problem: RecordingProblem, val offline: Boolean)

    /** A provider that ends the stream this close to the stop time has simply finished with the programme. */
    const val END_TOLERANCE_MS = 5_000L

    fun map(reason: LivePauseFailure, online: Boolean): Mapped = when (reason) {
        LivePauseFailure.NO_SPACE -> Mapped(RecordingProblem.NO_SPACE, false)
        LivePauseFailure.UNSUPPORTED_STREAM -> Mapped(RecordingProblem.UNSUPPORTED_FORMAT, false)
        LivePauseFailure.PROVIDER_REFUSED ->
            if (online) Mapped(RecordingProblem.PROVIDER_REFUSED, false) else Mapped(RecordingProblem.NETWORK, true)
        LivePauseFailure.STALLED, LivePauseFailure.TIMED_OUT, LivePauseFailure.SEQUENCE_RESET ->
            if (online) Mapped(RecordingProblem.STALLED, false) else Mapped(RecordingProblem.NETWORK, true)
    }

    fun endedEarly(nowMs: Long, stopAtMs: Long): Boolean = stopAtMs - nowMs > END_TOLERANCE_MS
}
