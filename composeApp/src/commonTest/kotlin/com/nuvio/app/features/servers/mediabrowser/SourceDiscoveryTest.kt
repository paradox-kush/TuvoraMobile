package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.*
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Regression fixtures for empty and on-demand server item source listings. */
class SourceDiscoveryTest {
    private val session = ServerSession(ServerConnection("repro", "jellyfin", "Repro", "https://fixture.invalid", "s", "u", "u", "k"), "fixture-token")
    private val playable = """{"Id":"m","Name":"Movie","Type":"Movie","MediaSources":[{"Id":"source","Name":"1080p","SupportsDirectPlay":true,"Container":"mp4"}]}"""
    private val empty = """{"Id":"m","Name":"Movie","Type":"Movie","MediaSources":[]}"""
    private val placeholder = """{"Id":"m","Name":"Movie","Type":"Movie","MediaSources":[{"Id":"p","Type":"Placeholder"}]}"""
    private val info = """{"MediaSources":[{"Id":"source","Name":"1080p","SupportsDirectPlay":true,"Container":"mp4"}]}"""

    @Test fun populatedItemDoesNotRequestPlaybackInfo() = runTest {
        val h = TestHttp { playable }
        assertEquals("source", JellyfinProvider(h.client).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(1, h.requests.size)
    }

    @Test fun emptyItemDiscoversSourcesWithoutStartingPlayback() = runTest {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) info else empty }
        assertEquals("source", JellyfinProvider(h.client).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(2, h.requests.size)
        val request = h.requests.last()
        assertTrue(request.url.encodedPath.endsWith("/PlaybackInfo"))
        assertEquals("POST", request.method.value)
        val body = Json.parseToJsonElement(request.text).jsonObject
        assertEquals(false, body.getValue("IsPlayback").jsonPrimitive.boolean)
        assertEquals(false, body.getValue("AutoOpenLiveStream").jsonPrimitive.boolean)
        assertFalse("MediaSourceId" in body)
    }

    @Test fun placeholderItemDiscoversSourcesOnce() = runTest {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) info else placeholder }
        assertEquals("source", JellyfinProvider(h.client).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(2, h.requests.size)
    }

    @Test fun mixedListingUsesRealSourceWithoutLookup() = runTest {
        val h = TestHttp { playable.replace("[{", "[{\"Id\":\"p\",\"Type\":\"Placeholder\"},{") }
        assertEquals("source", JellyfinProvider(h.client).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(1, h.requests.size)
    }

    @Test fun emptyPlaybackInfoEndsEmptyWithoutRetrying() = runTest {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) """{"MediaSources":[]}""" else empty }
        assertTrue(JellyfinProvider(h.client).candidates(session, "m").isEmpty())
        assertEquals(2, h.requests.size)
    }

    @Test fun playbackInfoErrorIsNotAnEmptyResult() = runTest {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) """{"ErrorCode":"NotAllowed"}""" else empty }
        val error = assertFailsWith<ServerException> { JellyfinProvider(h.client).candidates(session, "m") }
        assertEquals(ServerFailure.FORBIDDEN, error.failure)
        assertEquals(2, h.requests.size)
    }

    @Test fun lookupFailureIsNotAnEmptyResult() = runTest {
        val h = TestHttp {
            if (it.url.encodedPath.endsWith("/PlaybackInfo")) throw ServerException(ServerFailure.UNREACHABLE)
            placeholder
        }
        val error = assertFailsWith<ServerException> { JellyfinProvider(h.client).candidates(session, "m") }
        assertEquals(ServerFailure.UNREACHABLE, error.failure)
    }

    @Test fun lookupCancellationPropagates() = runTest {
        val h = TestHttp {
            if (it.url.encodedPath.endsWith("/PlaybackInfo")) throw CancellationException("fixture cancellation")
            empty
        }
        assertFailsWith<CancellationException> { JellyfinProvider(h.client).candidates(session, "m") }
    }

    @Test fun stalledLookupEndsWithAnError() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/PlaybackInfo")) awaitCancellation()
                    respond(empty, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val error = assertFailsWith<ServerException> { JellyfinProvider(client).candidates(session, "m") }
            assertEquals(ServerFailure.UNREACHABLE, error.failure)
            assertEquals(30_000L, testScheduler.currentTime)
        } finally { client.close() }
    }
}
