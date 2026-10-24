package com.tuvora.tvos.screens

import com.nuvio.app.features.watchprogress.WatchProgressEntry

data class TvEpisodePresentation(val progress: Double, val watched: Boolean, val hideSpoilers: Boolean)
object TvEpisodePresentationPolicy {
    fun present(entry: WatchProgressEntry?, markedWatched: Boolean, blurUnwatched: Boolean): TvEpisodePresentation {
        val watched = markedWatched || entry?.isEffectivelyCompleted == true
        return TvEpisodePresentation(entry?.progressFraction?.toDouble() ?: 0.0, watched, blurUnwatched && !watched)
    }
}
