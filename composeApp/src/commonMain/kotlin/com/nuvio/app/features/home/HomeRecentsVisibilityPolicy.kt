package com.nuvio.app.features.home

/**
 * Pure decision for whether recently-watched live channels appear in the split Continue Watching UI
 * on the home screen (Settings -> Layout -> "Show Live TV on Home").
 *
 * Live playback records no watch progress, so recently-watched channels surface as a "recents" row
 * rather than resumable Continue Watching entries. This policy gates ONLY that live-channel row:
 * VOD/movie/series recents are never passed to it and are therefore always untouched. Extracted from
 * the home Composable so the decision is unit-testable without Compose, the player, or the network.
 *
 * Default behaviour (showLiveOnHome = true) returns the live recents unchanged.
 */
object HomeRecentsVisibilityPolicy {
    fun <T> visibleLiveRecents(showLiveOnHome: Boolean, liveRecents: List<T>): List<T> =
        if (showLiveOnHome) liveRecents else emptyList()
}
