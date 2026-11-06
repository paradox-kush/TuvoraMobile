package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.RecordingStatus
import com.nuvio.app.features.recordings.internal.ui.ProblemTextKind
import com.nuvio.app.features.recordings.internal.ui.problemTextKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingProblemTextTest {
    @Test
    fun `a done row cut short by space says it kept what was saved`() {
        assertEquals(ProblemTextKind.CUT_SHORT, problemTextKind(RecordingStatus.DONE, RecordingProblem.NO_SPACE, null, null))
    }

    @Test
    fun `a failed no-space row with both numbers names them`() {
        assertEquals(ProblemTextKind.NO_SPACE_KNOWN, problemTextKind(RecordingStatus.FAILED, RecordingProblem.NO_SPACE, 1_100_000_000L, 900_000_000L))
    }

    @Test
    fun `without both numbers or for other problems the generic sentence is used`() {
        assertEquals(ProblemTextKind.GENERIC, problemTextKind(RecordingStatus.FAILED, RecordingProblem.NO_SPACE, 5L, null))
        assertEquals(ProblemTextKind.GENERIC, problemTextKind(RecordingStatus.FAILED, RecordingProblem.NETWORK, 5L, 4L))
    }
}
