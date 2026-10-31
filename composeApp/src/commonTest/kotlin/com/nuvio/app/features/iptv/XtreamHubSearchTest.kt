package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F48 — the IPTV hub's Movies/Series search inside one playlist. The decisions are pure
 * ([IptvHubSearchPolicy]); the controller adds the debounce, the stale-answer guard and the
 * Loaded / Empty / Failed outcome (a failed search is never "no matches").
 */
class XtreamHubSearchTest {
    private val account = XtreamAccount(
        id = "acc1", name = "Home", baseUrl = "http://panel.example:8080", username = "u", password = "p",
    )
    private fun hit(name: String) = MetaPreview(id = "x:$name", type = "movie", name = name)

    // --- policy ---------------------------------------------------------------------------------

    @Test
    fun `every hub section searches its own content type`() {
        assertEquals(CONTENT_TYPE_LIVE, IptvHubSearchPolicy.contentTypeFor(XtreamHubSection.LIVE))
        assertEquals(CONTENT_TYPE_MOVIES, IptvHubSearchPolicy.contentTypeFor(XtreamHubSection.MOVIES))
        assertEquals(CONTENT_TYPE_SERIES, IptvHubSearchPolicy.contentTypeFor(XtreamHubSection.SERIES))
    }

    @Test
    fun `a query under two letters makes no request and spaces are trimmed`() {
        assertNull(IptvHubSearchPolicy.request(XtreamHubSection.MOVIES, ""))
        assertNull(IptvHubSearchPolicy.request(XtreamHubSection.MOVIES, " a "))
        assertEquals("ab", IptvHubSearchPolicy.request(XtreamHubSection.MOVIES, "  ab "))
        assertEquals("bbc one", IptvHubSearchPolicy.request(XtreamHubSection.LIVE, " bbc one "))
    }

    // --- controller -----------------------------------------------------------------------------

    private fun controller(
        calls: MutableList<Triple<String, String, String>>,
        search: suspend (XtreamAccount, String, String) -> List<MetaPreview>,
    ) = XtreamHubSearchController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        debounceMs = 40L,
        search = { acc, type, q -> calls += Triple(acc.id, type, q); search(acc, type, q) },
    )

    private suspend fun XtreamHubSearchController.settled(): HubSearchUiState =
        withTimeout(5_000) { state.first { it.load !is LoadStatus.Loading && it.load != LoadStatus.Idle } }

    @Test
    fun `typing a short query never calls the provider`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val c = controller(calls) { _, _, _ -> emptyList() }
        c.onQueryChange(account, XtreamHubSection.MOVIES, "a")
        delay(150)
        assertTrue(calls.isEmpty())
        assertEquals(LoadStatus.Idle, c.state.value.load)
    }

    @Test
    fun `fast typing sends one request for the final text`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val c = controller(calls) { _, _, q -> listOf(hit(q)) }
        c.onQueryChange(account, XtreamHubSection.MOVIES, "ma")
        c.onQueryChange(account, XtreamHubSection.MOVIES, "mat")
        c.onQueryChange(account, XtreamHubSection.MOVIES, "matrix")
        val done = c.settled()
        assertEquals(listOf(Triple("acc1", CONTENT_TYPE_MOVIES, "matrix")), calls)
        assertEquals(listOf("matrix"), done.items.map { it.name })
        assertEquals(LoadStatus.Loaded, done.load)
    }

    @Test
    fun `a slow answer for an old query cannot overwrite the newer one`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val firstStarted = CompletableDeferred<Unit>()
        val c = controller(calls) { _, _, q ->
            if (q == "old") { firstStarted.complete(Unit); delay(400); listOf(hit("STALE")) } else listOf(hit("fresh"))
        }
        c.onQueryChange(account, XtreamHubSection.SERIES, "old")
        firstStarted.await()
        c.onQueryChange(account, XtreamHubSection.SERIES, "new")
        val done = c.settled()
        delay(500)
        assertEquals(listOf("fresh"), done.items.map { it.name })
        assertEquals(listOf("fresh"), c.state.value.items.map { it.name })
    }

    @Test
    fun `no hits is Empty and a failed search is Failed - never Empty`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val empty = controller(calls) { _, _, _ -> emptyList() }
        empty.onQueryChange(account, XtreamHubSection.MOVIES, "zzzz")
        assertEquals(LoadStatus.Empty, empty.settled().load)

        val failing = controller(calls) { _, _, _ -> error("portal down") }
        failing.onQueryChange(account, XtreamHubSection.MOVIES, "matrix")
        val failed = failing.settled()
        assertIs<LoadStatus.Failed>(failed.load)
        assertTrue(failed.items.isEmpty())
    }

    @Test
    fun `retry asks again and recovers`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        var attempt = 0
        val c = controller(calls) { _, _, q -> if (attempt++ == 0) error("blip") else listOf(hit(q)) }
        c.onQueryChange(account, XtreamHubSection.MOVIES, "matrix")
        assertIs<LoadStatus.Failed>(c.settled().load)
        c.retry()
        val done = withTimeout(5_000) { c.state.first { it.load == LoadStatus.Loaded } }
        assertEquals(listOf("matrix"), done.items.map { it.name })
        assertEquals(2, calls.size)
    }

    @Test
    fun `switching section re-asks the new scope with the same text`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val c = controller(calls) { _, _, q -> listOf(hit(q)) }
        c.onQueryChange(account, XtreamHubSection.MOVIES, "bbc")
        c.settled()
        c.onScopeChange(account, XtreamHubSection.LIVE)
        withTimeout(5_000) { while (calls.size < 2) delay(10) }
        assertEquals(
            listOf(Triple("acc1", CONTENT_TYPE_MOVIES, "bbc"), Triple("acc1", CONTENT_TYPE_LIVE, "bbc")),
            calls,
        )
    }
}
