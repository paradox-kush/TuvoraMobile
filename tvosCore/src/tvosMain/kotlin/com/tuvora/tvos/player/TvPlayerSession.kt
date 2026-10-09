package com.tuvora.tvos.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.PlaybackSessionState
import com.nuvio.app.features.player.LivePlaybackRejoinPolicy
import com.nuvio.app.features.player.MpvStartPosition
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.NuvioPlayerBridgeCreator
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.externalPlaybackSession
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerStreamInfo
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.player.ResumeLoadPolicy
import com.nuvio.app.features.player.setMpvProperties
import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.addons.AddonRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.nuvio.app.features.player.decodePlayerStreamInfo
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController
import kotlin.time.TimeSource

/** The two Apple TV engines, registered by the Swift app at launch (libmpv bridge, AVPlayer bridge). */
object TvPlayerEngines {
    private val creators = mutableMapOf<PlaybackLane, NuvioPlayerBridgeCreator>()

    fun register(lane: PlaybackLane, creator: NuvioPlayerBridgeCreator) {
        creators[lane] = creator
    }

    internal fun create(lane: PlaybackLane): NuvioPlayerBridge? = creators[lane]?.createBridge()
}

/** The lane that played each title last, so a title AVPlayer failed on opens straight on libmpv next time. */
internal object TvLaneMemory {
    private val lanes = mutableMapOf<String, PlaybackLane>()
    fun get(key: String?): PlaybackLane? = key?.let(lanes::get)
    fun put(key: String?, lane: PlaybackLane) { if (key != null) lanes[key] = lane }
}

data class TvPlayerState(
    val lane: PlaybackLane,
    /** Bumps whenever the engine (and so its view controller) changes; Swift re-embeds on change. */
    val engineGeneration: Int,
    val isLive: Boolean,
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    /** Non-null when playback failed and nothing is left to try. */
    val errorMessage: String? = null,
    /** Series only: the next episode, and whether its card is due (PlayerNextEpisodeRules thresholds). */
    val nextEpisode: com.nuvio.app.features.details.MetaVideo? = null,
    val showNextEpisode: Boolean = false,
    /** The skip segment (intro / recap / ending) the position is in, for NuvioTV's Skip button. */
    val skipSegment: TvSkipSegment? = null,
    /** A slow resume: where it is resuming to, once "Start from beginning" should be offered. */
    val startOverAtMs: Long? = null,
    val speed: Float = 1f,
    val subtitleDelayMs: Int = 0,
    /** The add-on subtitle playing, if one was picked ([AddonSubtitle.id]). */
    val addonSubtitleId: String? = null,
    /** How many skip segments the providers returned for this title (diagnostics / smoke log). */
    val skipSegmentCount: Int = 0,
    val audioDelayMs: Int = 0,
    /** Whether the current engine can shift audio (libmpv yes, AVPlayer no). */
    val audioDelaySupported: Boolean = false,
    /** Whether the current engine draws the subtitle style (libmpv yes, AVPlayer uses the system style). */
    val subtitleStyleSupported: Boolean = false,
    /**
     * Live only: the display mode this channel wants (LiveDisplayCriteriaPolicy), once the engine knows
     * its frame rate. The fullscreen player hands it to LiveDisplayCriteriaController (Swift).
     */
    val displayCriteria: TvDisplayCriteria? = null,
    /**
     * A media server's own position is newer than Tuvora's record: "You watched further on your server.
     * Continue at hh:mm?" Swift shows it for a few seconds; [TvPlayerSession.acceptServerResume] takes it.
     */
    val serverResumeOfferMs: Long? = null,
)

/**
 * Engine controls the shared NuvioPlayerBridge doesn't carry. Apple TV's libmpv bridge implements this;
 * AVPlayer has no audio-delay control, so the player says it's unavailable there.
 */
interface TvAudioDelayControl {
    fun setAudioDelayMs(delayMs: Int)
}

/**
 * The decoded picture's format, for live display frame-rate matching. Apple TV's libmpv bridge
 * implements this from values it caches on its own poll (no mpv read here); AVPlayer does not, as
 * it plays VOD only.
 */
interface TvVideoFormatSource {
    fun videoFormat(): TvVideoFormat?
}

/**
 * One Apple TV playback: Apple TV's counterpart of the phone's PlayerScreenRuntime (upstream,
 * Compose-bound, excluded from :tvosCore). It reuses the phone's decisions — [PlaybackLanePolicy],
 * the live freeze/recovery policies through [TvLiveMonitor], the completed-save gate, and the same
 * save cadence (every 60 s while playing, 1 s after a seek, a flush on close).
 *
 * Swift owns the lifecycle: [attach] when the player screen appears, [detach] when it hides, [close]
 * when the viewer leaves. State is polled only while attached, so nothing runs off screen.
 */
