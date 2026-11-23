package com.nuvio.app.features.iptv.match

import com.nuvio.app.core.contracts.DeferredResolveRefusal
import com.nuvio.app.features.iptv.stalker.StalkerLinkRefusal
import kotlin.test.Test
import kotlin.test.assertEquals

/** B02: a Stalker portal's refusal reaches the viewer as a neutral named reason (fork-side; core stays Stalker-free). */
class XtreamRefusalMappingTest {

    @Test
    fun `a portal refusal maps to its neutral reason`() {
        assertEquals(DeferredResolveRefusal.SESSION_LIMIT, XtreamStreamSource.refusalOf(StalkerLinkRefusal.SESSION_LIMIT), "limit")
        assertEquals(DeferredResolveRefusal.NOTHING_TO_PLAY, XtreamStreamSource.refusalOf(StalkerLinkRefusal.NOTHING_TO_PLAY), "nothing_to_play")
        assertEquals(DeferredResolveRefusal.ACCESS_DENIED, XtreamStreamSource.refusalOf(StalkerLinkRefusal.ACCESS_DENIED), "access_denied")
        listOf(StalkerLinkRefusal.LINK_FAULT, StalkerLinkRefusal.PORTAL_ERROR, StalkerLinkRefusal.NO_LINK, StalkerLinkRefusal.REQUEST_FAILED)
            .forEach { assertEquals(DeferredResolveRefusal.OTHER, XtreamStreamSource.refusalOf(it), "generic for $it") }
    }
}
