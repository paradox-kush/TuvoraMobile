package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamItemRegistryTest {

    // account.id = "$baseUrl|$user" and baseUrl carries "://" and an optional ":port",
    // so the content id is full of colons — the parser must NOT naive-split on ':'.
    @Test
    fun parsesIdWhenAccountIdContainsSchemeAndPortColons() {
        val accountId = "http://example.com:8080|user"
        val id = XtreamItemRegistry.vodId(accountId, 12345)
        assertEquals("xtream:http://example.com:8080|user:vod:12345", id)

        val parsed = XtreamItemRegistry.parseId(id)
        assertTrue(parsed != null)
        assertEquals(accountId, parsed.accountId)
        assertEquals(XtreamKind.VOD, parsed.kind)
        assertEquals("12345", parsed.id)
    }

    @Test
    fun parsesStalkerIdWithPortalAndMacColonsPlusUnderscoreEpisode() {
        // A Stalker account id carries BOTH the portal's scheme/port colons AND the MAC's colons.
        val accountId = "stalker|http://fastshare1.com:8080|00:1A:79:58:B3:A6"
        val liveId = XtreamItemRegistry.liveId(accountId, 745149)
        val parsedLive = XtreamItemRegistry.parseId(liveId)
        assertTrue(parsedLive != null)
        assertEquals(accountId, parsedLive.accountId)
        assertEquals(XtreamKind.LIVE, parsedLive.kind)
        assertEquals("745149", parsedLive.id)

        // Stalker episode ids encode "{seriesId}_{episodeNum}" — the '_' keeps the inner value colon-free
        // so parseId's colon split stays anchored on kind + rawId.
        val epId = XtreamItemRegistry.episodeId(accountId, "42_3")
        val parsedEp = XtreamItemRegistry.parseId(epId)
        assertTrue(parsedEp != null)
        assertEquals(accountId, parsedEp.accountId)
        assertEquals(XtreamKind.EPISODE, parsedEp.kind)
        assertEquals("42_3", parsedEp.id)
    }

    @Test
    fun parsesIdForDefaultPortAccount() {
        val accountId = "http://example.com|user"   // no ":port" segment
        val parsed = XtreamItemRegistry.parseId(XtreamItemRegistry.liveId(accountId, 7))
        assertTrue(parsed != null)
        assertEquals(accountId, parsed.accountId)
        assertEquals(XtreamKind.LIVE, parsed.kind)
        assertEquals("7", parsed.id)
    }

    @Test
    fun roundTripsAllKinds() {
        val accountId = "https://host:443|u"
        val cases = listOf(
            XtreamItemRegistry.vodId(accountId, 1) to XtreamKind.VOD,
            XtreamItemRegistry.seriesId(accountId, 2) to XtreamKind.SERIES,
            XtreamItemRegistry.liveId(accountId, 3) to XtreamKind.LIVE,
            XtreamItemRegistry.episodeId(accountId, "99") to XtreamKind.EPISODE,
        )
        for ((cid, kind) in cases) {
            val p = XtreamItemRegistry.parseId(cid)
            assertTrue(p != null, "parse failed for $cid")
            assertEquals(accountId, p.accountId)
            assertEquals(kind, p.kind)
        }
    }

    @Test
    fun rejectsNonXtreamAndMalformedIds() {
        assertNull(XtreamItemRegistry.parseId("tt1234567"))
        assertNull(XtreamItemRegistry.parseId("tmdb:movie:550"))
        assertNull(XtreamItemRegistry.parseId("xtream:onlyprefix"))
        assertTrue(!XtreamItemRegistry.isXtreamId("tt1234567"))
        assertTrue(XtreamItemRegistry.isXtreamId("xtream:a:vod:1"))
    }

    @Test
    fun registerAndResolveStreamRoundTrips() {
        val accountId = "http://h:8000|bob"
        val cid = XtreamItemRegistry.vodId(accountId, 555)
        XtreamItemRegistry.register(
            XtreamResolvedItem(cid, accountId, XtreamKind.VOD, "The Movie", "http://h:8000/movie/bob/pw/555.mp4", poster = "p.jpg")
        )
        val item = XtreamItemRegistry.get(cid)
        assertTrue(item != null)
        assertEquals("The Movie", item.name)
        assertEquals("http://h:8000/movie/bob/pw/555.mp4", item.toStreamItem("Acct")?.url)

        XtreamItemRegistry.resetForProfile()
        assertNull(XtreamItemRegistry.get(cid))
    }
}
