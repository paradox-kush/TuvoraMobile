package com.tuvora.tvos.screens

import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.home.HomeSectionEntry
import com.nuvio.app.features.home.HomeSectionsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvHomeSectionsPolicyTest {
    private fun state(history: Boolean = false, config: HomeSectionsConfig? = null, showLive: Boolean = true) =
        HomeCatalogSettingsUiState(homeSections = config, hasWatchHistory = history, showLiveOnHome = showLive)

    @Test
    fun `default keeps the hero on top and skips sports and upcoming`() {
        assertEquals(
            listOf("hero", "new_episodes", "live_recents", "continue_watching", "catalogs"),
            TvHomeSectionsPolicy.visibleIds(state(), heroEnabled = true, continueWatchingVisible = true),
        )
    }

    @Test
    fun `returning default puts continue watching and recent channels under the hero`() {
        assertEquals(
            listOf("hero", "continue_watching", "live_recents", "new_episodes", "catalogs"),
            TvHomeSectionsPolicy.visibleIds(state(history = true), heroEnabled = true, continueWatchingVisible = true),
        )
    }

    @Test
    fun `layout preferences drive visibility until the list is customised`() {
        assertEquals(
            listOf("new_episodes", "catalogs"),
            TvHomeSectionsPolicy.visibleIds(state(), heroEnabled = false, continueWatchingVisible = false),
        )
        val customised = HomeSectionsConfig(true, listOf(HomeSectionEntry("hero", true), HomeSectionEntry("continue_watching", true), HomeSectionEntry("catalogs", true)))
        assertEquals(
            true,
            TvHomeSectionsPolicy.visibleIds(state(config = customised), heroEnabled = false, continueWatchingVisible = false).contains("hero"),
            "once customised the list wins over the preference",
        )
        assertEquals(true, TvHomeSectionsPolicy.togglesFrom(state(config = customised), false, false)?.heroEnabled)
        assertNull(TvHomeSectionsPolicy.togglesFrom(state(), true, true))
    }

    @Test
    fun `a list written by the phone keeps its order and unknown ids are skipped`() {
        val phone = HomeSectionsConfig(
            true,
            listOf("catalogs", "sports", "hero", "live_recents", "future_row", "continue_watching", "new_episodes", "upcoming")
                .map { HomeSectionEntry(it, true) },
        )
        assertEquals(
            listOf("hero", "catalogs", "live_recents", "continue_watching", "new_episodes"),
            TvHomeSectionsPolicy.visibleIds(state(config = phone), heroEnabled = true, continueWatchingVisible = true),
        )
    }

    @Test
    fun `hero never moves and nothing moves above it`() {
        val entries = TvHomeSectionsPolicy.resolve(state(), heroEnabled = true, continueWatchingVisible = true)
        assertNull(TvHomeSectionsPolicy.targetIndex(entries, "hero", 1), "hero")
        assertNull(TvHomeSectionsPolicy.targetIndex(entries, "new_episodes", -1), "into the hero slot")
        assertEquals(2, TvHomeSectionsPolicy.targetIndex(entries, "new_episodes", 1))
        assertNull(TvHomeSectionsPolicy.targetIndex(entries, "catalogs", 1), "last")
        assertNull(TvHomeSectionsPolicy.targetIndex(entries, "nope", 1), "unknown")
    }
}
