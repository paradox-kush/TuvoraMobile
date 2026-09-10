package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueWatchingEnrichmentCacheTest {

    @Test
    fun `storage keys are scoped by profile and effective source`() {
        val traktKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(
            profileId = 2,
            source = WatchProgressSource.TRAKT,
        )
        val nuvioKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(
            profileId = 2,
            source = WatchProgressSource.NUVIO_SYNC,
        )

        assertEquals("cw_enrichment_cache_trakt_2", traktKey)
        assertEquals("cw_enrichment_cache_nuvio_sync_2", nuvioKey)
        assertNotEquals(traktKey, nuvioKey)
        assertEquals("cw_enrichment_cache_2", ContinueWatchingEnrichmentCache.legacyStorageKey(profileId = 2))
    }

    @Test
    fun `stale resolver generation cannot write snapshots after invalidation`() {
        val profileId = 4
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.invalidate(
            profileId = profileId,
            source = WatchProgressSource.TRAKT,
        )

        assertFalse(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = profileId,
                source = WatchProgressSource.TRAKT,
                generation = staleGeneration,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )
        assertTrue(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = profileId,
                source = WatchProgressSource.TRAKT,
                generation = ContinueWatchingEnrichmentCache.generation.value,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )

        ContinueWatchingEnrichmentCache.clearAll(profileId)
    }

    @Test
    fun `account clear invalidates resolver writes from the previous account`() {
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.clearLocalState()

        assertFalse(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = 1,
                source = WatchProgressSource.NUVIO_SYNC,
                generation = staleGeneration,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )
        ContinueWatchingEnrichmentCache.clearAll(profileId = 1)
    }

    // --- gap 3: both bounds enforced in the right order (byte cap BEFORE decode; record cap after) ---

    private fun nextUpJson(id: String): String =
        "{\"contentId\":\"$id\",\"contentType\":\"movie\",\"name\":\"n\",\"videoId\":\"v\",\"lastWatched\":1,\"sortTimestamp\":1}"

    @Test
    fun `decodeBounded drops an over-cap value before ever decoding it`() {
        // A VALID payload object padded with whitespace past the char cap. Because it is valid JSON,
        // the only reason decodeBounded can reject it is the SIZE check firing BEFORE decode — which
        // is the whole point: capping the decoded lists would first need the full object graph.
        val oversizedButValid = "{\"nextUp\":[],\"inProgress\":[]" + " ".repeat(4 * 1024 * 1024 + 64) + "}"
        assertNull(ContinueWatchingEnrichmentCache.decodeBounded(oversizedButValid))
        // The same shape well under the cap decodes fine, proving it was the size, not the JSON.
        assertNotNull(ContinueWatchingEnrichmentCache.decodeBounded("{\"nextUp\":[],\"inProgress\":[]}"))
    }

    @Test
    fun `decodeBounded record-caps a large but in-cap payload at 500`() {
        val nextUp = (1..600).joinToString(",") { nextUpJson("n$it") }
        val payload = ContinueWatchingEnrichmentCache.decodeBounded("{\"nextUp\":[$nextUp],\"inProgress\":[]}")
        assertNotNull(payload)
        assertEquals(500, payload.nextUp.size)
    }

    @Test
    fun `decodeBounded returns null for corrupt json`() {
        assertNull(ContinueWatchingEnrichmentCache.decodeBounded("this is not json"))
    }
}
