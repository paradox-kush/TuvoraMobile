package com.nuvio.app.core.rec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecEventQueueRestorePolicyTest {
    private val sep = "\n"
    private fun select(raw: String?, max: Int = 500) =
        RecEventQueueRestorePolicy.select(raw, sep, max)

    @Test
    fun `null or blank yields nothing and is not oversized`() {
        assertEquals(emptyList(), select(null).lines)
        assertFalse(select(null).oversized)
        assertEquals(emptyList(), select("   ").lines)
        assertFalse(select("").oversized)
    }

    @Test
    fun `a healthy queue is returned in on-disk order`() {
        val out = select(listOf("a", "b", "c").joinToString(sep))
        assertEquals(listOf("a", "b", "c"), out.lines)
        assertFalse(out.oversized)
    }

    @Test
    fun `blank lines are dropped`() {
        assertEquals(listOf("a", "b"), select("a\n\n  \nb").lines)
    }

    @Test
    fun `only the newest maxRecords survive preserving order`() {
        val raw = (1..10).joinToString(sep) { "r$it" }
        assertEquals(listOf("r8", "r9", "r10"), select(raw, max = 3).lines, "keeps newest 3, on-disk order")
    }

    @Test
    fun `an over-length record is dropped but valid records survive`() {
        val huge = "x".repeat(RecEventQueueRestorePolicy.MAX_RECORD_CHARS + 1)
        val out = select("ok1\n$huge\nok2")
        assertEquals(listOf("ok1", "ok2"), out.lines, "the pathological line is skipped")
        assertFalse(out.oversized)
    }

    @Test
    fun `an oversized blob is rejected wholesale`() {
        val out = select("y".repeat(RecEventQueueRestorePolicy.MAX_QUEUE_CHARS + 1))
        assertTrue(out.oversized, "over the total cap")
        assertEquals(emptyList(), out.lines)
    }

    @Test
    fun `a non-positive maxRecords keeps nothing`() {
        assertEquals(emptyList(), select("a\nb", max = 0).lines)
    }

    // --- boundLines: the shared read+write bound (a 500-record queue is NOT byte-bounded) ---

    @Test
    fun `boundLines keeps newest N within the total-char budget`() {
        // 10 lines of "aaaa" (4 chars + 1 sep = 5). A 12-char budget fits 2 lines (10 chars), not 3.
        val lines = (1..10).map { "aaaa" }
        val out = RecEventQueueRestorePolicy.boundLines(lines, maxRecords = 500, maxTotalChars = 12)
        assertEquals(2, out.size, "total-char budget bounds the set even under the record cap")
    }

    @Test
    fun `boundLines drops blank and over-length lines`() {
        val huge = "x".repeat(RecEventQueueRestorePolicy.MAX_RECORD_CHARS + 1)
        val out = RecEventQueueRestorePolicy.boundLines(listOf("a", "", huge, "b"), maxRecords = 500)
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun `boundLines caps to the newest maxRecords in order`() {
        val out = RecEventQueueRestorePolicy.boundLines((1..10).map { "r$it" }, maxRecords = 3)
        assertEquals(listOf("r8", "r9", "r10"), out)
    }
}
