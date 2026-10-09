package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.PlaybackPlayMethod
import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.PlaybackNegotiation
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
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

class MediaServerStreamSourceProviderTest {
    @AfterTest
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val client = FakeClient()
    private fun rig(type: MediaServerType = MediaServerType.JELLYFIN) = TestRig(clientFactory = { client }).also { r ->
        val e = entry(type = type)
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    private fun provider(rig: TestRig) = MediaServerStreamSourceProvider(rig.store, rig.services)
    private fun id(type: String = "jellyfin", kind: String = "movie", item: String = "m1") = "ms:$type:$M:$U:$kind:$item"
    private fun register(rig: TestRig, vararg dto: com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto) {
        val e = rig.store.current().single()
        dto.forEach { MediaServerItemMapper.registered(e, it)?.let(MediaServerItemRegistry::register) }
    }

    @Test
    fun theItemRegistryIsBoundedAndKeepsTheNewest() {
        val e = entry()
        repeat(MediaServerItemRegistry.MAX_ITEMS + 50) { i ->
            MediaServerItemMapper.registered(e, item("i$i"))?.let(MediaServerItemRegistry::register)
        }
        assertEquals(MediaServerItemRegistry.MAX_ITEMS, MediaServerItemRegistry.sizeForTest())
        assertNull(MediaServerItemRegistry.get(id(item = "i0")), "the oldest went first")
        assertNotNull(MediaServerItemRegistry.get(id(item = "i${MediaServerItemRegistry.MAX_ITEMS + 49}")))
    }

    @Test
    fun ownershipIsBySourceIdPrefixAndNeverClaimsOtherSources() {
        val p = provider(rig())
        assertTrue(p.isHandledId(id())); assertFalse(p.isHandledId("xtream:http://a|b:vod:1")); assertFalse(p.isHandledId("tt0133093")); assertFalse(p.isHandledId(null))
        assertFalse(p.isStalkerSource(id()))
        assertTrue(p.isMatchSourceId("ms-match:jellyfin:$M:$U")); assertFalse(p.isMatchSourceId("ms")); assertFalse(p.isMatchSourceId("xtream-match:x"))
        assertTrue(p.isDeferredUrl("ms-deferred:jellyfin:$M:$U|i|s")); assertFalse(p.isDeferredUrl("https://nas/x"))
        assertTrue(p.matchSourceGroups("movie").isEmpty() && p.matchSourceGroups("series").isEmpty(), "the matched lane is P3")
    }

    @Test
    fun listingNeverHoldsATokenOrAPlayableUrl() {
        val rig = rig()
        register(rig, item("m1", "The Matrix", sources = listOf(source("srcA"), source("srcB", height = 720))))
        val s = assertNotNull(provider(rig).directStreamItem(id()))
        assertEquals("ms", s.addonId); assertEquals("Home", s.addonName); assertEquals("The Matrix", s.title)
        assertEquals("ms-deferred:jellyfin:$M:$U|m1|srcA", s.url, "a deferred reference minted at pick time")
        assertEquals("1080p · H.264 · 4.2 GB", s.name)
        assertFalse(s.toString().contains("TOKEN-1"), "no token on a Jellyfin stream item")
        assertNull(s.behaviorHints.proxyHeaders)
        assertNull(provider(rig).directStreamItem(id(item = "unknown")), "a registry miss is rebuilt by the meta lane")
    }

    @Test
    fun embyStreamItemsCarryTheTokenAsAHeaderNeverInTheUrl() {
        val rig = rig(MediaServerType.EMBY)
        register(rig, item("m1"))
        val s = provider(rig).directStreamItem(id("emby"))!!
        assertEquals(mapOf("X-Emby-Token" to "TOKEN-1"), s.behaviorHints.proxyHeaders?.request)
        assertFalse(s.url!!.contains("TOKEN-1"))
    }

    private fun withSidecars(vararg streams: com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaStreamDto) =
        source("srcA").let { it.copy(mediaStreams = it.mediaStreams + streams) }

    @Test
    fun sidecarTextSubtitlesRideWithTheStreamAsTokenlessUrls() {
        val rig = rig()
        val sub = com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaStreamDto(index = 4, type = "Subtitle", codec = "subrip", language = "eng", displayTitle = "English (SRT)", isExternal = true)
        val embedded = sub.copy(index = 5, isExternal = false)
        val bitmap = sub.copy(index = 6, codec = "pgssub")
        register(rig, item("m1", sources = listOf(withSidecars(sub, embedded, bitmap))))
        val s = provider(rig).directStreamItem(id())!!
        val only = s.externalSubtitles.single()
        assertEquals("http://nas:8096/Videos/m1/srcA/Subtitles/4/0/Stream.srt", only.url)
        assertEquals("eng", only.language); assertEquals("English (SRT)", only.name)
        assertNull(only.headers, "Jellyfin serves subtitle files anonymously")
        assertFalse(only.url.contains("TOKEN-1"))
    }

    @Test
    fun embySidecarSubtitlesCarryTheTokenInTheHeaderNotTheUrl() {
        val rig = rig(MediaServerType.EMBY)
        val sub = com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaStreamDto(index = 3, type = "Subtitle", codec = "srt", isExternal = true)
        register(rig, item("m1", sources = listOf(withSidecars(sub))))
        val only = provider(rig).directStreamItem(id("emby"))!!.externalSubtitles.single()
        assertEquals(mapOf("X-Emby-Token" to "TOKEN-1"), only.headers)
        assertFalse(only.url.contains("TOKEN-1"))
        assertEquals("und", only.language, "an unlabelled track is still listed")
    }

    @Test
    fun anItemWithNoKnownSourcesStillGetsADeferredUrl() {
        val rig = rig()
        register(rig, item("ep1", type = "Episode") { it.copy(seriesId = "s") })
        val s = provider(rig).directStreamItem(id(kind = "episode", item = "ep1"))!!
        assertEquals("ms-deferred:jellyfin:$M:$U|ep1|", s.url)
        assertEquals("Direct play", s.name)
    }

    private fun deferred(item: String = "m1", source: String? = "srcA") = MediaServerIds.deferredUrl("jellyfin:$M:$U", item, source)

    @Test
    fun directPlayMintsTheStaticStreamUrlWithoutAToken() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA")), "ps1")
        val url = provider(rig).resolveDeferredUrl(deferred(), forceMint = false)
        assertEquals("http://nas:8096/Videos/m1/stream?Static=true&MediaSourceId=srcA&Container=mkv&PlaySessionId=ps1", url)
        assertFalse(url!!.contains("TOKEN-1"))
        assertEquals("m1" to "srcA", client.playbackRequests.single().let { it.first to it.second.mediaSourceId }, "always pins the media source")
        val session = MediaServerPlaybackSessions.forItem("jellyfin:$M:$U", "m1")!!
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, session.playMethod); assertEquals("ps1", session.playSessionId); assertEquals("srcA", session.mediaSourceId)
    }

    @Test
    fun aFailedPlayAttemptTranscodesThroughTheServerBuiltUrl() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA")), "ps2")
        val url = provider(rig).resolveDeferredUrl(deferred(), forceMint = true)
        assertEquals("http://nas:8096/videos/i/master.m3u8?MediaSourceId=src1&ApiKey=SERVER-BUILT", url, "the server's own URL, token and all, kept verbatim")
        assertEquals(PlaybackPlayMethod.TRANSCODE, MediaServerPlaybackSessions.latestFor("jellyfin:$M:$U")!!.playMethod)
        assertTrue(client.playbackRequests.single().second.forceTranscode, "the server only builds a TranscodingUrl when asked not to direct play")
    }

    @Test
    fun theFirstAttemptNeverForcesATranscode() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA")), "ps")
        provider(rig).resolveDeferredUrl(deferred(), forceMint = false)
        assertFalse(client.playbackRequests.single().second.forceTranscode)
    }

    @Test
    fun aBlankMediaSourcePicksTheFirstTheServerOffers() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("first"), source("second")), null)
        val url = provider(rig).resolveDeferredUrl(deferred(source = null), false)
        assertTrue(url!!.contains("MediaSourceId=first"), url)
    }

    @Test
    fun aRevokedTokenDropsTheSessionAndMintsNothingSoThereIsNoRetryLoop() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Http(401)
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
        assertEquals(1, rig.store.current().size)
    }

    @Test
    fun anOfflineServerOrAnUnplayableSourceMintsNothing() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Unreachable("down")
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
        assertTrue(rig.services.isSignedIn(rig.store.current().single()), "offline is not revoked")
        client.failWith = null
        client.negotiation = PlaybackNegotiation(listOf(source("srcA", direct = false, stream = false, transcode = false, transcodingUrl = null)), null)
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
        client.negotiation = PlaybackNegotiation(emptyList(), null)
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
    }

    @Test
    fun aMalformedOrForeignDeferredUrlMintsNothing() = runTest {
        val rig = rig()
        val p = provider(rig)
        assertNull(p.resolveDeferredUrl("ms-deferred:garbage", false))
        assertNull(p.resolveDeferredUrl("https://nas/x", false))
        assertNull(p.resolveDeferredUrl(MediaServerIds.deferredUrl("jellyfin:$M:other-user", "m1", null), false), "no entry for that user")
        val noAddress = TestRig(clientFactory = { client }).also { r -> val e = entry(address = null); r.store.applyFromRemote(1, listOf(e)); r.credentials.save(e.serverKey, StoredCredential("t")) }
        assertNull(provider(noAddress).resolveDeferredUrl(deferred(), false))
    }
    @Test
    fun aSelectedVersionThatDisappearsCannotSilentlyPlayAnotherVersion() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("other-version")), "ps")
        assertNull(provider(rig).resolveDeferredUrl(deferred(source = "selected-version"), false))
        assertNull(MediaServerPlaybackSessions.latestFor("jellyfin:$M:$U"))
    }

    @Test
    fun anEmptyForcedTranscodeResponseFallsBackToTheSameOriginalVersionOnce() = runTest {
        val rig = rig()
        client.negotiationFor = { request -> PlaybackNegotiation(if (request.forceTranscode) emptyList() else listOf(source("srcA")), "ps") }
        val url = provider(rig).resolveDeferredUrl(deferred(), true)
        assertTrue(url!!.contains("Static=true&MediaSourceId=srcA"))
        assertEquals(listOf(true, false), client.playbackRequests.map { it.second.forceTranscode })
    }
}
