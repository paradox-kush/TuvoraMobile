package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamLiveRecent
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the Apple TV Top Shelf publisher and the Home rows settings screen read from shared code, so SwiftUI does no
 * I/O and names no repository. Section picks are in [TvTopShelfPolicy].
 */
object TvTopShelf {
    /** The Live TV row's channels (profile-scoped), capped for the Top Shelf. Re-emits when the row changes. */
    val recentChannels: Flow<List<XtreamLiveRecent>>
        get() {
            XtreamLiveRecents.ensureLoaded()
            return XtreamLiveRecents.recents.map { TvTopShelfPolicy.recentChannels(it) }.distinctUntilChanged()
        }
}

/** The Continue Watching "Order" choice, over the same shared preference the phone's setting writes. */
object TvContinueWatchingOrder {
    val isResumeFirst: Flow<Boolean>
        get() {
            ContinueWatchingPreferencesRepository.ensureLoaded()
            return ContinueWatchingPreferencesRepository.uiState.map { it.isResumeFirst }.distinctUntilChanged()
        }

    /** true = "Pick up where I left off"; false = "Most recent first" (also ends resume-first, as on the phone). */
    fun select(resumeFirst: Boolean) {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        if (resumeFirst) ContinueWatchingPreferencesRepository.setResumeFirst()
        else ContinueWatchingPreferencesRepository.setSortMode(ContinueWatchingSortMode.DEFAULT)
    }
}
