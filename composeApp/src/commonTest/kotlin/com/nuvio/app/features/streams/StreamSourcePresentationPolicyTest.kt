package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B162 / B186: what the stream list shows for lane states. "Still loading" is not "missing" and a
 * failure is not "empty"; every wait has a deadline and ends as Loaded, Empty or Failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourcePresentationPolicyTest {

    private fun manifest() = AddonManifest(
        id = "test",
        name = "Test",
        description = "",
        version = "1.0.0",
        resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt"))),
        types = listOf("movie"),
        transportUrl = "https://example.com",
    )

    private fun ready(url: String = "https://example.com/a/manifest.json") =
        ManagedAddon(manifestUrl = url, manifest = manifest())

    private fun pending(url: String = "https://example.com/b/manifest.json") =
        ManagedAddon(manifestUrl = url, isRefreshing = true)

    private fun failed(url: String = "https://example.com/c/manifest.json") =
        ManagedAddon(manifestUrl = url, errorMessage = "timeout")

    private fun group(
        name: String,
        streams: Int = 0,
        loading: Boolean = false,
        error: String? = null,
        iptvPending: Boolean = false,
    ) = AddonStreamGroup(
        addonName = name,
        addonId = name,
        streams = List(streams) { StreamItem(name = "s$it", url = "http://x/$it", addonName = name, addonId = name) },
        isLoading = loading,
        error = error,
        iptvPending = iptvPending,
    )

    // --- readiness -------------------------------------------------------------------------------

    @Test
    fun `an add-on whose manifest is still loading is pending, not missing`() {
        assertEquals(AddonLaneReadiness.Pending, StreamSourcePresentationPolicy.readiness(pending()))
        assertEquals(AddonLaneReadiness.Failed, StreamSourcePresentationPolicy.readiness(failed()))
        assertEquals(AddonLaneReadiness.Ready, StreamSourcePresentationPolicy.readiness(ready()))
        assertEquals(AddonLaneReadiness.Idle, StreamSourcePresentationPolicy.readiness(ManagedAddon(manifestUrl = "https://example.com/d")))
    }

    @Test
    fun `counts skip disabled add-ons`() {
        val counts = StreamSourcePresentationPolicy.counts(
            listOf(ready(), pending(), failed(), pending("https://example.com/off").copy(enabled = false)),
        )
        assertEquals(AddonReadinessCounts(ready = 1, pending = 1, failed = 1), counts)
    }

    // --- play gate -------------------------------------------------------------------------------

    @Test
    fun `play is not disabled while add-ons are still loading or failed to load`() {
        val pendingOnly = AddonReadinessCounts(pending = 1)
        val failedOnly = AddonReadinessCounts(failed = 1)
        assertTrue(StreamSourcePresentationPolicy.canAttemptPlayback(false, pendingOnly, addonSourcesEnabled = true), "pending add-on")
        assertTrue(StreamSourcePresentationPolicy.canAttemptPlayback(false, failedOnly, addonSourcesEnabled = true), "failed add-on")
        assertFalse(StreamSourcePresentationPolicy.canAttemptPlayback(false, AddonReadinessCounts(), addonSourcesEnabled = true), "no add-on at all")
        assertFalse(StreamSourcePresentationPolicy.canAttemptPlayback(false, pendingOnly, addonSourcesEnabled = false), "store builds have no add-on sources")
        assertTrue(StreamSourcePresentationPolicy.canAttemptPlayback(true, AddonReadinessCounts(), addonSourcesEnabled = false), "a usable source")
    }

    // --- no lane at all --------------------------------------------------------------------------

    @Test
    fun `with no lane yet and add-ons loading the list waits instead of saying nothing is available`() {
        assertEquals(
            NoLaneOutcome.WaitForAddons,
            StreamSourcePresentationPolicy.noLaneOutcome(AddonReadinessCounts(ready = 1, pending = 2), manifestWaitElapsed = false),
        )
    }

    @Test
    fun `after the wait a straggler counts as failed so the wait ends`() {
        assertEquals(
            NoLaneOutcome.AddonsFailed,
            StreamSourcePresentationPolicy.noLaneOutcome(AddonReadinessCounts(pending = 1), manifestWaitElapsed = true),
        )
    }

    @Test
    fun `an add-on that failed to load is a failure, never no compatible add-on`() {
        assertEquals(
            NoLaneOutcome.AddonsFailed,
            StreamSourcePresentationPolicy.noLaneOutcome(AddonReadinessCounts(ready = 1, failed = 1), manifestWaitElapsed = false),
        )
    }

    @Test
    fun `no compatible add-on is only said when every add-on loaded`() {
        assertEquals(
            NoLaneOutcome.NoCompatibleAddons,
            StreamSourcePresentationPolicy.noLaneOutcome(AddonReadinessCounts(ready = 2), manifestWaitElapsed = false),
        )
    }

    // --- the list body ---------------------------------------------------------------------------

    private fun body(
        hasAnyStreams: Boolean = false,
        anyLoading: Boolean = false,
        hasGroups: Boolean = true,
        reason: StreamsEmptyStateReason? = null,
        forThisRequest: Boolean = true,
    ) = StreamSourcePresentationPolicy.body(hasAnyStreams, anyLoading, hasGroups, reason, forThisRequest)

    @Test
    fun `the empty message never shows while a lane is loading`() {
        assertEquals(StreamsBody.Loading, body(anyLoading = true, reason = StreamsEmptyStateReason.NoStreamsFound))
        assertEquals(StreamsBody.Loading, body(anyLoading = true, hasGroups = false, reason = StreamsEmptyStateReason.NoCompatibleAddons))
    }

    @Test
    fun `streams already found are shown even while other lanes load`() {
        assertEquals(StreamsBody.Streams, body(hasAnyStreams = true, anyLoading = true))
    }

    @Test
    fun `a list that is not for this request yet is loading, not empty`() {
        assertEquals(StreamsBody.Loading, body(forThisRequest = false, hasGroups = false))
    }

    @Test
    fun `a request nobody has decided yet is loading, not empty`() {
        assertEquals(StreamsBody.Loading, body(hasGroups = false, reason = null))
    }

    @Test
    fun `a settled request with a reason is empty or failed`() {
        assertEquals(StreamsBody.Empty, body(hasGroups = false, reason = StreamsEmptyStateReason.NoAddonsInstalled))
        assertEquals(StreamsBody.Empty, body(reason = StreamsEmptyStateReason.StreamFetchFailed))
        assertEquals(StreamsBody.Empty, body(reason = StreamsEmptyStateReason.NoStreamsFound))
    }

    // --- naming what failed ----------------------------------------------------------------------

    @Test
    fun `failed lanes are named and the others are not`() {
        val groups = listOf(
            group("Good", streams = 2),
            group("Quiet"),
            group("Broken", error = "boom"),
            group("Slow", loading = true),
            group("Playlist", error = "still loading", iptvPending = true),
        )
        assertEquals(listOf(FailedLane("Broken", "boom")), StreamSourcePresentationPolicy.failedLanes(groups))
    }

    @Test
    fun `stalled lanes end as failed and loaded lanes keep their streams`() {
        val ended = StreamSourcePresentationPolicy.endStalledLanes(
            listOf(group("Hung", loading = true), group("Partial", streams = 1, loading = true), group("Done", streams = 1)),
            stalledMessage = "timed out",
        )
        assertTrue(ended.none { it.isLoading }, "every lane ends")
        assertEquals("timed out", ended[0].error)
        assertEquals(null, ended[1].error)
        assertEquals(1, ended[1].streams.size)
        assertEquals(1, ended[2].streams.size)
        assertEquals(listOf(FailedLane("Hung", "timed out")), StreamSourcePresentationPolicy.failedLanes(ended))
    }

    // --- the wait has a deadline -----------------------------------------------------------------

    @Test
    fun `the wait ends as soon as the last manifest settles`() = runTest {
        val addons = MutableStateFlow(listOf(ready(), pending()))
        val result = async { StreamSourcePresentationPolicy.awaitAddonsSettled(addons, deadlineMs = 45_000L) }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(5_000L)
        addons.value = listOf(ready(), ready("https://example.com/b/manifest.json"))
        runCurrent()
        assertTrue(result.await())
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `a manifest that never settles ends the wait at the deadline`() = runTest {
        val addons = MutableStateFlow(listOf(pending()))
        val result = CompletableDeferred<Boolean>()
        val job = async { result.complete(StreamSourcePresentationPolicy.awaitAddonsSettled(addons, deadlineMs = 45_000L)) }
        runCurrent()
        advanceTimeBy(44_999L)
        assertFalse(result.isCompleted, "still waiting just before the deadline")
        advanceTimeBy(2L)
        job.await()
        assertFalse(result.await(), "timed out, not settled")
        assertTrue(currentTime in 45_000L..45_001L, "ended at the deadline")
    }
}
