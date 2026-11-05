package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumeResponseCheckTest {
    @Test
    fun `the start of a content range is read`() {
        assertEquals(4L, ResumeResponseCheck.startOfRange("bytes 4-9/10"))
        assertEquals(0L, ResumeResponseCheck.startOfRange(" bytes 0-9/*"))
        assertEquals(1_048_576L, ResumeResponseCheck.startOfRange("bytes 1048576-2097151/9999999"))
    }

    @Test
    fun `a missing or unparsable content range has no start`() {
        assertNull(ResumeResponseCheck.startOfRange(null))
        assertNull(ResumeResponseCheck.startOfRange(""))
        assertNull(ResumeResponseCheck.startOfRange("bytes */10"))
        assertNull(ResumeResponseCheck.startOfRange("items 4-9/10"))
    }

    @Test
    fun `a 206 starting where the partial ends is accepted`() {
        assertTrue(ResumeResponseCheck.startsWhereAsked(206, "bytes 4-9/10", 4))
    }

    @Test
    fun `a 206 starting anywhere else is refused`() {
        assertFalse(ResumeResponseCheck.startsWhereAsked(206, "bytes 0-9/10", 4))
        assertFalse(ResumeResponseCheck.startsWhereAsked(206, "bytes 5-9/10", 4))
    }

    @Test
    fun `a 206 with no content range cannot be trusted`() {
        assertFalse(ResumeResponseCheck.startsWhereAsked(206, null, 4))
    }

    @Test
    fun `other statuses are not judged by the range`() {
        assertTrue(ResumeResponseCheck.startsWhereAsked(200, null, 4))
        assertTrue(ResumeResponseCheck.startsWhereAsked(416, null, 4))
    }
}
