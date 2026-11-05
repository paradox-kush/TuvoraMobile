package com.nuvio.app.features.recordings

import com.nuvio.app.features.livetv.pause.LivePauseFailure
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.internal.RecordingLiveOutcomePolicy as P
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingLiveOutcomePolicyTest {
    @Test
    fun `a full disk is no space online or off`() {
        assertEquals(P.Mapped(RecordingProblem.NO_SPACE, false), P.map(LivePauseFailure.NO_SPACE, online = true))
        assertEquals(P.Mapped(RecordingProblem.NO_SPACE, false), P.map(LivePauseFailure.NO_SPACE, online = false))
    }

    @Test
    fun `a stall while online is stalled and uses an attempt`() {
        assertEquals(P.Mapped(RecordingProblem.STALLED, false), P.map(LivePauseFailure.STALLED, online = true))
        assertEquals(P.Mapped(RecordingProblem.STALLED, false), P.map(LivePauseFailure.TIMED_OUT, online = true))
        assertEquals(P.Mapped(RecordingProblem.STALLED, false), P.map(LivePauseFailure.SEQUENCE_RESET, online = true))
    }

    @Test
    fun `a stall or a refusal with no network is the network and costs no attempt`() {
        assertEquals(P.Mapped(RecordingProblem.NETWORK, true), P.map(LivePauseFailure.STALLED, online = false))
        assertEquals(P.Mapped(RecordingProblem.NETWORK, true), P.map(LivePauseFailure.PROVIDER_REFUSED, online = false))
    }

    @Test
    fun `a provider refusal while online is provider refused`() {
        assertEquals(P.Mapped(RecordingProblem.PROVIDER_REFUSED, false), P.map(LivePauseFailure.PROVIDER_REFUSED, online = true))
    }

    @Test
    fun `a stream the capture cannot read is an unsupported format which is never retried`() {
        assertEquals(P.Mapped(RecordingProblem.UNSUPPORTED_FORMAT, false), P.map(LivePauseFailure.UNSUPPORTED_STREAM, online = true))
    }

    @Test
    fun `the provider ending the stream well before the slot is over is an early end`() {
        assertTrue(P.endedEarly(nowMs = 1_000_000L, stopAtMs = 1_000_000L + 60_000L))
        assertFalse(P.endedEarly(nowMs = 1_000_000L, stopAtMs = 1_000_000L + 2_000L), "within the tolerance is the end")
        assertFalse(P.endedEarly(nowMs = 1_000_000L, stopAtMs = 900_000L))
    }
}
