package com.nuvio.app.features.iptv

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The DoH redirect-follow is a real GET on the URL. A Stalker archive link is spent by its first request, so for a
 * single-use link that GET must not happen (the player's open is the one request); a reusable link still walks it.
 */
class SingleUseDnsProbeTest {
    private val start = "http://portal.example/archive.ts".toHttpUrl()
    private val edge = "http://edge.example/archive.ts?t=1".toHttpUrl()

    @Test
    fun `a single-use link is never probed`() {
        var probes = 0
        val url = finalUrlFor(start, followRedirects = false) { probes++; edge }
        assertEquals(0, probes)
        assertEquals(start, url)
    }

    @Test
    fun `a reusable link follows its redirect and falls back to itself when the probe fails`() {
        var probes = 0
        assertEquals(edge, finalUrlFor(start, followRedirects = true) { probes++; edge })
        assertEquals(1, probes)
        assertEquals(start, finalUrlFor(start, followRedirects = true) { null })
    }
}
