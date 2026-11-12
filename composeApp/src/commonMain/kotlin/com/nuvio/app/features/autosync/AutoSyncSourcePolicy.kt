package com.nuvio.app.features.autosync

import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.features.player.AddonSubtitleIdPolicy
import com.nuvio.app.features.streams.StreamLinkReusePolicy

/**
 * Whether AutoSync may read the playing STREAM itself: [EmbeddedSubtitleTimelineLoader] fetches the
 * container's subtitle index with HTTP Range requests on the very URL the player has open.
 *
 * Never for an own source (an IPTV / Stalker / Xtream account, a device source, a media server): a
 * Stalker `play_token` is single-use and many panels allow one connection, so a side request burns the
 * link or drops the player. Never for a live channel or a catch-up replay either (no stable file to
 * index, and the link may be single-use). These are the same exclusions the fork already applies to the
 * subtitle-hash probe ([AddonSubtitleIdPolicy.allowsStreamHashProbe]) and the reuse-last-link cache
 * ([StreamLinkReusePolicy]); an IPTV VOD reached from a TMDB title carries a public content id, so the
 * provider id is checked too. When this says no, the chosen subtitle is attached unchanged.
 *
 * Pure; the own-source provider predicate is injectable for tests.
 */
internal object AutoSyncSourcePolicy {
    fun mayProbe(
        activeVideoId: String?,
        providerAddonId: String?,
        streamType: String?,
        contentType: String?,
        isCatchUpPlayback: Boolean = false,
        singleUseLink: Boolean = false,
        isOwnProviderId: (String?) -> Boolean = OwnSourcePolicy::isOwnProviderId,
    ): Boolean {
        val isLiveOrReplay = isCatchUpPlayback || AddonSubtitleIdPolicy.isLivePlayback(streamType, contentType)
        return !singleUseLink &&
            AddonSubtitleIdPolicy.allowsStreamHashProbe(activeVideoId, isLivePlayback = isLiveOrReplay) &&
            StreamLinkReusePolicy.mayReuse(providerAddonId, isOwnProviderId)
    }
}
