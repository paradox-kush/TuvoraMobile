package com.nuvio.app.features.radar

import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import com.nuvio.app.features.sportsdetail.api.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/** The Sports hub's fixture as the game screen's reference. */
internal fun RadarFixture.toGameRef(): GameRef? {
    val league = leagueId ?: return null
    val eventId = id ?: return null
    val start = startEpochMs ?: return null
    val twoSides = !home.isNullOrBlank() && !away.isNullOrBlank()
    return GameRef(
        gameId = "tsdb:$eventId", leagueId = league, tsdbEventId = eventId, espnEventId = null,
        homeName = if (twoSides) home!! else (event ?: league), awayName = if (twoSides) away else null,
        eventName = event, startsAtMs = start,
    )
}

/** Whether the Sports hub can open the game screen for this fixture (it needs an id, a league and a kickoff). */
internal fun RadarFixture.opensGameScreen(): Boolean = toGameRef() != null

/**
 * The game screen's Watch block backend: the Sports Centre's own channel matcher (the same one the match sheet
 * uses) plus the Live TV player's current channel. Only a CONFIRMED match is offered as "Play" — a channel that
 * merely carries the competition stays in the match sheet behind "Find channels".
 */
internal class RadarGameWatch(
    override val nowWatching: StateFlow<NowWatching?>,
    /** The load's OWN scope, so a screen pause or the caller's timeout never cancels the shared matching (it restarted from zero every time). */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GameWatchPort {
    private val flight = SingleFlight<String, WatchLookup>(
        scope = scope,
        ttlMs = RESULT_TTL_MS,
        nowMs = { RadarTime.nowMs() },
        overallDeadlineMs = OVERALL_DEADLINE_MS,
        onDeadline = WatchLookup.Failed,
        shouldCache = { it !is WatchLookup.Failed },
    )

    /** Single-flight per game: concurrent and later callers join the same load; cancelling a caller only stops its waiting. */
    override suspend fun lookup(ref: GameRef): WatchLookup = flight.get(ref.gameId) { doLookup(ref) }

    private suspend fun doLookup(ref: GameRef): WatchLookup {
        return try {
            XtreamRepository.ensureLoaded()
            if (XtreamRepository.uiState.value.accounts.none { it.availableOnDevice }) return WatchLookup.NoPlaylist
            val state = RadarRepository.uiState.value
            val fixture = ref.tsdbEventId?.let { state.fixtureByKey(it) } ?: return WatchLookup.NoMatch
            val league = fixture.leagueId?.let { state.leagueById(it) }
            val matches = RadarChannelMatcher.match(fixture, league, stationLookup = { RadarRepository.tvStations(fixture.id) })
            // Every match, any confidence: what sports alerts treat as "already watching this game".
            val carriers = matches.map { it.channel.contentId }.toSet()
            val best = matches.firstOrNull { it.confidence == MatchConfidence.CONFIRMED && it.via == RadarChannelMatcher.MatchVia.EPG }
                ?: matches.firstOrNull { it.confidence == MatchConfidence.CONFIRMED }
                ?: return if (carriers.isEmpty()) WatchLookup.NoMatch else WatchLookup.Unconfirmed(carriers)
            WatchLookup.Found(WatchOffer(best.channel.contentId, best.channel.name, best.channel.logo), carriers + best.channel.contentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            WatchLookup.Failed
        }
    }

    private companion object {
        /** A good answer is reused for a minute (guides do not change faster); a failure is never reused. */
        const val RESULT_TTL_MS = 60_000L
        /** The whole shared load, however many callers come and go. */
        const val OVERALL_DEADLINE_MS = 45_000L
    }
}