class TvPlayerSession(
    private val launch: PlayerLaunch,
    /**
     * Live only: fetches a fresh address for a reconnect. Stalker links are single-use (create_link),
     * so replaying the old URL fails; Xtream/M3U just return the same address. Null = replay.
     */
    private val liveReresolve: (suspend () -> TvResolvedSource?)? = null,
    private val vodReresolve: (suspend () -> TvResolvedSource?)? = null,
) {
    private val log = Logger.withTag("TvPlayerSession")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isLive = LivePlaybackRejoinPolicy.rejoinsLiveEdge(launch.streamType, launch.liveReplay != null)
    private val liveChannel = launch.streamType == "live"
    private val progressKey = launch.videoId ?: launch.parentMetaId
    private val playbackSession = launch.externalPlaybackSession()
    private val gate = TvProgressGate()
    private var monitor = TvLiveMonitor()
    /** True while the TV changes display mode for this channel: the picture is deliberately off. */
    private var displayModeSwitching = false
    private val clock = TimeSource.Monotonic.markNow()

    private var vodRetryFailure: String? = null
    private var activeSourceUrl = launch.sourceUrl
    private var activeSourceHeaders = launch.sourceHeaders
    private val vodRetry = TvVodSourceRetry(scope,
        resolve = { vodReresolve?.invoke() },
        reopen = { fresh: TvResolvedSource, position: Long, paused: Boolean ->
            activeSourceUrl = fresh.url
            activeSourceHeaders = fresh.headers
            val headers = TvPlaybackHeaders.sanitize(fresh.headers)
            bridge?.loadFileWithAudio(fresh.url, launch.sourceAudioUrl,
                headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                launch.externalSubtitles.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                MpvStartPosition.loadOption(position, false))
            if (paused) bridge?.pause()
        },
        failed = {
            vodRetryFailure = "Could not reopen this source. Try again or choose another source."
            _state.value = _state.value.copy(isLoading = false, errorMessage = vodRetryFailure)
        },
    )
    private var bridge: NuvioPlayerBridge? = null
    /** Final: once closed no engine is ever opened again (a late attach / re-embed / zap would play with no screen). */
    private var closed = false
    private var escalated = false
    private var audioLanguagesApplied = false
    private var subtitleLanguageApplied = false
    private var subtitleUserPicked = false

    /** The profile's audio/subtitle languages, resolved exactly as the phone does, applied once tracks appear. */
    private fun applyLanguagePreferences(b: NuvioPlayerBridge) {
        val settings = PlayerSettingsRepository.uiState.value
        val device = com.nuvio.app.features.player.DeviceLanguagePreferences.preferredLanguageCodes()
        if (!audioLanguagesApplied && b.getAudioTrackCount() > 1) {
            audioLanguagesApplied = true
            com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets(
                preferredAudioLanguage = settings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
                deviceLanguages = device,
                contentOriginalLanguage = launch.contentLanguage,
            ).takeIf { it.isNotEmpty() }?.let(b::applyAudioLanguagePreferences)
        }
        if (TvSubtitleSelectionPolicy.appliesProfileLanguage(subtitleUserPicked, subtitleLanguageApplied, b.getSubtitleTrackCount())) {
            subtitleLanguageApplied = true
            val targets = com.nuvio.app.features.player.resolvePreferredSubtitleLanguageTargets(
                preferredSubtitleLanguage = settings.preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage,
                deviceLanguages = device,
            )
            val isNone = settings.preferredSubtitleLanguage == com.nuvio.app.features.player.SubtitleLanguageOption.NONE
            TvTrackLanguagePolicy.subtitleChoice(subtitleTracks(), targets, isNone)?.let(b::selectSubtitleTrack)
        }
    }
    private var wantsToPlay = true

    // Sources that keep their own watched state (media servers): the neutral session-reporter registry,
    // fed from the same moments as the phone (start once playing, a tick, pause/seek landing, stop on flush).
    private val sessionVideoId = launch.videoId ?: launch.parentMetaId
    private var sessionReported = false
    private var lastSessionTickAtMs = 0L
    private var resumeOfferAsked = false

    // Skip segments (IntroDB / AniSkip / Anime-Skip through the phone's SkipIntroRepository).
    private var skipIntervals: List<SkipInterval> = emptyList()
    private val autoSkipped = mutableSetOf<SkipInterval>()
    private var skipsRequested = false

    // Resume offer (ResumeLoadPolicy): when this engine load began, and whether a frame has shown.
    private var loadStartedAtMs = 0L
    private var playbackStarted = false
    private var resumeTargetMs = launch.initialPositionMs

    // Subtitles: the add-on subtitle to load once the engine is ready, and the saved delay.
    private var pendingAddonSubtitleUrl: String? = null
    private var subtitleDelayApplied = false
    private var audioDelayApplied = false
    private var addonSubtitlesRequested = false
    private var pollJob: Job? = null
    private var seekSaveJob: Job? = null
    private var lastSaveAtMs = 0L
    private var snapshot = PlayerPlaybackSnapshot()

    private val _state = MutableStateFlow(
        TvPlayerState(
            lane = PlaybackLanePolicy.initial(
                LaneInput(
                    url = launch.sourceUrl,
                    isLive = liveChannel,
                    setting = EngineSettingStore.parse(NSUserDefaults.standardUserDefaults.stringForKey(EngineSettingStore.KEY)),
                    remembered = TvLaneMemory.get(progressKey),
                    hasExternalSubtitles = launch.externalSubtitles.isNotEmpty(),
                    mimeType = launch.sourceResponseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
                ),
            ),
            engineGeneration = 0,
            isLive = isLive,
        ),
    )
    val state: StateFlow<TvPlayerState> = _state.asStateFlow()

    val title: String get() = launch.title
    val subtitle: String? get() = launch.episodeTitle ?: launch.streamTitle.takeIf { it != launch.title }

    /** The current engine's view controller. Changes when the session escalates; see [TvPlayerState.engineGeneration]. */
    fun viewController(): UIViewController? {
        if (closed) return null
        return (bridge ?: open(_state.value.lane, launch.initialPositionMs))?.createPlayerViewController()
    }

    /** True once [close] ran: the engine is gone and the session can't play again. */
    val isClosed: Boolean get() = closed

    /** The series this episode belongs to (loaded on attach), for the Episodes panel and Next Episode. */
    var seriesMeta: com.nuvio.app.features.details.MetaDetails? = null
        private set

    val currentSeason: Int? get() = launch.seasonNumber
    val currentEpisode: Int? get() = launch.episodeNumber
    val currentVideoId: String get() = launch.videoId ?: launch.parentMetaId

    private fun loadSeries() {
        if (launch.seasonNumber == null || launch.episodeNumber == null || seriesMeta != null) return
        scope.launch {
            val meta = runCatching {
                com.tuvora.tvos.screens.TvMetaLookup.fetch(launch.parentMetaType, launch.parentMetaId)
            }.getOrNull() ?: return@launch
            seriesMeta = meta
            val next = com.nuvio.app.features.player.skip.PlayerNextEpisodeRules.resolveNextEpisode(
                videos = meta.videos, currentSeason = launch.seasonNumber, currentEpisode = launch.episodeNumber,
            )
            _state.value = _state.value.copy(nextEpisode = next)
        }
    }

    fun attach() {
        if (closed) return
        loadSeries()
        loadSkipIntervals()
        fetchAddonSubtitles()
        if (bridge == null) open(_state.value.lane, launch.initialPositionMs)
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    fun detach() {
        vodRetry.cancel()
        pollJob?.cancel()
        pollJob = null
        save(flush = true)
        reportSessionStop()
    }

    fun close() {
        vodRetry.cancel()
        if (closed) return
        closed = true
        detach()
        seekSaveJob?.cancel()
        bridge?.clearNowPlayingInfo()
        bridge?.destroy()
        bridge = null
        scope.cancel()
    }

    /**
     * The TV is switching display mode (live frame-rate matching): the libmpv video output is idled
     * for it, so the freeze watch must not read the missing frames as a frozen channel. When the
     * switch settles the watch starts over, with the startup grace, as for a fresh channel.
     */
    fun displayModeSwitch(inProgress: Boolean) {
        if (displayModeSwitching == inProgress) return
        displayModeSwitching = inProgress
        if (!inProgress) monitor = TvLiveMonitor()
        log.i { "display mode switch ${if (inProgress) "started" else "settled"}" }
    }

    fun togglePlayPause() = if (_state.value.isPlaying) pause() else play()
    fun play() { if (!closed) { wantsToPlay = true; bridge?.play(); reportAfterToggle() } }
    fun pause() { wantsToPlay = false; bridge?.pause(); reportAfterToggle() }

    /** A pause / resume is told to a source that keeps its own state once the engine has caught up (the next poll). */
    private fun reportAfterToggle() {
        if (!sessionReported) return
        scope.launch { delay(TOGGLE_REPORT_DELAY_MS); poll(); reportSessionProgress() }
    }

    fun seekBy(offsetMs: Long) {
        if (isLive) return
        bridge?.seekBy(offsetMs)
        scheduleSeekSave()
    }

    fun seekTo(positionMs: Long) {
        if (isLive) return
        bridge?.seekTo(positionMs.coerceAtLeast(0L))
        scheduleSeekSave()
    }

    /** Audio tracks of the current engine (ids are the engine's own; pass them back to [selectAudio]). */
    fun audioTracks(): List<TvTrack> {
        val b = bridge ?: return emptyList()
        return (0 until b.getAudioTrackCount()).map { i ->
            TvTrack(b.getAudioTrackId(i).toIntOrNull() ?: i, b.getAudioTrackLabel(i), b.getAudioTrackLang(i), b.isAudioTrackSelected(i))
        }
    }

    fun subtitleTracks(): List<TvTrack> {
        val b = bridge ?: return emptyList()
        return (0 until b.getSubtitleTrackCount()).map { i ->
            TvTrackLanguagePolicy.subtitleTrack(b.getSubtitleTrackId(i).toIntOrNull() ?: i, b.getSubtitleTrackLabel(i), b.getSubtitleTrackLang(i), b.isSubtitleTrackSelected(i))
        }
    }

    fun selectAudio(trackId: Int) { bridge?.selectAudioTrack(trackId) }

    /**
     * -1 turns subtitles off. An explicit pick wins over the profile's language preference, which is
     * otherwise applied once when the first subtitle track appears (the phone's
     * isUserExplicitSubtitleSelection).
     */
    fun selectSubtitle(trackId: Int) {
        subtitleUserPicked = true
        if (_state.value.addonSubtitleId != null) {
            _state.value = _state.value.copy(addonSubtitleId = null)
            bridge?.clearExternalSubtitleAndSelect(trackId)
        } else {
            bridge?.selectSubtitleTrack(trackId)
        }
    }

    /** 0 = fit, 1 = fill, 2 = zoom (NuvioPlayerBridge.setResizeMode). */
    fun setResizeMode(mode: Int) { bridge?.setResizeMode(mode) }

    fun setSpeed(speed: Float) { bridge?.setPlaybackSpeed(speed) }

    fun retry() {
        if (closed) return
        vodRetryFailure = null
        _state.value = _state.value.copy(errorMessage = null, isLoading = true)
        if (!isLive && vodReresolve != null) {
            val position = if (snapshot.positionMs > 0) snapshot.positionMs else resumeTargetMs
            vodRetry.retry(position, !wantsToPlay)
        } else bridge?.retry()
    }

    // ---- Skip intro / recap / ending ---------------------------------------------------------

    /** The phone runtime's lookup (PlayerScreenRuntimeEffects): movie, MAL, Kitsu or IMDb episode. */
    private fun loadSkipIntervals() {
        if (skipsRequested || isLive) return
        skipsRequested = true
        val type = (launch.contentType ?: launch.parentMetaType).lowercase()
        if (type in setOf("cloud", "live", "tv")) return
        scope.launch {
            PlayerSettingsRepository.ensureLoaded()
            if (!PlayerSettingsRepository.uiState.value.skipIntroEnabled) return@launch
            val season = launch.seasonNumber
            val episode = launch.episodeNumber
            val vid = launch.videoId
            val fetched = runCatching {
                when {
                    type == "movie" -> SkipIntroRepository.getMovieSkipIntervals(launch.parentMetaId, vid)
                    season == null || episode == null || vid == null -> emptyList()
                    vid.startsWith("mal:") -> SkipIntroRepository.getSkipIntervalsForMal(
                        malId = vid.removePrefix("mal:").substringBefore(':'), episode = episode,
                        imdbId = imdbId(), imdbSeason = season, imdbEpisode = episode,
                    )
                    vid.startsWith("kitsu:") -> SkipIntroRepository.getSkipIntervalsForKitsu(
                        kitsuId = vid.removePrefix("kitsu:").substringBefore(':'), episode = episode,
                        imdbId = imdbId(), imdbSeason = season, imdbEpisode = episode,
                    )
                    else -> SkipIntroRepository.getSkipIntervals(
                        imdbId = vid.substringBefore(':').takeIf { it.startsWith("tt") } ?: imdbId(),
                        season = season, episode = episode,
                    )
                }
            }.onFailure { log.w(it) { "skip segments unavailable" } }.getOrDefault(emptyList())
            skipIntervals = fetched + skipIntervals.filter { it.provider == SMOKE_PROVIDER }
            _state.value = _state.value.copy(skipSegmentCount = skipIntervals.size)
            log.i { "skip segments: ${fetched.size} ${fetched.map { "${it.type}@${it.startTime}-${it.endTime}" }}" }
        }
    }

    private fun imdbId(): String? = launch.parentMetaId.takeIf { it.startsWith("tt") }
        ?: seriesMeta?.imdbId?.takeIf { it.startsWith("tt") }

    /** NuvioTV's Skip button: jump to the segment's end (or the post-credits scene). */
    fun skipSegment() {
        val segment = _state.value.skipSegment ?: return
        skipIntervals.firstOrNull { (it.startTime * 1000).toLong() == segment.startMs }?.let { autoSkipped += it }
        bridge?.seekTo(segment.targetMs)
        _state.value = _state.value.copy(skipSegment = null)
        scheduleSeekSave()
    }

    /** Simulator smoke hook: a segment to exercise the Skip button without a catalog title. */
    fun addSkipIntervalForTesting(startSeconds: Double, endSeconds: Double, type: String) {
        skipIntervals = skipIntervals + SkipInterval(startSeconds, endSeconds, type, SMOKE_PROVIDER)
        _state.value = _state.value.copy(skipSegmentCount = skipIntervals.size)
    }

    // ---- Resume: "Start from beginning" -------------------------------------------------------

    private fun updateStartOverOffer(b: NuvioPlayerBridge) {
        if (!playbackStarted && ResumeLoadPolicy.playbackStarted(
                videoProgressTicks = snapshot.videoProgressTicks,
                hasVideoTrack = snapshot.videoProgressTicks >= 0L,
                positionMs = snapshot.positionMs,
                initialPositionMs = resumeTargetMs,
            )
        ) playbackStarted = true
        val loadingFor = clock.elapsedNow().inWholeMilliseconds - loadStartedAtMs
        val offer = ResumeLoadPolicy.offerStartOver(
            isResumeLoad = ResumeLoadPolicy.isResumeLoad(resumeTargetMs, isLive),
            firstFrameShown = playbackStarted,
            loadingForMs = loadingFor,
        ) && _state.value.errorMessage == null
        val offerAt = if (offer) resumeTargetMs else null
        if (_state.value.startOverAtMs != offerAt) _state.value = _state.value.copy(startOverAtMs = offerAt)
    }

    /** NuvioTV "Start from beginning": drop the resume and play from 0:00 on the same engine. */
    fun startFromBeginning() {
        resumeTargetMs = 0L
        playbackStarted = false
        loadStartedAtMs = clock.elapsedNow().inWholeMilliseconds
        _state.value = _state.value.copy(startOverAtMs = null, isLoading = true)
        bridge?.restartFromBeginning()
        bridge?.play()
        wantsToPlay = true
    }

    // ---- Subtitles: add-ons and delay ----------------------------------------------------------

    val addonSubtitles: StateFlow<List<AddonSubtitle>> get() = SubtitleRepository.addonSubtitles
    val addonSubtitlesLoading: StateFlow<Boolean> get() = SubtitleRepository.isLoading

    /** The phone's add-on subtitle search for this title (PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem). */
    fun fetchAddonSubtitles() {
        if (addonSubtitlesRequested || isLive) return
        val type = (launch.contentType ?: launch.parentMetaType).takeIf { it.isNotBlank() } ?: return
        if (type.lowercase() in setOf("cloud", "live", "tv")) return
        val ownVideoId = launch.videoId?.takeIf { it.isNotBlank() } ?: return
        addonSubtitlesRequested = true
        AddonRepository.initialize()
        // As the phone's fetch key: search again whenever the set of subtitle add-ons changes (the
        // manifests may still be loading when the player opens).
        scope.launch {
            // F17: an IPTV item is looked up under its public IMDb id; its provider-scoped id (which
            // embeds the playlist key) never reaches an add-on (AddonSubtitleIdPolicy).
            val publicId = if (com.nuvio.app.features.player.AddonSubtitleIdPolicy.isProviderScoped(ownVideoId)) {
                runCatching {
                    com.nuvio.app.core.contracts.IptvSubtitleIdAccess.resolver.publicSubtitleVideoId(
                        parentMetaId = launch.parentMetaId,
                        season = launch.seasonNumber,
                        episode = launch.episodeNumber,
                    )
                }.getOrNull()
            } else {
                null
            }
            val videoId = com.nuvio.app.features.player.AddonSubtitleIdPolicy
                .requestVideoId(ownVideoId, publicId) ?: return@launch
            val requestType = com.nuvio.app.features.player.AddonSubtitleIdPolicy.requestType(type, videoId)
            AddonRepository.uiState
                .map { addonSubtitleRequests(requestType, videoId).map { it.url } }
                .distinctUntilChanged()
                .collect { urls -> if (urls.isNotEmpty()) SubtitleRepository.fetchAddonSubtitles(requestType, videoId) }
        }
    }

    /**
     * Plays an add-on subtitle. AVPlayer cannot load a side-loaded subtitle file, so on that lane the
     * title moves to libmpv at the current position first (the lane policy's external-subtitle rule).
     */
    fun selectAddonSubtitle(subtitle: AddonSubtitle) {
        // An explicit pick: the profile's subtitle language must not switch it off when the track lands.
        subtitleUserPicked = true
        _state.value = _state.value.copy(addonSubtitleId = subtitle.id)
        if (_state.value.lane == PlaybackLane.AvPlayer) {
            pendingAddonSubtitleUrl = subtitle.url
            switchLane(PlaybackLane.Libmpv, reason = "add-on subtitle")
            return
        }
        bridge?.setSubtitleUrl(subtitle.url)
    }

    fun setSubtitleDelay(delayMs: Int) {
        val clamped = delayMs.coerceIn(TvSubtitleDelay.MIN_MS, TvSubtitleDelay.MAX_MS)
        _state.value = _state.value.copy(subtitleDelayMs = clamped)
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(currentVideoId, clamped)
        bridge?.setSubtitleDelayMs(clamped)
    }

    /** Applies the saved delay and a pending add-on subtitle once the engine is ready for them. */
    private fun applyPendingSubtitleState(b: NuvioPlayerBridge) {
        if (snapshot.isLoading || snapshot.durationMs <= 0L) return
        pendingAddonSubtitleUrl?.let { url -> pendingAddonSubtitleUrl = null; b.setSubtitleUrl(url) }
        if (!audioDelayApplied) {
            audioDelayApplied = true
            _state.value.audioDelayMs.takeIf { it != 0 }?.let { (b as? TvAudioDelayControl)?.setAudioDelayMs(it) }
        }
        if (!subtitleDelayApplied) {
            subtitleDelayApplied = true
            val saved = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(currentVideoId) ?: 0
            if (saved != 0) b.setSubtitleDelayMs(saved)
            _state.value = _state.value.copy(subtitleDelayMs = saved)
        }
    }

    // ---- Stream info ---------------------------------------------------------------------------

    /** NuvioTV's stream info panel: what the current engine reports about the stream. */
    fun streamInfoSections(): List<TvInfoSection> {
        val b = bridge ?: return emptyList()
        val engine = if (_state.value.lane == PlaybackLane.AvPlayer) "AVPlayer" else "libmpv"
        val info = runCatching { decodePlayerStreamInfo(b.getStreamInfoJson(), engine) }.getOrDefault(PlayerStreamInfo(playerEngine = engine))
        val subtitleName = _state.value.addonSubtitleId?.let { id -> SubtitleRepository.addonSubtitles.value.firstOrNull { it.id == id }?.display }
            ?: subtitleTracks().firstOrNull { it.selected }?.let { it.label.ifBlank { it.language } }
        return TvStreamInfoRows.sections(info, launch.providerName, launch.streamTitle.takeIf { it != launch.title }, subtitleName)
    }

    /** Moves playback to [lane] at the current position (one connection at a time). */
    private fun switchLane(lane: PlaybackLane, reason: String) {
        log.i { "switching to $lane ($reason)" }
        val resumeAt = snapshot.positionMs.takeIf { it > 0L } ?: launch.initialPositionMs
        bridge?.destroy()
        bridge = null
        subtitleDelayApplied = false
        audioDelayApplied = false
        _state.value = _state.value.copy(lane = lane, engineGeneration = _state.value.engineGeneration + 1, isLoading = true)
        open(lane, resumeAt)
        TvLaneMemory.put(progressKey, lane)
    }

    // ---- Audio delay (NuvioTV Audio Delay) ------------------------------------------------------

    fun setAudioDelay(delayMs: Int) {
        val clamped = TvAudioDelay.clamp(delayMs)
        _state.value = _state.value.copy(audioDelayMs = clamped)
        (bridge as? TvAudioDelayControl)?.setAudioDelayMs(clamped)
    }

    // ---- Subtitle style (in-player, persisted through the shared subtitle settings) ------------

    fun subtitleStyleView(): TvSubtitleStyleView = TvSubtitleStyleEditor.view(PlayerSettingsRepository.uiState.value.subtitleStyle)

    fun subtitleSizeStep(up: Boolean) = editStyle { TvSubtitleStyleEditor.size(it, up) }
    fun subtitleOffsetStep(up: Boolean) = editStyle { TvSubtitleStyleEditor.offset(it, up) }
    fun setSubtitleTextColor(index: Int) = editStyle { TvSubtitleStyleEditor.textColor(it, index) }
    fun setSubtitleBackground(index: Int) = editStyle { TvSubtitleStyleEditor.background(it, index) }
    fun toggleSubtitleBold() = editStyle { TvSubtitleStyleEditor.bold(it) }
    fun toggleSubtitleOutline() = editStyle { TvSubtitleStyleEditor.outline(it) }
    fun resetSubtitleStyle() = editStyle { TvSubtitleStyleEditor.reset(it) }

    private fun editStyle(edit: (com.nuvio.app.features.player.SubtitleStyleState) -> com.nuvio.app.features.player.SubtitleStyleState): TvSubtitleStyleView {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setSubtitleStyle(edit(PlayerSettingsRepository.uiState.value.subtitleStyle))
        bridge?.let(::applySubtitleStyle)
        return subtitleStyleView()
    }

    private fun applySubtitleStyle(b: NuvioPlayerBridge) {
        val subStyle = TvSubtitleStyle.forMpv(PlayerSettingsRepository.uiState.value.subtitleStyle)
        b.applySubtitleStyle(
            textColor = subStyle.textColor, backgroundColor = subStyle.backgroundColor, outlineColor = subStyle.outlineColor,
            outlineSize = subStyle.outlineSize, bold = subStyle.bold, fontSize = subStyle.fontSize,
            subPos = subStyle.subPos, stripSdh = subStyle.stripSdh,
        )
        // UX61/F47: shared box/outline/side-padding mapping. Takes effect once the Apple TV MPV bridge
        // adopts NuvioPlayerPropertyBridge (tvosApp, lane E); until then this is a no-op.
        val style = PlayerSettingsRepository.uiState.value.subtitleStyle
        b.setMpvProperties(
            com.nuvio.app.features.player.SubtitleStyleMpvMapping.properties(
                backgroundColorHex = subStyle.backgroundColor,
                backgroundAlpha = style.backgroundColor.alpha,
                // The style's own outline: forMpv's opaque-box fallback must not become a real outline.
                outlineColorHex = TvSubtitleStyle.mpvColor(style.outlineColor.toArgb()),
                outlineSize = if (style.outlineEnabled) style.outlineWidth.toDouble() else 0.0,
                sideMarginPercent = style.sideMarginPercent,
            ),
        )
    }

    /**
     * F36 manual zoom for the Apple TV player UI (tvosApp, lane E): video-scale-x/y + video-pan-x/y
     * through [com.nuvio.app.features.player.VideoZoomPolicy]. No-op until the bridge adopts
     * NuvioPlayerPropertyBridge.
     */
    fun setVideoZoom(zoom: com.nuvio.app.features.player.VideoZoom) {
        bridge?.setMpvProperties(com.nuvio.app.features.player.VideoZoomPolicy.mpvProperties(zoom))
    }

    // ---- Playback-issue report --------------------------------------------------------------

    /**
     * NuvioTV's "Report Issue". NuvioTV uploads to its own report endpoint, which Tuvora doesn't
     * configure; here the report goes to the log and, as an analytics event, through AnalyticsSink
     * (PostHog once it is wired on Apple TV). No stream address, host or credentials are sent.
     */
    fun reportIssue(trigger: String) {
        val st = _state.value
        val b = bridge
        val engine = if (st.lane == PlaybackLane.AvPlayer) "AVPlayer" else "libmpv"
        val info = b?.let { runCatching { decodePlayerStreamInfo(it.getStreamInfoJson(), engine) }.getOrNull() }
        val props = TvPlaybackIssueReport.properties(
            trigger = trigger, lane = engine, isLive = st.isLive,
            contentType = launch.contentType ?: launch.parentMetaType,
            positionMs = st.positionMs, durationMs = st.durationMs, isLoading = st.isLoading, isPlaying = st.isPlaying,
            errorMessage = st.errorMessage, videoCodec = info?.videoCodec,
            resolution = info?.let { com.nuvio.app.features.player.StreamInfoFormat.qualityLabel(it.videoWidth, it.videoHeight) },
            audioCodec = info?.audioCodec, speed = st.speed, audioDelayMs = st.audioDelayMs, subtitleDelayMs = st.subtitleDelayMs,
        )
        log.w { "playback issue reported: $props" }
        com.nuvio.app.core.analytics.AnalyticsSink.capture(TvPlaybackIssueReport.EVENT, props)
    }

    private fun open(lane: PlaybackLane, startMs: Long): NuvioPlayerBridge? {
        if (closed) return null
        val created = TvPlayerEngines.create(lane) ?: TvPlayerEngines.create(PlaybackLane.Libmpv) ?: run {
            log.e { "no player engine registered for $lane" }
            _state.value = _state.value.copy(errorMessage = "No player available")
            return null
        }
        bridge = created
        loadStartedAtMs = clock.elapsedNow().inWholeMilliseconds
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        created.configureAudioOutput(audioOutput = settings.iosAudioOutputMode.mpvValue)
        created.configureVideoOutput(
            hardwareDecoder = settings.iosHardwareDecoderMode.mpvValue,
            targetColorspaceHint = settings.iosTargetColorspaceHintEnabled,
            toneMapping = settings.iosToneMappingMode.mpvValue,
            hdrComputePeak = settings.iosHdrComputePeakEnabled,
            targetPrimaries = settings.iosTargetPrimaries.mpvValue,
            targetTransfer = settings.iosTargetTransfer.mpvValue,
            extendedDynamicRange = settings.iosExtendedDynamicRangeEnabled,
            deband = settings.iosDebandEnabled,
            interpolation = settings.iosInterpolationEnabled,
            brightness = settings.iosBrightness,
            contrast = settings.iosContrast,
            saturation = settings.iosSaturation,
            gamma = settings.iosGamma,
        )
        applySubtitleStyle(created)
        val delayControl = created as? TvAudioDelayControl
        _state.value = _state.value.copy(
            audioDelaySupported = delayControl != null,
            subtitleStyleSupported = lane == PlaybackLane.Libmpv,
        )
        created.setIsLiveStream(isLive)
        val headers = TvPlaybackHeaders.sanitize(activeSourceHeaders)
        created.loadFileWithAudio(
            videoUrl = activeSourceUrl,
            audioUrl = launch.sourceAudioUrl,
            headersJson = headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            subtitlesJson = launch.externalSubtitles.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            // The resume rides the load (mpv `start=`), as on the phone (B59b).
            startOption = MpvStartPosition.loadOption(startMs, isLive),
        )
        created.updateNowPlayingMetadata(title = launch.title, subtitle = subtitle, artworkUrl = launch.poster ?: launch.background)
        if (!wantsToPlay) created.pause()
        return created
    }

    private fun poll() {
        // The old engine still reports its previous failure while a replacement link is minted.
        if (vodRetry.isResolving || vodRetryFailure != null) return
        val b = bridge ?: return
        val error = b.getErrorMessage().takeIf { it.isNotBlank() }
        snapshot = PlayerPlaybackSnapshot(
            isLoading = b.getIsLoading(),
            isPlaying = b.getIsPlaying(),
            isEnded = b.getIsEnded(),
            durationMs = b.getDurationMs(),
            positionMs = b.getPositionMs(),
            bufferedPositionMs = b.getBufferedMs(),
            playbackSpeed = b.getPlaybackSpeed(),
            videoProgressTicks = b.getVideoFrameTicks(),
            hasVideoTrack = true,
        )
        val lane = _state.value.lane
        if (error != null && handleFailure(lane, error)) return
        if (snapshot.isPlaying && snapshot.positionMs > 0L) TvLaneMemory.put(progressKey, lane)
        applyLanguagePreferences(b)
        val settings = PlayerSettingsRepository.uiState.value
        val showNext = _state.value.nextEpisode != null && snapshot.durationMs > 0 &&
            com.nuvio.app.features.player.skip.PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                skipIntervals = skipIntervals,
                thresholdMode = settings.nextEpisodeThresholdMode,
                thresholdPercent = settings.nextEpisodeThresholdPercent,
                thresholdMinutesBeforeEnd = settings.nextEpisodeThresholdMinutesBeforeEnd,
            )
        applyPendingSubtitleState(b)
        val skip = TvSkipPolicy.decide(
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            isPlaying = snapshot.isPlaying && !snapshot.isLoading && playbackStarted,
            intervals = skipIntervals,
            autoSkipTypes = if (settings.skipIntroEnabled) settings.autoSkipSegmentTypes else emptySet(),
            alreadyAutoSkipped = autoSkipped,
        )
        if (skip.autoSkipToMs != null && skip.interval != null) {
            autoSkipped += skip.interval
            log.i { "auto-skip ${skip.interval.type} -> ${skip.autoSkipToMs}" }
            b.seekTo(skip.autoSkipToMs)
            scheduleSeekSave()
        }
        updateStartOverOffer(b)
        _state.value = _state.value.copy(
            skipSegment = skip.segment?.takeIf { skip.autoSkipToMs == null },
            speed = snapshot.playbackSpeed,
            showNextEpisode = showNext,
            isLoading = snapshot.isLoading,
            isPlaying = snapshot.isPlaying,
            isEnded = snapshot.isEnded,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            bufferedMs = snapshot.bufferedPositionMs,
            displayCriteria = if (isLive) LiveDisplayCriteriaPolicy.criteria((b as? TvVideoFormatSource)?.videoFormat()) else null,
        )
        driveSessionReports()
        if (isLive) {
            // While the TV switches display mode the video is idled on purpose (displayModeSwitch).
            if (!displayModeSwitching) when (monitor.sample(clock.elapsedNow().inWholeMilliseconds, snapshot, wantsToPlay)) {
                TvLiveAction.None -> Unit
                TvLiveAction.Reconnect -> { log.i { "live freeze: reconnecting" }; reconnectLive() }
                TvLiveAction.GiveUp -> _state.value = _state.value.copy(errorMessage = "The channel stopped responding")
            }
        } else {
            val now = clock.elapsedNow().inWholeMilliseconds
            if (snapshot.isPlaying && now - lastSaveAtMs >= SAVE_INTERVAL_MS) {
                lastSaveAtMs = now
                save(flush = false)
            }
        }
    }

    /** Handles a reported error; true means this poll is done (state already updated).*/
    private fun handleFailure(lane: PlaybackLane, error: String): Boolean {
        val next = PlaybackLanePolicy.escalation(lane, escalated)
        if (next == null) {
            if (isLive) {
                // A live error is a stream that ended: same bounded reconnect ladder as a freeze.
                val ended = snapshot.copy(isEnded = true, isPlaying = false, isLoading = false)
                when (monitor.sample(clock.elapsedNow().inWholeMilliseconds, ended, wantsToPlay = true)) {
                    TvLiveAction.Reconnect -> reconnectLive()
                    TvLiveAction.GiveUp -> _state.value = _state.value.copy(errorMessage = error, isLoading = false, isPlaying = false)
                    TvLiveAction.None -> _state.value = _state.value.copy(isLoading = true)
                }
                return true
            }
            _state.value = _state.value.copy(errorMessage = error, isLoading = false, isPlaying = false)
            return true
        }
        log.w { "$lane failed ($error); escalating to $next" }
        escalated = true
        val resumeAt = snapshot.positionMs.takeIf { it > 0L } ?: launch.initialPositionMs
        // One connection at a time: release the failed engine before the next one opens the stream.
        bridge?.destroy()
        bridge = null
        subtitleDelayApplied = false
        audioDelayApplied = false
        _state.value = _state.value.copy(lane = next, engineGeneration = _state.value.engineGeneration + 1, isLoading = true)
        open(next, resumeAt)
        TvLaneMemory.put(progressKey, next)
        return true
    }

    private fun reconnectLive() {
        val reresolve = liveReresolve ?: run { bridge?.retry(); return }
        scope.launch {
            val fresh = runCatching { reresolve() }.getOrNull()
            if (fresh == null) { bridge?.retry(); return@launch }
            val headers = TvPlaybackHeaders.sanitize(fresh.headers)
            bridge?.loadFileWithAudio(
                videoUrl = fresh.url,
                audioUrl = null,
                headersJson = headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                subtitlesJson = null,
                startOption = null,
            )
        }
    }

    private fun sessionState() = PlaybackSessionState(
        videoId = sessionVideoId,
        parentMetaId = launch.parentMetaId,
        providerAddonId = launch.providerAddonId,
        positionMs = snapshot.positionMs.coerceAtLeast(0L),
        durationMs = snapshot.durationMs.coerceAtLeast(0L),
    )

    private fun ownedBySource(): Boolean =
        !liveChannel && !PlaybackSessionReporterRegistry.isEmpty &&
            PlaybackSessionReporterRegistry.handlersFor(sessionVideoId, launch.providerAddonId).isNotEmpty()

    /** Start once playing, then a local tick: the reporter's pure policy decides whether anything is sent. */
    private fun driveSessionReports() {
        val now = clock.elapsedNow().inWholeMilliseconds
        val owned = !closed && ownedBySource()
        when (TvSessionReportPolicy.decide(owned, liveChannel, closed, sessionReported, snapshot.isPlaying, snapshot.isEnded, snapshot.durationMs, now, lastSessionTickAtMs)) {
            TvSessionReportPolicy.Action.NONE -> Unit
            TvSessionReportPolicy.Action.START -> {
                sessionReported = true
                lastSessionTickAtMs = now
                val state = sessionState()
                scope.launch { PlaybackSessionReporterRegistry.start(state) }
                askServerResume()
            }
            TvSessionReportPolicy.Action.TICK -> {
                lastSessionTickAtMs = now
                reportSessionProgress()
            }
        }
    }

    private fun reportSessionProgress() {
        if (!sessionReported) return
        val state = sessionState()
        val paused = !snapshot.isPlaying && !snapshot.isLoading
        scope.launch { PlaybackSessionReporterRegistry.progress(state, paused) }
    }

    /** The session ended (detach, close): survives the session's own scope being cancelled right after. */
    private fun reportSessionStop() {
        if (!sessionReported) return
        sessionReported = false
        val state = sessionState()
        reportScope.launch { PlaybackSessionReporterRegistry.stop(state) }
    }

    /** "Resume from server": once per playback, one item fetch inside the source, never a poll (design D3). */
    private fun askServerResume() {
        if (resumeOfferAsked || !PlaybackResumeOfferRegistry.handles(sessionVideoId, launch.providerAddonId)) return
        resumeOfferAsked = true
        val record = WatchProgressRepository.uiState.value.entries.firstOrNull { it.videoId == sessionVideoId }
        val tuvoraPositionMs = launch.initialPositionMs.takeIf { it > 0L } ?: record?.lastPositionMs
        val updatedAt = record?.lastUpdatedEpochMs
        val duration = snapshot.durationMs.takeIf { it > 0L }
        scope.launch {
            val offer = PlaybackResumeOfferRegistry.offerFor(sessionVideoId, launch.providerAddonId, tuvoraPositionMs, updatedAt, duration) ?: return@launch
            if (!TvSessionReportPolicy.shouldOfferResume(snapshot.positionMs, offer.positionMs)) return@launch
            if (offer.autoStart) {
                seekTo(offer.positionMs)
            } else {
                _state.value = _state.value.copy(serverResumeOfferMs = offer.positionMs)
                delay(RESUME_OFFER_VISIBLE_MS)
                _state.value = _state.value.copy(serverResumeOfferMs = null)
            }
        }
    }

    fun acceptServerResume() {
        val to = _state.value.serverResumeOfferMs ?: return
        _state.value = _state.value.copy(serverResumeOfferMs = null)
        seekTo(to)
    }

    private fun scheduleSeekSave() {
        seekSaveJob?.cancel()
        seekSaveJob = scope.launch {
            delay(SEEK_SAVE_DELAY_MS)
            poll()
            save(flush = false, syncRemote = true)
            reportSessionProgress() // the seek has landed: tell the server where the viewer is now
        }
    }

    private fun save(flush: Boolean, syncRemote: Boolean = flush) {
        if (liveChannel) return
        // A bare address (simulator smoke / UI tests) is no title: never into Continue Watching or sync.
        if (launch.parentMetaId.startsWith(TvPlayerLaunches.DIRECT_PREFIX)) return
        if (snapshot.durationMs <= 0L && !snapshot.isEnded) return
        if (!gate.admit(playbackSession.videoId, snapshot)) return
        runCatching {
            if (flush) {
                WatchProgressRepository.flushPlaybackProgress(playbackSession, snapshot, syncRemote)
            } else {
                WatchProgressRepository.upsertPlaybackProgress(playbackSession, snapshot, syncRemote)
            }
        }.onFailure { log.w(it) { "progress save failed" } }
    }

    private companion object {
        /** Reports outlive the session scope (close cancels it right after the stop is queued). */
        val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val TOGGLE_REPORT_DELAY_MS = 700L
        const val RESUME_OFFER_VISIBLE_MS = 12_000L
        const val POLL_MS = 500L
        const val SAVE_INTERVAL_MS = 60_000L
        const val SEEK_SAVE_DELAY_MS = 1_000L
        const val SMOKE_PROVIDER = "smoke"
    }
}

