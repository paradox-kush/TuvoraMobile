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
import kotlinx.coroutines.channels.Channel
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

/** One open game screen. Create when the screen appears; run [runWhileVisible] from a task tied to its visibility. */
class TvGameDetailModel internal constructor(private val ref: GameRef, private val service: SportsDetailService) {
    private val source: GameDetailSource = service.open(ref)
    private val _state = MutableStateFlow<TvGameDetailState>(TvGameDetailState.Loading)
    val state: StateFlow<TvGameDetailState> = _state.asStateFlow()
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
        retrySignal.trySend(Unit)
    }

    /**
     * Fetches, then refreshes on [GameDetailRefreshPolicy] until the game is idle (final / far future).
     * Cancelling the caller's task (screen gone, app backgrounded) ends it: no free-running poll.
     */
    suspend fun runWhileVisible() {
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
            _state.value = TvGameDetailReducer.reduce(_state.value, load)
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
