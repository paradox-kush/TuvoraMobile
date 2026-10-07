package com.nuvio.app.features.radar

import com.nuvio.app.core.contracts.ProfileChangeParticipant

/**
 * Sports Centre's reaction to a profile switch: reload follows/prefs + fixtures cache for the new
 * profile. Registered into [ProfileChangeParticipant]s by FeatureWiring so the shared
 * ProfileRepository never imports RadarRepository (firewall).
 */
internal object RadarProfileChange : ProfileChangeParticipant {
    override fun onProfileChanged(profileIndex: Int) {
        RadarChannelMatcher.resetForProfile()
        RadarRepository.onProfileChanged(profileIndex)
    }
}
