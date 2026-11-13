package com.nuvio.app.features.iptv.stalker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * commonTest (kotlin.test) - `assertEquals(expected, actual, message)`.
 *
 * B259: the Stalker live stream went out with no portal identity (only DNS-rewrite headers and the Chrome default
 * UA), while iptvnator sends the portal identity to a portal-owned stream and a credential-free player profile to a
 * foreign one. These pin that rule - and that the mac cookie and token never reach a foreign host.
 */
class StalkerStreamHeaderPolicyTest {
    private val portal = "http://portal.example"
    private val identity = StalkerStreamIdentity(
        portalBaseUrl = portal,
        cookie = "mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en; timezone=Europe/London",
        magUserAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200 stbapp",
        xUserAgent = "Model: MAG250; Link: WiFi",
        token = "TOKEN123",
    )

    private fun split(streamUrl: String, ua: String? = null, id: StalkerStreamIdentity = identity) =
        StalkerStreamHeaderPolicy.headersFor(id, streamUrl, ua)

    /** Everything the Media3 lane sends on a credential-safe hop: public plus origin-bound. */
    private fun headers(streamUrl: String, ua: String? = null, id: StalkerStreamIdentity = identity) =
        split(streamUrl, ua, id).let { it.public + it.originBound }

    private fun assertNoCredentials(h: Map<String, String>, context: String) {
        val keys = h.keys.map { it.lowercase() }
        assertFalse("cookie" in keys, "$context: no Cookie")
        assertFalse("authorization" in keys, "$context: no Authorization")
        assertFalse("x-user-agent" in keys, "$context: no MAG X-User-Agent")
        assertFalse(h.values.any { "00%3A1A" in it || "00:1A" in it || "TOKEN123" in it }, "$context: no mac or token in any value")
    }

    @Test
    fun `a stream on the portal host carries the portal identity`() {
        assertEquals(
            mapOf(
                "Cookie" to identity.cookie,
                "User-Agent" to identity.magUserAgent,
                "X-User-Agent" to identity.xUserAgent,
                "Authorization" to "Bearer TOKEN123",
                "Referer" to portal,
                "Origin" to portal,
            ),
            headers("http://portal.example/play/live.php?stream=1&play_token=abc"),
            "same origin gets the full identity",
        )
    }

    // Review (privacy): FFmpeg re-sends custom headers on every redirect hop to any host, so the libmpv/FFmpeg lanes
    // (Android mpv, iOS, Apple TV, desktop) receive only the public part. Every secret lives in the origin-bound part.
    @Test
    fun `the secrets are only ever in the origin bound part`() {
        val same = split("http://portal.example/play/live.php?stream=1")
        assertEquals(
            mapOf("User-Agent" to identity.magUserAgent, "X-User-Agent" to identity.xUserAgent),
            same.public,
            "the mpv lanes get the UA pair and nothing else",
        )
        assertNoCredentials(same.public.filterKeys { it != "X-User-Agent" }, "public part")
        assertFalse(same.public.keys.any { it.lowercase() in setOf("referer", "origin") }, "no portal Referer or Origin in the public part")
        assertEquals(
            setOf("Cookie", "Authorization", "Referer", "Origin"),
            same.originBound.keys,
            "the Media3-only part holds every secret",
        )
    }

    @Test
    fun `a foreign stream has nothing origin bound`() {
        assertEquals(emptyMap(), split("http://cdn.other.example/live/ch1.ts").originBound, "foreign host")
        assertEquals(emptyMap(), split("http://portal.example/x.ts", id = identity.copy(portalBaseUrl = "https://portal.example")).originBound, "downgrade")
    }

    @Test
    fun `a same host stream on another port is still portal owned`() {
        val h = headers("http://portal.example:8080/live/ch1.ts")
        assertEquals("Bearer TOKEN123", h["Authorization"], "port differs but the host is the portal's")
        assertEquals(identity.cookie, h["Cookie"], "cookie rides along")
    }

    @Test
    fun `an http portal handing out an https stream keeps the identity`() {
        assertEquals(identity.cookie, headers("https://portal.example/live/ch1.ts")["Cookie"], "scheme upgrade is safe")
    }

    @Test
    fun `an https portal handing out an http stream is a downgrade and gets no credentials`() {
        val secure = identity.copy(portalBaseUrl = "https://portal.example")
        val h = headers("http://portal.example/live/ch1.ts", id = secure)
        assertNoCredentials(h, "https to http downgrade")
        assertEquals(StalkerStreamHeaderPolicy.PLAYER_USER_AGENT, h["User-Agent"], "player profile UA")
    }

    @Test
    fun `a foreign host gets the credential free player profile`() {
        val h = headers("http://cdn.other.example/live/ch1.ts?token=xyz")
        assertEquals(
            mapOf("User-Agent" to StalkerStreamHeaderPolicy.PLAYER_USER_AGENT, "Accept" to "*/*"),
            h,
            "foreign host: player UA and Accept only",
        )
        assertNoCredentials(h, "foreign host")
    }

