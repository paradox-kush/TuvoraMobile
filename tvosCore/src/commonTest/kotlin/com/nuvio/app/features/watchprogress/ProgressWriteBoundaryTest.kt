package com.nuvio.app.features.watchprogress

import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ProgressWriteBoundaryTest {
    @Test fun diskFailureIsReportedWithoutEscapingPlayback() {
        val error = IOException("synthetic disk failure")
        var reported: IOException? = null
        persistPlaybackProgressOrContinue({ reported = it }) { throw error }
        assertSame(error, reported)
    }
    @Test fun successfulWriteRunsOnce() {
        var writes = 0
        persistPlaybackProgressOrContinue({ fail("Unexpected failure") }) { writes++ }
        assertEquals(1, writes)
    }
    @Test fun cancellationAndProgrammingErrorsPropagate() {
        assertFailsWith<CancellationException> {
            persistPlaybackProgressOrContinue({ fail("Cancellation was swallowed") }) { throw CancellationException("ended") }
        }
        assertFailsWith<IllegalStateException> {
            persistPlaybackProgressOrContinue({ fail("Programming error was swallowed") }) { error("invalid state") }
        }
    }
}
