package com.tuvora.tvos.screens

import com.nuvio.app.features.details.*
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watched.WatchedRepository

object TvTitlePresentation {
    /** Synthetic local-only UI acceptance data; the Swift entry point is DEBUG + simulator gated. */
    fun sample(): MetaDetails = MetaDetails(id = "parity-sample", type = "series", name = "Presentation preview",
        description = "Synthetic local title", imdbRating = "8.2", videos = listOf(
            MetaVideo(id = "parity-episode-1", title = "The hidden reveal", season = 1, episode = 1, overview = "A spoiler description"),
            MetaVideo(id = "parity-episode-2", title = "The second reveal", season = 1, episode = 2)))

    fun episode(meta: MetaDetails, video: MetaVideo): TvEpisodePresentation {
        MetaScreenSettingsRepository.ensureLoaded()
        val progress = WatchProgressRepository.progressForVideo(video.id, meta.id, video.season, video.episode)
        return TvEpisodePresentationPolicy.present(progress,
            WatchedRepository.isWatched(meta.id, meta.type, video.season, video.episode),
            MetaScreenSettingsRepository.uiState.value.blurUnwatchedEpisodes)
    }
    fun sectionEnabled(key: MetaScreenSectionKey): Boolean {
        MetaScreenSettingsRepository.ensureLoaded()
        return MetaScreenSettingsRepository.uiState.value.items.firstOrNull { it.key == key }?.enabled != false
    }
}
