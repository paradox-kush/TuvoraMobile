package com.nuvio.app.features.streams

import com.nuvio.app.core.contracts.OwnSourcePolicy

/**
 * Whether a played link may be written to, or replayed from, the reuse-last-link cache
 * ([StreamLinkCacheRepository]).
 *
 * Add-on and debrid links may: upstream (3d70db683) caches tokenized links too, and the player's
 * error path re-fetches them once they expire. Own-source links (an IPTV/Stalker account, a media
 * server) never may: a Stalker `create_link` URL is single-use, and an Xtream panel can renumber its
 * catalog so the cached stream id 401s. They are re-resolved through their own lane every time.
 *
 * Pure: the own-source decision is the registered [OwnSourcePolicy] predicate, injectable for tests.
 */
internal object StreamLinkReusePolicy {
    fun mayReuse(
        addonId: String?,
        isOwnProviderId: (String?) -> Boolean = OwnSourcePolicy::isOwnProviderId,
    ): Boolean = !isOwnProviderId(addonId)
}
