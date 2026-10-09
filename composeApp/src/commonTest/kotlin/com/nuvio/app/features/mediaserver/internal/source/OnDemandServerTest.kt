package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.FakeHttp
import com.nuvio.app.features.mediaserver.internal.OnDemandServerFixtures
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.SlowResolveServerFixtures
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerRequest
import com.nuvio.app.features.mediaserver.internal.client.PlaybackInfoRequest
import com.nuvio.app.features.mediaserver.internal.client.PlaybackNegotiation
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserClient
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserDialect
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaSourceDto
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.json
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.policy.MintFailurePolicy
import com.nuvio.app.features.mediaserver.internal.source
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A server that prepares streams only when playback starts (items are created on demand, streams are resolved when
 * the title is opened or played and are proxied through the server). What that changes for a client, recorded
 * against real servers.
 */
class OnDemandServerTest {
    @AfterTest
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val identity = MediaBrowserClientIdentity("Tuvora", "Pixel 8", "dev-1", "1.0.0")
    private fun api(http: FakeHttp) = MediaBrowserClient(http, "http://nas:8096/", MediaBrowserDialect.JELLYFIN, identity, "u1", { "TOK" }, { 1_790_000_000_000L })

    // ---- on-demand resolve is slow: the server blocks the request while it prepares the stream and probes the file ----

    @Test
    fun playbackInfoWaitsForAnOnDemandResolveLongerThanTheDefaultTimeout() = runTest {
        val http = FakeHttp { json(OnDemandServerFixtures.OD_PLAYBACKINFO_FAST) }
        api(http).playbackInfo("m1", PlaybackInfoRequest(mediaSourceId = "m1"))
        val waited = http.requests.single().timeoutMs
        assertTrue(waited >= 60_000L, "the server blocked PlaybackInfo 15 s while preparing the stream (plus a probe of up to 60 s) - timeout was $waited ms")
    }

    @Test
    fun anItemFetchThatAsksForMediaSourcesWaitsForTheFirstStreamSync() = runTest {
        val http = FakeHttp { json("""{"Id":"m1","Name":"x","Type":"Movie"}""") }
        api(http).item("m1", fields = "Overview,MediaSources")
        val waited = http.requests.single().timeoutMs
        assertTrue(waited >= 60_000L, "the first GET /Items/{id} with MediaSources waits for the server to prepare the streams (15 s in the recording) - timeout was $waited ms")
    }

    @Test
    fun anItemFetchWithoutMediaSourcesKeepsTheShortTimeout() = runTest {
        val http = FakeHttp { json("""{"Id":"m1","Name":"x","Type":"Movie"}""") }
        api(http).item("m1", fields = "Overview")
        assertEquals(MediaServerRequest.DEFAULT_TIMEOUT_MS, http.requests.single().timeoutMs, "a dead server must still fail fast on ordinary reads")
    }

    // ---- failure feedback: a title the server could not prepare used to fail silently ----

