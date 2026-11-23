package com.nuvio.app.core.contracts

import com.nuvio.app.features.iptv.match.XtreamStreamSource
import com.nuvio.app.features.iptv.stalker.StalkerLinkRefusal
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** B02: a source's refusal reaches the viewer as a named reason, never a silent null. */
class DeferredResolveRefusalTest {

    @Test
    fun `each named refusal has its own message key and the rest share the generic one`() {
        assertEquals("provider_source_session_limit", DeferredResolveRefusal.SESSION_LIMIT.messageKey, "limit")
        assertEquals("provider_source_nothing_to_play", DeferredResolveRefusal.NOTHING_TO_PLAY.messageKey, "nothing_to_play")
        assertEquals("provider_source_access_denied", DeferredResolveRefusal.ACCESS_DENIED.messageKey, "access_denied")
        assertEquals("provider_source_resolve_failed", DeferredResolveRefusal.OTHER.messageKey, "generic")
    }

    @Test
    fun `a portal refusal maps to its neutral reason`() {
        assertEquals(DeferredResolveRefusal.SESSION_LIMIT, XtreamStreamSource.refusalOf(StalkerLinkRefusal.SESSION_LIMIT), "limit")
        assertEquals(DeferredResolveRefusal.NOTHING_TO_PLAY, XtreamStreamSource.refusalOf(StalkerLinkRefusal.NOTHING_TO_PLAY), "nothing_to_play")
        assertEquals(DeferredResolveRefusal.ACCESS_DENIED, XtreamStreamSource.refusalOf(StalkerLinkRefusal.ACCESS_DENIED), "access_denied")
        listOf(StalkerLinkRefusal.LINK_FAULT, StalkerLinkRefusal.PORTAL_ERROR, StalkerLinkRefusal.NO_LINK, StalkerLinkRefusal.REQUEST_FAILED)
            .forEach { assertEquals(DeferredResolveRefusal.OTHER, XtreamStreamSource.refusalOf(it), "generic for $it") }
    }

    private class Plain(private val minted: String?) : StreamSourceProvider {
        override fun isHandledId(videoId: String?) = false
        override fun isStalkerSource(videoId: String) = false
        override fun directStreamItem(videoId: String): StreamItem? = null
        override fun matchSourceGroups(type: String) = emptyList<StreamSourceGroup>()
        override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?) =
            emptyList<StreamItem>()
        override fun isMatchSourceId(providerAddonId: String) = false
        override fun isDeferredUrl(url: String?) = true
        override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? = minted
    }

    @Test
    fun `a provider that only knows the url answer reports the generic reason on null`() = runTest {
        assertEquals(DeferredResolveResult.Minted("https://x/y"), Plain("https://x/y").resolveDeferred("d", false), "minted")
        assertEquals(DeferredResolveResult.Refused(DeferredResolveRefusal.OTHER), Plain(null).resolveDeferred("d", false), "null")
        assertEquals(DeferredResolveResult.Refused(DeferredResolveRefusal.OTHER), Plain(" ").resolveDeferred("d", false), "blank")
    }
}
