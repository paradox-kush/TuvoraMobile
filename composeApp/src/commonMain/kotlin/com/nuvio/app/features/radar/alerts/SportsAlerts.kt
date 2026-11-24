@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.radar.alerts

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.core.contracts.PlaybackContextAccess
import com.nuvio.app.core.contracts.PlayingChannelsAccess
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.sync.RealtimeSyncConfig
import com.nuvio.app.features.common.lifecycle.Disposable
import com.nuvio.app.features.common.lifecycle.EffectScope
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.radar.RadarRepository
import com.nuvio.app.features.radar.RadarTime
import com.nuvio.app.features.radar.RadarUiState
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.radar.fixtureByKey
import com.nuvio.app.features.radar.toGameRef
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NoGameWatch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What the "Sports alerts" settings section draws. */
data class SportsAlertSettingsView(
    val settings: SportsAlertSettings = SportsAlertSettings(),
    val signedIn: Boolean = false,
    /** Followed clubs in follow order: id to name. */
    val teams: List<Pair<String, String>> = emptyList(),
    /** Followed leagues in follow order: id to name. */
    val leagues: List<Pair<String, String>> = emptyList(),
)

/**
 * The alerts runtime, one per process: the [controller] (state holder), the foreground-bound topic subscription and the
 * settings actions every screen calls (phone, iPad, Apple TV through its facade). Composables call these and collect the
 * flows; they never touch the repository, the channel or the network themselves.
 *
 * Mobile: [installWatch] at process init (FeatureWiring), the banner host starts [startForApp] while it is composed in
 * the app-runtime owner. Apple TV: TvAppLifecycle calls [startForegroundSession] / [stopForegroundSession] on its
 * Foreground / Background branches.
 */
object SportsAlerts {
    private val log = Logger.withTag("SportsAlerts")
    private val crashGuard = CoroutineExceptionHandler { _, e -> log.e(e) { "sports alerts failed" } }

    /** Realtime work. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    /** The controller's confinement: payloads, timers and user actions all land here, one thread. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashGuard)

    private var watch: GameWatchPort = NoGameWatch
    private var dismissAfterMs: Long = SportsAlertController.PHONE_DISMISS_MS

    /** Process init: the game screen's Watch port (and the TV dismiss time on Apple TV). Before the first [controller] read. */
    fun installWatch(port: GameWatchPort, dismissAfter: Long = SportsAlertController.PHONE_DISMISS_MS) {
        watch = port
        dismissAfterMs = dismissAfter
    }

    private val actions by lazy {
        SportsAlertActions(watch, ioScope) { eventId -> RadarRepository.uiState.value.fixtureByKey(eventId)?.toGameRef() }
    }

    internal val controller: SportsAlertController by lazy {
        SportsAlertController(Inputs, uiScope, actions, dismissAfterMs)
    }

    /** The banner to draw, or null. */
    val banner: StateFlow<AlertBanner?> get() = controller.banner

    // --- lifecycle -----------------------------------------------------------------------------------------------

    /**
     * Starts listening while the app is in the foreground (phone: the process lifecycle). Returns the session handle;
     * disposing it leaves every topic. At most one live session per caller.
     */
    fun startForApp(): Disposable = startSession(appForeground())

    private var tvSession: Disposable? = null

    /** Apple TV: TvAppLifecycle's Foreground branch. Idempotent. */
    fun startForegroundSession() {
        if (tvSession != null) return
        tvSession = startSession(flowOf(true))
    }

    /** Apple TV: TvAppLifecycle's Background branch (and its session teardown). Leaves every topic. */
    fun stopForegroundSession() {
        tvSession?.dispose()
        tvSession = null
        uiScope.launch { controller.clearHeld() }
    }

    private fun startSession(foreground: Flow<Boolean>): Disposable {
        val effects = EffectScope(onRevertFailure = { e -> log.w(e) { "alerts teardown" } })
        // An account or profile change resets what is held, so nothing of the old profile surfaces under the new one.
        val identity = ioScope.launch {
            combine(
                AuthRepository.state.map { (it as? AuthState.Authenticated)?.userId },
                ProfileRepository.state.map { ProfileRepository.activeProfileId },
            ) { user, profile -> user to profile }
                .distinctUntilChanged().drop(1).collect { uiScope.launch { controller.reset() } }
        }
        effects.onRevert { identity.cancel() }
        // Background: nothing held survives (it would surface as old news on return).
        val background = ioScope.launch {
            foreground.distinctUntilChanged().collect { fg -> if (!fg) uiScope.launch { controller.clearHeld() } }
        }
        effects.onRevert { background.cancel() }
        SportsAlertSubscription(
            client = { SupabaseProvider.client },
            desiredTopics = desiredTopics(foreground),
            onAlert = { payload -> uiScope.launch { controller.onPayload(payload) } },
            scope = ioScope,
        ).start(effects)
        return effects
    }

    /** The topics to hold right now: empty in the background, signed out, with Realtime off or alerts off. */
    private fun desiredTopics(foreground: Flow<Boolean>): Flow<Set<String>> =
        combine(foreground, RadarRepository.uiState, AuthRepository.state) { fg, radar, auth ->
            log.w { "DIAG topics input fg=$fg realtime=${RealtimeSyncConfig.ENABLED} signedIn=${auth.signedIn()} enabled=${radar.prefs.alerts.enabled} follows=${radar.follows.size} teams=${radar.teamFollows.size}" }
            if (!fg || !RealtimeSyncConfig.ENABLED) emptySet()
            else SportsAlertTopicPolicy.topics(
                settings = radar.prefs.alerts,
                follows = radar.follows,
                teamFollows = radar.teamFollows,
                signedIn = auth.signedIn(),
                leagueSport = { id -> radar.leagueById(id)?.sport },
            )
        }

