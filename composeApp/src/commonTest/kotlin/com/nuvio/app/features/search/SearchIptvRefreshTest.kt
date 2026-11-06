package com.nuvio.app.features.search

import com.nuvio.app.core.contracts.IptvCatalog
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.SearchProviderRegistry
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.core.contracts.IptvSearchProvider
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * UX15: Search kept showing the old IPTV rows after the viewer changed a playlist's content
 * settings (content types, categories, hidden channels/groups), because the request key only
 * recorded whether IPTV was on — so the same query was "reused" against stale IPTV results.
 */
class SearchIptvRefreshTest {

    private val catalog = FakeCatalog()
    private val iptv = FakeIptvSearch()

    @BeforeTest
    fun setUp() {
        SearchRepository.reset()
        IptvCatalogAccess.register(catalog)
        resetAllSourceRegistriesForTest()
        SearchProviderRegistry.register("test-iptv", iptv)
    }

    @AfterTest
    fun tearDown() {
        SearchRepository.reset()
        IptvCatalogAccess.unregisterForTest()
        resetAllSourceRegistriesForTest()
    }

    @Test
    fun `changed IPTV content settings refresh the shown IPTV rows`() = runBlocking {
        iptv.rowsFor = { query -> listOf(moviesRow("$query (old)")) }
        SearchRepository.search(query = "Matrix", addons = emptyList())
        awaitIptvItems(listOf("Matrix (old)"))

        // The viewer narrows a playlist's Movies categories, then comes back to Search.
        iptv.rowsFor = { query -> listOf(moviesRow("$query (new)")) }
        iptv.signature.value = "sig-B"
        SearchRepository.search(query = "Matrix", addons = emptyList())

        assertFalse(SearchRepository.uiState.value.isLoading, "IPTV-only change keeps the shown rows, no full re-run")
        awaitIptvItems(listOf("Matrix (new)"))
        assertEquals(listOf("Matrix", "Matrix"), iptv.queriesSnapshot(), "IPTV lane re-ran for the shown query")
    }

    @Test
    fun `an unchanged IPTV source set reuses the shown search`() = runBlocking {
        iptv.rowsFor = { query -> listOf(moviesRow(query)) }
        SearchRepository.search(query = "Matrix", addons = emptyList())
        awaitIptvItems(listOf("Matrix"))

        SearchRepository.search(query = "Matrix", addons = emptyList())

        assertEquals(listOf("Matrix"), iptv.queriesSnapshot(), "no second IPTV search")
        assertEquals(listOf("Matrix"), iptvItems(), "rows untouched")
    }

    @Test
    fun `settings that leave no IPTV hit clear the stale IPTV row`() = runBlocking {
        iptv.rowsFor = { query -> listOf(moviesRow(query)) }
        SearchRepository.search(query = "Matrix", addons = emptyList())
        awaitIptvItems(listOf("Matrix"))

        iptv.rowsFor = { emptyList() }
        iptv.signature.value = "sig-B"
        SearchRepository.search(query = "Matrix", addons = emptyList())

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { SearchRepository.uiState.first { it.sections.isEmpty() && !it.isLoading } }
        }
        assertEquals(SearchEmptyStateReason.NoResults, SearchRepository.uiState.value.emptyStateReason)
    }

    @Test
    fun `F85 a zero-hit IPTV answer marks the lane answered`() = runBlocking {
        iptv.rowsFor = { emptyList() }
        SearchRepository.search(query = "kapil", addons = emptyList())

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { SearchRepository.uiState.first { !it.isLoading && it.iptvAnswered } }
        }
        assertEquals(emptyList(), SearchRepository.uiState.value.sections.map { it.key }, "no rows")
    }

    @Test
    fun `F85 a throwing IPTV provider is not an answer`() = runBlocking {
        iptv.rowsFor = { error("provider down") }
        SearchRepository.search(query = "kapil", addons = emptyList())

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { SearchRepository.uiState.first { !it.isLoading && it.emptyStateReason != null } }
        }
        assertFalse(SearchRepository.uiState.value.iptvAnswered, "a failure must not read as zero hits")
    }

    private fun iptvItems(): List<String> =
        SearchRepository.uiState.value.sections.filter { it.key.startsWith("xtream_") }.flatMap { s -> s.items.map { it.name } }

    private suspend fun awaitIptvItems(expected: List<String>) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                SearchRepository.uiState.first { state ->
                    !state.isLoading &&
                        state.sections.filter { it.key.startsWith("xtream_") }.flatMap { s -> s.items.map { it.name } } == expected
                }
            }
        }
    }

    private fun moviesRow(name: String) = HomeCatalogSection(
        key = "xtream_movies",
        title = "IPTV Movies",
        subtitle = "IPTV",
        addonName = "IPTV",
        target = CatalogTarget.Library(contentType = "movie", sectionType = "xtream"),
        items = listOf(MetaPreview(id = "xtream:acc:vod:$name", type = "movie", name = name)),
    )

    private class FakeCatalog : IptvCatalog {
        override fun ensureLoaded() = Unit
        override fun hasEnabledAccounts(): Boolean = true
        override val enabledAccountCount: Int = 1
        override fun warmUpMatchIndexes(startDelayMs: Long) = Unit
        override suspend fun refreshDuePlaylists() = Unit
        override val servedStreamTypes: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val hasAnyPlaylist: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeIptvSearch : IptvSearchProvider {
        var rowsFor: (String) -> List<HomeCatalogSection> = { emptyList() }
        private val queries = mutableListOf<String>()
        /** The playlists' settings fingerprint; null = no enabled playlist. */
        val signature = MutableStateFlow<String?>("sig-A")

        fun queriesSnapshot(): List<String> = queries.toList()

        override fun isEnabled(): Boolean = true

        override suspend fun search(query: String): List<HomeCatalogSection> {
            queries += query
            return rowsFor(query)
        }

        override fun sourceSignature(): String? = signature.value
        override fun sourceSignatureChanges(): Flow<String?> = signature
    }
}
