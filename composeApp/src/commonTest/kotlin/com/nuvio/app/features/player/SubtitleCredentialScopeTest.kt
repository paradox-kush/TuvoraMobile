package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the subtitle credential-scope fix on both runners. Vectors locked down:
 *  (A) a same-host subtitle URL redirecting to a foreign host — creds stripped on the hop.
 *  (B) an https stream forwarding creds to an http (downgraded) subtitle hop — forward nothing.
 * Also closes the pre-fix Mobile gap of forwarding stream creds to any foreign subtitle host.
 */
class SubtitleCredentialScopeTest {

    private val creds = mapOf(
        "Authorization" to "Bearer secret",
        "Cookie" to "sid=abc",
        "X-Debrid-Token" to "tok",
    )

    @Test
    fun sameHostNoDowngradeForwardsCredentials() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "https://host.example/sub.srt",
            streamHeaders = creds,
        )
        assertEquals(creds, out, "same-host same-scheme hop forwards all credential headers")
    }

    @Test
    fun foreignHostForwardsNothing() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "https://opensubtitles.org/sub.srt",
            streamHeaders = creds,
        )
        assertTrue(out.isEmpty(), "no stream credentials may cross to a foreign subtitle host")
    }

    @Test
    fun httpsToHttpDowngradeForwardsNothing() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "http://host.example/sub.srt",
            streamHeaders = creds,
        )
        assertTrue(out.isEmpty(), "credentials must not ride an https->http downgrade")
    }

    @Test
    fun httpStreamSameHostForwards() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "http://host.example/stream.mkv",
            subtitleUrl = "http://host.example/sub.srt",
            streamHeaders = creds,
        )
        assertEquals(creds, out, "no downgrade when the stream itself is http")
    }

    @Test
    fun hopByHopHeadersNeverForwarded() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "https://host.example/sub.srt",
            streamHeaders = mapOf(
                "Authorization" to "Bearer secret",
                "Range" to "bytes=0-",
                "Host" to "host.example",
                "connection" to "keep-alive",
                "Transfer-Encoding" to "chunked",
            ),
        )
        assertEquals(mapOf("Authorization" to "Bearer secret"), out, "only Authorization survives the hop-by-hop filter")
    }

    @Test
    fun nonHttpOrMissingUrlForwardsNothing() {
        assertTrue(
            SubtitleCredentialScope.forwardableStreamHeaders(
                streamUrl = "https://host.example/stream.mkv",
                subtitleUrl = "ftp://host.example/sub.srt",
                streamHeaders = creds,
            ).isEmpty(),
            "non-http subtitle url forwards nothing",
        )
        assertTrue(
            SubtitleCredentialScope.forwardableStreamHeaders(
                streamUrl = null,
                subtitleUrl = "https://host.example/sub.srt",
                streamHeaders = creds,
            ).isEmpty(),
            "missing stream url forwards nothing",
        )
    }

    @Test
    fun redirectHostChangeDetected() {
        val origin = "https://host.example/sub.srt"
        assertFalse(
            SubtitleCredentialScope.redirectLeavesOriginHost(origin, "https://host.example/cdn/sub.srt"),
            "same host with path change only is not a leaving hop",
        )
        assertTrue(
            SubtitleCredentialScope.redirectLeavesOriginHost(origin, "https://cdn.other.net/sub.srt"),
            "a hop to another host is a leaving hop",
        )
        assertTrue(
            SubtitleCredentialScope.redirectLeavesOriginHost(origin, "not a url"),
            "an unparseable hop fails closed",
        )
    }
}
