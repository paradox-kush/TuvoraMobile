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
    fun `the first channel claims focus only until the viewer has moved`() {
        assertEquals(true, TvIptvEntryFocus.claimsFirstChannel(XtreamHubSection.LIVE, hasChannels = true, viewerMoved = false), "fresh entry")
        assertEquals(false, TvIptvEntryFocus.claimsFirstChannel(XtreamHubSection.LIVE, hasChannels = true, viewerMoved = true), "never steals after a move")
        assertEquals(false, TvIptvEntryFocus.claimsFirstChannel(XtreamHubSection.MOVIES, hasChannels = true, viewerMoved = false), "other sections")
    }

    @Test
    fun `live without channels and the other sections land on the header`() {
        assertEquals(TvIptvLanding.SelectedChip, TvIptvEntryFocus.landing(XtreamHubSection.LIVE, hasChannels = false), "nothing to land on")
        assertEquals(TvIptvLanding.SelectedChip, TvIptvEntryFocus.landing(XtreamHubSection.MOVIES, hasChannels = true), "movies")
        assertEquals(TvIptvLanding.SelectedChip, TvIptvEntryFocus.landing(XtreamHubSection.SERIES, hasChannels = true), "series")
    }
}
