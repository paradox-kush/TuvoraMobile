package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * B259 wiring through the REAL resolver: a minted Stalker link's stream headers come from the same session that
 * minted it (the cookie and token the portal's API calls carried), and only for a stream on the portal's host.
 */
class StalkerStreamIdentityWiringTest {
    private var linkHost = "http://portal.test"
    private val apiHeaders = mutableListOf<Map<String, String>>()

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, headers ->
        apiHeaders += headers
        when (Regex("action=([^&]+)").find(url)?.groupValues?.get(1)) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"3","name":"M","cmd":"ffmpeg http://localhost/vod/3"}]}}"""
            "create_link" -> """{"js":{"cmd":"ffmpeg $linkHost/live/999.ts?token=x"}}"""
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String, userAgent: String? = null) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6", userAgent = userAgent,
    )

    @BeforeTest
    fun setUp() {
        com.nuvio.app.features.iptv.content.IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal, { u, h, c -> c(fakePortal(u, h)) }) }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    @Test
    fun `a minted portal owned link opens with the session cookie and token`() = runBlocking {
        val acc = account("wiring-same")
        StalkerClient.vodMovies(acc, null).getOrThrow()
        val url = assertNotNull(StalkerClient.resolveMovieUrl(acc, 3))

        val split = assertNotNull(StalkerClient.streamHeadersFor(acc, url), "identity snapshot taken at mint")
        // Origin-bound (Android Media3 only): the session's own secrets.
        assertEquals("Bearer T", split.originBound["Authorization"], "the token the link was minted under")
        assertEquals(apiHeaders.last()["Cookie"], split.originBound["Cookie"], "the very cookie the API calls send")
        assertEquals("http://portal.test", split.originBound["Origin"], "portal origin")
        // Public (every engine, libmpv included): the UA pair and no secret.
        assertEquals(apiHeaders.last()["User-Agent"], split.public["User-Agent"], "the MAG UA the API calls send")
        assertEquals(setOf("User-Agent", "X-User-Agent"), split.public.keys, "the mpv lanes get no Cookie, token, Referer or Origin")
    }

    @Test
    fun `a minted foreign link opens with no portal credentials`() = runBlocking {
        linkHost = "http://edge.cdn.test"
        val acc = account("wiring-foreign")
        StalkerClient.vodMovies(acc, null).getOrThrow()
        val url = assertNotNull(StalkerClient.resolveMovieUrl(acc, 3))

        val split = assertNotNull(StalkerClient.streamHeadersFor(acc, url))
        assertEquals(emptyMap(), split.originBound, "nothing origin-bound for a foreign host")
        val headers = split.public
        assertNull(headers["Cookie"], "no mac cookie to a foreign host")
        assertNull(headers["Authorization"], "no token to a foreign host")
        assertFalse(headers.values.any { "58%3AB3" in it || "58:B3" in it }, "no MAC anywhere")
        assertEquals(StalkerStreamHeaderPolicy.PLAYER_USER_AGENT, headers["User-Agent"], "player profile")
    }

    @Test
    fun `the playlist user agent wins on the minted link`() = runBlocking {
        val acc = account("wiring-ua", userAgent = "IBOPlayer/1.0")
        StalkerClient.vodMovies(acc, null).getOrThrow()
        val url = assertNotNull(StalkerClient.resolveMovieUrl(acc, 3))
        assertEquals("IBOPlayer/1.0", StalkerClient.streamHeadersFor(acc, url)?.public?.get("User-Agent"))
    }

    @Test
    fun `vod and catch up keep the portal identity after live learned neutral`() = runBlocking {
        val acc = account("wiring-learned-neutral")
        StalkerStreamProfileMemory.Process.rememberNeutral(acc.id)
        try {
            StalkerClient.vodMovies(acc, null).getOrThrow()
            val url = assertNotNull(StalkerClient.resolveMovieUrl(acc, 3))
            val split = assertNotNull(StalkerClient.streamHeadersFor(acc, url), "the default profile is the VOD/catch-up one")
            assertEquals(apiHeaders.last()["User-Agent"], split.public["User-Agent"], "VOD still sends the MAG UA")
            assertEquals("Bearer T", split.originBound["Authorization"], "Media3 catch-up/VOD keeps the token")
            assertEquals(
                StalkerStreamHeaderPolicy.PLAYER_USER_AGENT,
                StalkerClient.streamHeadersFor(acc, url, StalkerStreamProfile.NEUTRAL)?.public?.get("User-Agent"),
                "only a live tune asks for neutral",
            )
        } finally {
            StalkerStreamProfileMemory.Process.forgetNeutral(acc.id)
        }
    }

    @Test
    fun `nothing minted means no identity`() {
        assertNull(StalkerClient.streamHeadersFor(account("wiring-none"), "http://portal.test/live/1.ts"))
    }
}
