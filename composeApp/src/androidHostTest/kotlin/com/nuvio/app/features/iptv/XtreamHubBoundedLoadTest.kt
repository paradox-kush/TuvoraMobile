package com.nuvio.app.features.iptv

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.match.MatchDbDriver
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: the IPTV page (the phone's Live TV page) spun forever on a provider that never answered —
 * or trickled bytes so no socket timeout fired — and a row whose fetch failed read as an empty category
 * and vanished for the session. Reproduced on the emulator 2026-10-10 with an 11k-channel mock that stalls.
 * Every load now goes through [BoundedLoad]: it ends, and a failure is never "empty".
 *
 * The real hub repository and Xtream client over a fake provider ([IptvTransport] seam); deadlines are
 * shortened through [BoundedLoad.stallOverrideMsForTests].
 */
class XtreamHubBoundedLoadTest {

    private val account = XtreamAccount(
        id = "http://stall.test|u", name = "Stall", baseUrl = "http://stall.test", username = "u", password = "p",
    )

    private var categoryName = "News"
    private var hangCategories = false
    private var failCategoryStreamsTimes = 0
    private val categoryStreamAsks = AtomicInteger()

    private val provider = object : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String {
            val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
            return when {
                action == null -> """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""
                action == "get_live_categories" -> {
                    if (hangCategories) awaitCancellation()
                    """[{"category_id":"1","category_name":"$categoryName"}]"""
                }
                action == "get_live_streams" && "category_id=" in url -> {
                    if (categoryStreamAsks.incrementAndGet() <= failCategoryStreamsTimes) throw IOException("HTTP 503")
                    """[{"stream_id":7,"name":"BBC One","category_id":"1"}]"""
                }
                // The whole-catalog index build stalls, so the first-run network path is the one under test.
                else -> awaitCancellation()
            }
        }

        // Bulk lists arrive streamed: the same answers, line by line (as BackupServerFailoverIntegrationTest).
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) {
            getText(url, dnsProvider).lines().forEach(onLine)
        }
    }

    @BeforeTest
    fun setUp() {
        // Background index builds and the match index's connection are process-wide: start cold (B230).
        runBlocking { XtreamTmdbResolver.resetForTests(); XtreamMatchIndex.resetForTests() }
        BoundedLoad.stallOverrideMsForTests = 400L
        IptvTransport.current = provider
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        PlaylistServerFailover.installForTest(store = InMemoryServerFailoverStateStore(), clock = { 1_000L }, profileId = { 1 })
        XtreamRepository.installAccountsForTest(listOf(account))
        XtreamHubRepository.resetForProfile()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { XtreamTmdbResolver.resetForTests(); XtreamMatchIndex.resetForTests() }
        BoundedLoad.stallOverrideMsForTests = null
        IptvTransport.current = IptvTransport.Platform
        PlaylistServerFailover.resetForTest()
        XtreamRepository.installAccountsForTest(emptyList())
        XtreamHubRepository.resetForProfile()
    }

    private suspend fun openPage() {
        XtreamHubRepository.ensureLoaded()
        XtreamHubRepository.selectAccount(account.id)
        XtreamHubRepository.selectSection(XtreamHubSection.LIVE)
    }

    @Test
    fun `a committed refresh replaces a successfully displayed category`() = runBlocking {
        openPage()
        assertNotNull(withTimeoutOrNull(5_000) {
            XtreamHubRepository.uiState.first { it.categories.any { c -> c.name == "News" } }
        })
        categoryName = "Today events"
        IptvImportProgress.finished(account.id)
        val refreshed = withTimeoutOrNull(5_000) {
            XtreamHubRepository.uiState.first { it.categories.any { c -> c.name == "Today events" } }
        }
        assertNotNull(refreshed, "successful categories stayed cached after refresh")
        Unit
    }

    @Test
    fun `a provider that never answers the category list ends on the error card`() = runBlocking {
        hangCategories = true
        openPage()

        val ended = withTimeoutOrNull(5_000) {
            XtreamHubRepository.uiState.first { !it.loadingCategories && it.loadError != null }
        }

        assertNotNull(ended, "the page skeleton never ended - the provider held it forever")
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, ended.loadError?.kind)
    }

    @Test
    fun `a row whose fetch fails stays on the page instead of vanishing as empty`() = runBlocking {
        failCategoryStreamsTimes = Int.MAX_VALUE
        openPage()
        withTimeoutOrNull(5_000) { XtreamHubRepository.uiState.first { it.categories.any { c -> c.id == "1" } } }
        XtreamHubRepository.loadCategory("1")

        val row = withTimeoutOrNull(5_000) {
            XtreamHubRepository.uiState.first { st -> st.categories.firstOrNull { it.id == "1" }?.failed == true }
        }?.categories?.first { it.id == "1" }

        assertNotNull(row, "the failed row never settled")
        assertTrue(!row.loaded, "a failed row must not be marked loaded (the page hides loaded-and-empty rows)")
        XtreamHubRepository.loadCategory("1") // composing it again must not ask again
        assertEquals(1, categoryStreamAsks.get(), "a failed row is re-asked only by Retry or a finished import")
    }

    @Test
    fun `a failed row loads once its playlist import lands`() = runBlocking {
        failCategoryStreamsTimes = 1
        openPage()
        withTimeoutOrNull(5_000) { XtreamHubRepository.uiState.first { it.categories.any { c -> c.id == "1" } } }
        XtreamHubRepository.loadCategory("1")
        withTimeoutOrNull(5_000) {
            XtreamHubRepository.uiState.first { st -> st.categories.firstOrNull { it.id == "1" }?.failed == true }
        }

        IptvImportProgress.finished(account.id)

        val healed = withTimeoutOrNull(5_000) {
            XtreamHubRepository.uiState.first { st -> st.categories.firstOrNull { it.id == "1" }?.items?.isNotEmpty() == true }
        }
        assertNotNull(healed, "the row stayed failed after its catalog landed")
        assertEquals(2, categoryStreamAsks.get())
    }
}
