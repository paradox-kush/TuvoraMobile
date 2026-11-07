package com.nuvio.app.features.radar

import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import kotlinx.coroutines.CancellationException
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
internal class RadarGameWatch(override val nowWatching: StateFlow<NowWatching?>) : GameWatchPort {
    override suspend fun lookup(ref: GameRef): WatchLookup {
        return try {
            XtreamRepository.ensureLoaded()
            if (XtreamRepository.uiState.value.accounts.none { it.availableOnDevice }) return WatchLookup.NoPlaylist
            val state = RadarRepository.uiState.value
            val fixture = ref.tsdbEventId?.let { state.fixtureByKey(it) } ?: return WatchLookup.NoMatch
            val league = fixture.leagueId?.let { state.leagueById(it) }
            val matches = RadarChannelMatcher.match(fixture, league, stationLookup = { RadarRepository.tvStations(fixture.id) })
            val best = matches.firstOrNull { it.confidence == MatchConfidence.CONFIRMED && it.via == RadarChannelMatcher.MatchVia.EPG }
                ?: matches.firstOrNull { it.confidence == MatchConfidence.CONFIRMED }
                ?: return WatchLookup.NoMatch
            WatchLookup.Found(WatchOffer(best.channel.contentId, best.channel.name, best.channel.logo))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            WatchLookup.Failed
        }
    }
}
