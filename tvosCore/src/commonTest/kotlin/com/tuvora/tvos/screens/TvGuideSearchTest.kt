package com.tuvora.tvos.screens

import com.nuvio.app.features.livetv.LiveGuideChannel
import kotlin.test.*

class TvGuideSearchTest {
    private fun channel(id: String, name: String) = LiveGuideChannel(id, name, null, 0, null)
    @Test fun providerFormattingAndWordOrderDoNotHideMatches() {
        val rows = listOf(channel("1", "UK | BBC-ONE FHD"), channel("2", "BBC One"), channel("3", "BBC Two"))
        assertEquals(listOf("2", "1"), TvGuideSearch.channels(rows, "bbc one").map { it.contentId })
        assertEquals(listOf("1"), TvGuideSearch.channels(rows, "FHD BBC").map { it.contentId })
        assertEquals(3, rows.size)
    }
    @Test fun emptyAndPunctuationOnlyInputDoesNotPretendToMatchEverything() {
        val rows = listOf(channel("1", "BBC One"))
        assertTrue(TvGuideSearch.channels(rows, "").isEmpty())
        assertTrue(TvGuideSearch.channels(rows, "---").isEmpty())
    }
}
