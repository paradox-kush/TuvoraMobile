package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonSubtitleIdPolicyTest {

    private val m3uEpisode = "xtream:m3u|http://panel.example/get.php?username=u&password=p:episode:991"

    @Test
    fun providerIdsAreScopedAndNeverRequested() {
        assertTrue(AddonSubtitleIdPolicy.isProviderScoped(m3uEpisode))
        assertFalse(AddonSubtitleIdPolicy.isProviderScoped("tt0388629:1:2"))
        assertNull(AddonSubtitleIdPolicy.requestVideoId(m3uEpisode, resolvedPublicId = null))
        assertEquals("tt0388629:1:2", AddonSubtitleIdPolicy.requestVideoId(m3uEpisode, "tt0388629:1:2"))
        assertEquals("tt0111161", AddonSubtitleIdPolicy.requestVideoId("tt0111161", resolvedPublicId = null))
    }

    @Test
    fun publicIdsFollowTheStremioConvention() {
        assertEquals("tt0111161", AddonSubtitleIdPolicy.publicVideoId("tt0111161", isSeries = false, null, null))
        assertEquals("tt0388629:3:12", AddonSubtitleIdPolicy.publicVideoId("TT0388629", isSeries = true, 3, 12))
        assertNull(AddonSubtitleIdPolicy.publicVideoId("tt0388629", isSeries = true, 3, null))
        assertNull(AddonSubtitleIdPolicy.publicVideoId("1111", isSeries = false, null, null))
        assertNull(AddonSubtitleIdPolicy.publicVideoId(null, isSeries = false, null, null))
        assertEquals("series", AddonSubtitleIdPolicy.requestType("movie", "tt0388629:3:12"))
        assertEquals("movie", AddonSubtitleIdPolicy.requestType("movie", "tt0111161"))
    }

    @Test
    fun anAddonWithoutIdPrefixesIsNeverGivenAProviderId() {
        // Privacy regression: an add-on declaring no idPrefixes matched every id, so the fetch key
        // (and then the request URL) carried the playlist credentials embedded in the IPTV id.
        val openAddon = ManagedAddon(
            manifestUrl = "https://subs.example/manifest.json",
            manifest = AddonManifest(
                id = "open.subs",
                name = "Open subs",
                description = "",
                version = "1.0.0",
                transportUrl = "https://subs.example",
                resources = listOf(AddonResource(name = "subtitles", types = emptyList(), idPrefixes = emptyList())),
                types = emptyList(),
            ),
        )
        assertNull(buildAddonSubtitleFetchKey(listOf(openAddon), "movie", m3uEpisode))
    }

    @Test
    fun anAddonSubtitleRequestCarriesOnlyTheTypeAndPublicId() {
        // T5 parity pin: NuvioTV once appended `filename=<stream file>` (the provider's stream id or a
        // token) to the add-on request. Mobile/Desktop/Apple TV build the URL from type + id alone;
        // a filename extra must never be added for an IPTV item.
        val url = com.nuvio.app.features.addons.buildAddonResourceUrl(
            manifestUrl = "https://subs.example/manifest.json",
            resource = "subtitles",
            type = "movie",
            id = "tt0133093",
        )
        assertEquals("https://subs.example/subtitles/movie/tt0133093.json", url)
    }

    @Test
    fun aMediaServerItemIdIsProviderScopedAndNeverReachesAnAddon() {
        // The ms: id embeds the server's own machine id and the user id; an add-on request URL must not carry them.
        com.nuvio.app.core.contracts.OwnSourcePolicy.resetForTest()
        com.nuvio.app.core.contracts.OwnSourcePolicy.registerContentIdPredicate("test") { it.startsWith("ms:") }
        try {
            val id = "ms:jellyfin:6f3c1a9e2b7d4c58a1e0f9d8c7b6a543:0f1e2d3c4b5a69788796a5b4c3d2e1f0:movie:abc123"
            assertTrue(AddonSubtitleIdPolicy.isProviderScoped(id))
            assertNull(AddonSubtitleIdPolicy.requestVideoId(id, resolvedPublicId = null))
            assertEquals("tt0133093", AddonSubtitleIdPolicy.requestVideoId(id, resolvedPublicId = "tt0133093"))
        } finally {
            com.nuvio.app.core.contracts.OwnSourcePolicy.resetForTest()
        }
    }

    @Test
    fun aProviderScopedStreamIsNeverProbedForAnOpenSubtitlesHash() {
        // The hash reads the first and last bytes of the stream URL, and an IPTV link can be single-use.
        assertFalse(AddonSubtitleIdPolicy.allowsStreamHashProbe("xtream:http://panel.example:8080|user|movie|42"))
        assertTrue(AddonSubtitleIdPolicy.allowsStreamHashProbe("tt0133093"))
        assertTrue(AddonSubtitleIdPolicy.allowsStreamHashProbe(null))
    }

    @Test
    fun aLiveOrCatchUpStreamIsNeverProbedForAnOpenSubtitlesHash() {
        // Catch-up replays stay streamType "live"; their link can be single-use.
        assertTrue(AddonSubtitleIdPolicy.isLivePlayback("live", null), "streamType live")
        assertTrue(AddonSubtitleIdPolicy.isLivePlayback(null, "LIVE"), "contentType live, any case")
        assertFalse(AddonSubtitleIdPolicy.isLivePlayback("movie", "movie"), "vod")
        assertFalse(AddonSubtitleIdPolicy.isLivePlayback(null, null), "unknown")
        assertFalse(
            AddonSubtitleIdPolicy.allowsStreamHashProbe("tt0133093", isLivePlayback = true),
            "a plain id on a live stream must not be probed",
        )
        assertTrue(AddonSubtitleIdPolicy.allowsStreamHashProbe("tt0133093", isLivePlayback = false), "vod still hashed")
    }

    @Test
    fun aMatchedOwnSourceStreamUnderAPublicIdIsNeverProbedForAnOpenSubtitlesHash() {
        // B255: an IPTV copy opened from a TMDB title has a public tt id, but its provider is an own source.
        val own: (String?) -> Boolean = { it == "iptv.provider" }
        assertFalse(
            AddonSubtitleIdPolicy.allowsStreamHashProbe("tt0133093", providerAddonId = "iptv.provider", isOwnProviderId = own),
            "own-source provider under a public id must not be probed",
        )
        assertTrue(
            AddonSubtitleIdPolicy.allowsStreamHashProbe("tt0133093", providerAddonId = "some.addon", isOwnProviderId = own),
            "an ordinary add-on stream is still hashed",
        )
        assertTrue(
            AddonSubtitleIdPolicy.allowsStreamHashProbe("tt0133093", providerAddonId = null, isOwnProviderId = own),
            "no provider known: the video-id rule alone decides",
        )
    }
}
