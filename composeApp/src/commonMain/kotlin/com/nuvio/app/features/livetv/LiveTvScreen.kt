package com.nuvio.app.features.livetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.CatchUpPlayback
import com.nuvio.app.features.iptv.TileEpgQueue
import com.nuvio.app.features.iptv.XtreamCatchUp
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.core.analytics.Breadcrumbs
import com.nuvio.app.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.app.core.analytics.LivePlaybackReconnector
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.EnterImmersivePlayerMode
import com.nuvio.app.features.player.ManagePlayerPictureInPicture
import com.nuvio.app.features.player.LIVE_FREEZE_SURFACE_DOCKED
import com.nuvio.app.features.player.LiveReplayLaunch
import com.nuvio.app.features.player.onLiveSnapshot
import com.nuvio.app.features.player.onLiveSnapshotStopped
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerStreamInfo
import com.nuvio.app.features.player.StreamInfoOverlay
import com.nuvio.app.features.player.rememberIsInPictureInPicture
import com.nuvio.app.features.player.rememberStreamInfoLines
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.compose_iptv_hub_epg_next
import nuvio.composeapp.generated.resources.compose_livetv_catchup_badge
import nuvio.composeapp.generated.resources.compose_livetv_catchup_no_recording
import nuvio.composeapp.generated.resources.compose_livetv_catchup_no_scrub
import nuvio.composeapp.generated.resources.compose_livetv_catchup_session_limit
import nuvio.composeapp.generated.resources.compose_livetv_catchup_start_over
import nuvio.composeapp.generated.resources.compose_livetv_catchup_watch_live
import nuvio.composeapp.generated.resources.compose_livetv_error_tap_retry
import nuvio.composeapp.generated.resources.compose_livetv_exit_fullscreen
import nuvio.composeapp.generated.resources.compose_livetv_fullscreen
import nuvio.composeapp.generated.resources.compose_livetv_live_badge
import nuvio.composeapp.generated.resources.compose_livetv_no_programme_info
import nuvio.composeapp.generated.resources.compose_livetv_play_pause
import org.jetbrains.compose.resources.stringResource

/** Share of the window height the docked player takes when the window is wider than it is tall. */
private const val DOCKED_PLAYER_HEIGHT_FRACTION = 0.58f

/**
 * Dedicated Live TV experience. Portrait shows a docked 16:9 player over an EPG timeline guide;
 * landscape (via rotation or the fullscreen button) fills the screen with the video. Channel taps
 * in the guide switch playback in place — no re-navigation.
 */
