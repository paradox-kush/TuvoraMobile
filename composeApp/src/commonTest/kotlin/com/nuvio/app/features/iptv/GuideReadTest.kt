package com.nuvio.app.features.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuideReadTest {
    @Test
    fun `a failed read falls back to the default`() = runTest {
        assertEquals(listOf<Int>(), GuideRead.softly(listOf<Int>()) { error("db") })
        assertEquals(listOf(1), GuideRead.softly(listOf<Int>()) { listOf(1) })
    }

    @Test
    fun `cancellation is control flow and is never swallowed into the default`() = runTest {
        assertFailsWith<CancellationException> { GuideRead.softly(0) { throw CancellationException("left") } }
    }
}
