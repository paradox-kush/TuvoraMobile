package com.nuvio.app.features.player

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * B259 (Android Media3 lane, the only lane that may carry a Stalker portal's origin-bound credentials): the stream's
 * OkHttp stack sends them to the stream's own host and drops ALL of them on a redirect to another host. OkHttp itself
 * drops only Authorization on a cross-host redirect; a Cookie, Referer or Origin set as a default header rides to any
 * host. Drives [PlayerPlaybackNetworking.streamRequestSetup], the exact setup the Media3 data source uses. Two loopback
 * servers stand in for portal and CDN ("127.0.0.1" vs "localhost" are different hosts to the scope rule).
 */
class PlaybackRedirectCredentialScopeTest {
    private val portal = MockWebServer()
    private val cdn = MockWebServer()

    private val public = mapOf("User-Agent" to "MAG200 stbapp", "X-User-Agent" to "Model: MAG250; Link: WiFi")
    private fun originBound(port: Int) = mapOf(
        "Cookie" to "mac=00%3A1A",
        "Authorization" to "Bearer T",
        "Referer" to "http://127.0.0.1:$port",
        "Origin" to "http://127.0.0.1:$port",
    )

    @AfterTest
    fun tearDown() {
        portal.shutdown()
        cdn.shutdown()
    }

    private fun get(url: String, port: Int) {
        val (factory, headers) = PlayerPlaybackNetworking.streamRequestSetup(
            defaultHeaders = public,
            originBoundHeaders = originBound(port),
            useLongReadTimeout = false,
            retryConnectionFailures = false,
        )
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        factory.newCall(request).execute().use { it.body.string() }
    }

    @Test
    fun `a redirect to another host carries none of the origin bound headers`() {
        cdn.enqueue(MockResponse().setBody("ts"))
        cdn.start()
        portal.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${cdn.port}/live.ts"))
        portal.start()

        get("http://127.0.0.1:${portal.port}/play/live.php", portal.port)

        val first = assertNotNull(portal.takeRequest(5, TimeUnit.SECONDS), "portal hop")
        assertEquals("mac=00%3A1A", first.getHeader("Cookie"), "the portal itself gets its cookie")
        assertEquals("Bearer T", first.getHeader("Authorization"), "and its token")
        assertEquals("http://127.0.0.1:${portal.port}", first.getHeader("Origin"), "and its origin")
        val hop = assertNotNull(cdn.takeRequest(5, TimeUnit.SECONDS), "cdn hop")
        assertNull(hop.getHeader("Cookie"), "cookie stripped on the cross-host hop")
        assertNull(hop.getHeader("Authorization"), "token stripped on the cross-host hop")
        assertNull(hop.getHeader("Referer"), "portal referer stripped on the cross-host hop")
        assertNull(hop.getHeader("Origin"), "portal origin stripped on the cross-host hop")
        assertEquals("Model: MAG250; Link: WiFi", hop.getHeader("X-User-Agent"), "the public part still rides along")
    }

    @Test
    fun `a redirect on the same host keeps the origin bound headers`() {
        portal.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/live.ts"))
        portal.enqueue(MockResponse().setBody("ts"))
        portal.start()

        get("http://127.0.0.1:${portal.port}/play/live.php", portal.port)

        portal.takeRequest(5, TimeUnit.SECONDS)
        val hop = assertNotNull(portal.takeRequest(5, TimeUnit.SECONDS), "same host hop")
        assertEquals("mac=00%3A1A", hop.getHeader("Cookie"), "same host keeps the cookie")
        assertEquals("Bearer T", hop.getHeader("Authorization"), "same host keeps the token")
        assertEquals("http://127.0.0.1:${portal.port}", hop.getHeader("Referer"), "same host keeps the referer")
    }
}
