package com.nuvio.app.core.util

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoroutineGuardsTest {
    @Test
    fun `guarded returns Ok for a value`() {
        val g = guarded(onError = { }) { 42 }
        assertTrue(g is Guarded.Ok)
        assertEquals(42, (g as Guarded.Ok).value)
    }

    @Test
    fun `guarded preserves a null value as Ok not Failed`() {
        val g = guarded<Int?>(onError = { }) { null }
        assertTrue(g is Guarded.Ok, "null is a value, not a failure")
        assertNull((g as Guarded.Ok).value)
    }

    @Test
    fun `guarded reports and returns Failed on a thrown error`() {
        var seen: Throwable? = null
        val g = guarded(onError = { seen = it }) { throw IllegalStateException("boom") }
        assertTrue(g is Guarded.Failed)
        assertTrue(seen is IllegalStateException)
    }

    @Test
    fun `guarded re-raises CancellationException without reporting`() {
        var reported = false
        assertFailsWith<CancellationException> {
            guarded(onError = { reported = true }) { throw CancellationException("cancel") }
        }
        assertEquals(false, reported, "cancellation is never swallowed or reported")
    }

    @Test
    fun `containTask contains a thrown error`() {
        var seen: Throwable? = null
        containTask(onError = { seen = it }) { throw RuntimeException("x") }
        assertTrue(seen is RuntimeException, "the task boundary contained the failure")
    }

    @Test
    fun `containTask re-raises CancellationException`() {
        var reported = false
        assertFailsWith<CancellationException> {
            containTask(onError = { reported = true }) { throw CancellationException("c") }
        }
        assertEquals(false, reported)
    }

    @Test
    fun `containTask runs the body on the happy path`() {
        var ran = false
        containTask(onError = { }) { ran = true }
        assertTrue(ran)
    }
}
