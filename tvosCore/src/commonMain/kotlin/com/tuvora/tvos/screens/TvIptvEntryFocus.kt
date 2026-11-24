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

    /**
     * An empty-hub message (Retry, Add Playlist) takes focus only on a fresh entry, where the remote would otherwise
     * have nothing to act on. Once the viewer has pressed a direction they are on the header chips (Series, Movies):
     * a message that appears under the chip they just chose must not pull focus away from it.
     */
    fun messageClaimsFocus(viewerMoved: Boolean): Boolean = !viewerMoved
}
