package com.tuvora.tvos.screens

/**
 * What each Apple TV Top Shelf section carries, decided without the OS so it tests without a device. The feed itself
 * (tvosApp/Shared/TopShelfFeed.swift) is assembled in Swift from these picks.
 *
 * - Continue Watching: the Home row exactly as the app shows it (already ordered by ResumeRankPolicy or recency), capped.
 * - New episodes: Next Up items whose air date is within [WINDOW_MS] of now. Unaired ones are INCLUDED on purpose: the
 *   Top Shelf extension filters them against its own clock, so an episode appears at air time while the app is closed.
 * - Recent channels: the first few, as the Live TV row has them.
 */
object TvTopShelfPolicy {
    const val CONTINUE_MAX = 12
    const val NEW_EPISODES_MAX = 6
    const val CHANNELS_MAX = 6
    const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    fun continueWatching(items: List<TvCwItem>): List<TvCwItem> = items.take(CONTINUE_MAX)

    /** Newest air date first; items with no air date are never "new episodes". */
    fun recentlyAiredEpisodes(items: List<TvCwItem>, nowMs: Long): List<TvCwItem> =
        items.filter { item ->
            val airs = item.releasedAtMs
            item.isNextUp && airs != null && airs >= nowMs - WINDOW_MS && airs <= nowMs + WINDOW_MS
        }.sortedByDescending { it.releasedAtMs }.take(NEW_EPISODES_MAX)

    fun <T> recentChannels(channels: List<T>): List<T> = channels.take(CHANNELS_MAX)
}
