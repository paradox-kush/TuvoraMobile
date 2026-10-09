package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.contracts.StreamSourceAccess
import com.nuvio.app.core.contracts.StreamSourceRegistry
import com.nuvio.app.core.contracts.StreamSourceGroup
import com.nuvio.app.core.contracts.StreamSourceProvider
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wave 3 / P0 bug fix. A matched-lane Stalker source is LISTED with a deferred (not yet minted) url -
 * listing must stay free, the link is minted only for the edition the viewer picks. Every pick site
 * minted except the in-player source switch (and episode switch), which handed the engine the
 * "stalker-deferred:..." placeholder. The pick must mint first, and on a failed mint must leave the
 * playing source alone.
 */
class PlayerDeferredSourceSwitchTest {

    private class FakeSources(var minted: String?) : StreamSourceProvider {
        var mintCalls = 0
        override fun isHandledId(videoId: String?) = false
        override fun isStalkerSource(videoId: String) = false
        override fun directStreamItem(videoId: String): StreamItem? = null
        override fun matchSourceGroups(type: String) = emptyList<StreamSourceGroup>()
        override suspend fun resolveMatchStreams(
            sourceId: String, type: String, videoId: String, season: Int?, episode: Int?,
        ) = emptyList<StreamItem>()
        override fun isMatchSourceId(providerAddonId: String) = providerAddonId.startsWith("fake-match:")
        override fun isDeferredUrl(url: String?) = url != null && url.startsWith("fake-deferred:")
        override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? {
            mintCalls++
            return minted
        }
    }

    @AfterTest
    fun tearDown() = StreamSourceAccess.resetForTest()

    private fun deferredStream() = StreamItem(
        name = "Edition", url = "fake-deferred:42", addonName = "Home portal", addonId = "fake-match:acc",
    )

    @Test
    fun `picking a deferred source in the player mints its link before switching`() = runTest {
        val sources = FakeSources(minted = "https://cdn.example/minted.mp4")
        StreamSourceRegistry.register("fake", sources)
        val runtime = PlayerScreenRuntime(testArgs()).apply { scope = this@runTest }

        runtime.switchToSource(deferredStream())
        advanceUntilIdle()

        assertEquals("https://cdn.example/minted.mp4", runtime.activeSourceUrl, "the engine gets the minted url")
        assertEquals("fake-match:acc", runtime.activeProviderAddonId)
        assertEquals(1, sources.mintCalls, "exactly one mint for the picked edition")
    }

    @Test
    fun `a failed mint leaves the playing source untouched`() = runTest {
        val sources = FakeSources(minted = null)
        StreamSourceRegistry.register("fake", sources)
        val runtime = PlayerScreenRuntime(testArgs()).apply {
            scope = this@runTest
            resolveText = { "resolve failed" }
        }

        runtime.switchToSource(deferredStream())
        advanceUntilIdle()

        assertEquals("https://example.com/video.mp4", runtime.activeSourceUrl, "never the deferred placeholder")
        assertEquals(1, sources.mintCalls)
        assertEquals("resolve failed", NuvioToastController.currentToast.value?.message, "the viewer is told why nothing changed")
    }

    @Test
    fun `a plain source switches immediately without minting`() = runTest {
        val sources = FakeSources(minted = "unused")
        StreamSourceRegistry.register("fake", sources)
        val runtime = PlayerScreenRuntime(testArgs()).apply { scope = this@runTest }

        runtime.switchToSource(StreamItem(url = "https://cdn.example/other.mp4", addonName = "A", addonId = "addon"))
        advanceUntilIdle()

        assertEquals("https://cdn.example/other.mp4", runtime.activeSourceUrl)
        assertEquals(0, sources.mintCalls)
    }

    @Test
    fun `picking a deferred source for an episode mints its link before switching`() = runTest {
        val sources = FakeSources(minted = "https://cdn.example/ep2.mp4")
        StreamSourceRegistry.register("fake", sources)
        val runtime = PlayerScreenRuntime(testArgs()).apply { scope = this@runTest }
        val episode = MetaVideo(id = "tt1234567:1:2", title = "Two", season = 1, episode = 2)

        runtime.switchToEpisodeStream(deferredStream(), episode)
        advanceUntilIdle()

        assertEquals("https://cdn.example/ep2.mp4", runtime.activeSourceUrl)
        assertEquals("tt1234567:1:2", runtime.activeVideoId)
        assertEquals(1, sources.mintCalls)
    }

    private fun testArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
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
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}
