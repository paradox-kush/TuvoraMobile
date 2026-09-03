package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.match.IndexedItem
import com.nuvio.app.features.iptv.match.MatchDbDriver
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * RED-FIRST regression for the live-favourite renumber bug (Overlay Build Spec P1, test "stream_id
 * wholesale renumber"; mechanism documented in commit 6c622d49 "panels renumber their catalogs").
 *
 * A live favourite is stored as `xtream:{account}:live:{sid}` and today
 * [XtreamItemRegistry.liveStreamUrlFor] rebuilds the play URL from that FROZEN sid
 * (`XtreamClient.streamUrl` → `/live/{u}/{p}/{sid}.ts`) with no re-resolution against the current
 * catalog. When the provider renumbers the channel (100 → 900, same name + tvg-id) the favourite
 * keeps playing sid 100: a dead stream, or a different channel the panel reassigned that id to.
 *
 * Expected after P1: the favourite resolves through its identity to the CURRENT sid in the catalog.
 * This test is red on the current code and must go green without being rewritten.
 */
class LiveFavouriteRenumberTest {

    private val account = XtreamAccount(
        id = "http://panel.example.com:8080|u",
        name = "Panel",
        baseUrl = "http://panel.example.com:8080",
        username = "u",
        password = "p",
    )

    private fun bbcOne(sid: Int) = IndexedItem(
        sid = sid, name = "BBC ONE HD", year = null, tmdb = null, ext = null,
        poster = null, categoryId = "uk", epgId = "BBCOne.uk", hasArchive = false,
    )

    @BeforeTest
    fun setUp() {
        MatchDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        XtreamRepository.installAccountsForTest(listOf(account))
    }

    @Test
    fun `a live favourite survives a provider stream id renumber`() = runBlocking {
        val provider = account.id
        // Day 1: BBC ONE HD is stream 100 and the user favourites it. All the app keeps is this id.
        XtreamMatchIndex.rebuild(provider, MatchKind.LIVE, listOf(bbcOne(100)))
        val favouriteId = XtreamItemRegistry.liveId(account.id, 100)

        val before = XtreamItemRegistry.liveStreamUrlForAsync(favouriteId)
        assertNotNull(before, "baseline: favourite resolves while sid 100 exists")
        assertTrue(before.endsWith("/live/u/p/100.ts"), "baseline resolves to sid 100 but was $before")

        // Day 2: the panel renumbers its catalog. Same channel (name + tvg-id), new sid; 100 is gone.
        XtreamMatchIndex.rebuild(provider, MatchKind.LIVE, listOf(bbcOne(900)))

        val after = XtreamItemRegistry.liveStreamUrlForAsync(favouriteId)
        assertNotNull(after, "favourite must still resolve after the renumber")
        assertTrue(
            after.endsWith("/live/u/p/900.ts"),
            "favourite must follow the channel to its CURRENT sid 900 (identity = name + tvg-id), " +
                "but resolved from the frozen sid: $after",
        )
    }
}
