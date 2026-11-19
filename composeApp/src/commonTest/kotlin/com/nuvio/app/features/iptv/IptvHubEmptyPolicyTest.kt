package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvHubEmptyPolicyTest {
    @Test
    fun `an empty hub offers Retry and no still-empty note`() {
        val view = IptvHubEmptyPolicy.view(userRetried = false)
        assertTrue(view.offersRetry, "empty must never be a dead end")
        assertFalse(view.showsStillEmptyNote, "nothing was retried yet")
    }

    @Test
    fun `an empty hub after a Retry shows the checked-just-now note and still offers Retry`() {
        val view = IptvHubEmptyPolicy.view(userRetried = true)
        assertTrue(view.offersRetry, "the viewer can ask again")
        assertTrue(view.showsStillEmptyNote, "a tap that comes back empty must show a result")
    }

    @Test
    fun `only the viewer's own Retry arms the note`() {
        assertTrue(IptvHubEmptyPolicy.retriedAfter(IptvHubEmptyPolicy.Event.USER_RETRY), "retry arms it")
        assertFalse(IptvHubEmptyPolicy.retriedAfter(IptvHubEmptyPolicy.Event.RELOAD), "a section switch or sync does not")
        assertEquals(false, IptvHubEmptyPolicy.view(IptvHubEmptyPolicy.retriedAfter(IptvHubEmptyPolicy.Event.RELOAD)).showsStillEmptyNote)
    }

    private fun hub(load: LoadStatus, rows: List<XtreamHubCategory> = emptyList(), error: IptvLoadFailurePolicy.Failure? = null) =
        XtreamHubUiState(categories = rows, categoriesLoad = load, loadError = error)

    @Test
    fun `a finished load with no rows is the empty state`() {
        assertTrue(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Empty)), "Empty outcome")
        assertTrue(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Loaded)), "Loaded with nothing")
    }

    @Test
    fun `rows that all loaded empty also read as the empty state`() {
        val rows = listOf(XtreamHubCategory("1", "A", loaded = true), XtreamHubCategory("2", "B", loaded = true))
        assertTrue(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Loaded, rows)))
    }

    @Test
    fun `a wait a failure or a row still to show is not the empty state`() {
        assertFalse(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Idle)), "idle")
        assertFalse(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Failed(timedOut = false))), "failed status")
        assertFalse(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Loaded, error = IptvLoadFailurePolicy.classify(null))), "failure card up")
        assertFalse(IptvHubEmptyPolicy.showsEmptyState(hub(LoadStatus.Loaded, listOf(XtreamHubCategory("1", "A")))), "an unloaded row shows as a shimmer row")
    }
}
