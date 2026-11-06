package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Step 0.3 — the [PlaylistServerFailover] seam over a fake transport. Platform-free failures only
 * (HTTP statuses), so it runs on BOTH runners; the connect-refused integration lives in
 * androidHostTest (BackupServerFailoverIntegrationTest).
 */
class PlaylistServerFailoverTest {

    private var now = 1_000L
    private lateinit var store: InMemoryServerFailoverStateStore

    private val acc = XtreamAccount(
        id = "http://main.test|u", name = "P", baseUrl = "http://main.test", username = "u", password = "p",
        backupUrls = listOf("http://b1.test", "http://b2.test"),
    )

    @BeforeTest
    fun setUp() {
        store = InMemoryServerFailoverStateStore()
        PlaylistServerFailover.installForTest(store = store, clock = { now })
    }

    @AfterTest
    fun tearDown() = PlaylistServerFailover.resetForTest()

    /** A fake request: each server either answers with its own base or throws [failures]'s throwable. */
    private fun fakeRequest(tried: MutableList<String>, failures: Map<String, Throwable>): suspend (XtreamAccount) -> String = { a ->
        tried += a.baseUrl
        failures[a.baseUrl]?.let { throw it }
        a.baseUrl
    }

    @Test
    fun `main 503 fails over to backup 1 and the stream url follows`() = runBlocking {
        val tried = mutableListOf<String>()
        val served = PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(503, "HTTP 503"))))
        assertEquals("http://b1.test", served)
        assertEquals(listOf("http://main.test", "http://b1.test"), tried)
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        assertEquals("http://b1.test", PlaylistServerFailover.activeAccount(acc).baseUrl)
        assertEquals(
            "http://b1.test/live/u/p/7.ts",
            PlaylistServerFailover.rebaseStreamUrl(acc, "http://main.test/live/u/p/7.ts"),
            "a stream url built on main moves to the active backup",
        )
    }

    @Test
    fun `401 is the same answer on every server and is thrown without trying a backup`() = runBlocking {
        val tried = mutableListOf<String>()
        val auth = HttpStatusException(401, "HTTP 401")
        val thrown = assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to auth)))
        }
        assertSame(auth, thrown)
        assertEquals(listOf("http://main.test"), tried)
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
    }

    @Test
    fun `waf 456 does not fail over`() = runBlocking {
        val tried = mutableListOf<String>()
        assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(456, "HTTP 456"))))
        }
        assertEquals(listOf("http://main.test"), tried)
    }

    @Test
    fun `every server down surfaces the main server's error and keeps the state`() = runBlocking {
        val mainErr = HttpStatusException(502, "main 502")
        val tried = mutableListOf<String>()
        val thrown = assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf(
                "http://main.test" to mainErr,
                "http://b1.test" to HttpStatusException(503, "b1 503"),
                "http://b2.test" to HttpStatusException(404, "b2 404"),
            )))
        }
        assertSame(mainErr, thrown)
        assertEquals(3, tried.size)
        assertEquals(ServerFailoverState(), store.read(1, acc.id).copy(stats = emptyMap()), "active server and window untouched")
        assertEquals(setOf(0, 1, 2), store.read(1, acc.id).stats.keys, "each server that failed is remembered (stagger hint only)")
    }

    @Test
    fun `a streamed body that already delivered rows is never replayed on a backup`() = runBlocking {
        val tried = mutableListOf<String>()
        var delivered = false
        assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(acc, canRetry = { !delivered }) { a ->
                tried += a.baseUrl
                delivered = true
                throw HttpStatusException(503, "mid-body")
            }
        }
        assertEquals(listOf("http://main.test"), tried)
    }

    @Test
    fun `inside the window the backup leads and after it main is retried and wins back`() = runBlocking {
        val tried = mutableListOf<String>()
        PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(503, "x"))))
        tried.clear()
        now += 60_000
        PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, emptyMap()))
        assertEquals(listOf("http://b1.test"), tried, "no request to main inside the window")
        tried.clear()
        now = 1_000L + ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, emptyMap()))
        assertEquals(listOf("http://main.test"), tried)
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
        assertEquals("http://main.test/live/u/p/7.ts", PlaylistServerFailover.rebaseStreamUrl(acc, "http://b1.test/live/u/p/7.ts"))
    }

    @Test
    fun `a playlist without backups takes the plain path and stores nothing`() = runBlocking {
        val single = acc.copy(backupUrls = emptyList())
        val err = HttpStatusException(503, "x")
        assertFailsWith<HttpStatusException> { PlaylistServerFailover.run(single) { throw err } }
        assertTrue(store.all(1).isEmpty())
        assertEquals("http://main.test/x", PlaylistServerFailover.rebaseStreamUrl(single, "http://main.test/x"))
    }

    @Test
    fun `m3u and stalker stream urls are never rebased`() {
        store.write(1, "m3u", ServerFailoverState(1, 99_000))
        val m3u = acc.copy(id = "m3u", sourceType = SOURCE_TYPE_M3U_URL, baseUrl = "http://main.test/list.m3u", backupUrls = listOf("http://b1.test/list.m3u"))
        assertEquals("http://main.test/live/1.ts", PlaylistServerFailover.rebaseStreamUrl(m3u, "http://main.test/live/1.ts"))
    }

    @Test
    fun `an inspection asks the active server only and writes no failover state`() = runBlocking {
        val tried = mutableListOf<String>()
        PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(503, "HTTP 503"))))
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        val before = store.read(PlaylistServerFailover.profileId(), acc.id)
        tried.clear()
        assertFailsWith<HttpStatusException> {
            com.nuvio.app.core.sources.DeviceSources.inspecting {
                PlaylistServerFailover.run(acc, attempt = fakeRequest(tried, mapOf("http://b1.test" to HttpStatusException(503, "HTTP 503"))))
            }
        }
        assertEquals(listOf("http://b1.test"), tried, "the active server alone: no race, no backup probe, no walk to main or b2")
        assertEquals(before, store.read(PlaylistServerFailover.profileId(), acc.id), "no new active server, no stats")
        assertEquals(null, PlaylistServerFailover.lastFailedServerUrl(acc), "no failure recorded for the error card")
    }
}
