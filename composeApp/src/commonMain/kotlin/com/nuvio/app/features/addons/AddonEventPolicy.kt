package com.nuvio.app.features.addons

import com.nuvio.app.core.contracts.OwnSourcePolicy

/**
 * Where a player / library event may go (Stremio "tracking" add-ons). Privacy rules, pure so they test
 * without the network:
 *  - **No consent, no event.** An add-on that declares the `player` / `library` resource receives nothing
 *    until the user explicitly consented to THAT add-on on this device (the install dialog records it).
 *    Consent defaults to OFF: an add-on that was installed before the feature, restored from a backup or
 *    arrived through account sync has no consent here, so it gets nothing.
 *  - **Own-source items are never described.** An IPTV / media-server id says what the user watches in a
 *    source that is theirs alone; it never leaves the device through a tracker.
 * Events only ever go to the add-on's own manifest host (the user's choice), never to the app's backend.
 */
internal object AddonEventPolicy {
    /** True when [transportUrl] may receive events: the user consented to exactly this add-on on this device. */
    fun mayDeliver(transportUrl: String, consentedTransportUrls: Set<String>): Boolean =
        transportUrl.isNotBlank() && transportUrl in consentedTransportUrls

    /** True when an event about [id] may be sent to a tracker add-on at all. */
    fun mayDescribe(
        id: String,
        isServerId: (String) -> Boolean,
        isOwnContentId: (String) -> Boolean = OwnSourcePolicy::isOwnContentId,
        isTrackingExcluded: (String) -> Boolean = OwnSourcePolicy::isExcludedFromTrackingScrobble,
    ): Boolean = id.isNotBlank() && !isServerId(id) && !isOwnContentId(id) && !isTrackingExcluded(id)
}
