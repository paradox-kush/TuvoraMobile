package com.tuvora.tvos.screens

import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.iptv.toMetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Apple TV Home's section order and its Recent Channels row. The decision is [TvHomeSectionsPolicy]; this
 * object writes it back through the shared Home settings (which sync as `home_sections`) and exposes the
 * recently watched live channels the Recent Channels row shows.
 */
object TvHomeSections {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Recently watched live channels, newest first (the phone's Live TV row, same store). */
    val recentChannels: StateFlow<List<MetaPreview>> by lazy {
        XtreamLiveRecents.ensureLoaded()
        XtreamLiveRecents.recents
            .map { recents -> recents.map { it.toMetaPreview() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    fun move(id: String, toIndex: Int) =
        HomeCatalogSettingsRepository.moveHomeSection(
            id, toIndex, TvHomeSectionsPolicy.supportedIds, heroPinnedFirst = true,
        )

    fun setEnabled(id: String, enabled: Boolean) =
        HomeCatalogSettingsRepository.setHomeSectionEnabled(
            id, enabled, TvHomeSectionsPolicy.supportedIds, heroPinnedFirst = true,
        )

    /** Home reports once this profile has Continue Watching items or recent channels. */
    fun reportWatchHistory(has: Boolean) = HomeCatalogSettingsRepository.reportWatchHistory(has)
}
