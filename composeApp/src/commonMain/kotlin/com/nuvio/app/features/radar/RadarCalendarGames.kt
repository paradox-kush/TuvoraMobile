package com.nuvio.app.features.radar

import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarGameState

/**
 * Pure mapping from what the Sports feature already holds to the Calendar's neutral game items. No network, no clock of
 * its own (the caller passes [nowMs]): the followed leagues' fixtures, the followed teams' fixtures and the live
 * scores are read as they are. The Calendar never sees a radar type; the composition root wires this to its port.
 */
object RadarCalendarGames {
    fun games(state: RadarUiState, nowMs: Long): List<CalendarGameItem> {
        val fixtures = state.followedLeagueIds.asSequence().flatMap { state.fixturesByLeague[it].orEmpty() } +
            state.followedTeamIds.asSequence().flatMap { state.fixturesByTeam[it].orEmpty() }
        return fixtures
            .distinctBy(::radarFixtureKey)
            .mapNotNull { toGame(it, state, nowMs) }
            .sortedBy { it.kickoffMs }
            .toList()
    }

    internal fun toGame(fixture: RadarFixture, state: RadarUiState, nowMs: Long): CalendarGameItem? {
        val kickoff = fixture.startEpochMs ?: return null
        val key = radarFixtureKey(fixture)
        val score = fixture.id?.let { state.liveScores[it] }
        val gameState = stateOf(fixture, state, nowMs)
        // The feed's score is fresher than the fixture row while a game is on; a fixture's own score is the final one.
        val homeScore = score?.homeScore?.takeIf { it.isNotBlank() } ?: fixture.homeScore
        val awayScore = score?.awayScore?.takeIf { it.isNotBlank() } ?: fixture.awayScore
        val league = fixture.league?.takeIf { it.isNotBlank() }
            ?: fixture.leagueId?.let { state.leagueById(it)?.name }
        return CalendarGameItem(
            id = key,
            sport = fixture.sport,
            league = league,
            leagueBadge = fixture.leagueBadge ?: fixture.leagueId?.let { state.leagueById(it)?.badge },
            home = fixture.home,
            away = fixture.away,
            homeBadge = fixture.homeBadge,
            awayBadge = fixture.awayBadge,
            event = fixture.event,
            kickoffMs = kickoff,
            state = gameState,
            homeScore = homeScore.takeIf { gameState == CalendarGameState.LIVE || gameState == CalendarGameState.FINAL },
            awayScore = awayScore.takeIf { gameState == CalendarGameState.LIVE || gameState == CalendarGameState.FINAL },
            progress = score?.progress.takeIf { gameState == CalendarGameState.LIVE },
            channelHintId = fixture.id,
        ).takeIf { it.title.isNotBlank() }
    }

    internal fun stateOf(fixture: RadarFixture, state: RadarUiState, nowMs: Long): CalendarGameState {
        val status = fixture.status?.trim()?.lowercase()?.replace('_', ' ').orEmpty()
        if (fixture.postponed == "yes" || status.contains("postpone") || status.contains("cancel") || status.contains("abandon")) {
            return CalendarGameState.POSTPONED
        }
        if (state.isLive(fixture, nowMs)) return CalendarGameState.LIVE
        val start = fixture.startEpochMs ?: return CalendarGameState.SCHEDULED
        return if (fixture.isFinishedOrOff || nowMs >= start + fixture.maxLiveWindowMs()) CalendarGameState.FINAL else CalendarGameState.SCHEDULED
    }
}

/** A fixture's stable id: TheSportsDB's event id, else a key built from what identifies it. */
internal fun radarFixtureKey(fixture: RadarFixture): String =
    fixture.id?.takeIf { it.isNotBlank() } ?: "${fixture.leagueId}/${fixture.event}/${fixture.ts}"

/** The fixture [id] (as [radarFixtureKey] makes it) among everything the Sports feature holds, or null. */
internal fun RadarUiState.fixtureByKey(id: String): RadarFixture? =
    (fixturesByLeague.values.asSequence() + fixturesByTeam.values.asSequence()).flatMap { it.asSequence() }
        .firstOrNull { radarFixtureKey(it) == id }
