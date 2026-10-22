package com.nuvio.app.features.servers

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedBridgePolicyTest {
    @Test fun exchangeAddressIsBoundToTheExactManagedOriginAndIdentity() {
        val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        assertTrue(ManagedBridgePolicy.validAddress(id, "https://addon.thekush.dev/jellyfin/$id"))
        for (address in listOf("https://attacker.example/jellyfin/$id", "http://addon.thekush.dev/jellyfin/$id", "https://addon.thekush.dev/jellyfin/$id?redirect=1", "https://addon.thekush.dev/jellyfin/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            assertFalse(ManagedBridgePolicy.validAddress(id, address))
    }
    @Test fun matchingTitleAloneCannotSelectTheWrongMovie() {
        val titles = listOf(ServerTitle(MetaPreview(ServerItemRef("bridge", "wrong").encode(), "movie", "Same title"), TrackingExternalIds(imdb = "tt2222222")))
        assertTrue(managedItemIds("bridge", titles, TrackingExternalIds(imdb = "tt1111111")).isEmpty())
    }
    @Test fun conflictingIdsAndAnotherConnectionsResultsAreRejected() {
        fun title(connection: String, item: String, tmdb: Long) = ServerTitle(
            MetaPreview(ServerItemRef(connection, item).encode(), "movie", "Same title"), TrackingExternalIds(imdb = "tt1111111", tmdb = tmdb))
        val titles = listOf(title("bridge", "correct", 7), title("bridge", "conflict", 9), title("other", "foreign", 7))
        assertEquals(listOf("correct"), managedItemIds("bridge", titles, TrackingExternalIds(imdb = "tt1111111", tmdb = 7)))
    }
}
