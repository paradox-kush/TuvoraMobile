package com.tuvora.tvos.screens

import com.nuvio.app.features.home.HomeNoAddonsCard
import com.nuvio.app.features.home.HomeNoAddonsCardPolicy

/** What an empty Home says — the phone's [HomeNoAddonsCardPolicy] decision, exported for SwiftUI. */
enum class TvEmptyHomeHint {
    /** Full builds: install add-ons or add a playlist. */
    INSTALL_SOURCES,

    /** Store builds with no playlist: point at IPTV setup. */
    ADD_PLAYLIST,

    /** Store builds with a playlist: its content lives in the IPTV tab, so never ask for one (UX38). */
    PLAYLIST_IN_IPTV_TAB,
}

object TvEmptyHomeHintPolicy {
    fun hint(addonsEnabled: Boolean, hasAnyIptvPlaylist: Boolean): TvEmptyHomeHint =
        when (HomeNoAddonsCardPolicy.card(addonsEnabled, hasAnyIptvPlaylist)) {
            HomeNoAddonsCard.NoActiveAddons -> TvEmptyHomeHint.INSTALL_SOURCES
            HomeNoAddonsCard.AddIptvPlaylist -> TvEmptyHomeHint.ADD_PLAYLIST
            HomeNoAddonsCard.None -> TvEmptyHomeHint.PLAYLIST_IN_IPTV_TAB
        }
}
