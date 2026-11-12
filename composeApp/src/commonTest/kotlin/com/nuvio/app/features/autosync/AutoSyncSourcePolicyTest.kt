package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AutoSync reads the STREAM itself (HTTP Range requests for the container's subtitle index). An own
 * source's link (IPTV / Stalker / Xtream, a device source) can be single-use or limited to one
 * connection, and a live or catch-up stream has no stable file: probing those burns the link the
 * player is about to use or drops the player. Sync-15 review defect.
 */
class AutoSyncSourcePolicyTest {
    // The provider-id namespaces the IPTV feature registers (direct lane + TMDB-matched lane).
    private val isOwnProviderId: (String?) -> Boolean = { id ->
        id == "xtream" || id?.startsWith("xtream-match:") == true
    }

    private fun mayProbe(
        activeVideoId: String? = "tt0111161",
        providerAddonId: String? = "addon:https://torrents.example/manifest.json",
        streamType: String? = null,
        contentType: String? = "movie",
        isCatchUpPlayback: Boolean = false,
        singleUseLink: Boolean = false,
    ) = AutoSyncSourcePolicy.mayProbe(
        activeVideoId = activeVideoId,
        providerAddonId = providerAddonId,
        streamType = streamType,
        contentType = contentType,
        isCatchUpPlayback = isCatchUpPlayback,
        singleUseLink = singleUseLink,
        isOwnProviderId = isOwnProviderId,
    )

    @Test
    fun aPlainAddonStreamMayBeProbed() {
        assertTrue(mayProbe(), "add-on movie")
        assertTrue(mayProbe(activeVideoId = "tt0388629:1:2", contentType = "series"), "add-on episode")
        assertTrue(mayProbe(providerAddonId = null), "unknown provider, public id")
    }

    @Test
    fun aStalkerMatchedStreamIsNeverProbed() {
        // IPTV VOD reached from a TMDB title: the content id is public, only the provider id says IPTV.
        assertFalse(
            mayProbe(providerAddonId = "xtream-match:stalker|http://portal.example|00:1A:79:00:00:01"),
            "stalker play_token is single-use",
        )
    }

    @Test
    fun anXtreamDirectLaneStreamIsNeverProbed() {
        assertFalse(mayProbe(providerAddonId = "xtream"), "direct-lane provider id")
        assertFalse(
            mayProbe(activeVideoId = "xtream:acc1:movie:991", providerAddonId = null),
            "provider-scoped content id",
        )
    }

    @Test
    fun anOwnSourceContentIdIsNeverProbed() {
        assertFalse(
            mayProbe(activeVideoId = "xtream:m3u|http://panel.example/get.php?username=u&password=p:episode:991"),
            "own-source content id with an add-on-looking provider",
        )
    }

    @Test
    fun aLiveStreamIsNeverProbed() {
        assertFalse(mayProbe(streamType = "live"), "live stream type")
        assertFalse(mayProbe(contentType = "live"), "live content type")
        assertFalse(mayProbe(singleUseLink = true), "single-use link")
    }

    @Test
    fun aCatchUpReplayIsNeverProbed() {
        assertFalse(mayProbe(isCatchUpPlayback = true), "catch-up flag")
        assertFalse(mayProbe(streamType = "live", isCatchUpPlayback = true), "catch-up down the live pipe")
    }

    @Test
    fun aGatedMpvCoordinatorAttachesTheOriginalAndNeverStartsAProbe() {
        val job = Job()
        val scope = CoroutineScope(job)
        val base = RecordingController()
        val coordinator = AutoSyncMpvCoordinator(
            scope = scope,
            sourceUrl = "http://portal.example/play/movie.php?play_token=abc",
            sourceHeaders = emptyMap(),
            getSubtitleHeaders = { emptyMap() },
            getPreferredLanguage = { "en" },
            platform = object : AutoSyncMpvPlatform {
                override suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String? = null
                override fun showMessage(text: String) {}
            },
            mayProbeSource = false,
        )
        try {
            coordinator.start(
                url = "https://subs.example/en.srt",
                candidateScope = AutoSyncCandidateScope.STARTUP_SEARCH,
                base = base,
                replaceExternalSubtitle = { _, _ -> },
            )
            assertEquals(listOf("https://subs.example/en.srt"), base.subtitleUris, "original subtitle attached")
            assertEquals(0, job.children.count(), "no prefetch and no AutoSync run launched")
        } finally {
            scope.cancel()
        }
    }

    private class RecordingController : PlayerEngineController {
        val subtitleUris = mutableListOf<String>()
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(offsetMs: Long) {}
        override fun retry() {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) {}
        override fun selectAudioTrack(index: Int) {}
        override fun selectSubtitleTrack(index: Int) {}
        override fun setSubtitleUri(url: String) { subtitleUris += url }
        override fun clearExternalSubtitle() {}
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {}
    }
}
