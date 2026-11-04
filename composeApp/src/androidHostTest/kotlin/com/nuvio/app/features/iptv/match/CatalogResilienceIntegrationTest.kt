package com.nuvio.app.features.iptv.match

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.core.journal.StartupJournal
import com.nuvio.app.core.journal.StartupJournalStore
import com.nuvio.app.features.iptv.IptvTransport
import com.nuvio.app.features.iptv.InMemoryServerFailoverStateStore
import com.nuvio.app.features.iptv.PlaylistServerFailover
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.tmdb.TmdbTitleBundle
import kotlinx.coroutines.CompletableDeferred
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
 * Regression (2026-11-02, owner's iPad + TV): two real Xtream panels answered a full `get_series` with a 200
 * whose body stopped mid-JSON (24 KB / 110 KB) or trickled at ~0.45 KB/s. Every lookup waited on that catalog,
 * the source lane cut it, and every title read "No streams found" — even titles another device had already
 * matched. The real resolver, index and client over a fake panel ([IptvTransport] seam), in-memory SQLite.
 */
class CatalogResilienceIntegrationTest {

    private fun account(id: String) = XtreamAccount(
        id = "http://$id.test|u", name = id, baseUrl = "http://$id.test", username = "u", password = "p",
    )

    /** Hung downloads wait here; tearDown opens it so a leaked build releases the global build permit. */
    private val gate = CompletableDeferred<Unit>()

    private val undekhi = TmdbTitleBundle(primary = "Undekhi", original = null, alternatives = emptyList(), year = 2020)

    private class Panel(private val answer: suspend (action: String?, categoryId: String?) -> String) : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String {
            val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
            val category = Regex("category_id=([^&]+)").find(url)?.groupValues?.get(1)
            if (action == null) return """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""
            return answer(action, category)
        }

        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) {
            getText(url, dnsProvider).lines().forEach(onLine)
        }
    }

    @BeforeTest
    fun setUp() {
        // Without a healthy StartupJournal the resolver withholds every build (beginAttempt == null), so a
        // hanging download would never be awaited and the test could not tell blocking from not blocking.
        // The journal latches `initialized`/`healthy` process-wide: any earlier test class that touched it
        // with no storage left it permanently unhealthy. Reset, then point it at a fresh temp dir.
        StartupJournal.resetForTests()
        StartupJournalStore.initializeForTest(java.nio.file.Files.createTempDirectory("journal").toFile())
        runBlocking { XtreamTmdbResolver.resetForTests() }
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        PlaylistServerFailover.installForTest(store = InMemoryServerFailoverStateStore(), clock = { 1_000L }, profileId = { 1 })
    }

    @AfterTest
    fun tearDown() {
        IptvTransport.current = IptvTransport.Platform
        gate.complete(Unit)
        // Let the builds the hung tests leaked finish (they release their global build permit and stop
        // touching the journal) BEFORE the next test swaps in its own storage.
        runBlocking { withTimeoutOrNull(5_000) { XtreamTmdbResolver.resetForTests() } }
        PlaylistServerFailover.resetForTest()
    }

    @Test
    fun a_remembered_match_plays_while_the_catalog_download_hangs() = runBlocking {
        val acc = account("hang")
        IptvTransport.current = Panel { action, _ ->
            when (action) {
                "get_series_categories" -> """[{"category_id":"1","category_name":"Hindi"}]"""
                else -> { gate.await(); "[]" } // the full catalog never arrives
            }
        }
        XtreamMatchIndex.putMapping(acc.id, MatchKind.SERIES, tmdb = 777, sid = 42, matchedName = "Undekhi")

        val match = withTimeoutOrNull(3_000) { XtreamTmdbResolver.resolve(acc, MatchKind.SERIES, 777, undekhi) }

        assertNotNull(match, "the lookup must not wait for a catalog that never lands")
        assertEquals(42, match.item.sid)
    }

    @Test
    fun a_lookup_with_nothing_remembered_answers_at_once_and_reports_the_catalog_pending() = runBlocking {
        val acc = account("pending")
        IptvTransport.current = Panel { action, _ ->
            when (action) {
                "get_series_categories" -> """[{"category_id":"1","category_name":"Hindi"}]"""
                else -> { gate.await(); "[]" }
            }
        }

        val answered = withTimeoutOrNull(3_000) { XtreamTmdbResolver.resolve(acc, MatchKind.SERIES, 888, undekhi); true }

        assertEquals(true, answered, "the lookup must answer without waiting for the catalog")
        assertTrue(XtreamTmdbResolver.catalogPending(acc.id, MatchKind.SERIES))
    }

    @Test
    fun a_cut_off_full_list_keeps_what_arrived_and_the_category_walk_completes_the_catalog() = runBlocking {
        val acc = account("cutoff")
        IptvTransport.current = Panel { action, category ->
            when {
                action == "get_series_categories" ->
                    """[{"category_id":"1","category_name":"English"},{"category_id":"2","category_name":"Hindi"}]"""
                // A 200 whose body stops mid-JSON, as measured on the real panel.
                action == "get_series" && category == null ->
                    """[{"series_id":1,"name":"Alpha Show","category_id":"1"},{"series_id":5,"name":"Undek"""
                action == "get_series" && category == "1" -> """[{"series_id":1,"name":"Alpha Show","category_id":"1"}]"""
                action == "get_series" && category == "2" -> """[{"series_id":5,"name":"Undekhi","category_id":"2"}]"""
                else -> "[]"
            }
        }

        XtreamTmdbResolver.ensureIndexed(acc, MatchKind.SERIES)

        assertNotNull(XtreamMatchIndex.builtAt(acc.id, MatchKind.SERIES), "a complete category walk stamps the catalog built")
        val hits = XtreamMatchIndex.searchByName(acc.id, MatchKind.SERIES, "Undekhi", 5)
        assertEquals(listOf(5), hits.map { it.sid })
    }

    @Test
    fun a_full_list_that_never_finishes_hits_its_deadline_and_the_category_walk_completes_the_catalog() = runBlocking {
        val acc = account("trickle")
        XtreamTmdbResolver.deadlineOverrideMsForTests = 500L
        IptvTransport.current = Panel { action, category ->
            when {
                action == "get_series_categories" ->
                    """[{"category_id":"1","category_name":"English"},{"category_id":"2","category_name":"Hindi"}]"""
                // A provider that trickles: the full list never finishes and no read timeout ever trips.
                action == "get_series" && category == null -> { gate.await(); "[]" }
                action == "get_series" && category == "1" -> """[{"series_id":1,"name":"Alpha Show","category_id":"1"}]"""
                action == "get_series" && category == "2" -> """[{"series_id":5,"name":"Undekhi","category_id":"2"}]"""
                else -> "[]"
            }
        }

        val finished = withTimeoutOrNull(8_000) { XtreamTmdbResolver.ensureIndexed(acc, MatchKind.SERIES); true }

        assertEquals(true, finished, "the build must not wait forever on a full list that never finishes")
        assertNotNull(XtreamMatchIndex.builtAt(acc.id, MatchKind.SERIES), "the category walk completes and stamps the catalog built")
        assertEquals(listOf(5), XtreamMatchIndex.searchByName(acc.id, MatchKind.SERIES, "Undekhi", 5).map { it.sid })
    }
}
