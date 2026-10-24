package com.tuvora.tvos.screens

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.*

class TvEpisodePresentationTest {
    private fun entry(position: Long = 250, duration: Long = 1000, percent: Float? = null) = WatchProgressEntry(
        contentType = "series", parentMetaId = "series", parentMetaType = "series", videoId = "episode", title = "Episode",
        seasonNumber = 1, episodeNumber = 1, lastPositionMs = position, durationMs = duration, lastUpdatedEpochMs = 1,
        progressPercent = percent,
    )
    @Test fun rendersActualFractionInsteadOfPlaceholder() {
        val result = TvEpisodePresentationPolicy.present(entry(), false, false)
        assertEquals(0.25, result.progress); assertFalse(result.watched); assertFalse(result.hideSpoilers)
    }
    @Test fun unwatchedHasNoInventedProgressAndCanHideSpoilers() {
        val result = TvEpisodePresentationPolicy.present(null, false, true)
        assertEquals(0.0, result.progress); assertTrue(result.hideSpoilers)
    }
    @Test fun completionAndExplicitWatchedBothRevealSpoilers() {
        assertFalse(TvEpisodePresentationPolicy.present(entry(percent = 100f), false, true).hideSpoilers)
        assertTrue(TvEpisodePresentationPolicy.present(null, true, true).watched)
        assertFalse(TvEpisodePresentationPolicy.present(null, true, true).hideSpoilers)
    }
    @Test fun explicitProviderPercentAndUnknownDurationUseSharedRules() {
        assertEquals(0.75, TvEpisodePresentationPolicy.present(entry(duration = 0, percent = 75f), false, false).progress)
        assertEquals(0.0, TvEpisodePresentationPolicy.present(entry(duration = 0), false, false).progress)
    }
}
