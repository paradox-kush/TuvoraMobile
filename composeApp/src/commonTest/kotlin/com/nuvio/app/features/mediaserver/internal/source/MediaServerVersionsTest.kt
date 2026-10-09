package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.internal.AggregatorServerFixtures
import com.nuvio.app.features.mediaserver.internal.FakeHttp
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.SlowResolveServerFixtures
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.json
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.ExternalIds
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.TitleFacts
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.policy.MintFailurePolicy
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Servers whose library is filled by add-ons answer ONE title with SEVERAL versions - one per add-on result. Every one is
 * offered, told apart by the add-on's own words, and a pick plays THAT version. Recorded answers only.
 */
class MediaServerVersionsTest {
    @AfterTest
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val requests = mutableListOf<String>()

    private fun lookup(id: String, name: String, year: Int, imdb: String) =
        """{"Items":[{"Id":"$id","Name":"$name","Type":"Movie","ProductionYear":$year,"ProviderIds":{"Imdb":"$imdb"}}],"TotalRecordCount":1}"""

    private fun rig(route: (String) -> String?): Pair<TestRig, String> {
        val rig = TestRig(http = FakeHttp { r ->
            requests += r.method + " " + r.url
            route(r.url)?.let { json(it) } ?: json("not found: ${r.url}", 404)
        })
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-LIVE"))
        return rig to e.serverKey
    }

    private fun lane(rig: TestRig, facts: TitleFacts) = MediaServerMatchLane(rig.store, rig.services, { rig.nowMs }, { _, _ -> facts })

    // ---- a standalone server: six results, six versions; the first carries the ITEM id ----

    private val manyItem = "7c8cf7256b5c4167abfbd0711fa6a20c"
    private fun manyRig() = rig { u ->
        when {
            "/Items?" in u -> lookup(manyItem, "Many Streams Movie", 2012, "tt0000011")
            "/Items/$manyItem" in u -> SlowResolveServerFixtures.SR_ITEM_MANY_VERSIONS
            else -> null
        }
    }
    private val manyFacts = TitleFacts(ExternalIds(imdb = "tt0000011"), primary = "Many Streams Movie", year = 2012)

    @Test
    fun everyResultOfATitleIsOfferedAsItsOwnVersion() = runTest {
        val (rig, key) = manyRig()
        val streams = lane(rig, manyFacts).streams(MediaServerIds.matchGroupId(key), "movie", "tt0000011", null, null)
        assertEquals(6, streams.size)
        assertEquals(6, streams.map { it.url }.toSet().size)
    }

    @Test
    fun pickingTheFirstListedVersionAsksForThatVersionNotTheServersOwnChoice() = runTest {
        // recorded: MediaSourceId = the item id streamed the server's preferred result (2160p REMUX), not the listed 1080p BluRay
        val (rig, key) = manyRig()
        val first = lane(rig, manyFacts).streams(MediaServerIds.matchGroupId(key), "movie", "tt0000011", null, null).first()
        assertEquals("ms-deferred:jellyfin:$M:$U|$manyItem|8e5ca1f5b4d858debaad8a62a3667978", first.url)
    }

    @Test
    fun versionsThatProbeAlikeAreToldApartByTheAddOnsOwnWords() = runTest {
        val (rig, key) = manyRig()
        val streams = lane(rig, manyFacts).streams(MediaServerIds.matchGroupId(key), "movie", "tt0000011", null, null)
        val described = streams.associate { it.description to it.name }
        assertEquals("1080p · H.264", described["Server A · 1080p · BluRay x264 12 GB"])
        assertEquals("1080p · H.264", described["Server B · 1080p · WEB-DL H264 4.2 GB"])
        assertEquals(6, streams.mapNotNull { it.description }.toSet().size, "every version has its own description: ${streams.map { it.description }}")
    }

    // ---- an aggregator that resolves only on play: the item page lists placeholders, PlaybackInfo the versions ----

    private val lazyItem = "a1110100000000000cffffffff000000"
    private val noneItem = "a11101000000000006ffffffff000000"
    private fun lazyRig() = rig { u ->
        when {
            "/Items?" in u && "Lazy" in u -> lookup(lazyItem, "Lazy Versions Movie", 2011, "tt0000012")
            "/Items?" in u && "No%20Streams" in u || "/Items?" in u && "No+Streams" in u -> lookup(noneItem, "No Streams Movie", 2017, "tt0000006")
            "/Items/$lazyItem/PlaybackInfo" in u -> AggregatorServerFixtures.AG_PLAYBACKINFO_LAZY
            "/Items/$lazyItem" in u -> AggregatorServerFixtures.AG_ITEM_LAZY
            "/Items/$noneItem/PlaybackInfo" in u -> AggregatorServerFixtures.AG_PLAYBACKINFO_NO_STREAMS
            "/Items/$noneItem" in u -> AggregatorServerFixtures.AG_ITEM_NO_STREAMS
            else -> null
        }
    }

    @Test
    fun placeholderVersionsAreNeverOfferedAndTheRealOnesComeFromPlaybackInfo() = runTest {
        val (rig, key) = lazyRig()
        val facts = TitleFacts(ExternalIds(imdb = "tt0000012"), primary = "Lazy Versions Movie", year = 2011)
        val streams = lane(rig, facts).streams(MediaServerIds.matchGroupId(key), "movie", "tt0000012", null, null)
        assertEquals(3, streams.size, "${streams.map { it.name }}")
        assertTrue(streams.none { it.name == "Load versions" || it.description.orEmpty().contains("Load versions") })
        assertEquals("[Service A] Release 2160p · Release.2160p.WEB.DL.HEVC.15.GB.mp4 · 💾1.18 MiB", streams.first().description)
        assertTrue(requests.any { "/Items/$lazyItem/PlaybackInfo" in it }, "the versions were asked for: $requests")
    }

    @Test
    fun aTitleTheAggregatorFoundNothingForOffersNothing() = runTest {
        val (rig, key) = lazyRig()
        val facts = TitleFacts(ExternalIds(imdb = "tt0000006"), primary = "No Streams Movie", year = 2017)
        assertEquals(emptyList(), lane(rig, facts).streams(MediaServerIds.matchGroupId(key), "movie", "tt0000006", null, null))
    }

    @Test
    fun openingAServerTitleRegistersItsRealVersionsNotThePlaceholders() = runTest {
        val (rig, _) = lazyRig()
        val id = "ms:jellyfin:$M:$U:movie:$lazyItem"
        assertNotNull(MediaServerMetaSource(rig.store, rig.services).buildNativeMeta(id))
        val streams = MediaServerStreamSourceProvider(rig.store, rig.services).directStreamItems(id)
        assertEquals(3, streams.size, "${streams.map { it.name }}")
        assertTrue(streams.none { it.name.orEmpty().contains("Load versions") })
    }

    @Test
    fun aPlaceholderIsNeverMintedIntoAPlayUrl() = runTest {
        val (rig, key) = lazyRig()
        val reasons = mutableListOf<MintFailurePolicy.Reason>()
        val provider = MediaServerStreamSourceProvider(rig.store, rig.services, onMintFailure = { reasons += it })
        assertNull(provider.resolveDeferredUrl(MediaServerIds.deferredUrl(key, noneItem, null), forceMint = false))
        assertEquals(listOf(MintFailurePolicy.noPlayableSource), reasons)
    }
}
