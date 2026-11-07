package com.nuvio.app.features.sportsdetail.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The live channel the viewer is playing right now (from the player), if any. */
data class NowWatching(val channelId: String, val channelName: String)

/** A channel the matcher says carries this game. [channelId] is the id the app's live-play route takes. */
data class WatchOffer(val channelId: String, val channelName: String, val logo: String? = null)

sealed interface WatchLookup {
    data class Found(val offer: WatchOffer) : WatchLookup
    /** No IPTV playlist on this device. */
    data object NoPlaylist : WatchLookup
    /** Playlists exist but no channel lists this game. */
    data object NoMatch : WatchLookup
    /** The matcher itself failed (provider unreachable). Never presented as "no match". */
    data object Failed : WatchLookup
}

/**
 * What the Watch block shows. Decided by `WatchBlockPolicy` from [NowWatching] + [WatchLookup] + the game state.
 *  - [Watching]: a channel is playing; [switchTo] offers the matched one when it differs.
 *  - [Browsing]: nothing playing; the matcher's channel gets a Play button.
 */
sealed interface WatchUi {
    data object Hidden : WatchUi
    data object Checking : WatchUi
    data class Watching(val current: NowWatching, val switchTo: WatchOffer?) : WatchUi
    data class Browsing(val offer: WatchOffer) : WatchUi
    data object NoPlaylist : WatchUi
    data object NoMatch : WatchUi
    data object LookupFailed : WatchUi
}

/**
 * Port to the IPTV side (implemented by the Sports Centre's channel matcher and the Live TV player state, wired in
 * `FeatureWiring`). The game screen names no radar/iptv/livetv type.
 */
interface GameWatchPort {
    val nowWatching: StateFlow<NowWatching?>
    suspend fun lookup(ref: GameRef): WatchLookup
}

/** Default for previews and builds without IPTV. */
object NoGameWatch : GameWatchPort {
    override val nowWatching: StateFlow<NowWatching?> = MutableStateFlow(null)
    override suspend fun lookup(ref: GameRef): WatchLookup = WatchLookup.NoPlaylist
}
