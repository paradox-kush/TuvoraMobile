package com.nuvio.app.features.search

import com.nuvio.app.core.contracts.IptvCatalog
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.IptvSearchProvider
import com.nuvio.app.core.contracts.SearchProviderRegistry
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX180: IPTV rows must show while an add-on that never answers is still pending (the add-on call
 * is only bounded by the 60 s text-request limit).
 */
class SearchIptvWhileAddonHangsTest {
    private lateinit var hangingServer: ServerSocket

    @BeforeTest
    fun setUp() {
        SearchRepository.reset()
        hangingServer = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        IptvCatalogAccess.register(FakeCatalog())
        resetAllSourceRegistriesForTest()
        SearchProviderRegistry.register("test-iptv", FakeIptv())
    }

    @AfterTest
    fun tearDown() {
        SearchRepository.reset()
        runCatching { hangingServer.close() }
        IptvCatalogAccess.unregisterForTest()
        resetAllSourceRegistriesForTest()
    }

    @Test
    fun `IPTV rows publish while an add-on never answers`() = runBlocking {
        val url = "http://127.0.0.1:${hangingServer.localPort}/manifest.json"
        val addon = ManagedAddon(
            manifestUrl = url,
            manifest = AddonManifest(
                id = "hang",
                name = "Hang",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "catalog", types = listOf("movie"))),
                types = listOf("movie"),
                catalogs = listOf(
                    AddonCatalog(type = "movie", id = "top", name = "Top", extra = listOf(AddonExtraProperty("search"))),
                ),
                transportUrl = url,
            ),
        )

        SearchRepository.search(query = "Matrix", addons = listOf(addon))

        val state = withContext(Dispatchers.Default) {
            withTimeout(5_000) { SearchRepository.uiState.first { s -> s.sections.any { it.key == "xtream_movies" } } }
        }
        assertTrue(state.isLoading, "the hanging add-on keeps the search loading")
    }

    private class FakeCatalog : IptvCatalog {
        override fun ensureLoaded() = Unit
        override fun hasEnabledAccounts(): Boolean = true
        override val enabledAccountCount: Int = 1
        override fun warmUpMatchIndexes(startDelayMs: Long) = Unit
        override suspend fun refreshDuePlaylists() = Unit
        override val servedStreamTypes: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val hasAnyPlaylist: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeIptv : IptvSearchProvider {
        private val signature = MutableStateFlow<String?>("sig")
        override fun sourceSignature(): String? = signature.value
        override fun sourceSignatureChanges(): Flow<String?> = signature
        override fun isEnabled(): Boolean = true
        override suspend fun search(query: String): List<HomeCatalogSection> = listOf(
            HomeCatalogSection(
                key = "xtream_movies",
                title = "IPTV Movies",
                subtitle = "IPTV",
                addonName = "IPTV",
                target = CatalogTarget.Library(contentType = "movie", sectionType = "xtream"),
                items = listOf(MetaPreview(id = "xtream:acc:vod:$query", type = "movie", name = query)),
            ),
        )
    }
}
