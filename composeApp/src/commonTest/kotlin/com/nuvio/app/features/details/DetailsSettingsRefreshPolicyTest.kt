package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailsSettingsRefreshPolicyTest {
    @Test
    fun serverLoadCompletionDoesNotStartAnotherRequest() {
        // The settings effect observes isLoading, so every completion used to start the next load.
        for (id in listOf("srv1:jellyfin:movie1", "srv1:emby:series1")) {
            repeat(12) {
                assertFalse(DetailsSettingsRefreshPolicy.shouldRefresh(id, hasMeta = true, isLoading = false))
            }
        }
    }

    @Test
    fun addonAndTmdbMetadataStillRefreshForSettings() {
        for (id in listOf("tt1234567", "tmdb:123", "custom:addon:123")) {
            assertTrue(DetailsSettingsRefreshPolicy.shouldRefresh(id, hasMeta = true, isLoading = false))
        }
    }

    @Test
    fun initialLoadAndRetryAreLeftToTheirOwnEffects() {
        for (id in listOf("srv1:home:item", "tt1234567")) {
            assertFalse(DetailsSettingsRefreshPolicy.shouldRefresh(id, hasMeta = false, isLoading = false))
        }
    }

    @Test
    fun settingsDoNotStartConcurrentLoads() {
        for (id in listOf("srv1:home:item", "tt1234567")) {
            assertFalse(DetailsSettingsRefreshPolicy.shouldRefresh(id, hasMeta = true, isLoading = true))
        }
    }
}