    /** The process lifecycle's foreground signal; collected on Main (ProcessLifecycleOwner requires it on Android). */
    private fun appForeground(): Flow<Boolean> =
        AppForegroundMonitor.events().map { it == AppVisibility.Foreground }.flowOn(Dispatchers.Main)

    /** Wall-clock now (what the open-game request is stamped and judged with). */
    fun nowMs(): Long = RadarTime.nowMs()

    // --- screens report ------------------------------------------------------------------------------------------

    /** The hub's game screen is showing [eventId] (gate 3). Call [closedGame] when it leaves. */
    fun openedGame(eventId: String) = controller.setOpenGame(eventId)

    fun closedGame(eventId: String) = controller.clearOpenGame(eventId)

    /** The banner's button: telemetry, dismiss, and what the shell must do. */
    fun act(): BannerAction? = controller.act()

    fun dismiss() = controller.dismiss(byUser = true)

    // --- settings (every change is synced per profile through radar_prefs) ---------------------------------------

    val signedIn: StateFlow<Boolean> by lazy {
        AuthRepository.state.map { it.signedIn() }.stateIn(ioScope, SharingStarted.Eagerly, AuthRepository.state.value.signedIn())
    }

    val settingsView: Flow<SportsAlertSettingsView>
        get() = combine(RadarRepository.uiState, AuthRepository.state) { radar, auth -> view(radar, auth.signedIn()) }

    fun settings(): SportsAlertSettings = RadarRepository.uiState.value.prefs.alerts

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }
    fun toggleEnabled() = update { it.copy(enabled = !it.enabled) }
    fun cycleTeamLevel() = update { it.copy(teamLevel = SportsAlertLabels.nextLevel(it.teamLevel)) }
    fun cycleLeagueLevel() = update { it.copy(leagueLevel = SportsAlertLabels.nextLevel(it.leagueLevel)) }
    fun setQuietOn(on: Boolean) = update { it.copy(quietOn = on) }
    fun setQuietRange(startMin: Int, endMin: Int) = update { it.copy(quietStartMin = startMin, quietEndMin = endMin) }
    fun cyclePerHour() = update { it.copy(maxPerHour = SportsAlertSettings.nextPerHour(it.maxPerHour)) }
    fun cycleBatch() = update { it.copy(batchSeconds = SportsAlertSettings.nextBatch(it.batchSeconds)) }
    fun cycleDuringPlayback() = update { it.copy(duringPlayback = SportsAlertLabels.nextDuringPlayback(it.duringPlayback)) }
    fun setLeagueMuted(leagueId: String, muted: Boolean) = update { it.withLeagueMuted(leagueId, muted) }
    fun setTeamMuted(teamId: String, muted: Boolean) = update { it.withTeamMuted(teamId, muted) }

    /** The game page chip: Default → All → Off → Default. The override expires 24 h after kick-off. */
    fun cycleGame(eventId: String, kickoffMs: Long) {
        val now = RadarTime.nowMs()
        update { s ->
            val next = SportsAlertLabels.nextGameLevel(s.gameOverride(eventId, now))
            s.withGame(eventId, next, expMs = kickoffMs + GAME_OVERRIDE_TTL_MS, nowMs = now)
        }
    }

    /** The game page chip for [eventId] (null when the game is not followed). */
    fun gameChip(state: RadarUiState, eventId: String, leagueId: String?, homeId: String?, awayId: String?, signedIn: Boolean): AlertChip? {
        val followed = (leagueId != null && leagueId in state.followedLeagueIds) ||
            listOfNotNull(homeId, awayId).any { it in state.followedTeamIds }
        return SportsAlertLabels.gameChip(state.prefs.alerts, eventId, followed, signedIn, RadarTime.nowMs())
    }

    private fun update(transform: (SportsAlertSettings) -> SportsAlertSettings) = RadarRepository.updateAlertSettings(transform)

    internal fun view(radar: RadarUiState, signedIn: Boolean) = SportsAlertSettingsView(
        settings = radar.prefs.alerts,
        signedIn = signedIn,
        teams = radar.followedTeams.map { it.id to it.name },
        leagues = radar.follows.sortedBy { it.sortOrder }.map { f -> f.leagueId to (radar.leagueById(f.leagueId)?.name ?: f.name ?: f.leagueId) },
    )

    private fun AuthState.signedIn(): Boolean = this is AuthState.Authenticated && !isAnonymous

    private const val GAME_OVERRIDE_TTL_MS = 24 * 60 * 60_000L

    /** Production inputs: the shared repositories and ports, read at decision time. Never a fetch. */
    private object Inputs : SportsAlertInputs {
        private fun radar() = RadarRepository.uiState.value
        override fun nowMs(): Long = RadarTime.nowMs()
        override fun localMinuteOfDay(nowMs: Long): Int {
            val t = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.currentSystemDefault()).time
            return t.hour * 60 + t.minute
        }
        override fun playback(): PlaybackContext = PlaybackContextAccess.current().current.value
        override fun settings(): SportsAlertSettings = radar().prefs.alerts
        override fun followedLeagueIds(): Set<String> = radar().followedLeagueIds
        override fun followedTeamIds(): Set<String> = radar().followedTeamIds
        override fun spoiler(): ScoreSpoilerSettings = radar().scoreSpoiler
        override fun revealed(): RevealedGames = radar().revealedGames
        override fun playingChannelIds(): Set<String> = PlayingChannelsAccess.current().ids.value
    }
}
