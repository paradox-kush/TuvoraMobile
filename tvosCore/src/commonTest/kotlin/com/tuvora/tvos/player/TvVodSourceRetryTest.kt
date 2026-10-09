package com.tuvora.tvos.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvVodSourceRetryTest {
    @Test fun duplicateRetriesMintOnceAndPreserveResumeAndPause() = runTest {
        var calls = 0
        val reopened = mutableListOf<Triple<String, Long, Boolean>>()
        val retry = TvVodSourceRetry(this, { calls++; delay(50); "fresh" },
            { source, position, paused -> reopened += Triple(source, position, paused) }, { fail("mint failed") })
        retry.retry(42_000, true); retry.retry(0, false)
        assertTrue(retry.isResolving)
        advanceUntilIdle()
        assertFalse(retry.isResolving)
        assertEquals(1, calls)
        assertEquals(listOf(Triple("fresh", 42_000L, true)), reopened)
    }
    @Test fun disposalPreventsAProviderThatIgnoresCancellationFromReopening() = runTest {
        val retry = TvVodSourceRetry(this, { withContext(NonCancellable) { delay(50); "fresh" } },
            { _: String, _: Long, _: Boolean -> fail("reopened after disposal") }, { fail("failure after disposal") })
        retry.retry(0, false); runCurrent(); retry.cancel(); advanceUntilIdle()
    }
    @Test fun aFailedMintSurfacesAnErrorInsteadOfReplayingTheStaleUrl() = runTest {
        var failures = 0
        val retry = TvVodSourceRetry<String>(this, { null }, { _, _, _ -> fail("stale replay") }, { failures++ })
        retry.retry(0, false); advanceUntilIdle(); assertEquals(1, failures)
    }
    @Test fun aProviderThatNeverAnswersHasABoundedRetry() = runTest {
        var failures = 0
        val retry = TvVodSourceRetry<String>(this, { delay(180_000); "too late" },
            { _, _, _ -> fail("timed-out source reopened") }, { failures++ })
        retry.retry(0, false)
        advanceUntilIdle()
        assertEquals(1, failures)
        assertFalse(retry.isResolving)
    }
}
