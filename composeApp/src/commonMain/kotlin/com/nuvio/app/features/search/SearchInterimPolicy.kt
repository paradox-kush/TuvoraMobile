package com.nuvio.app.features.search

import com.nuvio.app.features.home.HomeCatalogSection

/**
 * UX180: what Search shows while its add-on run is still going. IPTV is local and fast, but its rows
 * were only read once every add-on had settled — one dead add-on (60 s text-request limit) hid them
 * for up to a minute. The in-flight state is now "whatever is ready": the add-on rows that arrived,
 * then the IPTV rows once the lane answered, in the same order the settled state uses.
 */
internal object SearchInterimPolicy {

    /**
     * The in-flight state for the rows ready so far, or null when there is nothing to show yet (the
     * current state, e.g. the loading skeleton, stays). [iptvRows] is null until the IPTV lane answers.
     */
    fun interim(
        addonSections: List<HomeCatalogSection>,
        iptvRows: List<HomeCatalogSection>?,
    ): SearchUiState? {
        val sections = addonSections + iptvRows.orEmpty()
        if (sections.isEmpty()) return null
        return SearchUiState(isLoading = true, sections = sections)
    }
}
