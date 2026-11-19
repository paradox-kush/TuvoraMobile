package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceFallbackPolicyTest {
    // The provider-id namespaces the IPTV feature registers (direct lane + TMDB-matched lane).
    private val isOwnProviderId: (String?) -> Boolean = { id ->
        id == "xtream" || id?.startsWith("xtream-match:") == true
    }

    @Test
    fun anAddonSourceMayFallBackToTheNextMatch() {
        assertTrue(SourceFallbackPolicy.mayFallBackFrom("addon:torrents", "movie", "url", isOwnProviderId))
    }

    @Test
    fun aStalkerOrIptvSourceNeverFallsBack() {
        assertFalse(SourceFallbackPolicy.mayFallBackFrom("xtream", "movie", "url", isOwnProviderId))
        assertFalse(SourceFallbackPolicy.mayFallBackFrom("xtream-match:stalker|http://p|00:1A", "series", "url", isOwnProviderId))
    }

    @Test
    fun liveContentNeverFallsBackWhateverTheProvider() {
        assertFalse(SourceFallbackPolicy.mayFallBackFrom("addon:live", "live", "url", isOwnProviderId))
        assertFalse(SourceFallbackPolicy.mayFallBackFrom("addon:live", "tv", "live", isOwnProviderId))
    }

    @Test
    fun anOwnSourceIsNeverPickedAsTheFallbackTarget() {
        assertFalse(SourceFallbackPolicy.mayFallBackTo("xtream-match:stalker|http://p|00:1A", isOwnProviderId))
        assertTrue(SourceFallbackPolicy.mayFallBackTo("addon:torrents", isOwnProviderId))
        assertTrue(SourceFallbackPolicy.mayFallBackTo(null, isOwnProviderId))
    }
}
