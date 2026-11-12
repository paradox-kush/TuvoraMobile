package com.nuvio.app.features.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B186: with six add-ons, one that hangs must not keep the whole source search "loading". Every add-on
 * lane ends (deadline per lane); a lane that hangs ends as Failed for that add-on and the others keep
 * their results. Virtual time, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonLaneDeadlineTest {

    private fun stream(addon: String) =
        StreamItem(name = "s", url = "http://x/$addon", addonName = addon, addonId = addon)

    @Test
    fun `a hung add-on lane ends as failed at the lane deadline`() = runTest {
        val group = async { resolveAddonGroup("Hung", "hung") { awaitCancellation() } }
        runCurrent()
        advanceTimeBy(SourceLaneGuard.LANE_DEADLINE_MS - 1)
        assertFalse(group.isCompleted, "still within the deadline")
        advanceTimeBy(2)
        val ended = group.await()
        assertFalse(ended.isLoading, "the lane ended")
        assertNotNull(ended.error, "a lane that hangs is Failed, not silently empty")
        assertTrue(ended.streams.isEmpty())
        assertTrue(currentTime in SourceLaneGuard.LANE_DEADLINE_MS..SourceLaneGuard.LANE_DEADLINE_MS + 1, "ended at the deadline")
    }

    @Test
    fun `six add-ons with one hanging return the five results and name the hung one`() = runTest {
        val lanes = (1..6).map { index ->
            async {
                val name = "Addon$index"
                resolveAddonGroup(name, name) {
                    if (index == 4) awaitCancellation() else {
                        delay(index * 500L)
                        listOf(stream(name))
                    }
                }
            }
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(5, lanes.count { it.isCompleted }, "five lanes answered before the hung one's deadline")

        advanceUntilIdle()
        val groups = lanes.awaitAll()
        assertTrue(groups.none { it.isLoading }, "every lane ended")
        assertEquals(5, groups.count { it.streams.isNotEmpty() })
        assertEquals(listOf(FailedLane("Addon4", groups[3].error)), StreamSourcePresentationPolicy.failedLanes(groups))
        assertEquals(SourceLaneGuard.LANE_DEADLINE_MS, currentTime)
    }

    @Test
    fun `a provider side cancellation ends as failed instead of leaving the lane open`() = runTest {
        val ended = resolveAddonGroup("Odd", "odd") { throw CancellationException("Source choices changed") }
        assertFalse(ended.isLoading)
        assertNotNull(ended.error)
        assertFalse(ended.error!!.contains("Source choices changed"), "the raw cancellation text is never shown")
    }

    @Test
    fun `only the callers own cancellation propagates`() = runTest {
        var reported = false
        val job = launch {
            resolveAddonGroup("Caller", "caller") { awaitCancellation() }
            reported = true
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse(reported, "a lane the caller left reports nothing")
    }
}
