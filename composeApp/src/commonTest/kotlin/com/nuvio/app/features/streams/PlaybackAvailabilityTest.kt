package com.nuvio.app.features.streams

import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginsUiState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackAvailabilityTest {
    // Which content ids are "own source" ids is the registered OwnSourcePolicy (no literal in the code
    // under test any more): wire the IPTV namespace the way the composition root does.
    @BeforeTest
    fun wireOwnSources() {
        resetAllSourceRegistriesForTest()
        OwnSourcePolicy.registerContentIdPredicate("test-iptv") { it.startsWith("xtream:") }
    }

    @AfterTest
    fun unwireOwnSources() = resetAllSourceRegistriesForTest()

    @Test
    fun `empty setup and catalog only addons do not enable playback`() {
        assertFalse(available())
        assertFalse(available(addons = listOf(addon(resource = "catalog"))))
        assertFalse(available(addons = listOf(addon(resource = "meta"))))
    }

    @Test
    fun `a compatible stream addon enables playback without plugins`() {
        assertTrue(available(addons = listOf(addon())))
        assertFalse(available(addons = listOf(addon().copy(enabled = false))))
        assertFalse(available(addons = listOf(addon().copy(manifest = null))))
    }

    @Test
    fun `play is available while an addon manifest is still loading (B162)`() {
        // The "Playback unavailable" label used to show for the minutes an add-on's manifest was still loading.
        val loading = ManagedAddon(manifestUrl = "https://example.com/slow/manifest.json", isRefreshing = true)
        assertTrue(PlaybackAvailability(addons = listOf(loading), plugins = PluginsUiState(pluginsEnabled = false)).canStream("movie", "tt123"))
        val failedToLoad = ManagedAddon(manifestUrl = "https://example.com/dead/manifest.json", errorMessage = "timeout")
        assertTrue(PlaybackAvailability(addons = listOf(failedToLoad), plugins = PluginsUiState(pluginsEnabled = false)).canStream("movie", "tt123"))
        assertFalse(
            PlaybackAvailability(addons = listOf(loading.copy(enabled = false)), plugins = PluginsUiState(pluginsEnabled = false)).canStream("movie", "tt123"),
            "a switched-off add-on proves nothing",
        )
    }

    @Test
    fun `stream addons must support the requested type and id`() {
        val addons = listOf(addon())
        assertFalse(available(addons = addons, type = "series"))
        assertFalse(available(addons = addons, videoId = "kitsu:123"))
        assertTrue(available(addons = listOf(addon(prefixes = emptyList())), videoId = "kitsu:123"))
    }

    @Test
    fun `an enabled compatible plugin enables playback without addons`() {
        val plugins = PluginsUiState(scrapers = listOf(scraper()))
        assertTrue(available(plugins = plugins))
        assertTrue(available(plugins = plugins, type = "series"))
        assertFalse(available(plugins = plugins, type = "channel"))
        assertFalse(available(plugins = plugins.copy(pluginsEnabled = false)))
        assertFalse(available(plugins = plugins.copy(scrapers = listOf(scraper().copy(enabled = false)))))
    }

    @Test
    fun `either source can enable playback independently`() {
        assertTrue(available(
            addons = listOf(addon(resource = "catalog")),
            plugins = PluginsUiState(scrapers = listOf(scraper())),
        ))
        assertTrue(available(
            addons = listOf(addon()),
            plugins = PluginsUiState(pluginsEnabled = false, scrapers = listOf(scraper())),
        ))
    }

    @Test
    fun `iptv items are playable with no addons or plugins`() {
        // Regression (2026-09-27): upstream's play-disable check greyed out Play/Resume for every
        // Xtream/Stalker/M3U item for IPTV-only users (store builds hide addons). The IPTV resolver
        // plays these ids directly, so they never need an addon or scraper. TV twin: 96324df71.
        val none = PlaybackAvailability(addons = emptyList(), plugins = PluginsUiState(pluginsEnabled = false))
        assertTrue(none.canStream("movie", "xtream:acc1:vod:42"), "xtream vod")
        assertTrue(none.canStream("series", "xtream:acc1:episode:7"), "xtream episode")
        assertFalse(none.canStream("movie", "tt123"), "a plain catalog id still needs a source")
    }

    @Test
    fun `iptv source lane counts as a source only for the content types it serves`() {
        // An IPTV account with movies enabled supplies streams for addon-catalog titles (tt/tmdb ids)
        // through the IPTV source lane, the same way a scraper supporting "movie" does.
        val moviesOnly = PlaybackAvailability(
            addons = emptyList(),
            plugins = PluginsUiState(pluginsEnabled = false),
            iptvSourceTypes = setOf("movie"),
        )
        assertTrue(moviesOnly.canStream("movie", "tt123"), "movie via iptv lane")
        assertFalse(moviesOnly.canStream("series", "tt123:1:1"), "series not served")
    }

    private fun available(
        addons: List<ManagedAddon> = emptyList(),
        plugins: PluginsUiState = PluginsUiState(),
        type: String = "movie",
        videoId: String = "tt123",
    ): Boolean = hasCompatiblePlaybackSource(addons, plugins, type, videoId)

    private fun addon(
        resource: String = "stream",
        prefixes: List<String> = listOf("tt"),
    ): ManagedAddon = ManagedAddon(
        manifestUrl = "https://example.com/manifest.json",
        manifest = AddonManifest(
            id = "test",
            name = "Test",
            description = "",
            version = "1.0.0",
            resources = listOf(AddonResource(resource, listOf("movie"), prefixes)),
            types = listOf("movie"),
            transportUrl = "https://example.com",
        ),
    )

    private fun scraper(): PluginScraper = PluginScraper(
        id = "test",
        repositoryUrl = "https://example.com/plugins.json",
        name = "Test",
        description = "",
        version = "1.0.0",
        filename = "test.js",
        supportedTypes = listOf("movie", "tv"),
        enabled = true,
        manifestEnabled = true,
        code = "",
    )
}
