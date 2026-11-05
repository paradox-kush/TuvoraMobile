// Game rows shaped after homelessbrian/NuvioPlusIPTV livetv/calendar (GPL-3.0). Pure: no I/O, no clock.
package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarGameState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** "Watch now" appears for a live game and from this long before kick-off. */
const val CALENDAR_WATCH_LEAD_MS = 15L * CALENDAR_MINUTE_MS

/** A scheduled game this long past its kick-off with no live or final word is stale data, not "upcoming". */
private const val GAME_STALE_AFTER_MS = 12L * CALENDAR_HOUR_MS

fun CalendarGameItem.canWatchNow(nowMs: Long): Boolean = when (state) {
    CalendarGameState.LIVE -> true
    CalendarGameState.SCHEDULED -> nowMs >= kickoffMs - CALENDAR_WATCH_LEAD_MS && nowMs < kickoffMs + GAME_STALE_AFTER_MS
    CalendarGameState.FINAL, CalendarGameState.POSTPONED -> false
}

/** Which kind of thing a calendar row is — what the filter chips select. */
fun CalendarFilter.keeps(type: CalendarTitleType): Boolean =
    this == if (type == CalendarTitleType.MOVIE) CalendarFilter.MOVIES else CalendarFilter.SHOWS

fun List<CalendarTitleRecord>.filteredBy(filters: Set<CalendarFilter>): List<CalendarTitleRecord> =
    if (CalendarFilter.SHOWS in filters && CalendarFilter.MOVIES in filters) this
    else filter { title -> filters.any { it.keeps(title.type) } }

/** Games to list, deduped by id (the latest listing wins), dropping stale and unrenderable ones. */
fun List<CalendarGameItem>.filteredBy(filters: Set<CalendarFilter>, nowMs: Long): List<CalendarGameItem> {
    if (CalendarFilter.SPORTS !in filters) return emptyList()
    return asReversed().distinctBy { it.id }.asReversed().filter { game ->
        game.id.isNotBlank() && game.title.isNotBlank() &&
            !(game.state == CalendarGameState.SCHEDULED && nowMs >= game.kickoffMs + GAME_STALE_AFTER_MS)
    }
}

/**
 * Games as calendar items in [zone], counted by the kick-off day there. Finals older than the Just aired window drop out,
 * and so does anything beyond [windowEnd] (a team's far-future fixtures never flood Later).
 */
fun calendarGameItems(
    games: List<CalendarGameItem>,
    nowMs: Long,
    zone: TimeZone,
    firstDay: LocalDate,
    windowEnd: LocalDate,
): List<CalendarItem> = games.mapNotNull { game ->
    val date = calendarDateAt(game.kickoffMs, zone)
    if (date < firstDay || date >= windowEnd) return@mapNotNull null
    CalendarItem(game.asTitle(), entry = null, date = date, time = calendarTimeAt(game.kickoffMs, zone), game = game)
}

/** A game has no title record; this stand-in keeps keys unique and lets shared row code read a name. */
fun CalendarGameItem.asTitle(): CalendarTitleRecord = CalendarTitleRecord(
    key = "game:$id",
    type = CalendarTitleType.SERIES,
    ids = CalendarIds(),
    name = title,
    poster = homeBadge ?: leagueBadge,
    network = league,
)

data class CalendarComingUpGame(val game: CalendarGameItem, val whenKind: CalendarComingUpWhen, val date: LocalDate)

/** Live games first, then today's and the next days' games, soonest first. Respects the Sports filter. */
fun buildCalendarComingUpGames(
    games: List<CalendarGameItem>,
    filters: Set<CalendarFilter>,
    nowMs: Long,
    zone: TimeZone,
    max: Int = 5,
): List<CalendarComingUpGame> {
    val today = calendarDateAt(nowMs, zone)
    return games.filteredBy(filters, nowMs)
        .filter { it.state == CalendarGameState.LIVE || (it.state == CalendarGameState.SCHEDULED && calendarDateAt(it.kickoffMs, zone) in today..today.plusDays(2)) }
        .sortedWith(compareBy<CalendarGameItem> { it.state != CalendarGameState.LIVE }.thenBy { it.kickoffMs })
        .take(max)
        .map { game ->
            val date = maxOf(calendarDateAt(game.kickoffMs, zone), today)
            val kind = when {
                game.state == CalendarGameState.LIVE -> CalendarComingUpWhen.NEW
                date == today -> CalendarComingUpWhen.TODAY
                date == today.plusDays(1) -> CalendarComingUpWhen.TOMORROW
                else -> CalendarComingUpWhen.WEEKDAY
            }
            CalendarComingUpGame(game, kind, date)
        }
}
