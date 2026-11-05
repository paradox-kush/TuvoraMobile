package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.RecordingCompletenessPolicy
import com.nuvio.app.features.recordings.internal.RecordingResumePolicy
import com.nuvio.app.features.recordings.internal.ResumePin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingResumePolicyTest {
    private val link = "http://panel.example/timeshift/u/p/60/2026-11-05:20-00/42.ts"

    @Test
    fun `a partial pinned to the same link resumes`() {
        assertTrue(RecordingResumePolicy.canResume(pinnedUrl = link, url = link, resumeFromBytes = 1_000))
    }

    @Test
    fun `a partial from another link is never handed on`() {
        assertFalse(RecordingResumePolicy.canResume(pinnedUrl = "$link?alt", url = link, resumeFromBytes = 1_000), "different candidate")
    }

    @Test
    fun `a partial with no recorded link is not resumed`() {
        assertFalse(RecordingResumePolicy.canResume(pinnedUrl = null, url = link, resumeFromBytes = 1_000))
    }

    @Test
    fun `nothing to resume when the repository asked for a fresh start`() {
        assertFalse(RecordingResumePolicy.canResume(pinnedUrl = link, url = link, resumeFromBytes = 0))
    }

    @Test
    fun `a clean end with no declared length far below the estimate is not a finished save`() {
        assertTrue(RecordingCompletenessPolicy.endedTooEarly(writtenBytes = 5_000_000, declaredLength = null, estimatedBytes = 2_000_000_000))
    }

    @Test
    fun `a declared length is trusted over the estimate`() {
        assertFalse(RecordingCompletenessPolicy.endedTooEarly(writtenBytes = 5_000_000, declaredLength = 5_000_000, estimatedBytes = 2_000_000_000))
    }

    @Test
    fun `no estimate means no judgement`() {
        assertFalse(RecordingCompletenessPolicy.endedTooEarly(writtenBytes = 10, declaredLength = null, estimatedBytes = 0))
    }

    @Test
    fun `a plausible size with no declared length is accepted`() {
        assertEquals(false, RecordingCompletenessPolicy.endedTooEarly(writtenBytes = 900_000_000, declaredLength = null, estimatedBytes = 2_000_000_000))
    }

    @Test
    fun `a pin round-trips the link and both validators`() {
        val pin = ResumePin(link, "\"abc\"", "Wed, 05 Nov 2026 10:00:00 GMT")
        assertEquals(pin, ResumePin.decode(pin.encode()))
    }

    @Test
    fun `a pin with only a link decodes with no validators`() {
        val pin = ResumePin.decode(link)
        assertEquals(ResumePin(link, null, null), pin)
        assertNull(pin?.validator)
    }

    @Test
    fun `the strong etag is the validator and a weak one falls back to the date`() {
        assertEquals("\"abc\"", ResumePin(link, "\"abc\"", "date").validator)
        assertEquals("date", ResumePin(link, "W/\"abc\"", "date").validator)
        assertNull(ResumePin(link, "W/\"abc\"", null).validator)
    }

    @Test
    fun `unreadable pin text means an unknown source`() {
        assertNull(ResumePin.decode(null))
        assertNull(ResumePin.decode(""))
        assertNull(ResumePin.decode("\n\n"))
    }
}
