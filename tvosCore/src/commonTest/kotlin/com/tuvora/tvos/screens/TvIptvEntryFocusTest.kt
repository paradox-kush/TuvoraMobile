package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamHubSection
import kotlin.test.Test
import kotlin.test.assertEquals

class TvIptvEntryFocusTest {
    @Test
    fun `entering Live TV lands on the channel list not the Movies chip`() {
        assertEquals(TvIptvLanding.Channels, TvIptvEntryFocus.landing(XtreamHubSection.LIVE, hasChannels = true), "live with channels")
    }

    @Test
    fun `live without channels and the other sections land on the header`() {
        assertEquals(TvIptvLanding.SelectedChip, TvIptvEntryFocus.landing(XtreamHubSection.LIVE, hasChannels = false), "nothing to land on")
        assertEquals(TvIptvLanding.SelectedChip, TvIptvEntryFocus.landing(XtreamHubSection.MOVIES, hasChannels = true), "movies")
        assertEquals(TvIptvLanding.SelectedChip, TvIptvEntryFocus.landing(XtreamHubSection.SERIES, hasChannels = true), "series")
    }
}
