package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.source
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerMetaSourceTest {
    @AfterTest
    fun reset() = MediaServerItemRegistry.reset()

    private val client = FakeClient()
    private fun rig() = TestRig(clientFactory = { client }).also { r ->
        val e = entry()
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    private fun id(kind: String, item: String) = "ms:jellyfin:$M:$U:$kind:$item"

    @Test
    fun aMovieBecomesNativeMetaAndRegistersItsSources() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "The Matrix", sources = listOf(source("a"), source("b", height = 720))) { it.copy(productionYear = 1999) }
        val meta = assertNotNull(MediaServerMetaSource(rig.store, rig.services).buildNativeMeta(id("movie", "m1")))
        assertEquals("The Matrix", meta.name); assertEquals(id("movie", "m1"), meta.id)
        assertEquals(listOf("a", "b"), MediaServerItemRegistry.get(id("movie", "m1"))!!.sources.map { it.id })
    }

    @Test
    fun aSeriesRegistersItsEpisodesSoTheyPlayThroughTheDirectLane() = runTest {
        val rig = rig()
        client.items["s1"] = item("s1", "Severance", "Series")
        client.episodesOf["s1"] = listOf(item("e1", "Good News", "Episode") { it.copy(indexNumber = 1, parentIndexNumber = 1) })
        val meta = assertNotNull(MediaServerMetaSource(rig.store, rig.services).buildNativeMeta(id("series", "s1")))
        assertEquals(listOf(id("episode", "e1")), meta.videos.map { it.id })
        assertNotNull(MediaServerItemRegistry.get(id("episode", "e1")), "the episode is registered for the direct lane")
        assertTrue(meta.videos.all { it.streams.isEmpty() })
    }

    @Test
    fun theLanesDoNotBuildPagesForWhatIsNotTheirs() = runTest {
        val rig = rig()
        val src = MediaServerMetaSource(rig.store, rig.services)
        assertTrue(src.handlesId(id("movie", "m1"))); assertFalse(src.handlesId("xtream:a|b:vod:1")); assertFalse(src.handlesId("tt0133093"))
        assertNull(src.buildNativeMeta("xtream:a|b:vod:1"))
        assertNull(src.buildNativeMeta(id("episode", "e1")), "an episode id has no page of its own")
        assertNull(src.buildNativeMeta(id("movie", "missing")))
        assertNull(src.buildNativeMeta("ms:jellyfin:$M:someone-else:movie:m1"), "no entry for that user")
    }

    @Test
    fun aRevokedTokenFailsQuietlyAndDropsTheSession() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Http(401)
        assertNull(MediaServerMetaSource(rig.store, rig.services).buildNativeMeta(id("movie", "m1")))
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun anOfflineServerIsNullNotACrash() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Unreachable("down")
        assertNull(MediaServerMetaSource(rig.store, rig.services).buildNativeMeta(id("movie", "m1")))
        assertTrue(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun ensureStreamRegisteredRebuildsAColdStartMissWithOneFetch() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "The Matrix", sources = listOf(source("a")))
        val src = MediaServerMetaSource(rig.store, rig.services)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = false, forceMint = false))
        assertEquals(1, client.itemRequests.size)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = false, forceMint = false))
        assertEquals(1, client.itemRequests.size, "already registered: no second fetch")
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = true, forceMint = false))
        assertEquals(2, client.itemRequests.size)
        assertFalse(src.ensureStreamRegistered(id("movie", "gone"), false, false))
        assertFalse(src.ensureStreamRegistered("tt1", false, false))
    }

    @Test
    fun aCardRegisteredWithoutMediaSourcesIsRefetchedOnceForPlayback() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "The Matrix", sources = listOf(source("a")))
        val src = MediaServerMetaSource(rig.store, rig.services)
        // a Home row / episode list registers cards from a light fetch: no MediaSources yet
        MediaServerItemRegistry.register(MediaServerItemRegistry.Item(id("movie", "m1"), "jellyfin:$M:$U", MediaServerIds.Kind.MOVIE, "m1", "The Matrix", null, emptyList(), null, null, null))
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = false, forceMint = false))
        assertEquals(1, client.itemRequests.size, "the sourceless record is completed with one fetch")
        assertEquals(listOf("a"), MediaServerItemRegistry.get(id("movie", "m1"))!!.sources.map { it.id })
    }

    @Test
    fun theRegistryKeyIsTheIdTheCallerAskedWith() = runTest {
        val rig = rig()
        client.items["e1"] = item("e1", "Pilot", "Episode") { it.copy(seriesId = "s1") }
        assertTrue(MediaServerMetaSource(rig.store, rig.services).ensureStreamRegistered(id("episode", "e1"), false, false))
        assertEquals(id("episode", "e1"), MediaServerItemRegistry.get(id("episode", "e1"))!!.contentId)
    }
    @Test
    fun aHydratedEmptySourceListDoesNotCauseAnotherFullItemFetch() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "Prepared on demand")
        val src = MediaServerMetaSource(rig.store, rig.services)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), false, false))
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), false, false))
        assertEquals(1, client.itemRequests.size)
        assertTrue(MediaServerItemRegistry.get(id("movie", "m1"))!!.sourcesLoaded)
    }
}
