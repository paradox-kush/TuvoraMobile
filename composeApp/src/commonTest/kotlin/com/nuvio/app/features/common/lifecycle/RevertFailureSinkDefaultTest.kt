package com.nuvio.app.features.common.lifecycle

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * B232 — with no `installFeatures` provider (iOS / Apple TV) a failed teardown revert must still be
 * logged, not dropped by a no-op default.
 */
class RevertFailureSinkDefaultTest {

    private class Capture : LogWriter() {
        val entries = mutableListOf<Triple<Severity, String, Throwable?>>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            entries += Triple(severity, tag, throwable)
        }
    }

    @Test
    fun theDefaultSinkLogsTheFailure() {
        val capture = Capture()
        val before = Logger.config.logWriterList.toList()
        Logger.setLogWriters(capture)
        try {
            val boom = IllegalStateException("revert blew up")
            logRevertFailure(boom)
            assertEquals(1, capture.entries.size, "exactly one log line")
            val (severity, tag, throwable) = capture.entries.single()
            assertEquals(Severity.Warn, severity, "severity")
            assertEquals("EffectScope", tag, "tag")
            assertSame(boom, throwable, "the throwable is attached")
            assertTrue(true)
        } finally {
            Logger.setLogWriters(before)
        }
    }
}
