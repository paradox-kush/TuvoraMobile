package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork behaviour kept on upstream's provider: add-on aggregators list Placeholder stand-ins until PlaybackInfo. */
class PlaceholderVersionsTest {
    private val session = ServerSession(
        ServerConnection(
            id = "c1", providerId = "jellyfin", name = "Aio", address = "https://aio.example.com/jellyfin",
            remoteServerId = "s1", remoteUserId = "u1", userName = "u", credentialRef = "k1",
        ),
        token = "t",
    )

    private val itemWithPlaceholder = """{"Id":"m1","Name":"Movie","Type":"Movie",
        "MediaSources":[{"Id":"m1","Type":"Placeholder","Name":"Streams resolve on play"}]}"""
    private val playbackInfo = """{"PlaySessionId":"p1","MediaSources":[
        {"Id":"m1","Name":"4K HDR","Container":"mkv","SupportsDirectPlay":true},
        {"Id":"m1b","Name":"1080p","Container":"mkv","SupportsDirectPlay":true}]}"""

    @Test
    fun `placeholder only item asks PlaybackInfo and lists the real versions`() = runTest {
        val http = TestHttp { request -> if (request.url.encodedPath.endsWith("/PlaybackInfo")) playbackInfo else itemWithPlaceholder }
        val candidates = JellyfinProvider(http.client).candidates(session, "m1")
        assertEquals(listOf("m1", "m1b"), candidates.map { it.target.mediaSourceId }, "both real versions are offered")
        assertTrue(http.requests.any { it.url.encodedPath.endsWith("/PlaybackInfo") }, "PlaybackInfo was asked")
    }

    @Test
    fun `item that already lists versions does not call PlaybackInfo`() = runTest {
        val real = """{"Id":"m1","Name":"Movie","Type":"Movie","MediaSources":[{"Id":"a","Name":"1080p","SupportsDirectPlay":true}]}"""
        val http = TestHttp { real }
        val candidates = JellyfinProvider(http.client).candidates(session, "m1")
        assertEquals(1, candidates.size, "the listed version is returned")
        assertFalse(http.requests.any { it.url.encodedPath.endsWith("/PlaybackInfo") }, "no extra request")
    }

    @Test
    fun `needsPlaybackInfo is true for empty listings or only placeholders`() {
        val placeholder = MediaSource(id = "p", type = "Placeholder")
        val real = MediaSource(id = "r", type = "Default")
        assertTrue(VersionListPolicy.needsPlaybackInfo(listOf(placeholder)), "all placeholders")
        assertFalse(VersionListPolicy.needsPlaybackInfo(listOf(placeholder, real)), "mixed")
        assertTrue(VersionListPolicy.needsPlaybackInfo(emptyList()), "empty")
    }
}
