package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B294: a hub Movies/Series row reads ONE `get_ordered_list` page per window. The row used to page five
 * times before returning, which behind a 2-slot session gate outlasted the row's 20 s deadline. The
 * request COUNT is what pins it - the row "works" either way, it just times out on a busy portal.
 */
class StalkerRowWindowTest {

    private val requests = mutableListOf<String>()
    private val orderedPages = mutableListOf<Int>()
    private var failPage: Int? = null
    private var orderedReply: ((type: String, page: Int) -> String)? = null

    private fun rows(page: Int) = (1..14).joinToString(",") { """{"id":"${page * 100 + it}","name":"T$page-$it","cmd":"c"}""" }

    private val portal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
        requests += "$type/$action"
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_categories" -> """{"js":[{"id":"*","title":"All"},{"id":"7","title":"Drama"}]}"""
            "get_ordered_list" -> {
                val p = Regex("[&?]p=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                orderedPages += p
                if (p == failPage) throw IllegalStateException("portal timeout on page $p")
                orderedReply?.invoke(type, p) ?: """{"js":{"total_items":100,"max_page_items":14,"data":[${rows(p)}]}}"""
            }
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
    )

    @BeforeTest
    fun setUp() {
        com.nuvio.app.features.iptv.content.IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.sessionFactory = { StalkerSession(it, portal, { u, h, c -> c(portal(u, h)) }) }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
        StalkerClient.clearMemoryCachesForTest()
    }

    @Test
    fun `the first series window costs one get_ordered_list request`() = runBlocking {
        val window = StalkerClient.seriesWindow(account("rw-series1"), "7", 0)
        assertEquals(listOf(1), orderedPages, "one page, not five")
        assertEquals(1, requests.count { it == "series/get_ordered_list" })
        assertEquals(14, window.items.size)
        assertTrue(window.hasMore, "100 total, 14 read")
        assertEquals(14, window.consumed)
    }

    @Test
    fun `the next series window asks for page two`() = runBlocking {
        val acc = account("rw-series2")
        StalkerClient.seriesWindow(acc, "7", 0)
        orderedPages.clear()
        val window = StalkerClient.seriesWindow(acc, "7", 14)
        assertEquals(listOf(2), orderedPages)
        assertEquals(14, window.consumed)
        assertTrue(window.hasMore)
        assertEquals(201, window.items.first().seriesId, "page two rows")
    }

    @Test
    fun `the last series window says there is no more`() = runBlocking {
        val acc = account("rw-series-last")
        StalkerClient.seriesWindow(acc, "7", 0)
        orderedReply = { _, p -> """{"js":{"total_items":100,"max_page_items":14,"data":[{"id":"${p * 100}","name":"Last","cmd":"c"}]}}""" }
        val window = StalkerClient.seriesWindow(acc, "7", 98)
        assertEquals(listOf(1, 8), orderedPages, "offset 98 = page 8")
        assertEquals(false, window.hasMore, "8 * 14 >= 100")
    }

    @Test
    fun `a window after a process death falls back to the default page size`() = runBlocking {
        val acc = account("rw-series-cold")
        StalkerClient.seriesWindow(acc, "7", 0)
        StalkerClient.clearMemoryCachesForTest()
        orderedPages.clear()
        StalkerClient.seriesWindow(acc, "7", 28)
        assertEquals(listOf(3), orderedPages)
    }

    @Test
    fun `a failed series page throws instead of reading as empty`() = runBlocking {
        failPage = 2
        val acc = account("rw-series-fail")
        StalkerClient.seriesWindow(acc, "7", 0)
        val failure = runCatching { StalkerClient.seriesWindow(acc, "7", 14) }.exceptionOrNull()
        assertTrue(failure != null, "a failure is never an empty window")
    }

    @Test
    fun `a non-array reply is a failure not an empty window`() = runBlocking {
        orderedReply = { _, _ -> """{"js":[]}""" }
        val failure = runCatching { StalkerClient.seriesWindow(account("rw-series-nonobj"), "7", 0) }.exceptionOrNull()
        assertTrue(failure != null, "no data array")
    }

    @Test
    fun `an empty data array is a genuine empty window`() = runBlocking {
        orderedReply = { _, _ -> """{"js":{"total_items":0,"max_page_items":14,"data":[]}}""" }
        val window = StalkerClient.seriesWindow(account("rw-series-empty"), "7", 0)
        assertEquals(0, window.items.size)
        assertEquals(false, window.hasMore)
        assertEquals(0, window.consumed)
    }

    @Test
    fun `the first movie window costs one get_ordered_list request`() = runBlocking {
        val window = StalkerClient.vodMoviesWindow(account("rw-movie1"), "7", 0)
        assertEquals(listOf(1), orderedPages, "one page, not five")
        assertEquals(1, requests.count { it == "vod/get_ordered_list" })
        assertEquals(14, window.items.size)
        assertTrue(window.hasMore)
        assertEquals(14, window.consumed)
    }

    @Test
    fun `the next movie window asks for page two`() = runBlocking {
        val acc = account("rw-movie2")
        StalkerClient.vodMoviesWindow(acc, "7", 0)
        orderedPages.clear()
        StalkerClient.vodMoviesWindow(acc, "7", 14)
        assertEquals(listOf(2), orderedPages)
    }

    @Test
    fun `movie rows flagged as series are hidden but still consumed`() = runBlocking {
        orderedReply = { _, _ ->
            """{"js":{"total_items":100,"max_page_items":14,"data":[{"id":"1","name":"Movie","cmd":"c"},{"id":"2","name":"Show","cmd":"c","is_series":"1"}]}}"""
        }
        val window = StalkerClient.vodMoviesWindow(account("rw-movie-ministra"), "7", 0)
        assertEquals(listOf(1), window.items.map { it.streamId })
        assertEquals(2, window.consumed, "the next offset counts RAW rows")
    }

    @Test
    fun `a failed movie page throws instead of reading as empty`() = runBlocking {
        failPage = 1
        val failure = runCatching { StalkerClient.vodMoviesWindow(account("rw-movie-fail"), "7", 0) }.exceptionOrNull()
        assertTrue(failure != null, "a failure is never an empty window")
    }
}