@Composable
fun LiveTvScreen(
    initialContentId: String,
    initialTitle: String,
    initialLogo: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set when the launch replays one programme (a Sports Centre replay): the screen begins the
     *  catch-up walk from these bounds instead of tuning the channel live. */
    initialReplay: LiveReplayLaunch? = null,
) {
    val colors = MaterialTheme.nuvio.colors

    var currentContentId by remember { mutableStateOf(initialContentId) }
    var currentTitle by remember { mutableStateOf(initialTitle) }
    var currentLogo by remember { mutableStateOf(initialLogo) }

    var source by remember { mutableStateOf<LiveChannelSource?>(null) }
    var resolveError by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
    var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
    // Live TV hosts its own player surface rather than going through PlayerScreen, so the
    // stream readout has to be wired up here too — this is the surface users actually ask
    // "what resolution is this channel?" about.
    var streamInfo by remember { mutableStateOf(PlayerStreamInfo()) }
    var showStreamInfo by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    // Live channels can wedge with no error at all, which no existing report path can see.
    // Survives channel switches (this composable stays alive), so the reporter is re-armed
    // per channel below rather than recreated.
    val freezeReporter = remember { LivePlaybackFreezeReporter() }
    val freezeReconnector = remember { LivePlaybackReconnector(freezeReporter) }
    // Keyed on the channel: fires on a channel switch and on leaving the screen, which are the
    // two ways a viewer escapes a frozen picture.
    DisposableEffect(currentContentId) {
        onDispose {
            freezeReporter.onLiveSnapshotStopped(snapshot)
            Breadcrumbs.playbackStopped()
        }
    }
    // Reset per channel: an in-place channel switch is a new playback start.
    val playbackStartRecorded = remember(currentContentId) { mutableStateOf(false) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<LiveGuideChannel>>(emptyList()) }
    // Personalization overlay: reload the guide when a hide/pin/reorder edit lands (native toggle or synced from the web).
    val overlaySnapshot by com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.uiState.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.ensureLoaded()
        com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.pullFromServer()
    }
    val programmes = remember { mutableStateMapOf<String, List<XtreamProgram>>() }
    val requestedProgrammes = remember { mutableSetOf<String>() }

    /**
     * The (channel, window) pairs whose stored history has been drawn.
     *
     * The two loaders finish in whatever order the network allows, so this is what stops a late
     * now-and-next result replacing the history already on screen — see [GuideWindowSource].
     */
    val historyShownWindows = remember { mutableSetOf<String>() }

    var nowMs by remember { mutableStateOf(TraktPlatformClock.nowEpochMs()) }
    // Where the guide's five-hour window sits. Starts live; travels back through the archive.
    var guideAnchorMs by remember { mutableStateOf(GuideTimeTravel.anchorForNow(nowMs)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val next = TraktPlatformClock.nowEpochMs()
            // The live window follows the clock; a window the viewer travelled to stays put.
            guideAnchorMs = GuideTimeTravel.onClockTick(guideAnchorMs, nowMs, next)
            nowMs = next
        }
    }

    // --- Catch-up state ------------------------------------------------------------------
    // One walk per screen: it single-flights per account and supersedes its own stale attempts,
    // so re-creating it per replay would throw away exactly the guards it exists for.
    val dialectWalk = remember { CatchUpDialectWalk(LiveTvData.winnerMemory()) }
    var catchUp by remember { mutableStateOf<CatchUpSession?>(null) }
    var sheetTarget by remember { mutableStateOf<ProgrammeSheetTarget?>(null) }
    var catchUpNotice by remember { mutableStateOf<CatchUpNotice?>(null) }
    val isCatchUp = catchUp != null

    /**
     * Is a replay on screen RIGHT NOW — for callbacks that outlive the composition that made them.
     *
     * [isCatchUp] is this pass's snapshot. A handler captured before the replay started (the
     * guide's channel click, the player's snapshot listener) still holds `false`, so asking it
     * mid-replay answers "this is live" and the live-only branch runs: the channel identity moves
     * while the archive session stays, and the viewer watches one channel's recording under
     * another channel's name. Reading the STATE instead is always current, because `remember`
     * hands back the same holder every composition.
     */
    fun replayOnScreen(): Boolean = catchUp != null

    // A launch that carries a replay must not tune the channel live first: on a
    // max_connections=1 account that live tune would spend the viewer's single connection on a
    // stream the replay immediately replaces. The live resolve stands down until the walk has
    // begun (or refused, in which case the launch falls back to a plain live tune).
    var pendingInitialReplay by remember { mutableStateOf(initialReplay != null) }
    LaunchedEffect(Unit) {
        val replay = initialReplay ?: return@LaunchedEffect
        val request = LiveTvData.catchUpRequest(
            initialContentId, replay.programmeStartMs, replay.programmeEndMs,
        )
        val session = beginLaunchReplay(
            walk = dialectWalk,
            request = request,
            contentId = initialContentId,
            programmeTitle = replay.programmeTitle,
            startMs = replay.programmeStartMs,
            endMs = replay.programmeEndMs,
        )
        if (session == null) {
            // No recording after all (the playlist vanished or can't serve catch-up): the same
            // notice a guide replay shows, over the live channel the viewer at least asked about.
            catchUpNotice = CatchUpNotice.NO_RECORDING
        } else {
            catchUp = session
        }
        pendingInitialReplay = false
    }

    // Resolve (or re-resolve on channel switch / retry) the playable source. A RETRY forces a
    // fresh Stalker create_link: with static-cmd playback the plain re-resolve would rebuild the
    // very URL that just failed (retryTick resets on channel switch, so a switch is never a mint).
    //
    // A replay owns its own resolution below — this effect must not overwrite the archive URL with
    // the live one, so it stands down whenever a catch-up session is active or arriving.
    LaunchedEffect(currentContentId, retryTick, isCatchUp, pendingInitialReplay) {
        if (isCatchUp || pendingInitialReplay) return@LaunchedEffect
        source = null
        resolveError = false
        playbackError = null
        val resolved = LiveTvData.resolveSource(
            currentContentId, currentTitle, currentLogo,
            forceMint = retryTick > 0,
        )
        if (resolved == null) resolveError = true else source = resolved
    }

    // Each attempt the dialect walk hands back becomes a real playback try — never an out-of-band
    // probe, which on a max_connections=1 account kicks the viewer's own live stream.
    LaunchedEffect(catchUp?.attempt?.token) {
        val session = catchUp ?: return@LaunchedEffect
        source = null
        resolveError = false
        playbackError = null
        source = LiveTvData.catchUpSource(session.contentId, session.attempt.url)
    }
    // Always re-resolve on retry: live links carry expiring tokens (Stalker create_link is
    // single-use/short-TTL), so controller.retry() would just replay the dead URL.
    // USER retry only: reset the panel breaker first (WP6) so the re-resolve is never met with a
    // fast-fail — the AUTOMATIC one-shot re-resolve below must not reset.
    val onRetry: () -> Unit = {
        LiveTvData.resetPanelGuard(currentContentId)
        retryTick++
    }

    // One AUTOMATIC fresh re-resolve per resolved URL: a mid-watch 401 (token expired, or the
    // portal session was rotated by another device on the same MAC) recovers invisibly; a second
    // failure on the freshly minted link means the channel/account is the problem — surface the
    // error pill instead of hammering the portal.
    //
    // Never during a replay: there the dialect walk owns failure, and a re-resolve would rebuild
    // the LIVE url for a viewer who asked for a recording.
    var autoRefreshBurntUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playbackError) {
        if (isCatchUp) return@LaunchedEffect
        val failedUrl = source?.url ?: return@LaunchedEffect
        if (playbackError != null && autoRefreshBurntUrl != failedUrl) {
            autoRefreshBurntUrl = failedUrl
            retryTick++
        }
    }

    /** Leaves the replay and returns the screen to the live channel it belongs to. */
    fun exitCatchUp() {
        catchUp = null
        source = null
        playbackError = null
        retryTick = 0
    }

    // Defence in depth for CatchUpPlayback.sessionSurvivesChannel: whatever moved the channel — a
    // guide click, the sheet's "Watch live", a caller added later — a session that no longer
    // belongs to the channel on screen is dropped HERE. Both replay entry points set the channel
    // before the session, so a legitimate replay never trips this.
    LaunchedEffect(currentContentId, catchUp) {
        val session = catchUp ?: return@LaunchedEffect
        if (!CatchUpPlayback.sessionSurvivesChannel(session.contentId, currentContentId)) exitCatchUp()
    }

    /**
     * Reports the outcome of the current attempt to the walk and follows its answer.
     *
     * TRANSPORT advances the ladder, DECODE stops it, exhaustion ends it with nothing pinned — a
     * dead stream_id or a panel briefly down must not poison the learned winner.
     */
    fun onCatchUpFailure(message: String?) {
        val session = catchUp ?: return
        if (session.proven) return
        if (CatchUpPlayback.isSessionLimit(message)) {
            catchUpNotice = CatchUpNotice.SESSION_LIMIT
            exitCatchUp()
            return
        }
        when (val step = dialectWalk.onFailure(session.attempt.token, CatchUpPlayback.failureKind(message))) {
            is CatchUpDialectWalk.Step.Next -> catchUp = session.copy(attempt = step.attempt)
            CatchUpDialectWalk.Step.Stale -> Unit   // a superseded walk's late answer — ignore
            else -> {
                catchUpNotice = CatchUpNotice.NO_RECORDING
                exitCatchUp()
            }
        }
    }

    /** Opens a replay: the walk picks the first URL to try, and the sheet (if any) closes. */
    fun startCatchUp(channel: LiveGuideChannel, programme: XtreamProgram) {
        sheetTarget = null
        scope.launch {
            val request = LiveTvData.catchUpRequest(channel.contentId, programme.startMs, programme.endMs)
            val session = beginLaunchReplay(
                walk = dialectWalk,
                request = request,
                contentId = channel.contentId,
                programmeTitle = programme.title,
                startMs = programme.startMs,
                endMs = programme.endMs,
            )
            if (session == null) {
                catchUpNotice = CatchUpNotice.NO_RECORDING
                return@launch
            }
            currentContentId = channel.contentId
            currentTitle = channel.name
            currentLogo = channel.logo
            catchUpNotice = null
            catchUp = session
        }
    }

    /** The OK rule, split by state — the artifact's decision 2. */
    fun onProgrammeAction(
        channel: LiveGuideChannel,
        programme: XtreamProgram,
        action: XtreamCatchUp.ProgrammeAction,
    ) {
        when (action) {
            // Finished and replayable: one press plays it, TiviMate-style. There is only one
            // destination, so a sheet would be a dialog asking "yes?".
            XtreamCatchUp.ProgrammeAction.REPLAY -> startCatchUp(channel, programme)
            // Airing on an archive channel: "restart this" and "join it live" are both reasonable
            // and neither is obviously default, so this is the ONE state that gets two buttons.
            XtreamCatchUp.ProgrammeAction.START_OVER -> sheetTarget = ProgrammeSheetTarget(channel, programme)
            else -> Unit
        }
    }

    // Guide channel column (once, from the launch channel's account).
    LaunchedEffect(initialContentId, overlaySnapshot) {
        channels = LiveTvData.guideChannels(initialContentId)
    }

    // Load programmes for any channel that asks (lazy, cached, de-duped).
    //
    // De-duped per (channel, window): the old key was the channel alone, which was right when the
    // guide only ever showed now-forward, and would mean nothing ever reloads once it can travel.
    val loadWindow: suspend (String) -> Unit = { contentId ->
        val fromMs = guideAnchorMs
        val toMs = GuideTimeTravel.windowEndMs(guideAnchorMs)
        val windowKey = "$contentId@$guideAnchorMs"
        val history = LiveTvData.historyProgrammes(contentId, fromMs, toMs)
        when (
            GuideWindowSource.forWindow(
                hasStoredHistory = history.isNotEmpty(),
                historyAlreadyShown = windowKey in historyShownWindows,
                travelling = GuideTimeTravel.isTravelling(guideAnchorMs, nowMs),
            )
        ) {
            GuideWindowSource.Source.HISTORY -> {
                historyShownWindows.add(windowKey)
                programmes[contentId] = history
            }
            // The panel's now-and-next: the live window's first paint, and what every non-Xtream
            // playlist has. Refused once history has landed for this window — see GuideWindowSource.
            GuideWindowSource.Source.NOW_NEXT -> {
                val list = LiveTvData.programmes(contentId)
                if (list.isNotEmpty() && windowKey !in historyShownWindows) programmes[contentId] = list
            }
            GuideWindowSource.Source.NONE -> programmes.remove(contentId)
        }
    }
    /**
     * Fetch now/next for a SETTLED window of channels ([GuideEpgPrefetchPolicy]).
     *
     * Two guards, because neither alone was enough:
     *  - `requestedProgrammes` stamps once per (channel, window), so a settle overlapping the
     *    previous one costs nothing.
     *  - [TileEpgQueue] is the same bounded, newest-first backlog the hub tiles run on, so even a
     *    settle storm can never put more than two requests on the panel at once. An evicted entry
     *    releases its stamp, so a revisit fetches it after all.
     *
     * [contentIds] arrives in resolve order and the queue is newest-first, so it is enqueued
     * REVERSED — the row that should resolve soonest has to end up at the FRONT of the backlog.
     */
    val onNeedProgrammes: (List<String>) -> Unit = { contentIds ->
        val anchorMs = guideAnchorMs
        for (contentId in contentIds.asReversed()) {
            val windowKey = "$contentId@$anchorMs"
            if (requestedProgrammes.add(windowKey)) {
                TileEpgQueue.enqueue(
                    key = "guide:$windowKey",
                    onEvicted = { requestedProgrammes.remove(windowKey) },
                ) {
                    // The queue owns ADMISSION only; the work itself runs on the composition
                    // scope so the guide's bookkeeping (`historyShownWindows`, `programmes`)
                    // stays confined to one dispatcher — Kotlin/Native does not forgive a racy
                    // plain set. Joining keeps the queue's two-worker ceiling meaningful, and a
                    // launch on a cancelled scope joins instantly, so leaving never wedges it.
                    scope.launch { loadWindow(contentId) }.join()
                    // Did any rung answer for this row? Drives the queue's per-channel cooldown,
                    // so a channel the panel has no guide for stops being re-asked every settle.
                    programmes[contentId]?.isNotEmpty() == true
                }
            }
        }
    }
    // The FOCUSED channel — and only it — gets its full history pulled. A page of rows each
    // fetching its own table is exactly how a guide turns 2 MB into 40 MB on a 1 GB box.
    //
    // Keyed on the ANCHOR too, so travelling re-attempts a history fetch that failed. A fetch is
    // swallowed on failure and never stamped, so the channel would otherwise show an empty past
    // for the life of the screen — one transient panel error (or an open circuit breaker at the
    // moment of entry) and the feature looks unimplemented. A fetch that DID land is stamped, so
    // this costs a DB read per travel step and no network at all (CatchUpEpgPolicy.shouldFetch).
    LaunchedEffect(currentContentId, guideAnchorMs) {
        onNeedProgrammes(listOf(currentContentId))
        LiveTvData.ensureHistory(currentContentId)
        requestedProgrammes.remove("$currentContentId@$guideAnchorMs")
        loadWindow(currentContentId)
    }

    fun switchTo(channel: LiveGuideChannel) {
        // A replay is never zapped away by the live path: the tap is deliberate, so it lands, but
        // the archive session is torn down first rather than leaving a replay URL wearing another
        // channel's identity. See CatchUpPlayback.allowsChannelChange.
        if (!CatchUpPlayback.allowsChannelChange(replayOnScreen())) exitCatchUp()
        if (channel.contentId == currentContentId) return
        currentContentId = channel.contentId
        currentTitle = channel.name
        currentLogo = channel.logo
        retryTick = 0   // a fresh channel is a first tune, not a retry — its resolve must not mint
    }

    // ---- Orientation / fullscreen state ----
    val physicalLandscape by rememberPhysicalLandscape()
    var manualOrientation by remember { mutableStateOf<Boolean?>(null) } // true=landscape,false=portrait,null=follow
    // Exiting fullscreen only needs a temporary portrait pin: once the phone physically catches
    // up, hand control back to the sensor so rotate-to-fullscreen keeps working. Entering
    // fullscreen is intentionally different. A tap on Fullscreen is explicit user intent, so keep
    // landscape locked until Back/Exit instead of letting a small device movement silently return
    // the viewer to the docked guide.
    LaunchedEffect(physicalLandscape, manualOrientation) {
        val manual = manualOrientation
        val physical = physicalLandscape
        if (manual == false && physical == false) {
            manualOrientation = null
        }
    }
    val orientationMode = when (manualOrientation) {
        true -> LiveOrientationMode.ForceLandscape
        false -> LiveOrientationMode.ForcePortrait
        null -> LiveOrientationMode.Sensor
    }
    ApplyLiveOrientation(orientationMode)

    val nowNext = remember(programmes[currentContentId], nowMs) {
        nowNextOf(programmes[currentContentId], nowMs)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(colors.surface),
    ) {
        // Rotation drives fullscreen where the window follows the device. Desktop windows are
        // landscape at every size, so there fullscreen is whatever the user last asked for —
        // otherwise the guide below would never get a chance to render.
        var toggledFullscreen by remember { mutableStateOf(false) }
        val wideWindow = maxWidth > maxHeight
        val fullscreen =
            if (LiveTvFullscreenFollowsWindowAspect) wideWindow else toggledFullscreen
        val dockedPlayerHeight = maxHeight * DOCKED_PLAYER_HEIGHT_FRACTION
        val hasError = resolveError || playbackError != null

        fun setFullscreen(enabled: Boolean) {
            if (LiveTvFullscreenFollowsWindowAspect) manualOrientation = enabled
            else toggledFullscreen = enabled
        }

        // Back in fullscreen exits fullscreen instead of leaving the screen.
        PlatformBackHandler(enabled = fullscreen) { setFullscreen(false) }
        // Immersive mode hides the system bars, so on a phone it belongs to fullscreen alone. On
        // desktop it is only a display-sleep inhibitor and docked is the normal way to watch, so
        // it stays on there whenever the screen is up — otherwise the monitor sleeps mid-channel.
        if (fullscreen || !LiveTvFullscreenFollowsWindowAspect) {
            EnterImmersivePlayerMode(keepScreenAwake = snapshot.isPlaying || snapshot.isLoading)
        }

        // Live TV hosts its own player surface rather than going through PlayerScreenContent, so it
        // never inherited PiP — pressing home on a live channel just backgrounded the app while
        // audio kept playing. Gated on hasVideoTrack because an IPTV lineup is full of radio
        // stations and a PiP window for one of those is a black rectangle.
        //
        // videoWidth/Height are legitimately 0 on the mpv path (reading them would touch mpv from
        // Main); buildAspectRatio treats that as "no hint" and lets the system pick, which is the
        // right degradation rather than a wrong ratio.
        val inPictureInPicture = rememberIsInPictureInPicture()
        ManagePlayerPictureInPicture(
            enabled = PlayerSettingsRepository.uiState.value.pictureInPictureEnabled,
            isPlaying = snapshot.isPlaying && snapshot.hasVideoTrack,
            videoSize = IntSize(snapshot.videoWidth, snapshot.videoHeight),
        )

        // The centred play/pause button auto-hides while playing in BOTH layouts; the docked edge
        // chrome stays. See LiveTvOverlayPolicy for why they are treated separately.
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(fullscreen) { controlsVisible = true }
        val overlay = LiveTvOverlayPolicy.evaluate(
            LiveTvOverlayPolicy.Input(
                fullscreen = fullscreen,
                isPlaying = snapshot.isPlaying,
                controlsShown = controlsVisible,
            )
        )
        LaunchedEffect(overlay.autoHideScheduled) {
            if (overlay.autoHideScheduled) {
                delay(LiveTvOverlayPolicy.AUTO_HIDE_DELAY_MS)
                controlsVisible = false
            }
        }
        val showPlayPause = !hasError && !(snapshot.isLoading && !snapshot.isPlaying)

        Column(
            modifier = Modifier.fillMaxSize().then(
                if (fullscreen) Modifier else Modifier.statusBarsPadding(),
            ),
        ) {
            // The player box keeps a STABLE position (always the Column's first child); only its size
            // modifier changes between docked and fullscreen (fill). The MPV SurfaceView is
            // therefore never detached/reattached on rotation, so the stream doesn't reload/rebuffer.
            //
            // Docked sizing differs by shape: a 16:9 box is the right dock over a portrait phone,
            // but the full width of a landscape desktop window would push the guide off-screen, so
            // there the dock is capped to a fraction of the height and the video letterboxes.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            fullscreen -> Modifier.weight(1f)
                            wideWindow -> Modifier.height(dockedPlayerHeight)
                            else -> Modifier.aspectRatio(16f / 9f)
                        },
                    )
                    .background(Color.Black)
                    .then(
                        if (overlay.tapTogglesControls) {
                            Modifier.clickable { controlsVisible = !controlsVisible }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                LivePlayerSurface(
                    source = source,
                    isCatchUpPlayback = isCatchUp,
                    onControllerReady = { controller = it },
                    onSnapshot = {
                        snapshot = it
                        val session = catchUp
                        if (session != null && !session.proven) {
                            when {
                                // The attempt played: pin the winner and stop walking.
                                it.isPlaying || it.positionMs > 0L -> {
                                    dialectWalk.onSuccess(session.attempt.token)
                                    catchUp = session.copy(proven = true)
                                }
                                // On Android a live/catch-up URL that never opens reports NO error
                                // at all — libmpv surfaces it as an immediate end-of-file. Without
                                // this the walk would sit on its first dialect forever.
                                it.isEnded -> onCatchUpFailure(playbackError)
                            }
                        }
                        if (!playbackStartRecorded.value && (it.positionMs > 0L || it.isPlaying)) {
                            playbackStartRecorded.value = true
                            Breadcrumbs.playbackStarted(
                                kind = "live",
                                engine = controller?.getStreamInfo()?.playerEngine?.lowercase() ?: "unknown",
                                surface = LIVE_FREEZE_SURFACE_DOCKED,
                                container = LivePlaybackFreezeReporter.streamContainerOf(source?.url),
                                nowMs = TraktPlatformClock.nowEpochMs(),
                            )
                        }
                        // The watchdog reports a fault when a live stream ENDS, because a live
                        // channel has no end. A recording ending is the recording finishing, so
                        // armed against a replay it would report a freeze on every successful
                        // catch-up and spend a provider connection re-minting a working URL.
                        if (CatchUpPlayback.armsFreezeWatchdog(replayOnScreen())) {
                            freezeReporter.onLiveSnapshot(
                                snapshot = it,
                                engine = { controller?.getStreamInfo()?.playerEngine },
                                streamUrl = source?.url,
                                contentId = currentContentId,
                                surface = LIVE_FREEZE_SURFACE_DOCKED,
                                reconnector = freezeReconnector,
                                // Re-resolve rather than controller.retry(): live links carry
                                // expiring tokens, so replaying the same URL can reconnect to a
                                // link the provider has already invalidated.
                                reconnect = onRetry,
                                // Video-only freeze: the stream is still delivering audio, so reset
                                // the decoder before spending a live link on a re-resolve.
                                resetVideo = { controller?.resetVideoPipeline() == true },
                            )
                        }
                    },
                    onError = { message ->
                        playbackError = message
                        if (message != null) onCatchUpFailure(message)
                    },
                )

                // Re-read per channel: switching channels in place keeps this composable
                // alive, so keying only on isPlaying would show the first channel's facts.
                LaunchedEffect(snapshot.isPlaying, source) {
                    if (!snapshot.isPlaying) return@LaunchedEffect
                    if (!PlayerSettingsRepository.uiState.value.showStreamInfo) return@LaunchedEffect
                    // mpv's video-bitrate is a throttled rolling estimate measured over
                    // keyframe intervals — at first frame it is still 0. Let it settle so
                    // the bitrate row isn't dropped on every live channel.
                    delay(STREAM_INFO_SETTLE_MS)
                    val info = controller?.getStreamInfo() ?: return@LaunchedEffect
                    if (!info.hasAnyValue) return@LaunchedEffect
                    streamInfo = info
                    showStreamInfo = true
                }

                StreamInfoOverlay(
                    lines = rememberStreamInfoLines(streamInfo),
                    // Never in PiP: the window is a few hundred pixels wide, so the resolution/codec
                    // readout covers the picture it is describing.
                    isVisible = showStreamInfo && !inPictureInPicture,
                    onAnimationComplete = { showStreamInfo = false },
                    modifier = Modifier.align(Alignment.TopEnd),
                    // Sits below the fullscreen button and the LIVE badge, which own the
                    // top edge of the dock.
                    contentPadding = PaddingValues(end = 16.dp, top = 76.dp),
                )

                // Loading / error indicators (both orientations).
                when {
                    hasError -> Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorPill(colors.danger, onRetry) }
                    snapshot.isLoading && !snapshot.isPlaying ->
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                }

                if (fullscreen) {
                    FullscreenControls(
                        visible = overlay.chromeVisible,
                        title = catchUp?.programmeTitle ?: currentTitle,
                        isPlaying = snapshot.isPlaying,
                        showPlayPause = showPlayPause,
                        danger = colors.danger,
                        accent = colors.accent,
                        isCatchUp = isCatchUp,
                        onPlayPause = { if (snapshot.isPlaying) controller?.pause() else controller?.play() },
                        onExitFullscreen = { setFullscreen(false) },
                        onBack = onBack,
                    )
                } else {
                    DockedPlayerOverlay(
                        isPlaying = snapshot.isPlaying,
                        showPlayPause = showPlayPause && overlay.centreControlVisible,
                        danger = colors.danger,
                        accent = colors.accent,
                        isCatchUp = isCatchUp,
                        onPlayPause = { if (snapshot.isPlaying) controller?.pause() else controller?.play() },
                        onEnterFullscreen = { setFullscreen(true) },
                        onBack = if (isCatchUp) ::exitCatchUp else onBack,
                    )
                }

                // The replay's own transport, docked below the chrome. A live channel has no
                // finite timeline and gets nothing here — which is what this screen has always
                // shown.
                catchUp?.let { session ->
                    CatchUpScrubBar(
                        session = session,
                        snapshot = snapshot,
                        streamUrl = source?.url,
                        nowMs = nowMs,
                        colors = colors,
                        onSeek = { controller?.seekTo(it) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            if (!fullscreen) {
                catchUpNotice?.let { notice ->
                    CatchUpNoticeBar(notice = notice, colors = colors, onDismiss = { catchUpNotice = null })
                }
                NowBar(logo = currentLogo, title = currentTitle, nowNext = nowNext, nowMs = nowMs, colors = colors)
                LiveGuideGrid(
                    channels = channels,
                    currentContentId = currentContentId,
                    nowMs = nowMs,
                    windowStartMs = guideAnchorMs,
                    // Travel floor = the deepest window any visible channel declares; each cell
                    // judges replayability against its own channel's window. 0 (panel silent)
                    // stays permissive: `tv_archive` is the real flag.
                    catchUpDays = channels.maxOfOrNull { it.catchUpDays } ?: 0,
                    programmesOf = { programmes[it] },
                    onNeedProgrammes = onNeedProgrammes,
                    onSelectChannel = ::switchTo,
                    onLongPressChannel = { ch ->
                        val acc = com.nuvio.app.features.iptv.XtreamItemRegistry.parseId(ch.contentId)?.accountId
                        com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.toggleChannelHidden(ch.entityId, acc)
                    },
                    onProgrammeAction = ::onProgrammeAction,
                    onTravel = { guideAnchorMs = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }

        sheetTarget?.let { target ->
            ProgrammeSheet(
                target = target,
                nowMs = nowMs,
                onDismiss = { sheetTarget = null },
                onStartOver = { startCatchUp(target.channel, target.programme) },
                onWatchLive = {
                    sheetTarget = null
                    switchTo(target.channel)
                },
            )
        }
    }
}

/** now + next titles for the current channel, for the docked now-bar. */
private data class NowNext(val now: XtreamProgram?, val next: XtreamProgram?)

private fun nowNextOf(list: List<XtreamProgram>?, nowMs: Long): NowNext {
    if (list.isNullOrEmpty()) return NowNext(null, null)
    val sorted = list.sortedBy { it.startMs }
    val nowIdx = sorted.indexOfFirst { nowMs in it.startMs until it.endMs }
        .takeIf { it >= 0 }
        ?: sorted.indexOfFirst { it.startMs > nowMs }.takeIf { it >= 0 }?.let { it - 1 }
        ?: 0
    return NowNext(sorted.getOrNull(nowIdx), sorted.getOrNull(nowIdx + 1))
}

@Composable
private fun NowBar(
    logo: String?,
    title: String,
    nowNext: NowNext,
    nowMs: Long,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                .background(colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nowNext.now?.title ?: title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val nextLabel = nowNext.next?.let { stringResource(Res.string.compose_iptv_hub_epg_next, it.title) }
            val timeLabel = nowNext.now?.let { "${liveClockLabel(it.startMs)} – ${liveClockLabel(it.endMs)}" }
            val subtitle = listOfNotNull(timeLabel, nextLabel).joinToString("   ")
                .ifBlank { stringResource(Res.string.compose_livetv_no_programme_info) }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Portrait overlay: back + LIVE (or CATCH-UP) + fullscreen button on top, play/pause centered. */
@Composable
private fun DockedPlayerOverlay(
    isPlaying: Boolean,
    showPlayPause: Boolean,
    danger: Color,
    accent: Color,
    isCatchUp: Boolean,
    onPlayPause: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back), onBack)
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            if (isCatchUp) CatchUpBadge(accent) else LiveBadge(danger)
            Spacer(Modifier.weight(1f))
            OverlayIconButton(Icons.Filled.Fullscreen, stringResource(Res.string.compose_livetv_fullscreen), onEnterFullscreen)
        }
        if (showPlayPause) {
            OverlayIconButton(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(Res.string.compose_livetv_play_pause),
                onPlayPause,
                big = true,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Landscape overlay (fades in/out): back + LIVE + title on top, play/pause centered, minimise bottom-right. */
@Composable
private fun FullscreenControls(
    visible: Boolean,
    title: String,
    isPlaying: Boolean,
    showPlayPause: Boolean,
    danger: Color,
    accent: Color,
    isCatchUp: Boolean,
    onPlayPause: () -> Unit,
    onExitFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back), onBack)
            Spacer(Modifier.width(NuvioTokens.Space.s12))
            if (isCatchUp) CatchUpBadge(accent) else LiveBadge(danger)
            Spacer(Modifier.width(NuvioTokens.Space.s12))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showPlayPause) {
            OverlayIconButton(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(Res.string.compose_livetv_play_pause),
                onPlayPause,
                big = true,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(NuvioTokens.Space.s16),
        ) {
            OverlayIconButton(Icons.Filled.FullscreenExit, stringResource(Res.string.compose_livetv_exit_fullscreen), onExitFullscreen)
        }
    }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun LivePlayerSurface(
    source: LiveChannelSource?,
    isCatchUpPlayback: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val current = source ?: return
    // Key by url so a channel switch cleanly re-initialises the engine.
    androidx.compose.runtime.key(current.url) {
        PlatformPlayerSurface(
            sourceUrl = current.url,
            sourceHeaders = current.headers,
            // A replay stays streamType "live": the archive arrives down the same pipe and the
            // Android engine selection depends on it. What makes it different is the flag beside
            // it, which every live-only behaviour reads.
            streamType = "live",
            isCatchUpPlayback = isCatchUpPlayback,
            playbackSurface = com.nuvio.app.features.player.LIVE_FREEZE_SURFACE_DOCKED,
            modifier = Modifier.fillMaxSize(),
            playWhenReady = true,
            resizeMode = PlayerResizeMode.Fit,
            useNativeController = false,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    }
}

@Composable
private fun LiveBadge(danger: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(danger)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text(
            text = stringResource(Res.string.compose_livetv_live_badge),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ErrorPill(danger: Color, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onRetry)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = danger)
        Text(
            text = stringResource(Res.string.compose_livetv_error_tap_retry),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    big: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(if (big) 64.dp else 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(if (big) 36.dp else 22.dp),
        )
    }
}

/** How long to let mpv measure a bitrate before reading the stream info. */
private const val STREAM_INFO_SETTLE_MS = 2500L

// ---------------------------------------------------------------------------------------------
// Catch-up
// ---------------------------------------------------------------------------------------------

/**
 * One replay in progress.
 *
 * [attempt] is the dialect walk's current candidate; a new token means a new URL to try, and it is
 * what the effect keys on. [proven] latches on the first frame so a walk that has already won
 * never re-reports success, and so a stream that stops LATER reads as the recording finishing
 * rather than as this dialect failing.
 */
internal data class CatchUpSession(
    val contentId: String,
    val programmeTitle: String,
    val startMs: Long,
    val endMs: Long,
    val attempt: CatchUpDialectWalk.Attempt,
    val proven: Boolean = false,
)

/**
 * Begins the catch-up walk for one replay ask and answers the session to hold, or null when
 * nothing can be replayed (no request could be built, or the walk had nothing to offer).
 *
 * The one begin path for BOTH ways into a replay — a guide cell tapped on this screen and a
 * Sports Centre replay arriving with the launch — so every replay carries the same session shape:
 * `isCatchUpPlayback` on the player surface is exactly "this returned non-null", the gates read
 * it, and the walk it started advances on transport failure.
 */
internal fun beginLaunchReplay(
    walk: CatchUpDialectWalk,
    request: CatchUpDialectWalk.Request?,
    contentId: String,
    programmeTitle: String,
    startMs: Long,
    endMs: Long,
): CatchUpSession? {
    val step = request?.let(walk::begin)
    if (step !is CatchUpDialectWalk.Step.Next) return null
    return CatchUpSession(
        contentId = contentId,
        programmeTitle = programmeTitle,
        startMs = startMs,
        endMs = endMs,
        attempt = step.attempt,
    )
}

/** The programme whose sheet is open — only ever a START_OVER, the one state with two answers. */
private data class ProgrammeSheetTarget(
    val channel: LiveGuideChannel,
    val programme: XtreamProgram,
)

/**
 * Why a replay didn't happen. Both are things the VIEWER can act on, which is the whole reason
 * they aren't a generic playback error: "catch-up is broken" sends people to Discord blaming the
 * app for a provider's missing recording or their own subscription's connection cap.
 */
private enum class CatchUpNotice { NO_RECORDING, SESSION_LIMIT }

@Composable
private fun CatchUpBadge(accent: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(accent)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Icon(
            imageVector = Icons.Filled.Replay,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = stringResource(Res.string.compose_livetv_catchup_badge),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The replay transport.
 *
 * Whether the viewer gets a draggable handle is the PROVIDER's call, not ours: a panel answering
 * `.m3u8` sends a playlist with every segment's duration and it scrubs; one answering `.ts` sends
 * a progressive stream with no duration and it does not. Same programme, same app. So the
 * unseekable case draws a flat bar and says why, rather than a handle that ignores drags — an
 * absent control reads as a provider fact, a dead one reads as a broken app.
 */
@Composable
private fun CatchUpScrubBar(
    session: CatchUpSession,
    snapshot: PlayerPlaybackSnapshot,
    streamUrl: String?,
    nowMs: Long,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekable = CatchUpPlayback.isSeekable(streamUrl)
    // The programme's own length is the timeline, not the engine's reported duration: a timeshift
    // stream frequently reports nothing, or the whole remaining archive.
    val durationMs = (session.endMs - session.startMs).coerceAtLeast(1L)
    val maxSeekMs = CatchUpPlayback.maxSeekPositionMs(session.startMs, session.endMs, nowMs)
    var scrubbingMs by remember(session.attempt.token) { mutableStateOf<Long?>(null) }
    val positionMs = scrubbingMs ?: snapshot.positionMs.coerceIn(0L, durationMs)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
    ) {
        if (seekable) {
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { scrubbingMs = it.toLong().coerceIn(0L, maxSeekMs) },
                onValueChangeFinished = {
                    scrubbingMs?.let(onSeek)
                    scrubbingMs = null
                },
                // The right edge stops short of live: the segments either side of it have not been
                // written yet, so a seek there asks the panel for something that does not exist.
                valueRange = 0f..durationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
                modifier = Modifier.fillMaxWidth().height(20.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(Color.White.copy(alpha = 0.16f)),
            )
            Spacer(Modifier.height(NuvioTokens.Space.s4))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = liveClockLabel(session.startMs + positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (seekable) {
                    liveClockLabel(session.endMs)
                } else {
                    stringResource(Res.string.compose_livetv_catchup_no_scrub)
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Says something TRUE when a replay doesn't happen, and gets out of the way on a tap. */
@Composable
private fun CatchUpNoticeBar(
    notice: CatchUpNotice,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.14f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (notice) {
                CatchUpNotice.NO_RECORDING -> stringResource(Res.string.compose_livetv_catchup_no_recording)
                CatchUpNotice.SESSION_LIMIT -> stringResource(Res.string.compose_livetv_catchup_session_limit)
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
        )
    }
}

/**
 * The programme sheet, shown for exactly one state: a programme airing NOW on a channel that keeps
 * an archive, where "restart this" and "join it live" are both reasonable and neither is the
 * obvious default. Every other state has one destination and plays on a single press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgrammeSheet(
    target: ProgrammeSheetTarget,
    nowMs: Long,
    onDismiss: () -> Unit,
    onStartOver: () -> Unit,
    onWatchLive: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The guide's rows carry a description truncated in SQL; the sheet is the ONE place the whole
    // synopsis is read, and it is read lazily so a feed's 4 KB blurbs never sit in the guide page.
    var fullDescription by remember(target.programme.startMs) { mutableStateOf(target.programme.description) }
    LaunchedEffect(target.channel.contentId, target.programme.startMs) {
        LiveTvData.programmeDescription(target.channel.contentId, target.programme.startMs)
            ?.takeIf { it.isNotBlank() }
            ?.let { fullDescription = it }
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s20)
                .padding(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Text(
                text = buildString {
                    append(target.channel.name.uppercase())
                    append(" · ")
                    append(liveClockLabel(target.programme.startMs))
                    append("–")
                    append(liveClockLabel(target.programme.endMs))
                    append(" · ")
                    append(XtreamCatchUp.durationMinutes(target.programme.startMs, target.programme.endMs))
                    append(" MIN")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = target.programme.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
            if (fullDescription.isNotBlank()) {
                Text(
                    text = fullDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(NuvioTokens.Space.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                SheetActionButton(
                    icon = Icons.Filled.Replay,
                    label = stringResource(Res.string.compose_livetv_catchup_start_over),
                    background = colors.accent,
                    contentColor = colors.onAccent,
                    onClick = onStartOver,
                    modifier = Modifier.weight(1f),
                )
                SheetActionButton(
                    icon = Icons.Filled.PlayArrow,
                    label = stringResource(Res.string.compose_livetv_catchup_watch_live),
                    background = Color.Transparent,
                    contentColor = colors.textPrimary,
                    border = colors.borderSubtle,
                    onClick = onWatchLive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SheetActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: Color? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.button))
            .then(
                if (border != null) {
                    Modifier.border(NuvioTokens.Border.thin, border, RoundedCornerShape(NuvioTokens.Radius.button))
                } else {
                    Modifier
                },
            )
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(NuvioTokens.Space.s6))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
