package com.nuvio.app.features.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.sortedPlayableEpisodes
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watchprogress.toUpNextContinueWatchingItem
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.buildPlaybackVideoId
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.home.components.HomeCollectionRowSection
import com.nuvio.app.features.watching.domain.isReleasedBy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    onFirstCatalogRendered: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
        CollectionRepository.initialize()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val homeUiState by HomeRepository.uiState.collectAsStateWithLifecycle()
    val homeSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
    val continueWatchingPreferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val watchProgressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    val isTraktAuthenticated by remember {
        TraktAuthRepository.ensureLoaded()
        TraktAuthRepository.isAuthenticated
    }.collectAsStateWithLifecycle()

    val effectiveWatchProgressEntries = remember(watchProgressUiState.entries, isTraktAuthenticated) {
        if (!isTraktAuthenticated) {
            watchProgressUiState.entries
        } else {
            val cutoffMs = WatchProgressClock.nowEpochMs() - (TRAKT_CONTINUE_WATCHING_DAYS_CAP_DEFAULT.toLong() * 24L * 60L * 60L * 1000L)
            watchProgressUiState.entries.filter { entry -> entry.lastUpdatedEpochMs >= cutoffMs }
        }
    }

    val effectiveWatchedItems = remember(watchedUiState.items, isTraktAuthenticated) {
        if (isTraktAuthenticated) emptyList() else watchedUiState.items
    }

    val latestCompletedBySeries = remember(effectiveWatchProgressEntries, effectiveWatchedItems, continueWatchingPreferences.upNextFromFurthestEpisode) {
        WatchingState.latestCompletedBySeries(
            progressEntries = effectiveWatchProgressEntries,
            watchedItems = effectiveWatchedItems,
            preferFurthestEpisode = continueWatchingPreferences.upNextFromFurthestEpisode,
        )
    }
    val completedSeriesCandidates = remember(latestCompletedBySeries) {
        latestCompletedBySeries.map { (content, completed) ->
            CompletedSeriesCandidate(
                content = content,
                seasonNumber = completed.seasonNumber,
                episodeNumber = completed.episodeNumber,
                markedAtEpochMs = completed.markedAtEpochMs,
            )
        }
    }
    val visibleContinueWatchingEntries = remember(
        effectiveWatchProgressEntries,
        latestCompletedBySeries,
    ) {
        WatchingState.visibleContinueWatchingEntries(
            progressEntries = effectiveWatchProgressEntries,
            latestCompletedBySeries = latestCompletedBySeries,
        )
    }
    var nextUpItemsBySeries by remember { mutableStateOf<Map<String, Pair<Long, ContinueWatchingItem>>>(emptyMap()) }

    val cachedSnapshots = remember { ContinueWatchingEnrichmentCache.getSnapshots() }
    val cachedNextUpItems = remember(cachedSnapshots.first, continueWatchingPreferences.dismissedNextUpKeys) {
        cachedSnapshots.first.mapNotNull { cached ->
            if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in continueWatchingPreferences.dismissedNextUpKeys) {
                return@mapNotNull null
            }
            val item = cached.toContinueWatchingItem() ?: return@mapNotNull null
            cached.contentId to (cached.sortTimestamp to item)
        }.toMap()
    }

    val effectivNextUpItems = remember(nextUpItemsBySeries, cachedNextUpItems) {
        if (nextUpItemsBySeries.isNotEmpty()) nextUpItemsBySeries else cachedNextUpItems
    }

    val continueWatchingItems = remember(
        visibleContinueWatchingEntries,
        effectivNextUpItems,
    ) {
        buildHomeContinueWatchingItems(
            visibleEntries = visibleContinueWatchingEntries,
            nextUpItemsBySeries = effectivNextUpItems,
        )
    }
    val allManifestsSettled = addonsUiState.addons.isNotEmpty() &&
        addonsUiState.addons.none { it.isRefreshing }

    val metaProviderKey = remember(addonsUiState.addons, allManifestsSettled) {
        if (!allManifestsSettled) return@remember emptyList<String>()
        addonsUiState.addons
            .mapNotNull { addon ->
                addon.manifest?.takeIf { manifest ->
                    manifest.resources.any { resource -> resource.name == "meta" }
                }?.transportUrl
            }
            .sorted()
    }

    val catalogRefreshKey = remember(addonsUiState.addons, allManifestsSettled) {
        if (!allManifestsSettled) return@remember emptyList<String>()
        addonsUiState.addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    "${catalog.type}:${catalog.id}:${catalog.extra.count { it.isRequired }}"
                })
            }
        }
    }

    LaunchedEffect(catalogRefreshKey) {
        if (catalogRefreshKey.isEmpty()) return@LaunchedEffect
        HomeCatalogSettingsRepository.syncCatalogs(addonsUiState.addons)
        HomeRepository.refresh(addonsUiState.addons)
    }

    LaunchedEffect(completedSeriesCandidates, metaProviderKey) {
        if (completedSeriesCandidates.isEmpty()) {
            nextUpItemsBySeries = emptyMap()
            return@LaunchedEffect
        }

        if (metaProviderKey.isEmpty()) return@LaunchedEffect

        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val semaphore = Semaphore(4)
        val results = completedSeriesCandidates.map { completedEntry ->
            async {
                semaphore.withPermit {
                    val meta = MetaDetailsRepository.fetch(
                        type = completedEntry.content.type,
                        id = completedEntry.content.id,
                    ) ?: return@withPermit null
                    val nextEpisode = meta.nextReleasedEpisodeAfter(
                        seasonNumber = completedEntry.seasonNumber,
                        episodeNumber = completedEntry.episodeNumber,
                        todayIsoDate = todayIsoDate,
                        showUnairedNextUp = isTraktAuthenticated,
                    ) ?: return@withPermit null
                    val item = completedEntry.toContinueWatchingSeed(meta)
                        .toUpNextContinueWatchingItem(nextEpisode)
                    if (nextUpDismissKey(item.parentMetaId, item.nextUpSeedSeasonNumber, item.nextUpSeedEpisodeNumber) in continueWatchingPreferences.dismissedNextUpKeys) {
                        return@withPermit null
                    }
                    completedEntry.content.id to (completedEntry.markedAtEpochMs to item)
                }
            }
        }.awaitAll().filterNotNull().toMap()
        nextUpItemsBySeries = results

        val nextUpCache = results.mapNotNull { (contentId, pair) ->
            val item = pair.second
            CachedNextUpItem(
                contentId = contentId,
                contentType = item.parentMetaType,
                name = item.title,
                poster = item.poster,
                backdrop = item.background,
                logo = item.logo,
                videoId = item.videoId,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                episodeThumbnail = item.episodeThumbnail,
                pauseDescription = item.pauseDescription,
                lastWatched = pair.first,
                sortTimestamp = pair.first,
                seedSeason = item.nextUpSeedSeasonNumber,
                seedEpisode = item.nextUpSeedEpisodeNumber,
            )
        }
        val inProgressCache = visibleContinueWatchingEntries.map { entry ->
            CachedInProgressItem(
                contentId = entry.parentMetaId,
                contentType = entry.contentType,
                name = entry.title,
                poster = entry.poster,
                backdrop = entry.background,
                logo = entry.logo,
                videoId = entry.videoId,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
                episodeThumbnail = entry.episodeThumbnail,
                pauseDescription = entry.pauseDescription,
                position = entry.lastPositionMs,
                duration = entry.durationMs,
                lastWatched = entry.lastUpdatedEpochMs,
                progressPercent = entry.progressPercent,
            )
        }
        ContinueWatchingEnrichmentCache.saveSnapshots(
            nextUp = nextUpCache,
            inProgress = inProgressCache,
        )
    }

    val hasActiveAddons = addonsUiState.addons.any { it.manifest != null }
    val showHeroSlot = homeSettingsUiState.heroEnabled
    val isResolvingHeroSources = addonsUiState.addons.any { it.isRefreshing } || homeUiState.isLoading
    val showHeroSkeleton = showHeroSlot &&
        homeUiState.heroItems.isEmpty() &&
        isResolvingHeroSources
    var firstCatalogReported by remember { mutableStateOf(false) }

    LaunchedEffect(homeUiState.sections.firstOrNull()?.key, onFirstCatalogRendered) {
        if (firstCatalogReported || homeUiState.sections.isEmpty()) return@LaunchedEffect
        firstCatalogReported = true
        onFirstCatalogRendered?.invoke()
    }

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 0.dp,
        topPadding = if (showHeroSlot) 0.dp else null,
    ) {
        if (showHeroSlot) {
            item {
                when {
                    showHeroSkeleton -> HomeSkeletonHero(
                        modifier = Modifier,
                    )

                    homeUiState.heroItems.isNotEmpty() -> HomeHeroSection(
                        items = homeUiState.heroItems,
                        modifier = Modifier,
                        onItemClick = onPosterClick,
                    )

                    else -> HomeHeroReservedSpace(modifier = Modifier)
                }
            }
        }

        when {
            addonsUiState.addons.none { it.manifest != null } -> {
                if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                    item {
                        HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )
                    }
                }
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "No active addons",
                        message = "Install and validate at least one addon before loading catalog rows on Home.",
                    )
                }
            }

            homeUiState.isLoading && homeUiState.sections.isEmpty() -> {
                if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                    item {
                        HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )
                    }
                }
                items(3) {
                    HomeSkeletonRow(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            homeUiState.sections.isEmpty() && homeUiState.heroItems.isEmpty() &&
                (!continueWatchingPreferences.isVisible || continueWatchingItems.isEmpty()) -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "No home rows available",
                        message = homeUiState.errorMessage
                            ?: "Installed addons do not currently expose board-compatible catalogs without required extras.",
                    )
                }
            }

            else -> {
                if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                    item {
                        HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )
                    }
                }
                // Pin-to-top collection rows
                val pinnedCollections = collections.filter { it.pinToTop && it.folders.isNotEmpty() }
                val unpinnedCollections = collections.filter { !it.pinToTop && it.folders.isNotEmpty() }

                pinnedCollections.forEach { collection ->
                    item(key = "collection_${collection.id}") {
                        HomeCollectionRowSection(
                            collection = collection,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onFolderClick = onFolderClick,
                        )
                    }
                }

                items(
                    count = homeUiState.sections.size,
                    key = { index -> homeUiState.sections[index].key },
                ) { index ->
                    val section = homeUiState.sections[index]
                    HomeCatalogRowSection(
                        section = section,
                        entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT),
                        modifier = Modifier.padding(bottom = 12.dp),
                        onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                            onCatalogClick?.let { { it(section) } }
                        } else {
                            null
                        },
                        watchedKeys = watchedUiState.watchedKeys,
                        onPosterClick = onPosterClick,
                        onPosterLongClick = onPosterLongClick,
                    )
                }

                // Unpinned collection rows after catalog sections
                unpinnedCollections.forEach { collection ->
                    item(key = "collection_${collection.id}") {
                        HomeCollectionRowSection(
                            collection = collection,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onFolderClick = onFolderClick,
                        )
                    }
                }
            }
        }
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18
private const val TRAKT_CONTINUE_WATCHING_DAYS_CAP_DEFAULT = 60

