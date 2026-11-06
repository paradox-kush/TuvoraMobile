package com.tuvora.tvos.player

import com.nuvio.app.features.player.LiveLinkReconnectPolicy

/**
 * What the Apple TV player may do with a link that is SPENT by its first open (a Stalker `create_link`, live
 * or a archive replay). Pure, so the rules test without an engine; [TvPlayerSession] asks it.
 *
 * The Mobile/TV rule is the same ([LiveLinkReconnectPolicy]): the engine gives up on the first failure and the
 * app mints a fresh link through its own bounded path. Nothing here may open the same URL a second time.
 */
object TvSingleUseLinkPolicy {
    /** mpv `stream-lavf-o` mode for the next `loadfile`: every FFmpeg re-open switch off for a spent link. */
    fun reconnectMode(singleUse: Boolean): LiveLinkReconnectPolicy.Mode =
        LiveLinkReconnectPolicy.modeFor(singleUse)

    /** Whether a failed engine may be answered by opening the SAME url on the other engine (AVPlayer -> libmpv). */
    fun allowsLaneEscalation(singleUse: Boolean): Boolean =
        LiveLinkReconnectPolicy.allowsEngineFailover(LiveLinkReconnectPolicy.modeFor(singleUse))

    /** Whether the player's own Retry (`bridge.retry()`, a re-open of the loaded url) may run. Never for a spent link. */
    fun allowsPlayerRetry(singleUse: Boolean): Boolean =
        LiveLinkReconnectPolicy.allowsEngineFailover(LiveLinkReconnectPolicy.modeFor(singleUse))
}
