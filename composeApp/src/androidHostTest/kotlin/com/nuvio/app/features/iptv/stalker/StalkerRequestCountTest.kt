package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the request storm that got a live portal's Cloudflare to block the user's IP.
 *
 * `get_ordered_list` already returns each item's `cmd` (the create_link input), but the cmd lookups
 * used to THROW IT AWAY and re-page the entire catalog (genre=*, up to MAX_PAGES=200 requests) to find
 * one item again — so a single tap-to-play cost ~200 requests, and browsing a few titles was a DoS.
 *
 * These tests pin the request COUNT, which is the only thing that actually catches a regression here:
 * the feature still "works" when it's hammering the portal, it just gets you banned.
 */
class StalkerRequestCountTest {

    private val requests = mutableListOf<String>()

    /** A fake portal: 3 pages x 2 channels, each row carrying its `cmd` like a real one. */
    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        requests += "$type/$action"
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{}}"""
            // The whole lineup in one shot, like a real portal (2 categories x 3 channels).
            "get_all_channels" -> {
                val data = (1..6).joinToString(",") {
                    """{"id":"$it","name":"Ch $it","tv_genre_id":"${if (it <= 3) "g1" else "g2"}","cmd":"ffmpeg http://localhost/ch/$it"}"""
                }
                """{"js":{"data":[$data]}}"""
            }
            "get_ordered_list" -> {
                val p = Regex("[&?]p=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                if (p <= 3) {
                    val data = listOf((p - 1) * 2 + 1, (p - 1) * 2 + 2).joinToString(",") {
                        """{"id":"$it","name":"Ch $it","cmd":"ffmpeg http://portal/ch/$it"}"""
                    }
                    """{"js":{"total_items":6,"max_page_items":2,"data":[$data]}}"""
                } else {
                    """{"js":{"total_items":6,"max_page_items":2,"data":[]}}"""
                }
            }
            "create_link" -> """{"js":{"cmd":"ffmpeg http://portal/live/999.ts?token=x"}}"""
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
    )

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    @Test
    fun `the live lineup is ONE request and every category is served from it`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = account("rc-lineup")

        assertEquals(3, StalkerClient.liveChannels(acc, "g1").getOrThrow().size)
        assertEquals(3, StalkerClient.liveChannels(acc, "g2").getOrThrow().size)
        assertEquals(6, StalkerClient.liveChannels(acc, null).getOrThrow().size)

        // One get_all_channels for the whole lineup, and NEVER the paged path. This portal serves
        // get_ordered_list 14 rows/page, so paging 11k channels was ~800 requests (capped at 200,
        // which also truncated the lineup) — per category.
        assertEquals(1, requests.count { it == "itv/get_all_channels" })
        assertEquals(0, requests.count { it == "itv/get_ordered_list" })
    }

    @Test
    fun `playing a channel costs exactly one request`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = account("rc-browsed")

        StalkerClient.liveChannels(acc, "g1").getOrThrow()
        val afterBrowse = requests.size

        val url = StalkerClient.resolveLiveUrl(acc, 5)
        assertEquals("http://portal/live/999.ts?token=x", url)
        // The whole point: create_link and NOTHING else. Pre-fix this re-paged the catalog first.
        assertEquals(1, requests.size - afterBrowse)
        assertEquals("itv/create_link", requests.last())
    }

    @Test
    fun `cold-start play uses the cached lineup, never the catalog`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = account("rc-cold")

        // Nothing browsed: the lineup fetch (1 request) supplies the cmd — no paging at all.
        val url = StalkerClient.resolveLiveUrl(acc, 1)
        assertEquals("http://portal/live/999.ts?token=x", url)
        assertEquals(0, requests.count { it == "itv/get_ordered_list" })
        assertEquals(1, requests.count { it == "itv/get_all_channels" })
    }

    @Test
    fun `a VOD category row is capped, not paged to the end of the catalog`() = runBlocking {
        // A huge category: 100 pages available. The row must take its cap (70 = 5 pages) and stop —
        // the real portal has 63k movies at 14/page, and a poster row has no see-all.
        val big: suspend (String, Map<String, String>) -> String = { url, _ ->
            val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
            requests += "vod/$action"
            when (action) {
                "handshake" -> """{"js":{"token":"T"}}"""
                "get_profile" -> """{"js":{}}"""
                else -> {
                    val p = Regex("[&?]p=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                    val data = (1..14).joinToString(",") { """{"id":"${p * 100 + it}","name":"M","cmd":"c"}""" }
                    """{"js":{"total_items":1400,"max_page_items":14,"data":[$data]}}"""
                }
            }
        }
        StalkerClient.sessionFactory = { StalkerSession(it, big) }
        val movies = StalkerClient.vodMovies(account("rc-cap"), "big").getOrThrow()
        assertEquals(70, movies.size)
        assertEquals(5, requests.count { it == "vod/get_ordered_list" })   // 5 pages, not 100
    }

    @Test
    fun `cold-start VOD play stops paging at the match instead of slurping the catalog`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = account("rc-vod")

        // VOD has no get_all_channels equivalent, so it still scans — but must stop at the match.
        val url = StalkerClient.resolveMovieUrl(acc, 1)      // id 1 is on page 1
        assertEquals("http://portal/live/999.ts?token=x", url)
        assertEquals(1, requests.count { it == "vod/get_ordered_list" })   // not all 3 pages
    }
}
