package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun PlayerScreenRuntime.isAtNextEpisodeThreshold(): Boolean {
    if (playbackSnapshotKey != activePlaybackKey || playbackSnapshot.isLoading ||
        !initialSeekApplied || isScrubbingTimeline || errorMessage != null ||
        isShortPlaceholderDuration(playbackSnapshot.durationMs)
    ) return false
    // Preload: trigger source fetch before the button appears
    if (playerSettingsUiState.preloadNextEpisodeSources && !nextEpisodePreloadTriggered && nextEpisodeInfo != null) {
        val preloadLeadMs = maxOf(playerSettingsUiState.streamAutoPlayTimeoutSeconds.toLong() * 1_000L, 10_000L)
            .coerceAtMost(30_000L)
        val shouldPreloadByCard = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs + preloadLeadMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        val shouldPreloadByEnd = playbackSnapshot.durationMs > 0L &&
            (playbackSnapshot.durationMs - playbackSnapshot.positionMs) <= preloadLeadMs
        if (shouldPreloadByCard || shouldPreloadByEnd) {
            preloadNextEpisodeSources()
        }
    }
    return playbackSnapshot.isEnded || PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = playbackSnapshot.positionMs,
        durationMs = playbackSnapshot.durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
        thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
        thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    )
}

internal fun PlayerScreenRuntime.cancelNextEpisodeAutoPlay() {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    nextEpisodeAutoPlayAutomatic = false
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
}