internal fun buildHomeContinueWatchingItems(
    visibleEntries: List<WatchProgressEntry>,
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
): List<ContinueWatchingItem> {
    return buildList {
        addAll(
            visibleEntries.map { entry ->
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
                    item = entry.toContinueWatchingItem(),
                    isProgressEntry = true,
                )
            },
        )
        addAll(
            nextUpItemsBySeries.values.map { (lastUpdatedEpochMs, item) ->
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = lastUpdatedEpochMs,
                    item = item,
                    isProgressEntry = false,
                )
            },
        )
    }
        .sortedWith(
            compareByDescending<HomeContinueWatchingCandidate> { it.lastUpdatedEpochMs }
                .thenByDescending { it.isProgressEntry },
        )
        .distinctBy { it.item.videoId }
        .map(HomeContinueWatchingCandidate::item)
}

private data class CompletedSeriesCandidate(
    val content: WatchingContentRef,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

private data class HomeContinueWatchingCandidate(
    val lastUpdatedEpochMs: Long,
    val item: ContinueWatchingItem,
    val isProgressEntry: Boolean,
)

private fun CompletedSeriesCandidate.toContinueWatchingSeed(meta: com.nuvio.app.features.details.MetaDetails) =
    WatchProgressEntry(
        contentType = content.type,
        parentMetaId = content.id,
        parentMetaType = content.type,
        videoId = "${content.id}:${seasonNumber}:${episodeNumber}",
        title = meta.name,
        logo = meta.logo,
        poster = meta.poster,
        background = meta.background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = markedAtEpochMs,
        isCompleted = true,
    )

private fun com.nuvio.app.features.details.MetaDetails.nextReleasedEpisodeAfter(
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIsoDate: String,
    showUnairedNextUp: Boolean,
): com.nuvio.app.features.details.MetaVideo? {
    val content = WatchingContentRef(type = type, id = id)
    val watchedVideoId = buildPlaybackVideoId(
        content = content,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    val ordered = sortedPlayableEpisodes()
        .dropWhile { episode ->
            buildPlaybackVideoId(
                content = content,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
                fallbackVideoId = episode.id,
            ) != watchedVideoId
        }
        .drop(1)
        .filter { episode -> (episode.season ?: 0) > 0 }

    if (showUnairedNextUp) {
        return ordered.firstOrNull()
    }

    return ordered.firstOrNull { episode ->
        isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = episode.released)
    }
}

private fun CachedNextUpItem.toContinueWatchingItem(): ContinueWatchingItem? {
    val subtitle = buildString {
        append("Up Next")
        if (season != null && episode != null) {
            append(" • S")
            append(season)
            append("E")
            append(episode)
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let {
            append(" • ")
            append(it)
        }
    }
    return ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = contentType,
        videoId = videoId,
        title = name,
        subtitle = subtitle,
        imageUrl = episodeThumbnail ?: backdrop ?: poster,
        logo = logo,
        poster = poster,
        background = backdrop,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        pauseDescription = pauseDescription,
        isNextUp = true,
        nextUpSeedSeasonNumber = seedSeason,
        nextUpSeedEpisodeNumber = seedEpisode,
        resumePositionMs = 0L,
        resumeProgressFraction = null,
        durationMs = 0L,
        progressFraction = 0f,
    )
}
