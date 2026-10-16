package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.shuffle.EpisodeShuffleRepository
import com.nuvio.app.features.shuffle.ShuffleSurface
import com.nuvio.app.features.shuffle.watchedShuffleEpisodes
import com.nuvio.app.features.shuffle.shuffleEpisodeProgress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import co.touchlab.kermit.Logger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.shouldAutoSkip
import com.nuvio.app.features.player.skip.internalSkipAction
import com.nuvio.app.features.player.skip.intervalsAtSeekPositions
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.hasLikelyExpiringPlaybackCredentials
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Composable
internal fun PlayerScreenRuntime.BindPlayerRuntimeEffects() {
    val currentFeedback = liveGestureFeedback ?: gestureFeedback
    LaunchedEffect(currentFeedback) {
        if (currentFeedback != null) {
            renderedGestureFeedback = currentFeedback
        }
    }

    LaunchedEffect(parentMetaType, parentMetaId) {
        playerMeta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        playerMetaVideos = playerMeta?.videos.orEmpty()
        if (playerMetaVideos.isEmpty()) {
            MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.let { meta ->
                playerMeta = meta
                playerMetaVideos = meta.videos
            }
        }
    }

    LaunchedEffect(metaUiState.meta, parentMetaType, parentMetaId) {
        val currentMeta = metaUiState.meta ?: return@LaunchedEffect
        if (currentMeta.type == parentMetaType && currentMeta.id == parentMetaId) {
            playerMeta = currentMeta
            playerMetaVideos = currentMeta.videos
        }
    }

    LaunchedEffect(currentStreamBingeGroup, parentMetaId) {
        val bg = currentStreamBingeGroup
        if (bg != null && parentMetaId.isNotBlank()) {
            BingeGroupCacheRepository.save(parentMetaId, bg)
        }
    }

    LaunchedEffect(activePlaybackKey, activeSourceUrl, activeSourceAudioUrl, activeSourceHeaders, activeSourceResponseHeaders) {
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        playbackSnapshotKey = null
        cancelNextEpisodeAutoPlay()
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        // NOTE: do NOT reset the credential-refresh counter here. This effect fires on the refresh's
        // OWN source swap (activeSourceUrl changes to the fresh link), and resetting the counter here
        // is exactly what let a short-TTL Stalker link re-mint forever. The counter is re-armed only on
        // a new videoId (channel/content change) or after sustained healthy playback — see below.
        initialLoadCompleted = false
        resumePlaybackStarted = false
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        accumulatedSeekResetJob?.cancel()
        accumulatedSeekResetJob = null
        accumulatedSeekState = null
        speedBoostRestoreSpeed = null
        preferredAudioSelectionApplied = false
        appliedAudioPreferences = null
        preferredSubtitleSelectionApplied = false
        isUserExplicitAudioSelection = false
        isUserExplicitSubtitleSelection = false
        hasScannedTextTracksOnce = false
        selectedSubtitleIndex = -1
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
        showSourcesPanel = false
        showEpisodesPanel = false
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        PlayerStreamsRepository.clearEpisodeStreams()
        SubtitleRepository.clear()
        autoFetchedAddonSubtitlesForKey = null
        WatchProgressRepository.ensureLoaded()
    }

    // Re-arm the credential-refresh counter on a genuinely new stream (channel/content change). This is
    // keyed on the videoId, NOT the source URL, so it does NOT fire on the refresh's own re-mint swap
    // (which keeps the same videoId) — the loop guard survives a re-mint but a real channel-zap resets it.
    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber) {
        credentialRefreshAttempts = 0
        credentialRefreshBaselinePositionMs = 0L
    }

    LaunchedEffect(
        activeTorrentInfoHash,
        activeTorrentFileIdx,
        activeTorrentFilename,
        activeTorrentTrackers,
        p2pSettingsUiState.p2pEnabled,
    ) {
        val infoHash = activeTorrentInfoHash
        if (infoHash == null) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }
        if (!P2pSettingsRepository.isVisible || !p2pSettingsUiState.p2pEnabled) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }

        p2pResolvedSourceUrl = null
        val requestedFileIdx = activeTorrentFileIdx
        val requestedFilename = activeTorrentFilename
        val requestedTrackers = activeTorrentTrackers
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        initialLoadCompleted = false
        resumePlaybackStarted = false

        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = requestedFileIdx,
                    filename = requestedFilename,
                    trackers = requestedTrackers,
                ),
            )
            if (activeTorrentInfoHash == infoHash && activeTorrentFileIdx == requestedFileIdx) {
                activeSourceAudioUrl = null
                activeSourceHeaders = emptyMap()
                activeSourceResponseHeaders = emptyMap()
                p2pResolvedSourceUrl = localUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = getString(
                Res.string.player_error_failed_start_torrent,
                error.message ?: genericUnknownLabel,
            )
            controlsVisible = !playerControlsLocked
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(p2pStreamingState, activeTorrentInfoHash) {
        val state = p2pStreamingState
        if (activeTorrentInfoHash != null && state is P2pStreamingState.Error) {
            p2pResolvedSourceUrl = null
            playerController = null
            playerControllerSourceUrl = null
            playbackSnapshot = PlayerPlaybackSnapshot()
            initialLoadCompleted = true
            errorMessage = getString(Res.string.player_error_torrent, state.message)
            controlsVisible = !playerControlsLocked
        }
    }

    LaunchedEffect(playbackSession.videoId) {
        subtitleDelayMs = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(playbackSession.videoId) ?: 0
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleDelayMs) {
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    }

    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl) {
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleStyle) {
        playerController?.applySubtitleStyle(subtitleStyle)
    }

    val subtitlePreferenceKey = listOf(
        playerSettingsUiState.preferredSubtitleLanguage,
        playerSettingsUiState.secondaryPreferredSubtitleLanguage.orEmpty(),
        subtitleStyle.useForcedSubtitles,
    ).joinToString("|")
    var lastSubtitlePreferenceKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        playerController,
        subtitlePreferenceKey,
        preferredSubtitleSelectionApplied,
        selectedSubtitleIndex,
        selectedAddonSubtitleId,
        useCustomSubtitles,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        val preferenceChanged = lastSubtitlePreferenceKey != null &&
            lastSubtitlePreferenceKey != subtitlePreferenceKey
        lastSubtitlePreferenceKey = subtitlePreferenceKey

        controller.applySubtitlePreferences(
            preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
            secondaryPreferredLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            useForcedSubtitles = subtitleStyle.useForcedSubtitles,
            autoSelectionApplied = preferredSubtitleSelectionApplied,
            hasActiveSubtitle = selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null,
            useCustomSubtitles = useCustomSubtitles,
        )

        if (preferenceChanged) {
            preferredSubtitleSelectionApplied = false
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        title,
        activeStreamTitle,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        poster,
        background,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        controller.updateNowPlayingMetadata(buildNowPlayingInfo())
    }

    // F17: an IPTV movie/episode is looked up by subtitle add-ons under its public IMDb id; its own
    // provider-scoped id never leaves the app (AddonSubtitleIdPolicy).
    LaunchedEffect(activeVideoId, parentMetaId, activeSeasonNumber, activeEpisodeNumber) {
        resolvedPublicSubtitleId = null
        if (!AddonSubtitleIdPolicy.isProviderScoped(activeVideoId)) return@LaunchedEffect
        resolvedPublicSubtitleId = runCatchingUnlessCancelled {
            com.nuvio.app.core.contracts.IptvSubtitleIdAccess.resolver.publicSubtitleVideoId(
                parentMetaId = parentMetaId,
                season = activeSeasonNumber,
                episode = activeEpisodeNumber,
            )
        }.getOrNull()
    }

    LaunchedEffect(
        activeSourceUrl,
        addonSubtitleFetchKey,
    ) {
        if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) return@LaunchedEffect
        val fetchKey = addonSubtitleFetchKey ?: return@LaunchedEffect
        if (autoFetchedAddonSubtitlesForKey == fetchKey) return@LaunchedEffect
        autoFetchedAddonSubtitlesForKey = fetchKey
        fetchAddonSubtitlesForActiveItem()
    }

    LaunchedEffect(playerController, playerControllerSourceUrl, activeSourceUrl, preferredAudioLanguageTargets) {
        if (playerControllerSourceUrl == activeSourceUrl) {
            applyPreferredAudioTrack(preferredAudioLanguageTargets)
        }
    }

    LaunchedEffect(activeSourceUrl, preferredAudioLanguageTargets) {
        refreshServerTracks()
        applyPreferredServerAudioTrack()
    }

    LaunchedEffect(playbackSnapshot.isLoading, playerController, preferredAudioLanguageTargets) {
        if (!playbackSnapshot.isLoading && playerController != null) {
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playbackSnapshot.isLoading,
        preferredAudioSelectionApplied,
        preferredSubtitleSelectionApplied,
        trackPreferenceRestoreApplied,
        addonSubtitles,
        isLoadingAddonSubtitles,
    ) {
        if (playerController == null || playbackSnapshot.isLoading) {
            return@LaunchedEffect
        }
        if (trackPreferenceRestoreApplied && preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
            return@LaunchedEffect
        }

        repeat(10) {
            refreshTracks()
            if (trackPreferenceRestoreApplied && preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        initialSeekApplied,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        if (initialSeekApplied || playbackSnapshot.isLoading) return@LaunchedEffect
        if (isPartySynced && PartyRepository.playback.value != null) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        val progressFraction = activeInitialProgressFraction
            ?.takeIf { it > 0f }
            ?.coerceIn(0f, 1f)
        val targetPositionMs = when {
            activeInitialPositionMs > 0L -> activeInitialPositionMs
            progressFraction != null && playbackSnapshot.durationMs > 0L -> {
                (playbackSnapshot.durationMs.toDouble() * progressFraction.toDouble()).toLong()
            }
            progressFraction != null -> return@LaunchedEffect
            else -> 0L
        }
        if (targetPositionMs <= 0L) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        controller.seekTo(targetPositionMs)
        initialSeekApplied = true
    }

    BindPlayerUiVisibilityEffects()
    BindPlayerMetadataAndSkipEffects()
    BindSessionReportTick()
    BindPartyEffects()

    DisposableEffect(activeSourceUrl) {
        val effectSourceUrl = activeSourceUrl
        onDispose { ServerPlayback.stop(effectSourceUrl) }
    }

    DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
        val effectVideoId = playbackSession.videoId
        val effectSourceUrl = activeSourceUrl
        val effectSourceAudioUrl = activeSourceAudioUrl
        onDispose {
            if (
                playbackSession.videoId == effectVideoId &&
                activeSourceUrl == effectSourceUrl &&
                activeSourceAudioUrl == effectSourceAudioUrl
            ) {
                flushWatchProgress()
            }
        }
    }

    DisposableEffect(Unit) {
        PlayerStreamsRepository.pauseSearchForPlayback()
        onDispose {
            args.launchId?.let { launchId -> PlayerLaunchStore.update(launchId) { currentLaunch(it) } }
            playerController?.clearNowPlayingInfo()
            P2pStreamingEngine.shutdown()
            cancelNextEpisodePreload()
            PlayerStreamsRepository.clearAll()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerUiVisibilityEffects() {
    LaunchedEffect(
        controlsVisible,
        controlsActivityTick,
        isScrubbingTimeline,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        showParentalGuide,
        errorMessage,
    ) {
        if (
            !controlsVisible ||
            isScrubbingTimeline ||
            !playbackSnapshot.isPlaying ||
            playbackSnapshot.isLoading ||
            showParentalGuide ||
            errorMessage != null
        ) {
            return@LaunchedEffect
        }
        delay(3500)
        controlsVisible = false
    }

    LaunchedEffect(playerControlsLocked, lockedOverlayVisible) {
        if (!playerControlsLocked || !lockedOverlayVisible) return@LaunchedEffect
        delay(PlayerLockedOverlayDurationMs)
        lockedOverlayVisible = false
    }

    LaunchedEffect(
        playerSettingsUiState.pauseOverlayEnabled,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        errorMessage,
    ) {
        pausedOverlayVisible = false
        if (!playerSettingsUiState.pauseOverlayEnabled || playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
            return@LaunchedEffect
        }
        delay(5000)
        pausedOverlayVisible = true
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
    ) {
        ServerPlayback.onPlaybackSnapshot(
            url = activeSourceUrl,
            positionMs = playbackSnapshot.positionMs,
            isPlaying = playbackSnapshot.isPlaying,
            isLoading = playbackSnapshot.isLoading,
            isEnded = playbackSnapshot.isEnded,
        )
        if (playbackSnapshot.isEnded) {
            flushWatchProgress(TrackingScrobbleAction.STOP)
            previousIsPlaying = false
            pendingSeekScrobbleRestart = false
            return@LaunchedEffect
        }

        if (previousIsPlaying && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
            pendingSeekScrobbleRestart = false
            flushWatchProgress(TrackingScrobbleAction.PAUSE)
        }

        if (playbackSnapshot.isPlaying && pendingSeekScrobbleRestart) {
            pendingSeekScrobbleRestart = false
            if (hasRequestedScrobbleStartForCurrentItem) {
                emitTrackingSeekScrobbleStart()
            } else {
                emitTrackingScrobbleStart()
            }
        } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
            emitTrackingScrobbleStart()
        }

        // Playback sessions for sources that keep their own watched state (media servers): announce
        // the start once per playing item, and a resume after a pause as progress.
        if (playbackSnapshot.isPlaying) {
            val startedSession = reportSessionStart()
            if (!previousIsPlaying && !startedSession) reportSessionProgress(paused = false)
            // A media-server item watched further elsewhere: offer the jump once, as the session starts.
            if (startedSession) offerServerResumeIfNewer()
        }

        if (!playbackSnapshot.isLoading) {
            previousIsPlaying = playbackSnapshot.isPlaying
        }
        if (playbackSnapshot.isPlaying) {
            persistPlaybackProgressTick()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerMetadataAndSkipEffects() {
    val shuffleProfile by remember {
        EpisodeShuffleRepository.ensureLoaded()
        EpisodeShuffleRepository.uiState
    }.collectAsStateWithLifecycle()
    val shuffleSettings = remember(shuffleProfile, profileId, parentMetaId, parentMetaType) {
        val profile = if (profileId == com.nuvio.app.features.profiles.ProfileRepository.activeProfileId) shuffleProfile
            else EpisodeShuffleRepository.readProfile(profileId)
        profile.settings(parentMetaId, parentMetaType)
    }

    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber, parentMetaId, parentMetaType) {
        parentalWarnings = emptyList()
        showParentalGuide = false
        parentalGuideHasShown = false
        playbackStartedForParentalGuide = false

        val imdbId = resolveParentalGuideImdbId() ?: return@LaunchedEffect
        val guide = ParentalGuideRepository.getParentalGuide(imdbId) ?: return@LaunchedEffect
        parentalWarnings = buildParentalWarnings(guide, parentalGuideLabels)

        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, parentalWarnings) {
        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, activeSourceUrl) {
        if (playbackSnapshot.isPlaying) {
            // Give the engine a moment: mpv's bitrate is a rolling estimate that reads 0
            // at first frame, and ExoPlayer has not always resolved the audio Format yet.
            kotlinx.coroutines.delay(2_500L)
            tryShowStreamInfo()
        }
    }

    LaunchedEffect(
        activeVideoId, parentMetaId, parentMetaType, contentType, activeSeasonNumber, activeEpisodeNumber,
        playerSettingsUiState.skipIntroEnabled,
    ) {
        skipIntervals = emptyList()
        autoSkippedIntervals.clear()
        lastManualSkipSeekPositions = null
        activeSkipInterval = null
        skipIntervalDismissed = false
        showNextEpisodeCard = false
        nextEpisodeCardDismissed = false
        cancelNextEpisodeAutoPlay()

        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val vid = activeVideoId
        if (!playerSettingsUiState.skipIntroEnabled) return@LaunchedEffect
        if ((contentType ?: parentMetaType).equals("movie", ignoreCase = true)) {
            skipIntervals = SkipIntroRepository.getMovieSkipIntervals(parentMetaId, vid)
            return@LaunchedEffect
        }
        if (season == null || episode == null || vid == null) return@LaunchedEffect

        launch {
            val imdbFromContent = parentMetaId.takeIf { it.startsWith("tt") }
                ?: (metaUiState.meta ?: playerMeta)
                    ?.takeIf { it.id == parentMetaId }
                    ?.imdbId
                    ?.takeIf { it.startsWith("tt") }
            val intervals = when {
                vid.startsWith("mal:") -> {
                    val malId = vid.removePrefix("mal:").substringBefore(':')
                    SkipIntroRepository.getSkipIntervalsForMal(malId = malId, episode = episode, imdbId = imdbFromContent, imdbSeason = season, imdbEpisode = episode)
                }
                vid.startsWith("kitsu:") -> {
                    val kitsuId = vid.removePrefix("kitsu:").substringBefore(':')
                    SkipIntroRepository.getSkipIntervalsForKitsu(kitsuId = kitsuId, episode = episode, imdbId = imdbFromContent, imdbSeason = season, imdbEpisode = episode)
                }
                else -> SkipIntroRepository.getSkipIntervals(
                    imdbId = vid.substringBefore(':').takeIf { it.startsWith("tt") },
                    season = season,
                    episode = episode,
                )
            }
            skipIntervals = intervals
        }
    }

    LaunchedEffect(
        playbackSnapshot.positionMs, playbackSnapshot.durationMs, playbackSnapshot.isPlaying, skipIntervals,
        playerSettingsUiState.autoSkipSegmentTypes,
        playerSettingsUiState.skipIntroEnabled, isScrubbingTimeline, initialSeekApplied,
        lastManualSkipSeekPositions,
    ) {
        if (skipIntervals.isEmpty()) {
            activeSkipInterval = null
            return@LaunchedEffect
        }
        val positionSec = playbackSnapshot.positionMs / 1000.0
        lastManualSkipSeekPositions?.let { (fromMs, toMs) ->
            autoSkippedIntervals += skipIntervals.intervalsAtSeekPositions(fromMs, toMs)
        }
        val current = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec < interval.endTime &&
                interval.internalSkipAction(skipIntervals, playbackSnapshot.durationMs) != null
        }
        if (current != activeSkipInterval) {
            activeSkipInterval = current
            if (current != null) skipIntervalDismissed = false
        }
        val controller = playerController
        if (current != null && controller != null &&
            playerControllerSourceUrl == activeSourceUrl &&
            playerSettingsUiState.skipIntroEnabled && playbackSnapshot.isPlaying &&
            !isScrubbingTimeline && initialSeekApplied && !isPartyControlLocked &&
            current.shouldAutoSkip(playerSettingsUiState.autoSkipSegmentTypes) &&
            current !in autoSkippedIntervals
        ) {
            val durationMs = playbackSnapshot.durationMs
            val rawMs = current.internalSkipAction(skipIntervals, durationMs)?.targetMs ?: return@LaunchedEffect
            val seekPositionMs = if (durationMs > 0L) rawMs.coerceAtMost(durationMs - 1) else rawMs
            val notification = current.autoSkipNotificationMessage(seekPositionMs)
            autoSkippedIntervals.add(current)
            controller.seekTo(seekPositionMs)
            scheduleProgressSyncAfterSeek()
            publishPartyPlayback(positionMs = seekPositionMs)
            skipIntervalDismissed = true
            notification?.let { NuvioToastController.show(it, AUTO_SKIP_NOTIFICATION_DURATION_MS) }
        }
    }

    LaunchedEffect(
        playerMetaVideos,
        shuffleSettings,
        profileId,
        parentMetaId,
        activeSeasonNumber,
        activeEpisodeNumber,
        watchProgressUiState.entries,
        watchedUiState.watchedKeys,
    ) {
        if (!isSeries || playerMetaVideos.isEmpty()) {
            nextEpisodeInfo = null
            return@LaunchedEffect
        }
        val curSeason = activeSeasonNumber ?: return@LaunchedEffect
        val curEpisode = activeEpisodeNumber ?: return@LaunchedEffect
        val nextVideo = if (shuffleSettings.enabled) {
            EpisodeShuffleRepository.shuffle.select(
                profileId, parentMetaId, playerMetaVideos, shuffleSettings.includeWatched,
                watchedShuffleEpisodes(parentMetaId, parentMetaType, playerMetaVideos, watchedUiState.watchedKeys),
                shuffleEpisodeProgress(parentMetaId, watchProgressUiState.entries),
                ShuffleSurface.PLAYBACK, current = curSeason to curEpisode,
            )
        } else {
            EpisodeShuffleRepository.shuffle.clearSelection(profileId, parentMetaId, ShuffleSurface.PLAYBACK)
            PlayerNextEpisodeRules.resolveNextEpisode(
                videos = playerMetaVideos,
                currentSeason = curSeason,
                currentEpisode = curEpisode,
            )
        }
        val nextSeason = nextVideo?.season
        val nextEpisode = nextVideo?.episode
        nextEpisodeInfo = if (nextVideo != null && nextSeason != null && nextEpisode != null) {
            val playbackVideoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
                fallbackVideoId = nextVideo.id,
            )
            val isWatched = watchProgressUiState.progressForVideo(
                videoId = playbackVideoId,
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
            )?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = nextVideo,
            )
            NextEpisodeInfo(
                videoId = nextVideo.id,
                season = nextSeason,
                episode = nextEpisode,
                title = nextVideo.title,
                thumbnail = nextVideo.thumbnail,
                overview = nextVideo.overview,
                released = nextVideo.released,
                hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released),
                isWatched = isWatched,
                unairedMessage = if (!PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)) {
                    "$airsPrefix ${nextVideo.released ?: tbaLabel}"
                } else null,
            )
        } else null
    }

    LaunchedEffect(playbackSnapshot.isEnded) {
        if (playbackSnapshot.isEnded && nextEpisodeCardDismissed &&
            playerSettingsUiState.streamAutoPlayNextEpisodeEnabled &&
            nextEpisodeInfo?.hasAired == true
        ) {
            nextEpisodeCardDismissed = false
        }
    }

    LaunchedEffect(
        activePlaybackKey,
        playbackSnapshot,
        playbackSnapshotKey,
        initialSeekApplied,
        isScrubbingTimeline,
        errorMessage,
        nextEpisodeInfo,
        skipIntervals,
        playerSettingsUiState.nextEpisodeThresholdMode,
        playerSettingsUiState.nextEpisodeThresholdPercent,
        playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        playerSettingsUiState.streamAutoPlayNextEpisodeEnabled,
        nextEpisodeCardDismissed,
    ) {
        // Upstream's threshold (incl. ended + playback-key guards). `atThreshold` is kept separate
        // from `shouldShow` because the fork's reset below re-arms a dismissed card only once the
        // position leaves the end zone — keying it on shouldShow would re-show a dismissed card.
        val atThreshold = nextEpisodeInfo != null && isAtNextEpisodeThreshold()
        val shouldShow = atThreshold && !nextEpisodeCardDismissed
        if (nextEpisodeAutoPlayAutomatic &&
            (!shouldShow || !playerSettingsUiState.streamAutoPlayNextEpisodeEnabled)
        ) {
            cancelNextEpisodeAutoPlay()
        }
        if (shouldShow && !showNextEpisodeCard) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true && !isPartyControlLocked) {
                playNextEpisode(automatic = true)
            }
        } else if (!atThreshold && !nextEpisodeFlowIsManual) {
            // Seeking back out of the end zone cancels a pending auto-advance and re-arms the card.
            if (showNextEpisodeCard) {
                nextEpisodeAutoPlayJob?.cancel()
                nextEpisodeAutoPlaySearching = false
                nextEpisodeAutoPlaySourceName = null
                nextEpisodeAutoPlayCountdown = null
            }
            showNextEpisodeCard = false
            nextEpisodeCardDismissed = false
        }
    }
}

