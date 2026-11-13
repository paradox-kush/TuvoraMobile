package com.tuvora.tvos.screens

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.iptv.ChannelEpg
import com.nuvio.app.features.iptv.XtreamHubRepository
import com.nuvio.app.features.iptv.XtreamHubSection
import com.nuvio.app.features.iptv.XtreamHubUiState
import com.nuvio.app.features.livetv.LiveTvData
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.tuvora.tvos.player.TvPlayerSession
import com.tuvora.tvos.player.TvResolvedSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Apple TV's IPTV browse: playlists → categories → items, over the same XtreamHubRepository the
 * phone's hub uses. [section] picks Live, Movies or Series. Live channels play straight from here;
 * movies and series open Details like any catalog title.
 */
object TvIptvBrowse {
    val state: StateFlow<XtreamHubUiState> get() = XtreamHubRepository.uiState
    val epg: StateFlow<Map<String, ChannelEpg>> get() = XtreamHubRepository.epg

    fun open(section: XtreamHubSection) {
        XtreamHubRepository.ensureLoaded()
        XtreamHubRepository.selectSection(section)
    }

    /**
     * Keeps the hub in step with the playlist store while the IPTV tab is on screen — the SwiftUI hub
     * runs it in a `.task`, so it stops when the tab goes away (the phone's `repeatOnLifecycle`).
     * Event-driven and local: it never polls; only a newly shown provider fetches anything.
     */
    suspend fun followPlaylists() = XtreamHubRepository.followAccounts()

    fun selectPlaylist(accountId: String) = XtreamHubRepository.selectAccount(accountId)
    fun loadCategory(categoryId: String) = XtreamHubRepository.loadCategory(categoryId)
    fun loadMore(categoryId: String) = XtreamHubRepository.loadMore(categoryId)
    fun retry() = XtreamHubRepository.retryCategories()

    /** A failed row's Retry (the only way, besides a finished import, a failed row is asked again). */
    fun retryCategory(categoryId: String) = XtreamHubRepository.retryCategory(categoryId)

    /**
     * Whether [category]'s row shows its Retry now: failed, or still loading past its own deadline (the
     * guarantee that a wait ends even if the work behind it never returns — the phone's rememberEffectiveLoadStatus).
     */
    fun rowFailed(category: com.nuvio.app.features.iptv.XtreamHubCategory): Boolean =
        com.nuvio.app.features.iptv.BoundedLoad.effectiveAt(category.load, com.nuvio.app.features.iptv.BoundedLoad.clock()) is
            com.nuvio.app.features.iptv.LoadStatus.Failed

    /** Milliseconds until [category]'s loading deadline, or null when it is not loading (wake once, then re-check). */
    fun msUntilRowDeadline(category: com.nuvio.app.features.iptv.XtreamHubCategory): Long? =
        (category.load as? com.nuvio.app.features.iptv.LoadStatus.Loading)
            ?.let { (it.deadlineAtMs - com.nuvio.app.features.iptv.BoundedLoad.clock()).coerceAtLeast(0) }
    fun ensureEpg(contentId: String) = XtreamHubRepository.ensureEpg(contentId)

    val isOffline: Boolean
        get() = NetworkStatusRepository.uiState.value.condition == NetworkCondition.NoInternet

    /**
     * Resolves a live channel and opens a player session, or null when the channel has no playable
     * address (the phone's LiveChannelLaunchPolicy "unavailable" outcome) — the screen says so.
     */
    suspend fun playChannel(contentId: String, name: String, logo: String?): TvPlayerSession? {
        // B259 follow-up: one tune's Stalker header profile (neutral by default as before 1.20.3, the identity after a refused open).
        val fallback = com.nuvio.app.features.iptv.stalker.StalkerStreamFallback(LiveTvData.playlistIdOf(contentId))
        val source = LiveTvData.resolveSource(contentId, name, logo, stalkerProfile = fallback.profile) ?: return null
        fallback.onOpened(source.profileFallback)
        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = name,
            sourceUrl = source.url,
            sourceHeaders = source.headers,
            streamTitle = name,
            streamType = "live",
            providerName = com.nuvio.app.core.contracts.LivePlaybackAccess.current().accountNameFor(contentId) ?: "IPTV",
            providerAddonId = "xtream",
            logo = logo,
            contentType = "live",
            videoId = contentId,
            parentMetaId = contentId,
            parentMetaType = "tv",
        )
        return liveSession(launch, source.singleUseLink) {
            LiveTvData.resolveSource(contentId, name, logo, forceMint = true, stalkerProfile = fallback.profile)
                ?.also { fallback.onOpened(it.profileFallback) }
                ?.let { TvResolvedSource(it.url, it.headers) }
        }.bindIdentityFallback(fallback)
    }

    /**
     * The session every IPTV live launch (hub, guide, zap, Library, Search, Sports) plays in. The
     * re-resolver is NAMED: as a trailing lambda Kotlin bound it to the constructor's last function
     * parameter (`vodReresolve`), so live reconnects had no fresh link and replayed the spent one.
     */
    internal fun liveSession(
        launch: PlayerLaunch,
        singleUseLink: Boolean,
        remint: suspend () -> TvResolvedSource?,
    ): TvPlayerSession = TvPlayerSession(launch, liveReresolve = remint).bindSingleUseLink(singleUseLink)
}
