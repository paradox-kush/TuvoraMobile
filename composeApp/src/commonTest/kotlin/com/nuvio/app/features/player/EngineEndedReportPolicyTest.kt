package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression (iPhone sim, 458 at open): libmpv sets "ended" a moment before its error text. Reading the error first
 * and then delivering the ended snapshot let the screen fail the open with NO text ("The stream did not start") and
 * tear the engine down before the next poll could report "HTTP error 458". The ended snapshot waits one poll for it.
 */
class EngineEndedReportPolicyTest {

    @Test
    fun `an end with no error text yet is held for one poll`() {
        assertTrue(EngineEndedReportPolicy.holdEnded(isEnded = true, hasError = false, alreadyHeld = false))
        assertFalse(EngineEndedReportPolicy.holdEnded(isEnded = true, hasError = false, alreadyHeld = true), "never more than once")
    }

    @Test
    fun `an end that already has its error text or a running stream is never held`() {
        assertFalse(EngineEndedReportPolicy.holdEnded(isEnded = true, hasError = true, alreadyHeld = false))
        assertFalse(EngineEndedReportPolicy.holdEnded(isEnded = false, hasError = false, alreadyHeld = false))
    }

    @Test
    fun `the poll sequence of a 458 open delivers the error before the end`() {
        // Poll 1: ended, no text. Poll 2: ended, "HTTP error 458". The screen must see the text first.
        val events = ArrayList<String>()
        var held = false
        for ((ended, error) in listOf(true to null, true to "[ffmpeg] http: HTTP error 458")) {
            if (error != null) events += "error"
            if (EngineEndedReportPolicy.holdEnded(ended, error != null, held)) { held = true; continue }
            events += if (ended) "ended" else "snapshot"
        }
        assertEquals(listOf("error", "ended"), events)
    }
}
