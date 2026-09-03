package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.ProfileChangeParticipant
import com.nuvio.app.features.iptv.match.XtreamMatchSyncService

/**
 * IPTV's reaction to a profile switch: reload accounts + recents for the new profile and drop every
 * cross-profile cache. Registered into [ProfileChangeParticipant]s by FeatureWiring so the shared
 * ProfileRepository never imports these Xtream symbols (firewall). Order within this method is the
 * order the shared code used before the extraction.
 */
internal object IptvProfileChange : ProfileChangeParticipant {
    override fun onProfileChanged(profileIndex: Int) {
        XtreamRepository.onProfileChanged(profileIndex)
        XtreamLiveRecents.onProfileChanged(profileIndex)
        XtreamItemRegistry.resetForProfile()
        XtreamHubRepository.resetForProfile()
        XtreamSearchIndex.resetForProfile()
        com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.onProfileChanged(profileIndex)
        XtreamMatchSyncService.reset()
    }
}