/** A playable address: URL plus the request headers the provider needs. */
data class TvResolvedSource(val url: String, val headers: Map<String, String>)

/** Swift-friendly launch builders (Kotlin default arguments don't cross into Swift). */
object TvPlayerLaunches {
    /** parentMetaId prefix of a [direct] launch; such sessions record no watch progress. */
    const val DIRECT_PREFIX = "direct:"

    /** A stream with no catalog identity — the simulator smoke test and direct URLs. */
    fun direct(url: String, title: String, isLive: Boolean, startPositionMs: Long): PlayerLaunch = PlayerLaunch(
        profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
        title = title,
        sourceUrl = url,
        streamType = if (isLive) "live" else null,
        streamTitle = title,
        providerName = "Direct",
        parentMetaId = "$DIRECT_PREFIX$url",
        parentMetaType = if (isLive) "tv" else "movie",
        initialPositionMs = startPositionMs,
    )

    /**
     * Simulator smoke hook: a direct URL played as a catalog title ([videoId] like `tt0944947:1:1`), so
     * the title's skip segments and add-on subtitles load as they would for a real source.
     */
    fun directAs(url: String, title: String, type: String, videoId: String, startPositionMs: Long): PlayerLaunch {
        val parts = videoId.split(':')
        return PlayerLaunch(
            profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
            title = title,
            sourceUrl = url,
            streamTitle = title,
            providerName = "Direct",
            contentType = type,
            videoId = videoId,
            parentMetaId = parts.first(),
            parentMetaType = type,
            seasonNumber = parts.getOrNull(1)?.toIntOrNull(),
            episodeNumber = parts.getOrNull(2)?.toIntOrNull(),
            initialPositionMs = startPositionMs,
        )
    }
}
