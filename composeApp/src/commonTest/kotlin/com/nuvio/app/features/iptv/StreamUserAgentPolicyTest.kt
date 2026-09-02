package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * commonTest (kotlin.test) — assertion order is `assertEquals(expected, actual, message)`.
 *
 * Regression cover for a real support loop (first hit on Android TV): a Cloudflare-fronted Xtream
 * panel answered the *stream* request with HTTP 456 while the same account played in another IPTV
 * app. Before this, the [XtreamAccount.userAgent] field fed only the catalog/EPG fetch, never the
 * player stream, so the stream always went out under the spoofed-browser default and stayed blocked.
 */
class StreamUserAgentPolicyTest {

    private fun account(userAgent: String?, sourceType: String = "xtream") = XtreamAccount(
        id = "id",
        name = "name",
        baseUrl = "http://host",
        username = "realuser",
        password = "pass",
        sourceType = sourceType,
        userAgent = userAgent,
    )

    @Test
    fun pinnedUserAgentIsResolved() {
        val ua = "VLC/3.0.20 LibVLC/3.0.20"
        assertEquals(ua, StreamUserAgentPolicy.resolve(account(ua)), "the per-playlist UA is honored")
    }

    @Test
    fun noOverrideResolvesNull() {
        assertNull(StreamUserAgentPolicy.resolve(account(null)), "null means use the player default UA")
    }

    @Test
    fun blankOverrideResolvesNull() {
        assertNull(StreamUserAgentPolicy.resolve(account("   ")), "a whitespace-only UA is not sent")
    }

    @Test
    fun overrideIsTrimmed() {
        assertEquals("TiviMate/4.7.0", StreamUserAgentPolicy.resolve(account("  TiviMate/4.7.0  ")), "trimmed to a clean header")
    }

    // --- the wiring: the resolved UA reaches the player as a proxy request header ---

    private fun resolvedItem() = XtreamResolvedItem(
        contentId = "xtream:acc:vod:1",
        accountId = "acc",
        kind = XtreamKind.VOD,
        name = "A Movie",
        streamUrl = "http://host/movie/u/p/1.mkv",
    )

    @Test
    fun streamItemCarriesTheUserAgentAsAProxyHeader() {
        val item = resolvedItem().toStreamItem(accountName = "My Playlist", userAgent = "VLC/3.0.20 LibVLC/3.0.20")
        assertEquals(
            "VLC/3.0.20 LibVLC/3.0.20",
            item?.behaviorHints?.proxyHeaders?.request?.get("User-Agent"),
            "the player honors proxyHeaders.request, so the UA rides there",
        )
    }

    @Test
    fun streamItemWithoutAnOverrideSetsNoProxyHeaders() {
        val item = resolvedItem().toStreamItem(accountName = "My Playlist", userAgent = null)
        assertNull(item?.behaviorHints?.proxyHeaders, "no override -> playback is byte-identical to before")
    }
}
