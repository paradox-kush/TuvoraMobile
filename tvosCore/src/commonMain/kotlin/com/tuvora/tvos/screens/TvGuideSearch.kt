package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.IptvChannelSearchPolicy
import com.nuvio.app.features.livetv.LiveGuideChannel

/** Same word matching, divider exclusion and stable ranking used by Android TV and the phone guide. */
object TvGuideSearch {
    fun channels(lineup: List<LiveGuideChannel>, query: String): List<LiveGuideChannel> =
        IptvChannelSearchPolicy.search(lineup, query) { it.name }
}
