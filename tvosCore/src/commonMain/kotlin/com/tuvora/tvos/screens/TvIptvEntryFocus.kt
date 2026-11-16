package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamHubSection

enum class TvIptvLanding { Channels, SelectedChip }

/** Where focus lands when the IPTV hub is entered from the sidebar (Android TV: "first entry lands on the channel list"). */
object TvIptvEntryFocus {
    fun landing(section: XtreamHubSection, hasChannels: Boolean): TvIptvLanding =
        if (section == XtreamHubSection.LIVE && hasChannels) TvIptvLanding.Channels else TvIptvLanding.SelectedChip
}
