package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamLinkReusePolicyTest {
    // The provider-id namespaces the IPTV feature registers (direct lane + TMDB-matched lane).
    private val isOwnProviderId: (String?) -> Boolean = { id ->
        id == "xtream" || id?.startsWith("xtream-match:") == true
    }

    @Test
    fun stalkerSingleUseLinkIsNeverReusable() {
        assertFalse(
            StreamLinkReusePolicy.mayReuse(
                addonId = "xtream-match:stalker|http://portal.example|00:1A:79:00:00:01",
                isOwnProviderId = isOwnProviderId,
            ),
        )
    }

    @Test
    fun directLaneIptvLinkIsNeverReusable() {
        assertFalse(StreamLinkReusePolicy.mayReuse(addonId = "xtream", isOwnProviderId = isOwnProviderId))
    }

    @Test
    fun addonAndDebridLinksStayReusable() {
        assertTrue(StreamLinkReusePolicy.mayReuse(addonId = "addon:torrents", isOwnProviderId = isOwnProviderId))
        assertTrue(StreamLinkReusePolicy.mayReuse(addonId = null, isOwnProviderId = isOwnProviderId))
    }
}
