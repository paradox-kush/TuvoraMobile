package com.nuvio.app.features.player

import com.nuvio.app.core.contracts.LiveChannelInfo
import com.nuvio.app.core.contracts.LivePlaybackProvider
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B257 regression: every Stalker live tune minted TWO create_link links. The launch (App.kt
 * playLiveXtreamChannel) resolved the blank Stalker URL with liveStreamUrlForAsync, then the Live TV
 * screen — which never plays the payload URL — resolved again. The old launch called the resolver
 * for every blank URL, so the single-use cases below counted 1 launch resolve (2 per tune) and failed.
 */
class LiveChannelLauncherTest {

    /** The Live TV screen resolves the channel once on open (LiveTvScreen's resolve effect), whatever the launch did. */
    private val screenResolvesPerTune = 1

    private class CountingProvider(
        private val singleUse: Boolean,
        private val resolved: String?,
    ) : LivePlaybackProvider {
        var asyncResolves = 0
        override fun accountNameFor(contentId: String): String? = null
        override fun liveStreamUrlFor(contentId: String): String? = null
        override suspend fun liveStreamUrlForAsync(contentId: String): String? {
            asyncResolves++
            return resolved
        }
        override fun liveLinkIsSingleUse(contentId: String): Boolean = singleUse
        override fun channelInfoFor(contentId: String): LiveChannelInfo? = null
        override fun recordingPreview(contentId: String): MetaPreview? = null
    }

    private class Recorder {
        val launches = mutableListOf<String>()
        var unavailable = 0
    }

    private fun kotlinx.coroutines.test.TestScope.tune(provider: LivePlaybackProvider, knownUrl: String?): Recorder {
        val recorder = Recorder()
        LiveChannelLauncher.tune(
            provider = provider,
            contentId = "xtream:acc:live:7",
            knownUrl = knownUrl,
            scope = this,
            onLaunch = { recorder.launches += it },
            onUnavailable = { recorder.unavailable++ },
        )
        advanceUntilIdle()
        return recorder
    }

    @Test
    fun `a Stalker tune mints exactly one link in all`() = runTest {
        val portal = CountingProvider(singleUse = true, resolved = "http://portal/play/live.php?token=one-shot")
        val recorder = tune(portal, knownUrl = "")

        assertEquals(0, portal.asyncResolves, "the launch must leave the mint to the Live TV screen")
        assertEquals(1, portal.asyncResolves + screenResolvesPerTune, "one Stalker tune = one create_link")
        assertEquals(listOf(LiveChannelLauncher.SCREEN_RESOLVES_URL), recorder.launches, "the screen still opens")
        assertEquals(0, recorder.unavailable)
    }

    @Test
    fun `a Stalker tune with a null url also leaves the mint to the screen`() = runTest {
        val portal = CountingProvider(singleUse = true, resolved = "http://portal/play/live.php?token=one-shot")
        val recorder = tune(portal, knownUrl = null)

        assertEquals(0, portal.asyncResolves, "a null url is the same placeholder as a blank one")
        assertEquals(1, recorder.launches.size)
    }

    @Test
    fun `an M3U channel still resolves at launch so an unavailable channel toasts`() = runTest {
        val playlist = CountingProvider(singleUse = false, resolved = null)
        val recorder = tune(playlist, knownUrl = "")

        assertEquals(1, playlist.asyncResolves, "a reusable link keeps the early unavailable check")
        assertEquals(emptyList<String>(), recorder.launches)
        assertEquals(1, recorder.unavailable, "a failed resolve is never a silent no-op")
    }

    @Test
    fun `an M3U channel that resolves launches with its url`() = runTest {
        val playlist = CountingProvider(singleUse = false, resolved = "http://cdn/line.m3u8")
        val recorder = tune(playlist, knownUrl = "  ")

        assertEquals(1, playlist.asyncResolves)
        assertEquals(listOf("http://cdn/line.m3u8"), recorder.launches)
    }

    @Test
    fun `a known url launches without any resolve`() = runTest {
        val xtream = CountingProvider(singleUse = false, resolved = "http://never")
        val recorder = tune(xtream, knownUrl = "http://host/live/u/p/1.ts")

        assertEquals(0, xtream.asyncResolves)
        assertEquals(listOf("http://host/live/u/p/1.ts"), recorder.launches)
    }

    @Test
    fun `plan for a blank single-use url is screen resolves`() {
        assertEquals(LiveChannelLaunchPolicy.Plan.LaunchScreenResolves, LiveChannelLaunchPolicy.planFor("", singleUseLink = true))
        assertEquals(LiveChannelLaunchPolicy.Plan.ResolveThenLaunch, LiveChannelLaunchPolicy.planFor(null, singleUseLink = false))
        assertEquals(LiveChannelLaunchPolicy.Plan.LaunchNow("http://a"), LiveChannelLaunchPolicy.planFor("http://a", singleUseLink = true))
    }
}
