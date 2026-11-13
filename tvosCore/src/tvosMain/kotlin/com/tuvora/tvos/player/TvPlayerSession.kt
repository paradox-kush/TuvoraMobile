package com.tuvora.tvos.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.PlaybackSessionState
import com.nuvio.app.features.livetv.pause.LiveLink
import com.nuvio.app.features.livetv.pause.LivePauseView
import com.nuvio.app.features.player.LivePlaybackRejoinPolicy
import com.nuvio.app.features.player.LivePlaybackStartupPolicy
import com.nuvio.app.features.iptv.stalker.StalkerStreamFallback
import com.nuvio.app.features.player.MpvStartPosition
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.NuvioPlayerBridgeCreator
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.StartDeadlineClock
import com.nuvio.app.features.player.VodStartDeadlinePolicy
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.externalPlaybackSession
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerStreamInfo
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.player.ResumeLoadPolicy
import com.nuvio.app.features.player.setMpvProperties
import com.nuvio.app.features.player.applyStreamLavfOptions
import com.nuvio.app.features.player.LiveLinkReconnectPolicy
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
import com.tuvora.tvos.party.TvParty
import com.tuvora.tvos.party.TvPartyPlayerControls
import com.tuvora.tvos.party.TvPartyPlayerLink
import com.nuvio.app.core.diag.LogRedaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Live only: pause / rewind (libmpv's demuxer cache). [LivePauseView.Kind.Unavailable] for VOD and for an engine
     * that cannot hold the past, so the chrome keeps its plain LIVE badge there.
     */
    val livePause: LivePauseView = LivePauseView.Unavailable,
    /** A continuing M3U replay's title once the playhead moved into the next programme (null = [TvPlayerSession.title]). */
    val replayTitle: String? = null,
    /** A continuing replay's seek bar: the programme's start in the player's timeline, and its length ([TvReplayBar]). */
    val replayOriginMs: Long = 0L,
    val replaySpanMs: Long? = null,
) {
    /** What the seek bar shows: the programme for a replay, else the player's own position. */
    val barPositionMs: Long get() = replaySpanMs?.let { com.tuvora.tvos.screens.TvReplayBar.position(positionMs, replayOriginMs, it) } ?: positionMs
    val barDurationMs: Long get() = replaySpanMs ?: durationMs
}

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
    internal val launch: PlayerLaunch,
    /**
     * Live only: fetches a fresh address for a reconnect. Stalker links are single-use (create_link),
     * so replaying the old URL fails; Xtream/M3U just return the same address. Null = replay.
     */
    private val liveReresolve: (suspend () -> TvResolvedSource?)? = null,
    private val vodReresolve: (suspend () -> TvResolvedSource?)? = null,
) {
    /** A media-server stream: the session URL ServerPlayback prepared (progress is reported against it, stop on close). */
    private var serverPlaybackUrl: String? = null

    /** Runs once when the session is closed (frees a loopback relay opened for a pinned server). */
    private var onClosed: () -> Unit = {}

    /** Kept off the constructor so the Swift-visible initializer stays (launch:liveReresolve:vodReresolve:). */
    internal fun bindServer(playbackUrl: String?, onClosed: () -> Unit): TvPlayerSession {
        serverPlaybackUrl = playbackUrl
        this.onClosed = onClosed
        return this
    }

    /** The live link is spent after its first open (Stalker create_link): no in-engine re-open. */
    private var singleUseLink = false

    /** Whether a live reconnect can ask for a fresh link (tests; the factory must wire it). */
    internal val remintsLiveLink: Boolean get() = liveReresolve != null

    /** Kept off the constructor for the same reason as [bindServer]. See [LiveLinkReconnectPolicy]. */
    internal fun bindSingleUseLink(singleUse: Boolean): TvPlayerSession {
        singleUseLink = singleUse
        return this
    }

    /**
     * B259 follow-up: the tune's Stalker header-profile fallback (null = not a Stalker live channel). A portal-host
     * stream the provider refuses at open for the MAG User-Agent is re-minted once, at once, with the NEUTRAL
     * profile; the re-resolver reads [StalkerStreamFallback.profile]. Kept off the constructor like [bindServer].
     */
    private var identityFallback: StalkerStreamFallback? = null

    internal fun bindIdentityFallback(fallback: StalkerStreamFallback?): TvPlayerSession {
        identityFallback = fallback
        return this
    }

    /** The playing address is spent after its first open: a live Stalker channel (never a replay). */
    private val spentLinkAfterOpen: Boolean get() = singleUseLink && liveChannel && launch.liveReplay == null

    /** The live re-mint in flight, if any: one create_link at a time ([TvLiveRelinkPolicy]). */
    private var remintJob: Job? = null

    /** When a fresh live link was handed to the engine, until the engine stops reporting the old end. */
    private var freshOpenedAtMs: Long? = null

    /** mpv `stream-lavf-o` before every live load; the AVPlayer lane does not adopt it and is unaffected. */
    private fun applyLinkReconnectPolicy(b: NuvioPlayerBridge) {
        b.applyStreamLavfOptions(
            // Any single-use link (a live Stalker channel OR a Stalker archive replay) opens with reconnect off:
            // spentLinkAfterOpen covers live only (its relink ladder mints anew); a replay's link is spent too.
            LiveLinkReconnectPolicy.mpvStreamLavfOptions(
                TvSingleUseLinkPolicy.reconnectMode(singleUseLink && liveChannel),
            ),
        )
    }

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
            // mpv keeps its pause flag across a loadfile: say the intent explicitly either way.
            if (paused) bridge?.pause() else bridge?.play()
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

    /**
     * Watch party (shared PartyRepository / PartySync): while a party is running, remote presses go through
     * it, so a host-only party is respected and every change is told to the party. Live TV never joins.
     */
    val party: TvPartyPlayerLink by lazy { TvPartyPlayerLink(launch, isLive, partyControls) }

    /** Can this title be watched together (not live TV, not catch-up)? */
    val canWatchTogether: Boolean get() = TvParty.canHost(launch, isLive)

    // What the party loop reads and does (never routed back through the party).
    private val partyControls = object : TvPartyPlayerControls {
        override val partyPositionMs: Long get() = _state.value.positionMs
        override val partyDurationMs: Long get() = _state.value.durationMs
        override val partyIsPlaying: Boolean get() = _state.value.isPlaying
        override val partyIsLoading: Boolean get() = _state.value.isLoading
        override val partyIsEnded: Boolean get() = _state.value.isEnded
        override val partySpeed: Float get() = _state.value.speed
        override val partyStarted: Boolean get() = playbackStarted
        override val partyHasError: Boolean get() = _state.value.errorMessage != null
        override val partyWantsToPlay: Boolean get() = wantsToPlay
        override fun partyPlay() { if (!closed) { if (vodDeadlineFailed) retry() else { wantsToPlay = true; bridge?.play() } } }
        override fun partyPause() { wantsToPlay = false; bridge?.pause() }
        override fun partyAdoptPlaying(playing: Boolean) { wantsToPlay = playing }
        override fun partySeek(positionMs: Long) { if (!isLive) { bridge?.seekTo(positionMs.coerceAtLeast(0L)); scheduleSeekSave() } }
        override fun partySpeed(rate: Float) { bridge?.setPlaybackSpeed(rate) }
    }

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
    /**
     * B214/B217: the viewer's active wait. Survives engine escalations, restarts on Retry/start-over,
     * and does not count paused time or gaps with no polling (a re-resolve, the app being suspended).
     */
    private val vodClock = StartDeadlineClock()
    /** The start deadline fired: the engine is parked and poll() must not overwrite the error. */
    private var vodDeadlineFailed = false
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

    /**
     * Live pause / rewind for this channel (null for VOD and replays). Its view rides [TvPlayerState.livePause]; the
     * play intent goes through here so the live freeze watch never reads a pause as a dead channel.
     */
    private val livePause: TvLivePause? = if (isLive) TvLivePause(
        scope = scope,
        bridge = { bridge },
        playIntent = { play ->
            wantsToPlay = play
            if (play) bridge?.play() else bridge?.pause()
        },
        // Go live without a buffer to fall back on: the ordinary reconnect (fresh link for a spent one), playing.
        retune = {
            wantsToPlay = true
            reconnectLive()
        },
        mint = { liveReresolve?.invoke() },
        onView = { view -> _state.value = _state.value.copy(livePause = view) },
    ) else null

    val title: String get() = launch.title

    /** A continuing replay entered its next programme: the player shows [title] from here ([TvPlayerState.replayTitle]). */
    private var replayProgramme: Pair<Long, Long>? = null

    /** The seek bar spans one programme of a continuing replay: [originMs] is where it starts in the player's timeline. */
    fun setReplayBar(originMs: Long, spanMs: Long, programmeStartMs: Long, programmeEndMs: Long) {
        if (closed) return
        replayProgramme = programmeStartMs to programmeEndMs
        _state.value = _state.value.copy(replayOriginMs = originMs, replaySpanMs = spanMs)
    }

    /** A bar position held to what has aired (a replay of a programme still airing stops short of live). */
    fun clampBar(barMs: Long): Long {
        val (start, end) = replayProgramme ?: return barMs
        return com.tuvora.tvos.screens.TvReplayBar.clampSeek(barMs, start, end, com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs())
    }

    /** A seek to a position on the bar (see [TvPlayerState.barPositionMs]). */
    fun seekToBar(barMs: Long) = seekTo(com.tuvora.tvos.screens.TvReplayBar.raw(clampBar(barMs), _state.value.replayOriginMs))

    fun retitle(title: String) {
        if (!closed) _state.value = _state.value.copy(replayTitle = title)
    }
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
        party.attach(scope)
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
        party.detach()
        pollJob?.cancel()
        pollJob = null
        save(flush = true)
        reportSessionStop()
    }

    fun close() {
        vodRetry.cancel()
        if (closed) return
        closed = true
        livePause?.dispose()
        detach()
        ServerPlayback.stop(serverPlaybackUrl)
        onClosed()
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

    fun togglePlayPause() {
        if (closed) return
        if (party.toggle()) { reportAfterToggle(); return }
        livePause?.takeIf { it.handles }?.let { it.togglePlayPause(_state.value.isPlaying); return }
        if (_state.value.isPlaying) pause() else play()
    }

    /**
     * Live pause / rewind: one clickpad-edge press, [deltaMs] through the window the engine holds (negative = back).
     * Opens the shift from live, scrubs inside it, and goes live when stepping into its edge ([LivePauseScrub]).
     */
    fun liveStep(deltaMs: Long) {
        if (closed || !isLive) return
        livePause?.step(deltaMs, wasPlaying = wantsToPlay)
    }

    /** The "−1:05 · LIVE" chip: leave the time shift for the live edge. */
    fun goLive() {
        if (closed || !isLive) return
        livePause?.goLive()
    }
    fun play() { if (!closed) { if (vodDeadlineFailed) { retry(); return }; if (party.setPlaying(true)) reportAfterToggle() else { wantsToPlay = true; bridge?.play(); reportAfterToggle() } } }
    fun pause() { if (party.setPlaying(false)) reportAfterToggle() else { wantsToPlay = false; bridge?.pause(); reportAfterToggle() } }


    /** A pause / resume is told to a source that keeps its own state once the engine has caught up (the next poll). */
    private fun reportAfterToggle() {
        if (!sessionReported) return
        scope.launch { delay(TOGGLE_REPORT_DELAY_MS); poll(); reportSessionProgress() }
    }

    fun seekBy(offsetMs: Long) {
        if (isLive) return
        if (replayProgramme != null) { seekToBar(_state.value.barPositionMs + offsetMs); return }
        if (party.seek(_state.value.positionMs + offsetMs)) return
        bridge?.seekBy(offsetMs)
        scheduleSeekSave()
    }

    fun seekTo(positionMs: Long) {
        if (isLive) return
        if (party.seek(positionMs)) return
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

    fun setSpeed(speed: Float) { if (!party.speed(speed)) bridge?.setPlaybackSpeed(speed) }

    fun retry() {
        if (closed) return
        if (_state.value.livePause.kind == LivePauseView.Kind.Failed) { livePause?.retry(); return }
        // The player's own Retry re-opens the loaded URL; a spent link needs a fresh mint, which only the guide's Retry runs.
        if (!TvSingleUseLinkPolicy.allowsPlayerRetry(singleUseLink)) return
        vodRetryFailure = null
        if (vodDeadlineFailed) { vodDeadlineFailed = false; wantsToPlay = true }
        vodClock.reset(clock.elapsedNow().inWholeMilliseconds)
        _state.value = _state.value.copy(errorMessage = null, isLoading = true)
        if (!isLive && vodReresolve != null) {
            val position = if (snapshot.positionMs > 0) snapshot.positionMs else resumeTargetMs
            vodRetry.retry(position, !wantsToPlay)
        } else if (isLive) {
            // The viewer's Retry: a fresh, still bounded ladder, and a fresh link. Replaying the old
            // URL (bridge.retry) re-opened a spent Stalker create_link, and the monitor, still at
            // its cap, answered GiveUp on the first sample, so Retry could not recover the channel.
            // The reconnect itself goes through the monitor (sampled now), so it counts as the
            // ladder's first attempt and the next poll cannot mint a second link on top of it.
            monitor.onUserRetry()
            identityFallback?.reset()
            poll()
        } else {
            bridge?.retry()
            bridge?.let { restorePlayIntent(it) }
        }
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
        enforceVodStartDeadline()
    }

    /** A VOD start that never shows a frame ends in a terminal error with Retry (the screen's own backstop). */
    private fun enforceVodStartDeadline() {
        val now = clock.elapsedNow().inWholeMilliseconds
        vodClock.onAttempt("vod", now)
        val suspended = !wantsToPlay
        val action = VodStartDeadlinePolicy.decide(
            elapsedMs = vodClock.tick(now, suspended),
            started = playbackStarted,
            isLive = isLive,
            isTorrent = false,
            suspended = suspended,
            hasError = _state.value.errorMessage != null,
            canFailover = false,
        )
        if (action == VodStartDeadlinePolicy.Action.Fail) {
            log.w { "vod start deadline hit; failing with Retry" }
            vodDeadlineFailed = true
            // Park the engine so a late-completing open cannot play behind the error; retry() restarts it.
            wantsToPlay = false
            bridge?.pause()
            _state.value = _state.value.copy(
                errorMessage = VOD_START_FAILED_MESSAGE, isLoading = false, isPlaying = false,
            )
        }
    }

    /** NuvioTV "Start from beginning": drop the resume and play from 0:00 on the same engine. */
    fun startFromBeginning() {
        resumeTargetMs = 0L
        playbackStarted = false
        loadStartedAtMs = clock.elapsedNow().inWholeMilliseconds
        vodClock.reset(loadStartedAtMs)
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
            styledOverlay = false, // native TV retains mpv subtitle rendering

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

    /** Simulator smoke hook: the playback failure card, as if the engine had failed mid-play. */
    fun failForTesting(message: String) {
        _state.value = _state.value.copy(errorMessage = message, isLoading = false, isPlaying = false)
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
        applyLinkReconnectPolicy(created)
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
        announceLiveSource(created)
        return created
    }

    private fun poll() {
        // The old engine still reports its previous failure while a replacement link is minted.
        if (vodRetry.isResolving || vodRetryFailure != null || vodDeadlineFailed) return
        val b = bridge ?: run { enforceVodStartDeadline(); return }
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
        serverPlaybackUrl?.let {
            ServerPlayback.onPlaybackSnapshot(it, snapshot.positionMs, snapshot.isPlaying, snapshot.isLoading, snapshot.isEnded)
        }
        val lane = _state.value.lane
        // A frame showed: this tune's header profile is proven (a NEUTRAL one is remembered for the playlist).
        if (spentLinkAfterOpen && LivePlaybackStartupPolicy.hasStarted(snapshot)) identityFallback?.onFirstFrame()
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
        livePause?.onSnapshot(snapshot.positionMs, snapshot.isPlaying)
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
            val now = clock.elapsedNow().inWholeMilliseconds
            val sinceFreshOpen = freshOpenedAtMs?.let { now - it }
            if (!snapshot.isEnded) freshOpenedAtMs = null
            // The previous link's end, still reported while the fresh one opens, is not a new outage.
            val sampled = if (TvLiveRelinkPolicy.isStaleEnd(snapshot.isEnded, sinceFreshOpen)) {
                snapshot.copy(isEnded = false, isLoading = true)
            } else snapshot
            if (!displayModeSwitching) when (monitor.sample(now, sampled, wantsToPlay)) {
                TvLiveAction.None -> Unit
                TvLiveAction.Reconnect -> { log.i { "live freeze: reconnecting" }; reconnectLive() }
                TvLiveAction.GiveUp -> _state.value = _state.value.copy(errorMessage = TvLiveRelinkPolicy.viewerMessage(null))
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
        // A spent link (Stalker create_link, live or replay) never moves to another engine: it would open the
        // same dead URL there. Live: the reconnect ladder below mints a fresh one. Replay: it fails with Retry.
        val next = PlaybackLanePolicy.escalation(lane, escalated)
            ?.takeIf { TvSingleUseLinkPolicy.allowsLaneEscalation(singleUseLink) }
            ?.takeIf { !isLive || TvLiveRelinkPolicy.mayEscalateLane(spentLinkAfterOpen) }
        if (next == null) {
            if (isLive) {
                // B259 follow-up: refused at open on the MAG identity: one immediate re-mint with the NEUTRAL profile.
                // It skips the ladder's pacing (StalkerStreamIdentityPolicy.remintDelayMs: the provider refused the
                // request, it holds no connection) and is not one of the ladder's attempts; NEUTRAL failing is the ladder's.
                if (spentLinkAfterOpen && remintJob?.isActive != true && identityFallback?.onOpenFailure(error) == true) {
                    log.i { "live open refused: re-minting with the neutral stream profile" }
                    _state.value = _state.value.copy(isLoading = true)
                    remint(spent = true)
                    return true
                }
                // A live error is a stream that ended: same bounded reconnect ladder as a freeze.
                val ended = snapshot.copy(isEnded = true, isPlaying = false, isLoading = false)
                when (monitor.sample(clock.elapsedNow().inWholeMilliseconds, ended, wantsToPlay = true)) {
                    TvLiveAction.Reconnect -> reconnectLive()
                    TvLiveAction.GiveUp -> {
                        // The engine's text carries the stream URL and play token: log it redacted, show plain words.
                        if (_state.value.errorMessage == null) log.w { "live gave up: ${LogRedaction.text(error)}" }
                        _state.value = _state.value.copy(
                            errorMessage = TvLiveRelinkPolicy.viewerMessage(error), isLoading = false, isPlaying = false,
                        )
                    }
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

    /**
     * A reconnect the monitor scheduled (it owns the pacing). What it opens is [TvLiveRelinkPolicy]'s
     * call: a fresh link from the re-resolver, the same stable address, or nothing — a spent Stalker
     * link is never re-opened, by this engine or the next one.
     */
    private fun reconnectLive() {
        val spent = spentLinkAfterOpen
        val step = TvLiveRelinkPolicy.onReconnect(spent, canRemint = liveReresolve != null, remintInFlight = remintJob?.isActive == true)
        log.i { "live reconnect: $step (single-use=$spent)" }
        when (step) {
            TvLiveRelinkPolicy.Step.Replay -> replayLiveLink()
            TvLiveRelinkPolicy.Step.Remint -> remint(spent)
            TvLiveRelinkPolicy.Step.OpenFresh, TvLiveRelinkPolicy.Step.Skip -> Unit
        }
    }

    private fun remint(spent: Boolean) {
        val reresolve = liveReresolve ?: return
        remintJob = scope.launch {
            val fresh = withTimeoutOrNull(TvLiveRelinkPolicy.REMINT_TIMEOUT_MS) {
                try {
                    reresolve()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    log.w { "live re-mint failed: ${error::class.simpleName}" }
                    null
                }
            }
            val step = TvLiveRelinkPolicy.afterRemint(spent, gotFresh = fresh != null)
            log.i { "live re-mint: $step" }
            when (step) {
                TvLiveRelinkPolicy.Step.OpenFresh -> fresh?.let(::openFreshLiveLink)
                TvLiveRelinkPolicy.Step.Replay -> replayLiveLink()
                TvLiveRelinkPolicy.Step.Remint, TvLiveRelinkPolicy.Step.Skip -> Unit
            }
        }
    }

    /** Plays [fresh] on the current engine; any later engine open (a lane switch) uses it too. */
    private fun openFreshLiveLink(fresh: TvResolvedSource) {
        if (closed) return
        activeSourceUrl = fresh.url
        activeSourceHeaders = fresh.headers
        val b = bridge ?: return
        freshOpenedAtMs = clock.elapsedNow().inWholeMilliseconds
        val headers = TvPlaybackHeaders.sanitize(fresh.headers)
        applyLinkReconnectPolicy(b)
        b.loadFileWithAudio(
            videoUrl = fresh.url,
            audioUrl = null,
            headersJson = headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            subtitlesJson = null,
            startOption = null,
        )
        restorePlayIntent(b)
        announceLiveSource(b)
    }

    /** Re-opens a stable (Xtream / M3U) live address on the current engine. */
    private fun replayLiveLink() {
        val b = bridge ?: return
        b.retry()
        restorePlayIntent(b)
        announceLiveSource(b)
    }

    /**
     * A live source was just handed to [b]: whatever the pause buffer held belonged to the previous one, and the
     * engine that plays this one is what decides whether a time shift exists (libmpv with the live-cache bridge).
     */
    private fun announceLiveSource(b: NuvioPlayerBridge) {
        val pause = livePause ?: return
        pause.sourceOpened(sessionVideoId, LiveLink(activeSourceUrl, activeSourceHeaders), spentLinkAfterOpen)
        pause.attach(b)
    }

    /**
     * mpv keeps its pause flag across a loadfile, and a live stream that ran dry ended paused
     * (keep-open), so a reconnect would sit on its first frame. Restore the viewer's intent.
     */
    private fun restorePlayIntent(b: NuvioPlayerBridge) {
        if (wantsToPlay) b.play() else b.pause()
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
        const val VOD_START_FAILED_MESSAGE =
            "This stream didn't start. It may be slow, offline, or its video output failed to start. Check your connection and retry, or pick another source."
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
