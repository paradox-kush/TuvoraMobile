package com.nuvio.app.features.iptv

import com.nuvio.app.features.search.IptvSearchScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** F85: Search's scope chips (features/search, which may not import iptv) key on the rows XtreamSearchIndex emits. Lives on the fork side so the firewall allows the pair. */
class IptvSearchScopeKeysTest {
    @Test
    fun `scope keys match the rows the Xtream search emits`() {
        assertEquals(
            listOf(
                XtreamSearchIndex.SECTION_KEY_CHANNELS,
                XtreamSearchIndex.SECTION_KEY_MOVIES,
                XtreamSearchIndex.SECTION_KEY_SERIES,
            ),
            IptvSearchScope.entries.map { it.sectionKey },
        )
    }
}
