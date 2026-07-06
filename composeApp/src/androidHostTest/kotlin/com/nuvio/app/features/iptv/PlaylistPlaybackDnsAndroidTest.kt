package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3 live-mpv DNS rewrite. Two pure pieces exercised on the JVM host:
 *  - [rewriteHostToIp]: swaps a URL's host for a resolved IP and emits the original Host header (the
 *    mpv `http-header-fields` value), preserving port/path/query; a bare-IP host is left untouched.
 *  - [resolveLivePlaybackUrl] fast-paths that need NO network: a system/blank provider and an https
 *    URL both return the URL unchanged with no headers (rewriting https host->IP would break TLS SNI).
 */
class PlaylistPlaybackDnsAndroidTest {

    @Test
    fun rewriteHostToIpSwapsHostAndCarriesOriginalHostHeader() {
        val res = rewriteHostToIp("http://panel.example.com:8080/live/u/p/123.ts", "203.0.113.9")
        assertEquals("http://203.0.113.9:8080/live/u/p/123.ts", res.url)
        // Non-default port MUST stay in the Host header — IPTV panels routinely run on :8080/:25461.
        assertEquals(mapOf("Host" to "panel.example.com:8080"), res.headers)
    }

    @Test
    fun rewritePreservesQueryAndDefaultPort() {
        val res = rewriteHostToIp("http://cdn.example.net/stream?token=abc123", "198.51.100.7")
        assertEquals("http://198.51.100.7/stream?token=abc123", res.url)
        assertEquals("cdn.example.net", res.headers["Host"])
    }

    @Test
    fun rewriteLeavesBareIpHostUntouchedWithNoHeader() {
        // The final URL is already an IP host — nothing to rewrite, no Host header needed.
        val res = rewriteHostToIp("http://203.0.113.9:8080/live/u/p/123.ts", "203.0.113.9")
        assertEquals("http://203.0.113.9:8080/live/u/p/123.ts", res.url)
        assertTrue(res.headers.isEmpty())
    }

    @Test
    fun rewriteFallsBackForUnparseableInput() {
        val res = rewriteHostToIp("not a url", "203.0.113.9")
        assertEquals("not a url", res.url)
        assertTrue(res.headers.isEmpty())
    }

    @Test
    fun systemProviderIsANoOp() = runBlocking {
        val url = "http://panel.example.com:8080/live/u/p/123.ts"
        val res = resolveLivePlaybackUrl(url, "system")
        assertEquals(url, res.url)
        assertTrue(res.headers.isEmpty())
        // null provider too
        assertEquals(url, resolveLivePlaybackUrl(url, null).url)
    }

    @Test
    fun httpsIsLeftUntouchedEvenWithADohProvider() = runBlocking {
        // https host->IP rewrite would break TLS SNI/cert validation, so https is never rewritten —
        // returns unchanged WITHOUT doing any DoH lookup (no network in this test).
        val url = "https://panel.example.com:8443/live/u/p/123.ts"
        val res = resolveLivePlaybackUrl(url, "cloudflare")
        assertEquals(url, res.url)
        assertTrue(res.headers.isEmpty())
        assertNull(res.headers["Host"])
    }
}
