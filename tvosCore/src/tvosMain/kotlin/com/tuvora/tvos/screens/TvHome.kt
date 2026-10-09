package com.tuvora.tvos.screens

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.isWaitingForFirstEnabledManifest
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.tuvora.tvos.app.TvAppLifecycle
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedUiState
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Apple TV's Home data: the catalog rows (HomeRepository, refreshed when the installed add-ons change,
 * as the phone's HomeScreen effect does) and Continue Watching — in-progress entries plus Next Up for
 * finished series, both chosen by [TvContinueWatchingSources] (the phone's HomeScreen.kt rules: hidden
 * and dropped shows, the provider's window, Next Up seeded from progress AND watched history so a
 * Simkl/Trakt viewer's finished episodes count) and resolved with the phone's seriesPrimaryAction.
 * The phone builds this row inside HomeScreen.kt (upstream, Compose, excluded).
 */
object TvHome {
    private val log = Logger.withTag("TvHome")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var nextUpJob: Job? = null
    private var nextUpKey: Any? = null

    val rows: StateFlow<HomeUiState> get() = HomeRepository.uiState

    private val _continueWatching = MutableStateFlow<List<TvCwItem>>(emptyList())
    val continueWatching: StateFlow<List<TvCwItem>> = _continueWatching.asStateFlow()

    private var inProgress: List<TvCwItem> = emptyList()
    private var nextUp: List<TvCwItem> = emptyList()

    fun start() {
        if (started) return
        started = true
        AddonRepository.initialize()
        WatchProgressRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        // A profile switch clears HomeRepository, so reload per session as well as per add-on change
        // (TvHomeRefreshPolicy); enabled add-ons only, catalog settings synced first - as the phone.
        scope.launch {
            combine(TvAppLifecycle.sessionGeneration, AddonRepository.uiState.map { it.addons }) { generation, addons ->
                generation to addons
            }.distinctUntilChangedBy { (generation, addons) -> TvHomeRefreshPolicy.key(generation, addons) }
                .collect { (_, addons) ->
                    val enabled = addons.enabledAddons()
                    if (enabled.isWaitingForFirstEnabledManifest()) return@collect
                    HomeCatalogSettingsRepository.syncCatalogs(enabled)
                    HomeRepository.refresh(enabled)
                }
        }
        // Rebuilt from what is already in memory whenever progress, watched history or the CW settings
        // change; the only network is MetaDetailsRepository.fetch for Next Up (cached per title).
        scope.launch {
            combine(
                WatchProgressRepository.uiState,
                WatchedRepository.uiState,
                TrackingSettingsRepository.uiState.map { it.continueWatchingDaysCap }.distinctUntilChanged(),
                ContinueWatchingPreferencesRepository.uiState,
            ) { progress, watched, daysCap, prefs -> CwInputs(progress, watched, daysCap, prefs) }
                .collect(::rebuild)
        }
    }

    private class CwInputs(
        val progress: WatchProgressUiState,
        val watched: WatchedUiState,
        val daysCap: Int,
        val prefs: ContinueWatchingPreferencesUiState,
    )

    private fun rebuild(inputs: CwInputs) {
        val now = WatchProgressClock.nowEpochMs()
        val state = inputs.progress
        val cutoff = WatchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(inputs.daysCap, now)
        val ownsHistory = WatchProgressRepository.activeProviderOwnsCompletedHistoryProjection()
        val isDropped: (String) -> Boolean = WatchProgressRepository::isDroppedShow
        inProgress = TvContinueWatchingSources.inProgress(state.entries, state.hiddenContentIds, isDropped, cutoff)
            .take(TvContinueWatching.MAX_ITEMS).map { it.toCw(isNextUp = false) }
        publish()
        val watchedItems = if (ownsHistory) emptyList() else inputs.watched.items
        val seeds = TvContinueWatchingSources.nextUpSeeds(
            progressEntries = state.entries,
            watchedItems = watchedItems,
            providerOwnsCompletedHistory = ownsHistory,
            preferFurthestEpisode = inputs.prefs.upNextFromFurthestEpisode,
            hiddenContentIds = state.hiddenContentIds,
            isDropped = isDropped,
            shouldUseProgressSeed = { WatchProgressRepository.shouldUseAsNextUpSeed(it, now) },
            cutoffEpochMs = cutoff,
        )
        refreshNextUp(state.entries, watchedItems, seeds, inputs.prefs)
    }

    fun refresh() = HomeRepository.refresh(AddonRepository.uiState.value.addons.enabledAddons(), force = true)

    /** What an empty Home should say; a store build must not ask a playlist owner to add one (UX38). */
    fun emptyHomeHint(): TvEmptyHomeHint {
        XtreamRepository.ensureLoaded()
        return TvEmptyHomeHintPolicy.hint(
            addonsEnabled = AppFeaturePolicy.addonsEnabled,
            hasAnyIptvPlaylist = XtreamRepository.hasAnyPlaylist.value,
        )
    }

    private fun publish() {
        _continueWatching.value = TvContinueWatching.merge(inProgress, nextUp)
    }

    private fun refreshNextUp(
        entries: List<WatchProgressEntry>,
        watchedItems: List<WatchedItem>,
        seeds: List<TvNextUpSeed>,
        prefs: ContinueWatchingPreferencesUiState,
    ) {
        // Re-resolve only when something that decides Next Up changed, not on every progress tick.
        val key = listOf(seeds, watchedItems.size, entries.map { it.videoId to it.isCompleted }.toSet(),
            prefs.upNextFromFurthestEpisode, prefs.showUnairedNextUp, prefs.dismissedNextUpKeys)
        if (key == nextUpKey) return
        nextUpKey = key
        nextUpJob?.cancel()
        nextUpJob = scope.launch {
            val today = CurrentDateProvider.todayIsoDate()
            val permits = Semaphore(NEXT_UP_CONCURRENCY)
            val resolved = coroutineScope {
                seeds.map { seed -> async { permits.withPermit { resolveNextUp(seed, entries, watchedItems, prefs, today) } } }.awaitAll()
            }.filterNotNull()
            nextUp = resolved
            publish()
        }
    }

    private suspend fun resolveNextUp(
        seed: TvNextUpSeed,
        entries: List<WatchProgressEntry>,
        watchedItems: List<WatchedItem>,
        prefs: ContinueWatchingPreferencesUiState,
        today: String,
    ): TvCwItem? {
        if (nextUpDismissKey(seed.contentId, seed.seasonNumber, seed.episodeNumber) in prefs.dismissedNextUpKeys) return null
        val meta = runCatching { TvMetaLookup.fetch(seed.contentType, seed.contentId) }.getOrNull() ?: return null
        val progress = WatchProgressRepository.prepareNextUpProgressEntries(entries = entries, contentId = seed.contentId)
        val action = meta.seriesPrimaryAction(
            content = WatchingContentRef(type = seed.contentType, id = seed.contentId),
            entries = progress,
            watchedItems = watchedItems,
            todayIsoDate = today,
            preferFurthestEpisode = prefs.upNextFromFurthestEpisode,
            showUnairedNextUp = prefs.showUnairedNextUp,
        ) ?: return null
        if (action.resumePositionMs != null) return null
        val video = meta.videoFor(action.seasonNumber, action.episodeNumber, action.videoId) ?: return null
        return toNextUp(meta, video, seed.markedAtEpochMs)
    }

    /** Opens a card: the title's details and, for an episode, the video to play. */
    suspend fun resolve(item: TvCwItem): TvCwTarget? {
        val meta = runCatching { TvMetaLookup.fetch(item.parentMetaType, item.parentMetaId) }.getOrNull() ?: return null
        val video = if (item.seasonNumber != null) meta.videoFor(item.seasonNumber, item.episodeNumber, item.videoId) else null
        return TvCwTarget(meta, video)
    }

    private fun WatchProgressEntry.toCw(isNextUp: Boolean) = TvCwItem(
        parentMetaId = parentMetaId, parentMetaType = parentMetaType, videoId = videoId, title = title,
        episodeTitle = episodeTitle, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
        artwork = episodeThumbnail ?: background ?: poster, logo = logo, background = background ?: poster,
        positionMs = lastPositionMs, durationMs = durationMs, lastUpdatedEpochMs = lastUpdatedEpochMs, isNextUp = isNextUp,
        poster = poster,
    )

    private fun toNextUp(meta: MetaDetails, video: MetaVideo, lastUpdatedEpochMs: Long) = TvCwItem(
        parentMetaId = meta.id, parentMetaType = meta.type, videoId = video.id, title = meta.name,
        episodeTitle = video.title, seasonNumber = video.season, episodeNumber = video.episode,
        artwork = video.thumbnail ?: meta.background ?: meta.poster, logo = meta.logo, background = meta.background ?: meta.poster,
        positionMs = 0, durationMs = 0, lastUpdatedEpochMs = lastUpdatedEpochMs, isNextUp = true,
        poster = meta.poster,
    )

    private fun MetaDetails.videoFor(season: Int?, episode: Int?, videoId: String): MetaVideo? {
        if (season != null && episode != null) videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }
        return videos.firstOrNull {
            buildPlaybackVideoId(parentMetaId = id, seasonNumber = it.season, episodeNumber = it.episode, fallbackVideoId = it.id) == videoId || it.id == videoId
        }
    }

    /** NuvioTV CW_MAX_NEXT_UP_CONCURRENCY / the phone's NEXT_UP_RESOLUTION_CONCURRENCY. */
    private const val NEXT_UP_CONCURRENCY = 4
}

data class TvCwTarget(val meta: MetaDetails, val video: MetaVideo?)
