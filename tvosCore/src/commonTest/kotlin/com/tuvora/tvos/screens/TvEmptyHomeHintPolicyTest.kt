package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvEmptyHomeHintPolicyTest {
    @Test
    fun `full builds suggest add-ons or a playlist`() {
        assertEquals(TvEmptyHomeHint.INSTALL_SOURCES, TvEmptyHomeHintPolicy.hint(addonsEnabled = true, hasAnyIptvPlaylist = false))
        assertEquals(TvEmptyHomeHint.INSTALL_SOURCES, TvEmptyHomeHintPolicy.hint(addonsEnabled = true, hasAnyIptvPlaylist = true))
    }

    @Test
    fun `store build without a playlist asks for one`() {
        assertEquals(TvEmptyHomeHint.ADD_PLAYLIST, TvEmptyHomeHintPolicy.hint(addonsEnabled = false, hasAnyIptvPlaylist = false))
    }

    // Regression: StoreCopy told a viewer who already had a playlist to "Add your IPTV playlist in Settings".
    @Test
    fun `store build with a playlist points at the IPTV tab`() {
        assertEquals(TvEmptyHomeHint.PLAYLIST_IN_IPTV_TAB, TvEmptyHomeHintPolicy.hint(addonsEnabled = false, hasAnyIptvPlaylist = true))
    }
}
