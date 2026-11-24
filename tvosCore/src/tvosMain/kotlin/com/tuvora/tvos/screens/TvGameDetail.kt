package com.tuvora.tvos.screens

import com.nuvio.app.features.radar.RadarFixture
import com.nuvio.app.features.radar.RadarTime
import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.MappingStorage
import com.nuvio.app.features.sportsdetail.api.SportsDetail
import com.nuvio.app.features.sportsdetail.api.SportsDetailService
import com.nuvio.app.features.sportsdetail.api.StoredMapping
import com.nuvio.app.features.radar.RadarRepository
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.internal.GameRevealChip
import com.nuvio.app.features.sportsdetail.internal.GameSpoilerGate
import com.nuvio.app.features.sportsdetail.internal.revealChip
import com.nuvio.app.features.sportsdetail.internal.showsHiddenCard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSUserDefaults

/** The last good remote sports mapping (+ ETag) in UserDefaults, so a relaunch keeps a repaired mapping. */
internal class TvMappingStorage(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : MappingStorage {
    override fun load(): StoredMapping? =
        defaults.stringForKey(KEY_JSON)?.let { StoredMapping(it, defaults.stringForKey(KEY_ETAG)) }

    override fun save(value: StoredMapping) {
        defaults.setObject(value.json, KEY_JSON)
        if (value.etag != null) defaults.setObject(value.etag, KEY_ETAG) else defaults.removeObjectForKey(KEY_ETAG)
    }

    override fun loadRejectedEtag(): String? = defaults.stringForKey(KEY_REJECTED)

    override fun saveRejectedEtag(etag: String?) {
        if (etag != null) defaults.setObject(etag, KEY_REJECTED) else defaults.removeObjectForKey(KEY_REJECTED)
    }

    private companion object {
        const val KEY_REJECTED = "tuvora.sportsdetail.mapping.rejected"
        const val KEY_JSON = "tuvora.sportsdetail.mapping"
        const val KEY_ETAG = "tuvora.sportsdetail.mapping.etag"
    }
}

/**
 * Apple TV's door to the shared game-detail core (`features.sportsdetail`): open a fixture, observe
 * plain state. One service for the app (it holds the mapping).
 */
object TvGameDetail {
    private val service: SportsDetailService by lazy { SportsDetail.create(TvMappingStorage()) }

    /** The game identity for [fixture], or null when it has no event id (Swift then shows the match sheet). */
    fun ref(fixture: RadarFixture): GameRef? {
        val id = fixture.id?.takeIf { it.isNotBlank() } ?: return null
        val start = fixture.startEpochMs ?: return null
        return GameRef(
            gameId = "tsdb:$id",
            leagueId = fixture.leagueId.orEmpty(),
            tsdbEventId = id,
            espnEventId = null,
            homeName = fixture.home?.takeIf { it.isNotBlank() } ?: fixture.event.orEmpty(),
            awayName = fixture.away?.takeIf { it.isNotBlank() },
            eventName = fixture.event,
            startsAtMs = start,
        )
    }

    fun open(fixture: RadarFixture): TvGameDetailModel? = ref(fixture)?.let { TvGameDetailModel(it, service) }
}

/**
 * The game screen's spoiler state, decided by the same shared rules as the phone (GameSpoilerView.revealChip):
 * [hidden] shows "Show this game's score" (scores hidden on a live or final game; press: [TvSports.revealGame]);
 * [hideAgain] shows "Hide again" (this game was revealed on its own; press: [TvSports.hideGame]);
 * [pending] = delayed, nothing due yet ("–"); [hiddenCard] = redaction left nothing to show, say "Scores are hidden".
 */
data class TvGameSpoiler(
    val hidden: Boolean,
    val pending: Boolean,
    val hideAgain: Boolean = false,
    val hiddenCard: Boolean = false,
)

/** One open game screen. Create when the screen appears; run [runWhileVisible] from a task tied to its visibility. */
class TvGameDetailModel internal constructor(private val ref: GameRef, private val service: SportsDetailService) {
    private val source: GameDetailSource = service.open(ref)
    /** The newest fetched state (drives the refresh plan). */
    private val _state = MutableStateFlow<TvGameDetailState>(TvGameDetailState.Loading)
    /** What Swift renders: [_state] through Hide scores / score delay (delayed snapshot, redacted when hidden or pending). */
    private val _shown = MutableStateFlow<TvGameDetailState>(TvGameDetailState.Loading)
    val state: StateFlow<TvGameDetailState> = _shown.asStateFlow()
    private val _spoiler = MutableStateFlow(TvGameSpoiler(hidden = false, pending = false))
    /** Hidden -> Swift shows the "Scores hidden" chip beside the scoreboard (press: [TvSports.toggleScoresShown]). */
    val spoiler: StateFlow<TvGameSpoiler> = _spoiler.asStateFlow()
    private val gate = GameSpoilerGate()
    /** This game's key in RevealedGames (radarFixtureKey: the TheSportsDB event id the screen was opened with). */
    private val revealKey: String = ref.tsdbEventId?.takeIf { it.isNotBlank() } ?: ref.gameId

    /** This game's settings: a game revealed on its own reads as shown (session only, never the global setting). */
    private fun settingsNow(): ScoreSpoilerSettings = RadarRepository.uiState.value.scoreSpoilerFor(revealKey)
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    // Model fields, not locals: the Swift task restarts on every foreground, and a restart must not
    // re-fire a full tick (a FINAL game stays at zero requests).
    private var mappingSynced = false
    private var failures = 0
    private var lastEvent: Long? = null
    private var lastSummary: Long? = null
    private var first = true

    /** Retry after Failed/Empty (the only way back from them: an event, never a timer). */
    fun retry() {
        if (_state.value !is TvGameDetailState.Loaded) _state.value = TvGameDetailState.Loading
        republish(settingsNow())
        retrySignal.trySend(Unit)
    }

    /**
     * Fetches, then refreshes on [GameDetailRefreshPolicy] until the game is idle (final / far future).
     * Cancelling the caller's task (screen gone, app backgrounded) ends it: no free-running poll.
     */
    suspend fun runWhileVisible() {
        // The spoiler redraw is NOT run here: Swift runs [runScoreSpoilerWhileVisible] for the whole screen, and a second
        // copy tied to the poll only competed with it. poll() republishes each snapshot itself.
        poll()
    }

    /**
     * Keeps [state] in step with Hide scores / score delay: re-renders on a settings change, a new snapshot, or ONCE when
     * the next delayed snapshot falls due. Local only (no request). Swift runs it in its own task tied to the screen's
     * visibility (beside [runWhileVisible]) so the reveal chip and a due final score still apply after the poll went
     * idle. Its wake is a broadcast ([TvRedrawSignal]): any number of these loops each see every wake. Cancel to stop.
     */
    suspend fun runScoreSpoilerWhileVisible(): Unit = coroutineScope {
        var settings = settingsNow()
        // Keyed on the global setting and this game's reveal too: "Hide again" depends on both, not only on the settings.
        launch {
            RadarRepository.uiState.map { Triple(it.scoreSpoilerFor(revealKey), it.scoreSpoiler.shown, revealKey in it.revealedGames) }
                .distinctUntilChanged().collect { (s, _, _) -> settings = s; republish(s) }
        }
        launch { _state.collect { republish(settings) } }
        while (true) {
            val mark = redraw.mark()
            val due = gate.nextChangeAtMs(settings, RadarTime.nowMs())
            if (due == null) { redraw.awaitAfter(mark); continue }
            withTimeoutOrNull((due - RadarTime.nowMs()).coerceAtLeast(0L) + 50L) { redraw.awaitAfter(mark) }
            republish(settings, wake = false)
        }
    }

    private val redraw = TvRedrawSignal()

    /** [wake]: tell the due-time loop to re-plan (not from the loop itself, or it would spin). */
    private fun republish(settings: ScoreSpoilerSettings, wake: Boolean = true) {
        val s = _state.value
        if (s !is TvGameDetailState.Loaded) { _shown.value = s; _spoiler.value = TvGameSpoiler(false, false); return }
        val view = gate.view(s.detail, settings, RadarTime.nowMs())
        _shown.value = s.copy(detail = view.detail)
        val radar = RadarRepository.uiState.value
        val chip = view.revealChip(revealedHere = revealKey in radar.revealedGames, globalShown = radar.scoreSpoiler.shown)
        _spoiler.value = TvGameSpoiler(
            hidden = chip == GameRevealChip.SHOW_THIS_GAME,
            pending = view.pending,
            hideAgain = chip == GameRevealChip.HIDE_AGAIN,
            hiddenCard = view.showsHiddenCard,
        )
        if (wake) redraw.wake()
    }

    private suspend fun poll() {
        if (!mappingSynced) { mappingSynced = true; runCatching { service.syncMapping() } }
        while (true) {
            val now = RadarTime.nowMs()
            val current = _state.value
            val fetchEvent: Boolean
            val fetchSummary: Boolean
            if (first || current !is TvGameDetailState.Loaded) {
                fetchEvent = true; fetchSummary = true
            } else {
                val plan = TvGameRefresh.plan(
                    sport = current.detail.sport, state = current.detail.state, startsAtMs = ref.startsAtMs, nowMs = now,
                    lastEventMs = lastEvent, lastSummaryMs = lastSummary, failures = failures, config = service.refreshConfig(),
                )
                // Idle (final / long-dead live) ends the loop. Far-future upcoming is NOT idle: it carries
                // nextCheckInMs with no fetch, and the delay below wakes the screen when the lead window opens.
                if (plan.isIdle) return
                if (!plan.fetchEvent && !plan.fetchSummary) { delay(plan.nextCheckInMs ?: return); continue }
                fetchEvent = plan.fetchEvent; fetchSummary = plan.fetchSummary
            }
            first = false
            // The UI's own backstop: the wait ends even if a provider call never returns.
            val load = withTimeoutOrNull(LOAD_DEADLINE_MS) { source.tick(fetchEvent, fetchSummary) } ?: DetailLoad.Failed(timedOut = true)
            val at = RadarTime.nowMs()
            if (fetchEvent) lastEvent = at
            if (fetchSummary) lastSummary = at
            failures = TvGameDetailReducer.failures(failures, load)
            // Where the fetch result lands: time-stamp it for the score delay (never in Swift).
            if (load is DetailLoad.Loaded) gate.record(load.detail, at)
            _state.value = TvGameDetailReducer.reduce(_state.value, load)
            republish(settingsNow())
            val after = _state.value
            if (after !is TvGameDetailState.Loaded) {
                retrySignal.receive()          // Failed / Empty wait for Retry (or the screen going away)
                first = true
                continue
            }
            if (after.detail.state == GameState.LIVE) delay(MIN_TICK_MS)
        }
    }

    private companion object {
        const val LOAD_DEADLINE_MS = 20_000L
        const val MIN_TICK_MS = 1_000L
    }
}
