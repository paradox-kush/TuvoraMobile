// Game model shape adapted from homelessbrian/NuvioPlusIPTV livetv/calendar/CalendarModels.kt (GPL-3.0).
package com.nuvio.app.features.calendar.api

import kotlinx.coroutines.flow.Flow

/*
 * Sports games on the Calendar. Consumer-owned and neutral: the Calendar never names the sports (radar) subsystem; the
 * composition root (FeatureContributions.kt) implements [CalendarSportsSource] from the fixtures the app already holds.
 * Reading it makes NO request and starts NO poll — the sports feature refreshes under its own policy.
 */

enum class CalendarGameState { SCHEDULED, LIVE, FINAL, POSTPONED }

data class CalendarGameItem(
    val id: String,
    val sport: String? = null,
    val league: String? = null,
    val leagueBadge: String? = null,
    val home: String? = null,
    val away: String? = null,
    val homeBadge: String? = null,
    val awayBadge: String? = null,
    /** Full event name — the display fallback when there are no two sides (motorsport, golf…). */
    val event: String? = null,
    /** Kick-off, UTC epoch ms. */
    val kickoffMs: Long,
    val state: CalendarGameState = CalendarGameState.SCHEDULED,
    val homeScore: String? = null,
    val awayScore: String? = null,
    /** The live minute / period ("67'", "Q3"), when the feed gives one. */
    val progress: String? = null,
    /** Opaque hint for the host's channel matching; the Calendar only hands [id] back. */
    val channelHintId: String? = null,
) {
    val hasScore: Boolean get() = !homeScore.isNullOrBlank() && !awayScore.isNullOrBlank()

    /** "Home v Away", else the event name, else the league. */
    val title: String
        get() = when {
            !home.isNullOrBlank() && !away.isNullOrBlank() -> "$home v $away"
            !event.isNullOrBlank() -> event
            else -> league.orEmpty()
        }
}

interface CalendarSportsSource {
    /** Games of the followed leagues and teams, as of [nowMs] (live / final are decided at that instant). Local read only. */
    fun games(nowMs: Long): List<CalendarGameItem>

    /** Emits when the underlying fixtures or scores changed. */
    val changes: Flow<Unit>
}
