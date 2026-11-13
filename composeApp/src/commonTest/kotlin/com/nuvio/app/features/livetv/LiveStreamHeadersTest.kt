package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.stalker.StalkerStreamHeaderPolicy
import com.nuvio.app.features.iptv.stalker.StalkerStreamIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * commonTest (kotlin.test) - `assertEquals(expected, actual, message)`.
 *
 * B259 review (privacy): [LiveChannelSource.headers] reaches every engine, and the libmpv/FFmpeg lanes (Android mpv,
 * iOS, Apple TV, desktop) re-send it on every redirect hop to any host. A same-host Stalker stream's engine headers
 * must therefore never hold the portal's Cookie, Bearer token, Referer or Origin; those ride apart, for Media3 only.
 */
class LiveStreamHeadersTest {
    private val identity = StalkerStreamIdentity(
        portalBaseUrl = "http://portal.example",
        cookie = "mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en; timezone=Europe/London",
        magUserAgent = "MAG200 stbapp",
        xUserAgent = "Model: MAG250; Link: WiFi",
        token = "TOKEN123",
    )
    private val secrets = setOf("cookie", "authorization", "referer", "origin")

    private fun merged(streamUrl: String, dns: Map<String, String> = emptyMap(), ua: String? = null) =
        LiveStreamHeaders.merge(dns, StalkerStreamHeaderPolicy.headersFor(identity, streamUrl, ua))

    @Test
    fun `the engine headers of a same host stalker stream carry no secret`() {
        val h = merged("http://portal.example/play/live.php?stream=1")
        assertFalse(h.engine.keys.any { it.lowercase() in secrets }, "mpv/iOS/Apple TV/desktop never see a credential: ${h.engine.keys}")
        assertFalse(h.engine.values.any { "00%3A1A" in it || "TOKEN123" in it }, "no mac or token in any engine value")
        assertEquals(setOf("Cookie", "Authorization", "Referer", "Origin"), h.originBound.keys, "the secrets ride apart")
    }

    @Test
    fun `the DNS Host header stays and the public part replaces a default user agent`() {
        val h = merged("http://portal.example/x.ts", dns = mapOf("Host" to "portal.example", "user-agent" to "Chrome"))
        assertEquals(mapOf("Host" to "portal.example", "User-Agent" to "MAG200 stbapp", "X-User-Agent" to "Model: MAG250; Link: WiFi"), h.engine)
    }

    @Test
    fun `a foreign stream has nothing origin bound and a player profile for the engines`() {
        val h = merged("http://cdn.other.example/x.ts", ua = "VLC/3.0.20 LibVLC/3.0.20")
        assertEquals(emptyMap(), h.originBound, "nothing to scope")
        assertEquals(mapOf("User-Agent" to "VLC/3.0.20 LibVLC/3.0.20", "Accept" to "*/*"), h.engine, "playlist UA wins")
    }

    @Test
    fun `printing the headers never prints a secret`() {
        val text = merged("http://portal.example/x.ts").toString()
        assertFalse("TOKEN123" in text || "00%3A1A" in text, text)
    }
}
