package com.nuvio.app.features.player

import com.nuvio.app.core.contracts.OwnSourcePolicy

/**
 * Where upstream's "fall back to the next source on failure" may act. It is meant for add-on and debrid
 * results (many near-identical copies of one title). It must never touch an own source: an IPTV / Stalker
 * stream has its own bounded reconnect and its link is single-use, so switching away from it (or to it) on
 * a failure would mint again or leave a live channel for an unrelated VOD copy. Live content never falls back.
 *
 * Pure: the own-source decision is the registered [OwnSourcePolicy] predicate, injectable for tests.
 */
internal object SourceFallbackPolicy {
    /** True when the source that failed to start may be replaced by the next matching one. */
    fun mayFallBackFrom(
        addonId: String?,
        contentType: String?,
        streamType: String?,
        isOwnProviderId: (String?) -> Boolean = OwnSourcePolicy::isOwnProviderId,
    ): Boolean = !isOwnProviderId(addonId) && !isLive(contentType) && !isLive(streamType)

    /** True when a candidate may be chosen as the fallback target (own sources never are). */
    fun mayFallBackTo(
        addonId: String?,
        isOwnProviderId: (String?) -> Boolean = OwnSourcePolicy::isOwnProviderId,
    ): Boolean = !isOwnProviderId(addonId)

    private fun isLive(type: String?): Boolean = type.equals("live", ignoreCase = true)
}
