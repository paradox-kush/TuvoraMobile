package com.tuvora.tvos.player

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.livetv.LiveTvFailureReasonPolicy
import com.nuvio.app.features.livetv.LiveTvFailureReasonPolicy.Reason

/**
 * UX191 for Apple TV: the plain-language reason line under "Playback failed" for a live channel, from the shared
 * [LiveTvFailureReasonPolicy]. The error text is only classified, never shown or sent (it can name a provider);
 * the card gets one of these fixed sentences, or null when the failure does not say why. Apple TV's SwiftUI
 * screens carry their own English copy, so the sentences live here beside the facade rather than in resources.
 */
object TvLiveFailureReason {
    /** Reads the shared connectivity probe, so the Swift call site is unchanged: no connection says so (UX343). */
    fun of(message: String?): String? =
        of(message, offline = NetworkStatusRepository.uiState.value.condition == NetworkCondition.NoInternet)

    fun of(message: String?, offline: Boolean): String? = when (LiveTvFailureReasonPolicy.classify(failureCode = null, message = message, offline = offline)) {
        Reason.PROVIDER_REFUSED -> "Your provider refused this stream. The subscription may have expired, or this device is blocked."
        Reason.TOO_MANY_CONNECTIONS -> "Your provider allows fewer connections than you are using. Close another device and retry."
        Reason.PROVIDER_DOWN -> "The provider's server had a problem. Try again in a moment."
        Reason.LINK_UNAVAILABLE -> "The provider did not give a stream link for this channel."
        Reason.OFFLINE -> "No network connection"
        Reason.NETWORK -> "Couldn't reach the provider. Check your connection."
        Reason.UNSUPPORTED -> "This device can't read this stream."
        // Only a startup failure code yields NOT_STARTED; Apple TV classifies by message alone, so it never arrives.
        Reason.NOT_STARTED, Reason.UNKNOWN -> null
    }
}
