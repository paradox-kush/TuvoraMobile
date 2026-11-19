package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A portal whose category reply is not a JSON array is a FAILED load, never an empty one. It used to
 * come back as `emptyList()`, so the hub's BoundedLoad ended Empty ("Nothing here yet") instead of
 * Failed ("Couldn't reach this playlist" + Retry), and a garbled reply read like a truly empty portal.
 */
class StalkerCategoryReplyTest {

    @Test
    fun `an array reply parses to its categories and drops the All row`() {
        val js = Json.parseToJsonElement("""[{"id":"*","title":"All"},{"id":"7","title":"News"},{"id":"8","name":"Sport"}]""")
        assertEquals(listOf("7" to "News", "8" to "Sport"), StalkerCategoryReply.parse(js).map { it.id to it.name })
    }

    @Test
    fun `an empty array is a genuine empty list`() {
        assertEquals(emptyList(), StalkerCategoryReply.parse(Json.parseToJsonElement("[]")))
    }

    @Test
    fun `an object reply is a failure not an empty list`() {
        assertFailsWith<StalkerMalformedReplyException> {
            StalkerCategoryReply.parse(Json.parseToJsonElement("""{"error":"Authorization failed."}"""))
        }
    }

    @Test
    fun `a string reply is a failure not an empty list`() {
        assertFailsWith<StalkerMalformedReplyException> {
            StalkerCategoryReply.parse(Json.parseToJsonElement("\"oops\""))
        }
    }

    // --- through the client: the real seam the hub's BoundedLoad reads ---------------------------

    private var categoryReply = """{"js":[]}"""
    private var seriesReply = """{"js":[]}"""
    private var lastSession: StalkerSession? = null

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        when {
            action == "handshake" -> """{"js":{"token":"T"}}"""
            action == "get_profile" -> """{"js":{"id":"1","status":0}}"""
            type == "vod" && action == "get_categories" -> categoryReply
            type == "series" && action == "get_categories" -> seriesReply
            else -> """{"js":[]}"""
        }
    }

    private fun account() = XtreamAccount(
        id = "stalker|cat-${kotlin.random.Random.nextLong()}", name = "Portal", baseUrl = "http://portal.test/stalker_portal/c/",
        username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:00:00:09",
    )

    @BeforeTest fun setUp() {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal, { u, h, c -> c(fakePortal(u, h)) }).also { s -> lastSession = s } }
    }

    @AfterTest fun tearDown() {
        lastSession?.shutdown()
        StalkerClient.sessionFactory = { StalkerSession(it) }
        StalkerClient.clearMemoryCachesForTest()
    }

    @Test
    fun `the client reports a non-array category reply as a failed result`() = runBlocking {
        categoryReply = """{"js":{"error":"bad"}}"""
        val result = StalkerClient.vodCategories(account())
        assertTrue(result.isFailure, "a garbled reply must fail the load, not read as empty: $result")
    }

    @Test
    fun `the client still returns a valid category array unchanged`() = runBlocking {
        categoryReply = """{"js":[{"id":"1","title":"News"}]}"""
        assertEquals(listOf("News"), StalkerClient.vodCategories(account()).getOrThrow().map { it.name })
    }

    // The type=series call is a capability probe: a non-array reply there must NOT become a failure.
    @Test
    fun `a non-array series reply still falls back to the Ministra vod tree`() = runBlocking {
        seriesReply = """{"js":{"error":"no such module"}}"""
        categoryReply = """{"js":[{"id":"*","title":"All"},{"id":"5","title":"Shows"}]}"""
        val result = StalkerClient.seriesCategories(account())
        assertEquals(listOf("Shows"), result.getOrThrow().map { it.name })
    }

    @Test
    fun `a non-array series reply with an empty vod is an empty success not a failure`() = runBlocking {
        seriesReply = """{"js":{"error":"no such module"}}"""
        categoryReply = """{"js":[{"id":"*","title":"All"}]}"""
        val result = StalkerClient.seriesCategories(account())
        assertTrue(result.isSuccess, "the probe must not fail: $result")
        assertEquals(emptyList(), result.getOrThrow())
    }
}