internal fun CoroutineScope.launchPlayerNextEpisodeAutoPlay(
    previousJob: Job?,
    nextEpisodeInfo: NextEpisodeInfo?,
    allEpisodes: List<MetaVideo>,
    parentMetaId: String,
    parentMetaType: String,
    contentType: String?,
    settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
    currentProviderAddonId: String?,
    currentLanguageTargets: Set<String>,
    contentOriginalLanguage: String?,
    // User-initiated (button/card tap) vs. automatic near-end trigger: manual skips the
    // countdown and may open the source picker; auto never surfaces a panel uninvited.
    manualTrigger: Boolean,
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
    onSourceNameChanged: (String?) -> Unit,
    onCountdownChanged: (Int?) -> Unit,
    onNextEpisodeCardVisibleChanged: (Boolean) -> Unit,
): Job? {
    val nextVideoId = nextEpisodeInfo?.videoId ?: return null
    val nextVideo = allEpisodes.firstOrNull { video -> video.id == nextVideoId } ?: return null
    if (nextEpisodeInfo.hasAired != true) return null

    val downloadedNextEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextVideo.season,
        episodeNumber = nextVideo.episode,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    previousJob?.cancel()
    onSearchingChanged(true)
    onSourceNameChanged(null)
    onCountdownChanged(null)

    val type = contentType ?: parentMetaType
    // Users who configured an explicit auto-play mode keep their scoping/regex and its
    // pick-anything fallback; everyone else gets the tiered same-provider/same-language flow
    // that ends in the manual picker.
    val usesConfiguredAutoPlay = settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL
    val effectiveMode = if (usesConfiguredAutoPlay) settings.streamAutoPlayMode else StreamAutoPlayMode.FIRST_STREAM
    val effectiveSource = if (usesConfiguredAutoPlay) settings.streamAutoPlaySource else StreamAutoPlaySource.ALL_SOURCES
    val effectiveSelectedAddons = if (usesConfiguredAutoPlay) settings.streamAutoPlaySelectedAddons else emptySet()
    val effectiveSelectedPlugins = if (usesConfiguredAutoPlay) settings.streamAutoPlaySelectedPlugins else emptySet()
    val effectiveRegex = if (usesConfiguredAutoPlay) settings.streamAutoPlayRegex else ""
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }

    return launch {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextVideo.id,
            season = nextVideo.season,
            episode = nextVideo.episode,
        )

        if (effectiveMode == StreamAutoPlayMode.MANUAL) {
            onSearchingChanged(false)
            onNextEpisodeCardVisibleChanged(false)
            onManualSelectionRequired(nextVideo)
            return@launch
        }

        val installedAddonNames = AddonRepository.uiState.value.addons
            .enabledAddons()
            .map { it.displayTitle }
            .toSet()
        val debridSettings = DebridSettingsRepository.snapshot()

        val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
        var autoSelectTriggered = false
        var selectedStream: StreamItem? = null
        val autoSelectSettled = CompletableDeferred<Unit>()

        fun settleAutoSelect() {
            if (!autoSelectSettled.isCompleted) {
                autoSelectSettled.complete(Unit)
            }
        }

        fun selectStream(stream: StreamItem) {
            if (!stream.availableOnDevice) { autoSelectTriggered = true; settleAutoSelect(); return }
            autoSelectTriggered = true
            selectedStream = stream
            settleAutoSelect()
        }

        fun finishWithoutSelection() {
            autoSelectTriggered = true
            settleAutoSelect()
        }

        fun trySelectStream(streams: List<StreamItem>, confidentTiersOnly: Boolean = false): StreamItem? {
            val candidates = StreamAutoPlaySelector.autoPlayCandidateStreams(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
            )
            return PlayerNextEpisodeSourceSelector.select(
                streams = candidates,
                currentProviderAddonId = currentProviderAddonId,
                currentBingeGroup = preferredBingeGroup,
                currentLanguages = currentLanguageTargets,
                contentOriginalLanguage = contentOriginalLanguage,
                isReady = { stream ->
                    StreamAutoPlaySelector.isStreamReadyForAutoPlay(
                        stream = stream,
                        debridEnabled = debridSettings.canResolvePlayableLinks,
                        activeResolverProviderId = debridSettings.activeResolverProviderId,
                    )
                },
                allowAnyFallback = usesConfiguredAutoPlay && !confidentTiersOnly,
                confidentTiersOnly = confidentTiersOnly,
            )?.stream
        }

        // Upstream's coordinator (settles on "all providers answered" instead of waiting out
        // the timeout) driving the fork's tiered picker: confident tiers only while providers
        // are still answering, any-fallback once the delay elapsed or loading finished.
        val selectionCoordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = { streams -> trySelectStream(streams) },
            selectPreferred = { streams -> trySelectStream(streams, confidentTiersOnly = true) },
        )

        fun applySelectionDecision(decision: NextEpisodeStreamSelectionDecision) {
            if (autoSelectTriggered) return
            when (decision) {
                is NextEpisodeStreamSelectionDecision.Selected -> selectStream(decision.stream)
                NextEpisodeStreamSelectionDecision.ManualSelection -> finishWithoutSelection()
                NextEpisodeStreamSelectionDecision.Waiting -> Unit
            }
        }

        val innerJob = launch {
            PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
                applySelectionDecision(selectionCoordinator.onStreamsChanged(state))
            }
        }

        val timeoutMs = timeoutSeconds * 1_000L
        val isBoundedTimeout = timeoutSeconds in 1..30

        if (isBoundedTimeout) {
            // Preloaded (cached) streams let the coordinator settle at once — don't sit out the
            // whole timeout in that case (upstream 22c9ab203).
            withTimeoutOrNull(timeoutMs) { autoSelectSettled.await() }
        }
        applySelectionDecision(
            selectionCoordinator.onSelectionDelayElapsed(PlayerStreamsRepository.episodeStreamsState.value),
        )
        val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
        innerJob.cancel()
        if (completed == null && !autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            trySelectStream(allStreams)?.let(::selectStream) ?: finishWithoutSelection()
        }

        val selected = selectedStream?.let { stream ->
            when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, nextVideo.season, nextVideo.episode)) {
                is DirectDebridPlayableResult.Success -> result.stream
                else -> {
                    result.toastMessage()?.let { NuvioToastController.show(it) }
                    PlayerStreamsRepository.loadEpisodeStreams(
                        type = type,
                        videoId = nextVideo.id,
                        season = nextVideo.season,
                        episode = nextVideo.episode,
                        forceRefresh = true,
                    )
                    null
                }
            }
        }
        onSearchingChanged(false)
        if (selected != null) {
            onSourceNameChanged((selected.name?.takeIf { it.isNotBlank() } ?: selected.addonName).trim())
            if (!manualTrigger) {
                for (i in 3 downTo 1) {
                    onCountdownChanged(i)
                    delay(1000)
                }
            }
            onEpisodeStreamSelected(selected, nextVideo)
            onNextEpisodeCardVisibleChanged(false)
            onCountdownChanged(null)
            onSourceNameChanged(null)
        } else if (manualTrigger) {
            onManualSelectionRequired(nextVideo)
            onNextEpisodeCardVisibleChanged(false)
        }
        // Auto flow with no confident match: keep the card up, quietly idle — tapping it
        // re-runs as a manual trigger and surfaces the source picker.
    }
}

internal fun PlayerScreenRuntime.preloadNextEpisodeSources() {
    if (nextEpisodePreloadTriggered) return
    val nextEp = nextEpisodeInfo ?: return
    if (nextEp.hasAired != true) return
    val type = contentType ?: return

    nextEpisodePreloadTriggered = true
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = scope.launch {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextEp.videoId,
            season = nextEp.season,
            episode = nextEp.episode
        )
    }
}

internal fun PlayerScreenRuntime.cancelNextEpisodePreload() {
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = null
    nextEpisodePreloadTriggered = false
}