private fun PlayerScreenRuntime.buildNowPlayingInfo(): PlayerNowPlayingInfo {
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    return PlayerNowPlayingInfo(
        title = title.ifBlank { activeStreamTitle },
        subtitle = buildNowPlayingSubtitle(
            isEpisode = isEpisode,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        ),
        artworkUrl = firstNonBlankUrl(poster, background),
    )
}

private fun buildNowPlayingSubtitle(
    isEpisode: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String? {
    if (!isEpisode) return null

    val episodeParts = buildList {
        localizedSeasonEpisodeCode(seasonNumber, episodeNumber)?.let { add(it) }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    return when (episodeParts.size) {
        0 -> null
        1 -> episodeParts.first()
        else -> "${episodeParts[0]} - ${episodeParts[1]}"
    }
}

private fun firstNonBlankUrl(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

internal fun PlayerScreenRuntime.removeFailedStreamFromCache() {
    val currentVideoId = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = currentVideoId,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.remove(cacheKey)
}

internal fun PlayerScreenRuntime.tryRefreshCredentialedSourceAfterError(message: String?): Boolean {
    if (ServerStreams.isServerSourceId(activeProviderAddonId)) return retryServerSourceAfterError(message)
    val streamProvider = com.nuvio.app.core.contracts.StreamSourceAccess.current()
    val failedUrl = activeSourceUrl
    // IPTV (xtream/stalker) sources always qualify: a Stalker create_link token is often embedded
    // in the URL PATH (nginx secure_link style), which the query-param heuristic can't see — and a
    // 401 on those means the single-use/short-TTL token died, exactly what a refresh fixes.
    // Two iptv shapes: direct-lane content (xtream videoId) and the TMDB-matched lane, recognised
    // by its provider group id ("xtream-match:<accountId>").
    val matchedSourceId = activeProviderAddonId?.takeIf { streamProvider.isMatchSourceId(it) }
    val isIptvSource = matchedSourceId != null ||
        streamProvider.isHandledId(activeVideoId)
    val isEligible = isIptvSource || failedUrl.hasLikelyExpiringPlaybackCredentials()
    iptvRefreshLog.i {
        "gate: iptv=$isIptvSource matchedSrc=$matchedSourceId addonId=$activeProviderAddonId " +
            "videoId=$activeVideoId expiringCreds=${failedUrl.hasLikelyExpiringPlaybackCredentials()} " +
            "jobActive=${credentialRefreshJob?.isActive} attempts=$credentialRefreshAttempts"
    }
    // Bounded, URL-INDEPENDENT gate (PlayerCredentialRefreshPolicy): a Stalker create_link mints a new
    // unique short-TTL URL every time, so a URL-keyed guard never matched and the refresh looped
    // forever. Cap the consecutive re-mints instead; the counter re-arms only on a new channel/content
    // or sustained healthy playback (never on the refresh's own swap).
    when (
        PlayerCredentialRefreshPolicy.decide(
            isEligible = isEligible,
            refreshInFlight = credentialRefreshJob?.isActive == true,
            consecutiveRefreshes = credentialRefreshAttempts,
        )
    ) {
        PlayerCredentialRefreshPolicy.Decision.NOT_ELIGIBLE -> return false
        PlayerCredentialRefreshPolicy.Decision.IN_FLIGHT -> return true
        PlayerCredentialRefreshPolicy.Decision.EXHAUSTED -> {
            iptvRefreshLog.w {
                "credential-refresh cap reached ($credentialRefreshAttempts) — surfacing the error " +
                    "instead of re-minting (breaks the Stalker live re-mint loop)"
            }
            return false
        }
        PlayerCredentialRefreshPolicy.Decision.ATTEMPT -> {}
    }

    val currentVideoId = activeVideoId ?: return false
    // Before the first frame the snapshot reads 0, so the request's resume target wins (B59-H1);
    // after it, the viewer's own position. The recovery baseline must not fall below the reopen
    // point: a 0 baseline under a resume at 13:42 would count the reopened position as "recovered"
    // and re-arm the re-mint loop guard on the first snapshot (live keeps its snapshot baseline).
    val swapStart = sourceSwapStart()
    credentialRefreshAttempts++
    credentialRefreshBaselinePositionMs = maxOf(playbackSnapshot.positionMs.coerceAtLeast(0L), swapStart.positionMs)
    removeFailedStreamFromCache()

    val savedPositionMs = swapStart.positionMs
    val expectedProviderAddonId = activeProviderAddonId
    val expectedProviderName = activeProviderName
    val expectedStreamTitle = activeStreamTitle
    val expectedBingeGroup = currentStreamBingeGroup
    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber

    errorMessage = null
    controlsVisible = !playerControlsLocked

    credentialRefreshJob = scope.launch {
        try {
            var refreshedStream: StreamItem? = null
            if (matchedSourceId != null) {
                // TMDB-matched iptv stream: PlayerStreamsRepository has no xtream-match lane to poll,
                // so re-run the owning source's TMDB->stream match directly — one targeted resolve
                // that mints a fresh Stalker create_link (Xtream URLs come back identical and are
                // rejected by the candidate's url != failedUrl check, which is correct: a 401 on a
                // stable URL is an account problem, not a token problem).
                val streams = runCatchingUnlessCancelled {
                    streamProvider.resolveMatchStreams(matchedSourceId, type, currentVideoId, season, episode)
                }.getOrDefault(emptyList())
                iptvRefreshLog.i { "matched-lane re-resolve: sourceId=$matchedSourceId returned ${streams.size} stream(s) type=$type videoId=$currentVideoId" }
                refreshedStream = findCredentialRefreshCandidate(
                    streams = streams,
                    failedUrl = failedUrl,
                    expectedProviderAddonId = expectedProviderAddonId,
                    expectedProviderName = expectedProviderName,
                    expectedStreamTitle = expectedStreamTitle,
                    expectedBingeGroup = expectedBingeGroup,
                )
            } else {
                PlayerStreamsRepository.loadSources(
                    type = type,
                    videoId = currentVideoId,
                    season = season,
                    episode = episode,
                    forceRefresh = true,
                    // A static-cmd Stalker verdict would rebuild the URL that just 401'd — the
                    // refresh needs a genuinely fresh create_link.
                    forceMintIptv = true,
                )

                var pollCount = 0
                while (pollCount < CREDENTIAL_REFRESH_POLL_COUNT && refreshedStream == null) {
                    val state = PlayerStreamsRepository.sourceState.value
                    refreshedStream = findCredentialRefreshCandidate(
                        streams = state.groups.flatMap { it.streams },
                        failedUrl = failedUrl,
                        expectedProviderAddonId = expectedProviderAddonId,
                        expectedProviderName = expectedProviderName,
                        expectedStreamTitle = expectedStreamTitle,
                        expectedBingeGroup = expectedBingeGroup,
                    )
                    if (
                        refreshedStream != null ||
                        state.emptyStateReason != null ||
                        (!state.isAnyLoading && state.groups.isNotEmpty())
                    ) {
                        break
                    }
                    delay(CREDENTIAL_REFRESH_POLL_INTERVAL_MS)
                    pollCount++
                }
            }

            val stream = refreshedStream
            if (stream == null) {
                iptvRefreshLog.w { "no replacement stream found — surfacing the original error" }
                errorMessage = message
                controlsVisible = !playerControlsLocked
                return@launch
            }

            // A matched-lane Stalker candidate is DEFERRED ("stalker-deferred:…") — listing never
            // mints. The refresh must hand the engine a REAL url, and it forces the mint: a
            // static-cmd verdict here would rebuild the very URL that just died.
            val refreshedUrl = stream.playableDirectUrl?.let { candidate ->
                if (streamProvider.isDeferredUrl(candidate)) {
                    runCatchingUnlessCancelled {
                        streamProvider.resolveDeferredUrl(candidate, forceMint = true)
                    }.getOrNull()
                } else {
                    candidate
                }
            }
            if (refreshedUrl.isNullOrBlank() || refreshedUrl == failedUrl) {
                iptvRefreshLog.w { "replacement URL unusable (blank=${refreshedUrl.isNullOrBlank()} sameAsFailed=${refreshedUrl == failedUrl})" }
                errorMessage = message
                controlsVisible = !playerControlsLocked
                return@launch
            }
            iptvRefreshLog.i { "recovered with a fresh link, resuming at ${savedPositionMs}ms" }

            flushWatchProgress()
            stopActiveP2pStream()
            activeSourceUrl = refreshedUrl
            activeSourceAudioUrl = null
            activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
            activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
            activeStreamType = stream.streamType
            activeStreamTitle = stream.streamLabel
            activeStreamSubtitle = stream.streamSubtitle
            activeProviderName = stream.addonName
            activeProviderAddonId = stream.addonId
            activeStream = stream
            currentStreamBingeGroup = stream.behaviorHints.bingeGroup
            activeInitialPositionMs = savedPositionMs
            activeInitialProgressFraction = swapStart.progressFraction
            showSourcesPanel = false
            controlsVisible = true
        } finally {
            // Upstream: always release the sources loader, even when the refresh bails out early.
            PlayerStreamsRepository.stopSourcesLoading()
        }
    }
    return true
}

private fun PlayerScreenRuntime.retryServerSourceAfterError(message: String?): Boolean {
    if (credentialRefreshJob?.isActive == true) return true
    val failedUrl = activeSourceUrl
    // One fallback per source (fork gate is attempt-counted, not URL-keyed: see PlayerCredentialRefreshPolicy).
    if (credentialRefreshAttempts >= 1) return false
    credentialRefreshAttempts++
    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    errorMessage = null
    credentialRefreshJob = scope.launch {
        val playback = ServerPlayback.fallback(failedUrl)
        if (playback == null) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }
        externalSubtitles = playback.subtitles
        activeSourceUrl = playback.url
        activeSourceHeaders = playback.headers
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
    }
    return true
}

private fun findCredentialRefreshCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    expectedProviderAddonId: String?,
    expectedProviderName: String,
    expectedStreamTitle: String,
    expectedBingeGroup: String?,
): StreamItem? =
    streams
        .asSequence()
        .mapNotNull { stream ->
            val refreshedUrl = stream.playableDirectUrl?.takeIf { it.isNotBlank() && it != failedUrl }
                ?: return@mapNotNull null
            val providerMatches = if (!expectedProviderAddonId.isNullOrBlank()) {
                stream.addonId == expectedProviderAddonId
            } else {
                stream.addonName == expectedProviderName
            }
            if (!providerMatches) return@mapNotNull null

            var score = 100
            if (stream.streamLabel == expectedStreamTitle) score += 40
            if (!expectedBingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == expectedBingeGroup) {
                score += 20
            }
            if (refreshedUrl.hasLikelyExpiringPlaybackCredentials()) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

/** Traces the expired-link recovery so a field 401 can be diagnosed from a logcat capture. */
private val iptvRefreshLog = Logger.withTag("IptvLinkRefresh")

private const val CREDENTIAL_REFRESH_POLL_COUNT = 30
private const val CREDENTIAL_REFRESH_POLL_INTERVAL_MS = 500L

/**
 * The clock of a source's own playback reports (a media server's "Now playing"): a paused player emits no events and
 * the 60 s watch-progress save is far slower than the references' ~15 s, so without a tick of its own the server
 * would hear nothing while paused and the session would lapse. The tick is local and cheap - the reporter's pure
 * policy ([com.nuvio.app.features.servers.ServerPlayback.onPlaybackSnapshot], upstream's session reporter) decides
 * whether anything is sent - and it lives only while the player screen is on screen and STARTED (never in the
 * background, never stacked: one loop per player composition).
 */
@Composable
private fun PlayerScreenRuntime.BindSessionReportTick() {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        if (com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry.isEmpty) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                delay(PlaybackSessionTickMs)
                if (reportedSessionKey != null && !playbackSnapshot.isEnded) {
                    reportSessionProgress(paused = !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading)
                }
            }
        }
    }
}
