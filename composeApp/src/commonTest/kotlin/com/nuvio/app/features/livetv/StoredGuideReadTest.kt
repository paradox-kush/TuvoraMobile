package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.XtreamProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class StoredGuideReadTest {
    @Test
    fun `a read that returns programmes is Ok`() = runTest {
        val r = readStoredGuide { emptyList<XtreamProgram>() }
        assertEquals(StoredGuideRead.Ok(emptyList()), r)
    }

    @Test
    fun `a failing read is Failed and never an empty guide`() = runTest {
        val r = readStoredGuide { throw IllegalStateException("db locked") }
        assertEquals(StoredGuideRead.Failed, r, "a failure must not be rendered as 'no airings'")
    }

    @Test
    fun `cancellation is rethrown not swallowed`() = runTest {
        assertFailsWith<CancellationException> { readStoredGuide { throw CancellationException("scope gone") } }
    }

    @Test
    fun `Failed is distinct from an empty Ok`() {
        assertTrue(StoredGuideRead.Failed != StoredGuideRead.Ok(emptyList()))
    }
}
