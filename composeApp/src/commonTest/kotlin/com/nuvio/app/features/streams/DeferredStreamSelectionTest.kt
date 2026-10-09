package com.nuvio.app.features.streams

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredStreamSelectionTest {
    private fun stream(name: String) = StreamItem(name=name, url="deferred:$name", addonId="own", addonName="own")
    @Test fun aSupersededRequestCannotNavigateEvenIfItIgnoresCancellation() = runTest {
        val selection = DeferredStreamSelection(this)
        val opened = mutableListOf<String?>()
        selection.select(stream("old"), { withContext(NonCancellable) { delay(50); "https://old" } }, { opened += it.url }, { fail("old failure") })
        runCurrent()
        selection.select(stream("new"), { "https://new" }, { opened += it.url }, { fail("new failure") })
        advanceUntilIdle()
        assertEquals(listOf<String?>("https://new"), opened)
    }
    @Test fun leavingTheScreenPreventsLateNavigation() = runTest {
        val selection = DeferredStreamSelection(this)
        selection.select(stream("old"), { withContext(NonCancellable) { delay(50); "https://old" } }, { fail("disposed screen navigated") }, { fail("disposed failure") })
        runCurrent(); selection.cancel(); advanceUntilIdle()
    }
    @Test fun aNullMintReachesTheVisibleFailureCallback() = runTest {
        var failures = 0
        DeferredStreamSelection(this).select(stream("missing"), { null }, { fail("null minted") }, { failures++ })
        advanceUntilIdle(); assertEquals(1, failures)
    }
}
