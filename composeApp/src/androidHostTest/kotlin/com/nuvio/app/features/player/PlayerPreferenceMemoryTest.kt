package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F37 "Remember my player preferences" end to end through the real runtime and the Android store:
 * aspect + manual zoom (F36) and tracks carry to the next episode of the series, and nothing is
 * written or restored while the toggle is off. Harness shared with [PlayerSubtitleRestoreTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerPreferenceMemoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val episodeOneSubtitle = subtitle(1)
    private val episodeTwoSubtitle = subtitle(2)

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_player_track_preferences", "nuvio_watch_progress", "nuvio_player_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PlayerTrackPreferenceStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun aspectAndManualZoomCarryToTheNextEpisode() {
        val runtime = runtime()
        runtime.cycleResizeMode() // Fit -> Fill
        runtime.setVideoZoom(VideoZoom(scaleX = 1.33f))

        advanceToEpisodeTwo(runtime)

        assertEquals(PlayerResizeMode.Fill, runtime.resizeMode)
        assertEquals(VideoZoom(scaleX = 1.33f), runtime.videoZoom)
    }

    @Test
    fun reopeningTheSeriesRestoresItsZoomButAnotherSeriesStartsClean() {
        runtime().setVideoZoom(VideoZoom(scaleY = 1.2f, panY = -0.1f))

        assertEquals(VideoZoom(scaleY = 1.2f, panY = -0.1f), runtime().videoZoom)
        assertEquals(VideoZoom.IDENTITY, runtime(parentMetaId = "tt9999").videoZoom)
    }

    @Test
    fun resetZoomIsRememberedAsNoZoom() {
        val runtime = runtime()
        runtime.setVideoZoom(VideoZoom(scaleX = 1.5f))
        runtime.setVideoZoom(VideoZoom.IDENTITY)

        assertEquals(VideoZoom.IDENTITY, runtime().videoZoom)
        assertNull(PlayerTrackPreferenceStorage.load("tt2026")?.zoomScaleX)
    }

    @Test
    fun rememberOffWritesNothingAndRestoresNothing() {
        val off = runtime(remember = false)
        off.setVideoZoom(VideoZoom(scaleX = 1.5f))
        off.persistInternalSubtitlePreference(null)

        assertNull(PlayerTrackPreferenceStorage.load("tt2026"))
        assertEquals(VideoZoom.IDENTITY, runtime(remember = false).videoZoom)
    }

    @Test
    fun rememberOffIgnoresAnEarlierSeriesMemory() {
        runtime().persistInternalSubtitlePreference(null) // remembered while on: subtitles off
        val off = runtime(remember = false)
        val controller = advanceToEpisodeTwo(off)

        off.refreshTracks()

        // The preferred language (en) applies instead of the remembered "off".
        assertEquals(listOf(episodeTwoSubtitle.url), controller.subtitleUrls)
    }

    private fun advanceToEpisodeTwo(runtime: PlayerScreenRuntime): RecordingController {
        runtime.switchToEpisodeStream(
            StreamItem(url = "https://example.com/episode-2.mp4", addonName = "Test", addonId = "test"),
            MetaVideo(id = "tt2026:1:2", title = "Episode 2", season = 1, episode = 2),
        )
        runtime.resetIdentityStateIfNeeded()
        runtime.addonSubtitles = listOf(episodeTwoSubtitle)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        return RecordingController().also {
            runtime.playerController = it
            runtime.playerControllerSourceUrl = runtime.activeSourceUrl
        }
    }

    private fun runtime(remember: Boolean = true, parentMetaId: String = "tt2026") = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://example.com/episode-1.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Episode 1",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt2026:1:1",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerPreferenceMemoryTest.scope
        // No audio tracks here, so the forced-subtitles default (ON since upstream a6065c8b1) would hold
        // back the automatic pick; same fixture change upstream made in PlayerSubtitleRestoreTest.
        playerSettingsUiState = PlayerSettingsUiState(
            preferredSubtitleLanguage = "en",
            rememberPlayerPreferences = remember,
            subtitleStyle = SubtitleStyleState(useForcedSubtitles = false),
        )
        addonSubtitles = listOf(episodeOneSubtitle)
        playerController = RecordingController()
        resetIdentityStateIfNeeded()
    }

    private fun subtitle(episode: Int) = AddonSubtitle(
        id = "episode-$episode",
        url = "https://example.com/episode-$episode.srt",
        language = "en",
        display = "English",
        addonName = "Test",
    )

    private class RecordingController : PlayerEngineController {
        val subtitleUrls = mutableListOf<String>()
        val subtitleSelections = mutableListOf<Int>()

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
        }

        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
