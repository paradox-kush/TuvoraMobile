package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchUi

/** Pure: "watching" (current channel + Switch) vs "browsing" (matcher result + Play). Finished games have no Watch block. */
internal object WatchBlockPolicy {
    fun decide(now: NowWatching?, lookup: WatchLookup?, state: GameState): WatchUi {
        if (state == GameState.FINAL || state == GameState.POSTPONED) return WatchUi.Hidden
        val offer = (lookup as? WatchLookup.Found)?.offer
        if (now != null) return WatchUi.Watching(now, switchTo = offer?.takeIf { it.channelId != now.channelId })
        return when (lookup) {
            null -> WatchUi.Checking
            is WatchLookup.Found -> WatchUi.Browsing(lookup.offer)
            WatchLookup.NoPlaylist -> WatchUi.NoPlaylist
            WatchLookup.NoMatch, is WatchLookup.Unconfirmed -> WatchUi.NoMatch
            WatchLookup.Failed -> WatchUi.LookupFailed
        }
    }
}
