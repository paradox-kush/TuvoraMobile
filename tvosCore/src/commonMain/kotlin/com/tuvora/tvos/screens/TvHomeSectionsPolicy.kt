package com.tuvora.tvos.screens

import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.home.HomeSectionContext
import com.nuvio.app.features.home.HomeSectionEntry
import com.nuvio.app.features.home.HomeSectionIds
import com.nuvio.app.features.home.HomeSectionLegacyToggles
import com.nuvio.app.features.home.HomeSectionOrderPolicy

/**
 * Apple TV's view of the shared Home section order (`home_sections`, [HomeSectionOrderPolicy]): the same
 * decision the phone makes, for this device. Apple TV has no Sports or Upcoming section, so those ids are
 * skipped on render and kept on write, and the hero stays at the top because Home uses it as its backdrop:
 * it is on/off only here, while on the phone it can move.
 *
 * Apple TV keeps its hero and Continue Watching switches in the Layout settings (device preferences), so
 * they are passed in rather than read from the phone's repositories.
 */
object TvHomeSectionsPolicy {
    val supportedIds: Set<String> = setOf(
        HomeSectionIds.HERO,
        HomeSectionIds.CONTINUE_WATCHING,
        HomeSectionIds.LIVE_RECENTS,
        HomeSectionIds.NEW_EPISODES,
        HomeSectionIds.CATALOGS,
    )

    fun context(hasWatchHistory: Boolean) =
        HomeSectionContext(hasWatchHistory = hasWatchHistory, supportedIds = supportedIds, heroPinnedFirst = true)

    private fun legacy(state: HomeCatalogSettingsUiState, heroEnabled: Boolean, continueWatchingVisible: Boolean) =
        HomeSectionLegacyToggles(heroEnabled, continueWatchingVisible, state.showLiveOnHome)

    /** Sections Apple TV renders, in order, hidden ones included (the settings list). */
    fun resolve(state: HomeCatalogSettingsUiState, heroEnabled: Boolean, continueWatchingVisible: Boolean): List<HomeSectionEntry> =
        HomeSectionOrderPolicy.resolve(
            state.homeSections, context(state.hasWatchHistory), legacy(state, heroEnabled, continueWatchingVisible),
        )

    /** The ids Home draws, top to bottom. */
    fun visibleIds(state: HomeCatalogSettingsUiState, heroEnabled: Boolean, continueWatchingVisible: Boolean): List<String> =
        resolve(state, heroEnabled, continueWatchingVisible).filter { it.enabled }.map { it.id }

    /**
     * The hero and Continue Watching switches a customised list implies (null when nothing is customised),
     * for Swift to write to the Layout preferences so the two places never disagree.
     */
    fun togglesFrom(state: HomeCatalogSettingsUiState, heroEnabled: Boolean, continueWatchingVisible: Boolean): HomeSectionLegacyToggles? =
        HomeSectionOrderPolicy.togglesFrom(state.homeSections, legacy(state, heroEnabled, continueWatchingVisible))

    /**
     * Where [id] lands when moved [direction] (-1 up, +1 down) in [entries], or null when it cannot move:
     * the hero never moves, and nothing moves above the hero.
     */
    fun targetIndex(entries: List<HomeSectionEntry>, id: String, direction: Int): Int? {
        if (id == HomeSectionIds.HERO) return null
        val from = entries.indexOfFirst { it.id == id }
        if (from == -1) return null
        val to = from + direction
        if (to !in entries.indices) return null
        if (entries[to].id == HomeSectionIds.HERO) return null
        return to
    }
}
