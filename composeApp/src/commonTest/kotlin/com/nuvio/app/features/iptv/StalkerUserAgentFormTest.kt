package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * commonTest (kotlin.test) — `assertEquals(expected, actual, message)`.
 *
 * Regression: the Stalker form had no User-Agent field and the Stalker account builder dropped
 * [XtreamFormInput.userAgent], so a Stalker playlist behind a UA-keyed WAF (HTTP 456/403) could never
 * pin an honest client UA for the player stream.
 */
class StalkerUserAgentFormTest {
    private fun form(userAgent: String?) = XtreamFormInput(
        serverUrl = "http://portal.example.test", username = "", password = "", name = null,
        epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
        sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:AB:CD:EF", userAgent = userAgent,
    )

    @Test
    fun `stalker account keeps the trimmed user agent`() {
        val acc = stalkerAccountFromForm(form("  VLC/3.0.20 LibVLC/3.0.20  "))!!
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", acc.userAgent, "form UA is persisted on the stalker account")
    }

    @Test
    fun `stalker account without user agent stays null`() {
        assertNull(stalkerAccountFromForm(form(null))!!.userAgent, "no override")
        assertNull(stalkerAccountFromForm(form("   "))!!.userAgent, "blank is no override")
    }

    @Test
    fun `stalker user agent reaches the player stream policy`() {
        val acc = stalkerAccountFromForm(form("IBOPlayer/1.0"))!!
        assertEquals("IBOPlayer/1.0", StreamUserAgentPolicy.resolve(acc), "the player stream uses the stalker UA")
    }

    @Test
    fun `stalker live headers carry the account user agent over the default`() {
        val acc = stalkerAccountFromForm(form("IBOPlayer/1.0"))!!
        val headers = StreamUserAgentPolicy.headersFor(acc, mapOf("user-agent" to "Mozilla/5.0 Chrome", "Host" to "h"))
        assertEquals(mapOf("Host" to "h", "User-Agent" to "IBOPlayer/1.0"), headers, "the playlist UA replaces the default and other headers stay")
    }

    @Test
    fun `stalker live headers are unchanged without an override`() {
        val acc = stalkerAccountFromForm(form(null))!!
        val h = mapOf("Host" to "h")
        assertEquals(h, StreamUserAgentPolicy.headersFor(acc, h), "no override leaves headers alone")
    }
}
