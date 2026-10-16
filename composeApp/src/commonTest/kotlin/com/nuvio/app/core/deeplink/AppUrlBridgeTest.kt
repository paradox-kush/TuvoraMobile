package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUrlBridgeTest {

    @Test
    fun `parses party invites from the app and the Tuvora website`() {
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("nuvio://party/k7m-2qx"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("nuvio://party?code=K7M2QX"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://tuvora.co/party/K7M2QX"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://TUVORA.co/party/K7M2QX/"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://tuvora.co/party/k7m2qx?utm=x"))
        assertNull(parseAppDeepLink("https://tuvora.co/party/K7M2Q"))
        assertNull(parseAppDeepLink("https://tuvora.co/party"))
        assertNull(parseAppDeepLink("https://tuvora.co/link?code=K7M2QX"))
        assertNull(parseAppDeepLink("https://evil.example/party/K7M2QX"))
        assertNull(parseAppDeepLink("https://tuvora.co.evil.example/party/K7M2QX"))
    }

    @Test
    fun `party links are exactly https on tuvora dot co like the manifest filter and the app-site association`() {
        assertNull(parseAppDeepLink("http://tuvora.co/party/K7M2QX"), "plain http")
        assertNull(parseAppDeepLink("https://www.tuvora.co/party/K7M2QX"), "www is not a claimed host")
        // Upstream's site: we cannot verify nuvio.tv, and its invites point at upstream's backend.
        assertNull(parseAppDeepLink("https://nuvio.tv/party/K7M2QX"), "nuvio.tv is upstream's host")
    }

    @Test
    fun `parses existing notification meta deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://meta?type=series&id=tt0944947"),
        )
    }

    @Test
    fun `parses direct nuvio addon install deeplink`() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            parseAppDeepLink("nuvio://free.nebulapro.xyz/sports/i/free/manifest.json"),
        )
    }

    @Test
    fun `parses stremio addon install deeplink`() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            parseAppDeepLink("stremio://free.nebulapro.xyz/sports/i/free/manifest.json"),
        )
    }

    @Test
    fun `parses direct imdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://series/tt0944947"),
        )
    }

    @Test
    fun `parses provider imdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://imdb/series/tt0944947"),
        )
    }

    @Test
    fun `parses provider tmdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tmdb:1399"),
            parseAppDeepLink("nuvio://tmdb/tv/1399"),
        )
    }

    @Test
    fun `does not treat reserved auth link as addon install`() {
        assertNull(parseAppDeepLink("nuvio://auth/trakt?code=abc"))
    }

    @Test
    fun `does not treat non-host stremio link as addon install`() {
        assertNull(parseAppDeepLink("stremio://detail/series/tt0944947"))
    }

    @Test
    fun `parses a provider setup link`() {
        assertEquals(
            AppDeepLink.SetupCode("TUV-ABCD-EFGH-JKMN"),
            parseAppDeepLink("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN"),
        )
        assertEquals(
            AppDeepLink.SetupCode("TUV-ABCD-EFGH-JKMN"),
            parseAppDeepLink("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN?utm=x"),
        )
        assertEquals(AppDeepLink.SetupCode("TUV-ABCD-EFGH-JKMN"), parseAppDeepLink("nuvio://s/TUV-ABCD-EFGH-JKMN"))
    }

    @Test
    fun `only the setup path on the Tuvora host is a setup link`() {
        assertNull(parseAppDeepLink("https://tuvora.co/s"))
        assertNull(parseAppDeepLink("https://tuvora.co/privacy"))
        assertNull(parseAppDeepLink("https://evil.example.com/s/TUV-ABCD-EFGH-JKMN"))
        assertNull(parseAppDeepLink("https://tuvora.co.evil.example.com/s/TUV-ABCD-EFGH-JKMN"))
        assertNull(parseAppDeepLink("nuvio://s"))
    }

    @Test
    fun `the setup link is exactly https on tuvora dot co like the manifest filter and the app-site association`() {
        // Security L12: the parser must not be wider than what the platforms verify.
        assertNull(parseAppDeepLink("http://tuvora.co/s/TUV-ABCD-EFGH-JKMN"), "plain http")
        assertNull(parseAppDeepLink("https://www.tuvora.co/s/TUV-ABCD-EFGH-JKMN"), "www is not a claimed host")
        assertNull(parseAppDeepLink("https://TUVORA.CO.evil.example/s/TUV-ABCD-EFGH-JKMN"))
        assertEquals(AppDeepLink.SetupCode("TUV-ABCD-EFGH-JKMN"), parseAppDeepLink("HTTPS://Tuvora.Co/s/TUV-ABCD-EFGH-JKMN"), "scheme and host are case-insensitive")
    }

    @Test
    fun `a setup link never prints its code`() {
        val link = parseAppDeepLink("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN")
        assertEquals(false, link.toString().contains("ABCD"), "toString must not leak the code: $link")
    }
}
