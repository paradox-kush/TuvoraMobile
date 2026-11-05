package com.nuvio.app.features.streams

import com.nuvio.app.core.contracts.StreamSourceGroup
import com.nuvio.app.features.servers.loadBounded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B63 on Mobile/Desktop: one unreachable playlist held the whole source list (loading chips,
 * auto-play, the player's Next Episode / source fetch) open for its connect timeout — 60 s per
 * panel call on these clients. Virtual time, fakes only, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IptvMatchSourceLaneTest {

    private val deadHostMs = 60_000L

    private fun source(id: String) = StreamSourceGroup(sourceId = "xtream-match:$id", addonName = id)

    private fun streamFrom(src: StreamSourceGroup) =
        StreamItem(name = src.addonName, url = "http://panel/${src.addonName}.mkv", addonName = src.addonName, addonId = src.sourceId)

    @Test
    fun theSourceListStopsLoadingAtTheBudgetInsteadOfWaitingForADeadPlaylist() = runTest {
        // The shape both fetchers run: one addon + a healthy playlist + a dead playlist, concurrently;
        // the list stops loading (isAnyLoading = false) when the last group has finished.
        val loading = mutableSetOf("addon", "healthy", "dead")
        var listSettledAt = -1L
        val groups = mutableMapOf<String, AddonStreamGroup>()
        fun finished(id: String) {
            loading -= id
            if (loading.isEmpty()) listSettledAt = currentTime
        }

        val othersSettled = Job()
        val addon = launch { delay(2_000); finished("addon") }
        launch { addon.join(); othersSettled.complete() }
        listOf(source("healthy") to 1_500L, source("dead") to deadHostMs).forEach { (src, latency) ->
            launch {
                val group = IptvMatchSourceLane.resolveGroup(src, othersSettled) {
                    delay(latency); listOf(streamFrom(src))
                }
                groups[src.addonName] = group
                finished(src.addonName)
            }
        }
        advanceUntilIdle()

        assertEquals(IptvSourceWaitPolicy.PLAYLIST_BUDGET_MS, listSettledAt, "list stops loading at the budget, not at the dead host's timeout")
        assertEquals(1, groups.getValue("healthy").streams.size, "healthy playlist still delivered")
        val dead = groups.getValue("dead")
        assertFalse(dead.isLoading, "the cut playlist's chip stops loading")
        assertTrue(dead.streams.isEmpty(), "the cut playlist contributes nothing to this list")
        assertNull(dead.error, "a cut is not reported as a request failure")
    }

    @Test
    fun aSlowPlaylistStillMakesTheListWhileAnAddonIsLoadingAnyway() = runTest {
        val othersSettled = Job()
        launch { delay(20_000); othersSettled.complete() }
        val src = source("slow")
        val group = IptvMatchSourceLane.resolveGroup(src, othersSettled) { delay(12_000); listOf(streamFrom(src)) }
        assertEquals(1, group.streams.size, "kept: the list was still waiting on an addon")
        assertEquals(12_000L, currentTime, "delivered when it answered")
    }

    @Test
    fun aFailingPlaylistStillReportsItsError() = runTest {
        val othersSettled = Job().also { it.complete() }
        val group = IptvMatchSourceLane.resolveGroup(source("refused"), othersSettled) {
            delay(100); throw IllegalStateException("refused")
        }
        assertEquals("refused", group.error, "the failure reaches the group")
        assertFalse(group.isLoading, "failed group is finished")
    }
}

/** B226: a lane must always report a group; a provider-side CancellationException is a failure, not the caller leaving. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceLaneIsolationTest {
    private val src = StreamSourceGroup(sourceId = "xtream-match:p", addonName = "p")

    @Test
    fun aProviderSideCancellationBecomesAFailedGroupNotASilentlyDeadLane() = runTest {
        val group = IptvMatchSourceLane.resolveGroup(src, Job().also { it.complete() }) {
            throw kotlinx.coroutines.CancellationException("Source choices changed")
        }
        assertFalse(group.isLoading)
        assertTrue(group.streams.isEmpty())
        assertTrue(!group.error.isNullOrBlank(), "failure is reported, never swallowed as empty")
        assertTrue(group.error != "Source choices changed", "localized, not the raw provider message")
    }

    @Test
    fun aSlowPanelStillAnswersWhileAddonsAndPluginsAreRunning() = runTest {
        // B63: the playlist waits past every fixed budget while other lanes are loading; a plugin
        // that runs for 120 s must not turn a 70 s panel answer into a dropped source.
        val othersSettled = Job()
        launch { delay(120_000); othersSettled.complete() }
        var group: AddonStreamGroup? = null
        val job = launch {
            group = IptvMatchSourceLane.resolveGroup(src, othersSettled) { delay(70_000); listOf(StreamItem(name = "p", url = "http://panel/p.mkv", addonName = "p", addonId = src.sourceId)) }
        }
        advanceUntilIdle()
        job.join()
        assertEquals(1, group!!.streams.size)
        assertNull(group!!.error)
    }

    @Test
    fun aHungPanelStillEndsOnceTheOtherLanesSettle() = runTest {
        val othersSettled = Job()
        launch { delay(100_000); othersSettled.complete() }
        var group: AddonStreamGroup? = null
        val job = launch {
            group = IptvMatchSourceLane.resolveGroup(src, othersSettled) { delay(Long.MAX_VALUE); emptyList() }
        }
        advanceUntilIdle()
        job.join()
        assertEquals(100_000L, currentTime)
        assertFalse(group!!.isLoading)
        assertTrue(group!!.iptvPending, "cut like a budget cut: still loading, not failed")
        assertNull(group!!.error)
    }

    @Test
    fun anInterruptedAddonShowsLocalizedTextNeverTheRawProviderMessage() = runTest {
        val group = resolveAddonGroup("a", "addon:a") { throw kotlinx.coroutines.CancellationException("Source choices changed") }
        assertFalse(group.isLoading)
        assertTrue(!group.error.isNullOrBlank())
        assertTrue(group.error != "Source choices changed", "raw provider message leaked: ${group.error}")
    }

    @Test
    fun guardReportsForeignCancellationAndItsOwnDeadlineDistinctly() = runTest {
        assertTrue(SourceLaneGuard.run { throw kotlinx.coroutines.CancellationException("Server scope changed") }
            .exceptionOrNull() is SourceLaneInterruptedException)
        assertTrue(SourceLaneGuard.run(deadlineMs = 1_000) { delay(5_000) }.exceptionOrNull() is SourceLaneTimeoutException)
        assertEquals(1, SourceLaneGuard.run { 1 }.getOrNull())
    }

    @Test
    fun aProvidersOwnInnerTimeoutIsNotMislabelledAsTheLaneDeadline() = runTest {
        val failure = SourceLaneGuard.run(deadlineMs = 60_000) {
            kotlinx.coroutines.withTimeout(10) { delay(1_000) }
        }.exceptionOrNull()
        assertTrue(failure is SourceLaneInterruptedException, "was $failure")
        assertEquals(10, currentTime)
    }

    @Test
    fun noDeadlineLaneIsNeverCutButStillConvertsForeignCancellation() = runTest {
        // plugin scrapers: the runtime owns the budget (queue + pause + 60 s), so a long run is not cut
        assertEquals("ok", SourceLaneGuard.run(deadlineMs = null) { delay(500_000); "ok" }.getOrNull())
        assertTrue(SourceLaneGuard.run(deadlineMs = null) { throw kotlinx.coroutines.CancellationException("x") }
            .exceptionOrNull() is SourceLaneInterruptedException)
    }

    @Test
    fun theCallersOwnCancellationPropagatesAndStopsTheProvider() = runTest {
        var reportedValue = false
        var providerStopped = false
        val job = launch {
            SourceLaneGuard.run {
                try { delay(10_000) } finally { providerStopped = true }
            }
            reportedValue = true
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertFalse(reportedValue, "cancelling the caller must not turn into a Failed value")
        assertTrue(providerStopped)
    }

    /** The aggregator's shape: one completion per lane or the collector never returns. */
    @Test
    fun theListSettlesWhenAddonPluginServerAndIptvLanesEachThrowAProviderSideCancellation() = runTest {
        val foreign = { kotlinx.coroutines.CancellationException("provider side") }
        val completions = kotlinx.coroutines.channels.Channel<AddonStreamGroup>(kotlinx.coroutines.channels.Channel.BUFFERED)
        val pluginResults = mutableListOf<Result<List<Int>>>()
        val server = com.nuvio.app.features.servers.ServerStreamSource("server:s", "s", false) { throw foreign() }
        val settled = launch {
            launch { completions.send(resolveAddonGroup("a", "addon:a") { throw foreign() }) } // add-on lane, the production helper
            launch { // plugin lane
                pluginResults += SourceLaneGuard.run(deadlineMs = null) { throw foreign() }
                completions.send(AddonStreamGroup("p", "plugin:p", emptyList(), isLoading = false))
            }
            launch { completions.send(server.loadBounded()) }
            launch { completions.send(IptvMatchSourceLane.resolveGroup(src, Job().also { it.complete() }) { throw foreign() }) }
        }
        val received = mutableListOf<AddonStreamGroup>()
        repeat(4) { received += completions.receive() }
        settled.join()
        assertEquals(4, received.size)
        assertTrue(received.none { it.isLoading })
        assertTrue(pluginResults.single().isFailure)
        assertTrue(received.filter { it.addonId != "plugin:p" }.all { !it.error.isNullOrBlank() }, "failures are reported, not shown as empty")
    }
}
