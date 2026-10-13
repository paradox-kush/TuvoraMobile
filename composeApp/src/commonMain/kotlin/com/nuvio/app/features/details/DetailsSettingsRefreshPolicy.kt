package com.nuvio.app.features.details

import com.nuvio.app.features.servers.ServerItemRef

/** Settings enrichment belongs to addon/TMDB metadata; server details are loaded on entry/retry. */
internal object DetailsSettingsRefreshPolicy {
    fun shouldRefresh(itemId: String, hasMeta: Boolean, isLoading: Boolean): Boolean =
        hasMeta && !isLoading && !ServerItemRef.isServerId(itemId)
}