    private val client = FakeClient()
    private fun rig() = TestRig(clientFactory = { client }).also { r ->
        val e = entry(type = MediaServerType.JELLYFIN)
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    private fun deferred(item: String = "m1", source: String? = "m1") = MediaServerIds.deferredUrl("jellyfin:$M:$U", item, source)

    private suspend fun mintReason(negotiation: PlaybackNegotiation? = null, failWith: MediaServerException? = null): MintFailurePolicy.Reason? {
        val seen = mutableListOf<MintFailurePolicy.Reason>()
        val rig = rig()
        negotiation?.let { client.negotiation = it }
        client.failWith = failWith
        val url = MediaServerStreamSourceProvider(rig.store, rig.services, onMintFailure = { seen += it }).resolveDeferredUrl(deferred(source = negotiation?.sources?.firstOrNull()?.id), forceMint = false)
        return if (url == null) seen.singleOrNull() else null
    }

    @Test
    fun aSourceTheServerCouldNotProbeIsReportedAsUnavailable() = runTest {
        // recorded: PlaybackInfo of a title for which the server prepared no stream (or a dead link) - 200, no ErrorCode, nothing playable
        val http = FakeHttp { json(OnDemandServerFixtures.OD_PLAYBACKINFO_NO_STREAMS) }
        val negotiation = api(http).playbackInfo("dd2c", PlaybackInfoRequest())
        assertEquals(MintFailurePolicy.Reason.SOURCE_UNAVAILABLE, mintReason(negotiation))
    }

    @Test
    fun anEmptyNegotiationIsAlsoUnavailable() = runTest {
        assertEquals(MintFailurePolicy.Reason.SOURCE_UNAVAILABLE, mintReason(PlaybackNegotiation(emptyList(), null)))
    }

    @Test
    fun transportAndServerFailuresEachGetTheirOwnReason() = runTest {
        assertEquals(MintFailurePolicy.Reason.TIMED_OUT, mintReason(failWith = MediaServerException.Unreachable("slow", timedOut = true)))
        assertEquals(MintFailurePolicy.Reason.UNREACHABLE, mintReason(failWith = MediaServerException.Unreachable("down")))
        assertEquals(MintFailurePolicy.Reason.SIGN_IN_AGAIN, mintReason(failWith = MediaServerException.Http(401)))
        assertEquals(MintFailurePolicy.Reason.SERVER_ERROR, mintReason(failWith = MediaServerException.Http(500)))
        assertEquals(MintFailurePolicy.Reason.SERVER_ERROR, mintReason(failWith = MediaServerException.Http(502)))
        assertEquals(MintFailurePolicy.Reason.SERVER_ERROR, mintReason(failWith = MediaServerException.Malformed("html")))
    }

    @Test
    fun aGoodMintReportsNothing() = runTest {
        val http = FakeHttp { json(OnDemandServerFixtures.OD_PLAYBACKINFO_FAST) }
        val negotiation = api(http).playbackInfo("e5ee", PlaybackInfoRequest())
        assertNull(mintReason(negotiation))
    }

    // ---- the server's own name is the only description of a version before the server has probed it ----

    private fun dto(name: String?, streams: Boolean = false) = MediaSourceDto(id = "a", name = name, supportsDirectPlay = true)

    @Test
    fun anUnprobedVersionIsLabelledByItsFlattenedName() {
        val label = MediaServerItemMapper.sourceLabel(dto("Server A\n1080p\nWEB-DL 4.2 GB"))
        assertEquals("Server A · 1080p · WEB-DL 4.2 GB", label, "a multi-line server name must not reach a button label")
    }

    @Test
    fun aSingleLineNameIsKeptAsIs() {
        assertEquals("Version 2", MediaServerItemMapper.sourceLabel(dto("Version 2")))
    }

    @Test
    fun aProbedSourceStillUsesTheTechnicalLabel() {
        assertEquals("1080p · H.264 · 4.2 GB", MediaServerItemMapper.sourceLabel(source("a").copy(name = "Server A\n1080p")))
    }

    // ---- several prepared streams = several MediaSources; the direct lane offers every version ----

    @Test
    fun theDirectLaneListsEveryVersionOfATitle() {
        val rig = rig()
        val e = rig.store.current().single()
        MediaServerItemMapper.registered(
            e,
            item("m1", "Multi", sources = listOf(
                MediaSourceDto(id = "v1", name = "Server A\n1080p\nWEB-DL", supportsDirectPlay = true),
                MediaSourceDto(id = "v2", name = "Server B\n720p\nx264", supportsDirectPlay = true),
            )),
        )?.let(MediaServerItemRegistry::register)
        val streams = MediaServerStreamSourceProvider(rig.store, rig.services).directStreamItems("ms:jellyfin:$M:$U:movie:m1")
        assertEquals(listOf("Server A · 1080p · WEB-DL", "Server B · 720p · x264"), streams.map { it.name })
        assertEquals(listOf("ms-deferred:jellyfin:$M:$U|m1|v1", "ms-deferred:jellyfin:$M:$U|m1|v2"), streams.map { it.url })
    }

    @Test
    fun aTitleWithNoSourcesStillOffersTheOneDefaultStream() {
        val rig = rig()
        val e = rig.store.current().single()
        MediaServerItemMapper.registered(e, item("m1", "Plain"))?.let(MediaServerItemRegistry::register)
        val streams = MediaServerStreamSourceProvider(rig.store, rig.services).directStreamItems("ms:jellyfin:$M:$U:movie:m1")
        assertEquals(listOf("Direct play"), streams.map { it.name })
    }

    // ---- a search result is a stub: opening it answers with the canonical item under ANOTHER id ----

    @Test
    fun openingASearchResultRegistersTheItemUnderBothIds() = runTest {
        val rig = rig()
        // recorded: GET /Items/{search-result id} answered the item with a different Id (the one it was inserted under)
        client.items["searchid"] = item("canonicalid", "Search Only Movie", sources = listOf(MediaSourceDto(id = "canonicalid", name = "Server A\n1080p", supportsDirectPlay = true)))
        val asked = "ms:jellyfin:$M:$U:movie:searchid"
        val meta = assertNotNull(MediaServerMetaSource(rig.store, rig.services).buildNativeMeta(asked))
        assertEquals("ms:jellyfin:$M:$U:movie:canonicalid", meta.id)
        val viaSearchId = assertNotNull(MediaServerItemRegistry.get(asked), "the id the viewer arrived with must keep resolving")
        assertEquals("canonicalid", viaSearchId.itemId, "playback and reports use the canonical item")
        assertNotNull(MediaServerItemRegistry.get(meta.id))
    }

    // ---- a standalone Jellyfin-API server that prepares streams on demand, recorded from the real server ----

    @Test
    fun anOnDemandServerIdentifiesAsAJellyfinServerAndIsNotRejected() = runTest {
        val rig = TestRig(http = FakeHttp { json(SlowResolveServerFixtures.SR_SYSTEM_INFO_PUBLIC) })
        val info = rig.services.authApi("http://nas:3000", MediaServerType.JELLYFIN).publicInfo()
        assertEquals("0123456789abcdef0123456789abcdef", info.machineId)
        assertEquals("Home Server", info.name)
        assertEquals(MediaServerType.JELLYFIN, info.detectedType, "it speaks the Jellyfin dialect; ProductName says so too")
    }

    @Test
    fun aTitleWithNoStreamsPlaysAServerPlaceholderTheClientTreatsAsUnavailable() = runTest {
        // recorded: 200, SupportsDirectPlay true, Name "No streams found", Path /videos/no-streams (a 10-hour card video)
        val http = FakeHttp { json(SlowResolveServerFixtures.SR_PLAYBACKINFO_NO_STREAMS) }
        val negotiation = api(http).playbackInfo("7b02", PlaybackInfoRequest())
        assertEquals(MintFailurePolicy.Reason.SOURCE_UNAVAILABLE, mintReason(negotiation), "a card video is not a stream: say so instead of playing it for hours")
    }

    @Test
    fun aTitleThatPlaysMintsAStaticStream() = runTest {
        val http = FakeHttp { json(SlowResolveServerFixtures.SR_PLAYBACKINFO_FAST) }
        val negotiation = api(http).playbackInfo("56f0", PlaybackInfoRequest())
        assertNull(mintReason(negotiation))
    }

    @Test
    fun aProblemDetails500IsAServerError() = runTest {
        val http = FakeHttp { json(SlowResolveServerFixtures.SR_PROBLEM_500, status = 500) }
        val e = kotlin.runCatching { api(http).playbackInfo("e", PlaybackInfoRequest()) }.exceptionOrNull()
        assertEquals(MintFailurePolicy.Reason.SERVER_ERROR, MintFailurePolicy.forException(e as MediaServerException))
    }

    @Test
    fun aServerThatCannotTranscodeStillPlaysDirectWhenARetryAsksForATranscode() = runTest {
        // a direct-play-only deployment answers a forced-transcode request with nothing playable (no TranscodingUrl)
        val noTranscode = MediaSourceDto(id = "m1", container = "mp4", protocol = "File", supportsDirectPlay = false, supportsDirectStream = false, supportsTranscoding = false)
        val plays = MediaSourceDto(id = "m1", container = "mp4", protocol = "File", supportsDirectPlay = true, supportsDirectStream = true, supportsTranscoding = false)
        val rig = rig()
        client.negotiationFor = { req -> PlaybackNegotiation(listOf(if (req.forceTranscode) noTranscode else plays), "ps") }
        val seen = mutableListOf<MintFailurePolicy.Reason>()
        val url = MediaServerStreamSourceProvider(rig.store, rig.services, onMintFailure = { seen += it }).resolveDeferredUrl(deferred(), forceMint = true)
        assertEquals("http://nas:8096/Videos/m1/stream?Static=true&MediaSourceId=m1&Container=mp4&PlaySessionId=ps", url)
        assertTrue(seen.isEmpty(), "falling back to direct play is not a failure")
        assertEquals(listOf(true, false), client.playbackRequests.map { it.second.forceTranscode })
    }

    // ---- a server that implements only part of the Jellyfin API must not lose the shelves it does serve ----

    private val resumeBody = """{"Items":[{"Id":"m1","Name":"Resume Me","Type":"Movie","UserData":{"PlaybackPositionTicks":600000000}}],"TotalRecordCount":1}"""

    @Test
    fun aMissingNextUpRouteOnlyEmptiesThatShelf() = runTest {
        val http = FakeHttp { r ->
            when {
                r.url.contains("/Shows/NextUp") -> json("""{"status":404,"title":"Not Found"}""", 404)
                r.url.contains("/Items/Latest") -> json("""[{"Id":"l1","Name":"New","Type":"Movie"}]""")
                else -> json(resumeBody)
            }
        }
        val shelves = api(http).homeShelves(com.nuvio.app.features.mediaserver.api.MediaServerHomeRow.entries.toSet(), 20, "Overview")
        assertEquals(listOf("m1"), shelves.continueWatching.map { it.id })
        assertEquals(listOf("l1"), shelves.recentlyAdded.map { it.id })
        assertTrue(shelves.nextUp.isEmpty())
    }

    @Test
    fun aMissingLatestRouteOnlyEmptiesThatShelf() = runTest {
        val http = FakeHttp { r -> if (r.url.contains("/Items/Latest")) json("", 501) else json(resumeBody) }
        val shelves = api(http).homeShelves(com.nuvio.app.features.mediaserver.api.MediaServerHomeRow.entries.toSet(), 20, "Overview")
        assertEquals(listOf("m1"), shelves.continueWatching.map { it.id })
        assertTrue(shelves.recentlyAdded.isEmpty())
    }

    @Test
    fun aRevokedTokenOnAShelfStillSurfacesSoTheSessionDrops() = runTest {
        val http = FakeHttp { r -> if (r.url.contains("/Items/Latest")) json("", 401) else json(resumeBody) }
        val e = kotlin.runCatching { api(http).homeShelves(com.nuvio.app.features.mediaserver.api.MediaServerHomeRow.entries.toSet(), 20, "Overview") }.exceptionOrNull()
        assertTrue((e as MediaServerException.Http).isUnauthorized)
    }
}
