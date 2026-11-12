package com.nuvio.app.features.iptv

import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * B213 — iOS crash on Save after editing playlist 1's DNS while playlist 2 was still loading.
 *
 * Saving an edit runs `XtreamSearchIndex.resetForProfile()` on a Dispatchers.Default worker. A lineup
 * fetch for the playlist still loading (hub custom groups, the Live TV guide, the EPG mirror remap all
 * go through `liveChannelsFor`) writes the same plain HashMaps from another worker when it lands, and
 * every one of those writes holds the cache lock — but the reset did not take it. Two threads mutating
 * one HashMap is a data race; Kotlin/Native turns it into an abort (house precedent: B92).
 *
 * Deterministic shape: while another thread holds the cache lock, the reset must wait for it instead
 * of clearing the maps underneath.
 */
class XtreamSearchIndexResetRaceTest {
    @Volatile private var entered = false
    @Volatile private var release = false

    @AfterTest
    fun tearDown() {
        release = true
        XtreamSearchIndex.resetForProfile()
    }

    @Test
    fun resetForProfileWaitsForTheCacheLockHeldByALandingLineupFetch() = runBlocking {
        val accountId = "b213-loading-playlist"
        XtreamSearchIndex.seedChannelsForTest(accountId, listOf(channel()))

        // Stands in for ensureChannels' landing write on another worker: holds the cache lock.
        val holder = launch(Dispatchers.Default) {
            XtreamSearchIndex.underCacheLockForTest {
                entered = true
                val mark = TimeSource.Monotonic.markNow()
                while (!release && mark.elapsedNow() < 10.seconds) { /* hold the lock */ }
            }
        }
        val enteredBy = TimeSource.Monotonic.markNow()
        while (!entered && enteredBy.elapsedNow() < 5.seconds) delay(1)
        assertTrue(entered, "test setup: the holder never took the cache lock")

        // The Save path's reset, on another Default worker.
        val reset = launch(Dispatchers.Default) { XtreamSearchIndex.resetForProfile() }
        delay(300)
        val resetRanWhileLockHeld = reset.isCompleted

        release = true
        reset.join()
        holder.join()

        assertFalse(
            resetRanWhileLockHeld,
            "resetForProfile cleared the search caches while another thread held their lock (B213 data race)",
        )
        assertNull(XtreamSearchIndex.cachedChannelsForTest(accountId), "the reset still clears the cache once the lock is free")
    }

    private fun channel() = XtreamChannel(
        streamId = 1,
        name = "Channel",
        logo = null,
        epgChannelId = null,
        categoryId = null,
        hasArchive = false,
        streamUrl = "http://example.invalid/live/1.ts",
    )
}