    @Test
    fun `a look alike host is foreign`() {
        assertNoCredentials(headers("http://portal.example.evil.test/live/ch1.ts"), "suffix trick")
        assertNoCredentials(headers("http://evilportal.example/live/ch1.ts"), "prefix trick")
        assertNoCredentials(headers("http://portal.example@evil.test/live/ch1.ts"), "userinfo trick")
    }

    @Test
    fun `host comparison ignores case and the DNS root dot`() {
        assertEquals(identity.cookie, headers("http://PORTAL.Example:8080/live/ch1.ts")["Cookie"], "case")
        assertEquals(identity.cookie, headers("http://portal.example./live/ch1.ts")["Cookie"], "trailing dot")
    }

    @Test
    fun `non http schemes and garbage fail closed`() {
        assertNoCredentials(headers("rtsp://portal.example/ch1"), "rtsp")
        assertNoCredentials(headers("udp://portal.example:1234"), "udp")
        assertNoCredentials(headers("not a url"), "garbage")
        assertNoCredentials(headers(""), "blank")
    }

    @Test
    fun `the playlist user agent wins on a portal owned stream`() {
        val h = headers("http://portal.example/live/ch1.ts", ua = "  IBOPlayer/1.0  ")
        assertEquals("IBOPlayer/1.0", h["User-Agent"], "override replaces the MAG UA")
        assertEquals(identity.xUserAgent, h["X-User-Agent"], "X-User-Agent stays the MAG model")
    }

    @Test
    fun `the playlist user agent wins on a foreign stream`() {
        val h = headers("http://cdn.other.example/live/ch1.ts", ua = "VLC/3.0.20 LibVLC/3.0.20")
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", h["User-Agent"], "override replaces the player UA")
        assertNoCredentials(h, "foreign with override")
    }

    @Test
    fun `a blank override is no override`() {
        assertEquals(identity.magUserAgent, headers("http://portal.example/x.ts", ua = "   ")["User-Agent"], "same origin")
        assertEquals(StalkerStreamHeaderPolicy.PLAYER_USER_AGENT, headers("http://cdn.other.example/x.ts", ua = "")["User-Agent"], "foreign")
    }

    @Test
    fun `no session token means no Authorization header`() {
        assertNull(headers("http://portal.example/x.ts", id = identity.copy(token = null))["Authorization"], "null token")
        assertNull(headers("http://portal.example/x.ts", id = identity.copy(token = ""))["Authorization"], "empty token")
    }

    @Test
    fun `the origin keeps a non default portal port`() {
        val h = headers("http://portal.example/x.ts", id = identity.copy(portalBaseUrl = "http://portal.example:8080"))
        assertEquals("http://portal.example:8080", h["Origin"], "origin")
        assertEquals("http://portal.example:8080", h["Referer"], "referer")
    }

    @Test
    fun `neither profile sends Range or Icy headers the engines own`() {
        for (url in listOf("http://portal.example/x.ts", "http://cdn.other.example/x.ts")) {
            val keys = headers(url).keys.map { it.lowercase() }
            assertFalse("range" in keys, "$url: engines send their own Range")
            assertFalse("icy-metadata" in keys, "$url: engines request ICY where they can strip it")
            assertFalse("connection" in keys, "$url: hop by hop")
        }
    }

    @Test
    fun `credential safety mirrors iptvnator`() {
        assertTrue(StalkerStreamHeaderPolicy.isCredentialSafe("http://portal.example/stalker_portal/c/", "http://portal.example/live/1.ts"), "exact")
        assertTrue(StalkerStreamHeaderPolicy.isCredentialSafe("http://portal.example", "http://portal.example:8080/live/1.ts"), "port")
        assertTrue(StalkerStreamHeaderPolicy.isCredentialSafe("http://portal.example", "https://portal.example/live/1.ts"), "upgrade")
        assertFalse(StalkerStreamHeaderPolicy.isCredentialSafe("https://portal.example", "http://portal.example/live/1.ts"), "downgrade")
        assertFalse(StalkerStreamHeaderPolicy.isCredentialSafe("http://portal.example", "http://cdn.other.example/live/1.ts"), "host")
        assertFalse(StalkerStreamHeaderPolicy.isCredentialSafe(null, "http://portal.example/live/1.ts"), "no portal")
        assertFalse(StalkerStreamHeaderPolicy.isCredentialSafe("http://portal.example", null), "no stream")
        assertTrue(StalkerStreamHeaderPolicy.isCredentialSafe("http://[::1]:88", "http://[::1]:8080/x.ts"), "ipv6 literal")
    }

    @Test
    fun `a redirect hop that leaves the host or downgrades drops the credentials`() {
        val origin = "http://portal.example/live/1.ts"
        assertTrue(StalkerStreamHeaderPolicy.mayCarryCredentialsTo(origin, "http://portal.example:8080/x.ts"), "same host hop")
        assertFalse(StalkerStreamHeaderPolicy.mayCarryCredentialsTo(origin, "http://nginx.cdn.example/x.ts"), "cross host hop")
        assertFalse(StalkerStreamHeaderPolicy.mayCarryCredentialsTo("https://portal.example/1.ts", "http://portal.example/1.ts"), "downgrade hop")
        assertFalse(StalkerStreamHeaderPolicy.mayCarryCredentialsTo(origin, "garbage"), "unparseable hop")
    }
}
