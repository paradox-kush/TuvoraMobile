package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamHubSection

enum class TvIptvLanding { Channels, SelectedChip }

/** Where focus lands when the IPTV hub is entered from the sidebar (Android TV: "first entry lands on the channel list"). */
object TvIptvEntryFocus {
    fun landing(section: XtreamHubSection, hasChannels: Boolean): TvIptvLanding =
        if (section == XtreamHubSection.LIVE && hasChannels) TvIptvLanding.Channels else TvIptvLanding.SelectedChip

    /** The first channel takes focus when it appears, but only while the viewer has not pressed a direction (never steals). */
    fun claimsFirstChannel(section: XtreamHubSection, hasChannels: Boolean, viewerMoved: Boolean): Boolean =
        !viewerMoved && landing(section, hasChannels) == TvIptvLanding.Channels
}
