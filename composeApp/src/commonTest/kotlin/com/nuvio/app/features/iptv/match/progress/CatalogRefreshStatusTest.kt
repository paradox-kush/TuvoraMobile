package com.nuvio.app.features.iptv.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class CatalogRefreshStatusTest {
    @Test
    fun refreshBeforeFirstBatchSaysCatalogRemainsAvailable() {
        assertEquals(
            "Updating catalog in the background…",
            indexingStatusLine(true, IndexBuildProgress(0, isRefresh = true)),
        )
    }

    @Test
    fun refreshKeepsTheRunningCountAndClearsWhenFinished() {
        val progress = IndexBuildProgress(12_345, isRefresh = true)
        assertEquals("Updating catalog in the background… 12,345 items", indexingStatusLine(true, progress))
        assertNull(indexingStatusLine(false, progress))
    }

    @Test
    fun queuedBuildsAndCountUpdatesKeepExistingCatalogState() {
        val refresh = IndexBuildProgress(500, isRefresh = true)
        val firstBuildOfAnotherKind = IndexBuildProgress(0)
        assertTrue(refresh.mergeWith(firstBuildOfAnotherKind).isRefresh)
        assertTrue(firstBuildOfAnotherKind.mergeWith(refresh).isRefresh)
        val nextBatch = refresh.mergeWith(IndexBuildProgress(1_000))
        assertTrue(nextBatch.isRefresh)
        assertEquals(1_000, nextBatch.itemsWritten)
    }
}
