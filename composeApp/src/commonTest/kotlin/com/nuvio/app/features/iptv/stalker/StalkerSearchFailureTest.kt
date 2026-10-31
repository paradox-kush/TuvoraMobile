package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.IptvTransport
import com.nuvio.app.features.iptv.PlaylistServerFailover
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * F48 — a Stalker portal that cannot be reached is a FAILED search, not "no matches". Global search keeps the
 * forgiving behaviour (one dead playlist must not blank the others); the IPTV hub's search asks the throwing
 * variants so it can show "Search didn't finish" with Retry.
 */
class StalkerSearchFailureTest {
    private class Down : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String = error("portal down")
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit): Unit = error("portal down")
        override suspend fun readPrefix(url: String, userAgent: String?, dnsProvider: String?, headers: Map<String, String>, maxBytes: Int): String = error("portal down")
    }

    private val account = XtreamAccount(
        id = "st1", name = "Portal", baseUrl = "http://portal.test", username = "", password = "",
        sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:00:00:01",
    )

    @BeforeTest fun setUp() {
        PlaylistServerFailover.installForTest(clock = { 5_000L })
        IptvTransport.current = Down()
    }

    @AfterTest fun tearDown() {
        PlaylistServerFailover.resetForTest()
        IptvTransport.current = IptvTransport.Platform
        StalkerClient.clearMemoryCachesForTest()
    }

    @Test
    fun `global search stays forgiving when the portal is down`() = runTest {
        assertEquals(emptyList(), StalkerClient.searchMovies(account, "matrix").map { it.name })
        assertEquals(emptyList(), StalkerClient.searchSeries(account, "office").map { it.name })
    }

    @Test
    fun `the hub search sees the failure instead of an empty list`() = runTest {
        assertFailsWith<Throwable> { StalkerClient.searchMoviesOrThrow(account, "matrix") }
        assertFailsWith<Throwable> { StalkerClient.searchSeriesOrThrow(account, "office") }
    }
}
