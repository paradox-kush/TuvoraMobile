package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.AndroidProcFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidProcFactsTest {
    @Test
    fun `threads are read from the proc status text`() {
        val status = "Name:\tapp\nState:\tS (sleeping)\nThreads:\t87\nVmRSS:\t  412344 kB\n"
        assertEquals(87L, AndroidProcFacts.threadsFromStatus(status))
    }

    @Test
    fun `a status without a threads line or with junk gives null`() {
        assertNull(AndroidProcFacts.threadsFromStatus("Name:\tapp\n"))
        assertNull(AndroidProcFacts.threadsFromStatus("Threads:\tmany\n"))
        assertNull(AndroidProcFacts.threadsFromStatus(""))
    }

    @Test
    fun `the readers never throw on a host without proc facts`() {
        // On a desktop JVM /proc/self may not exist: the answer is null or a number, never an exception.
        AndroidProcFacts.readThreads()
        AndroidProcFacts.readFdCount()
    }
}
