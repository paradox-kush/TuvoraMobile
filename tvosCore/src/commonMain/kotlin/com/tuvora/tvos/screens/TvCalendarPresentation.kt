package com.tuvora.tvos.screens

import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.internal.CalendarBody
import com.nuvio.app.features.calendar.internal.CalendarComingUpGame
import com.nuvio.app.features.calendar.internal.CalendarFilter
import com.nuvio.app.features.calendar.internal.calendarTimeAt
import com.nuvio.app.features.calendar.internal.canWatchNow
import com.nuvio.app.features.calendar.internal.asTitle
import com.nuvio.app.features.calendar.internal.CalendarComingUpItem
import com.nuvio.app.features.calendar.internal.CalendarEntryKind
import com.nuvio.app.features.calendar.internal.CalendarItem
import com.nuvio.app.features.calendar.internal.CalendarReminderCard
import com.nuvio.app.features.calendar.internal.CalendarState
import com.nuvio.app.features.calendar.internal.CalendarTitleRecord
import com.nuvio.app.features.calendar.internal.CalendarTitleType
import com.nuvio.app.features.calendar.internal.CalendarViewMode
import com.nuvio.app.features.calendar.internal.calendarContentIds
import com.nuvio.app.features.calendar.internal.calendarNextUiChangeMs
import com.nuvio.app.features.calendar.internal.calendarScreenUi
import kotlinx.datetime.LocalDate

/*
 * The Apple TV Calendar's view data: the shared screen model (CalendarScreenModel, the same Loaded / Empty / Failed
 * decision and deadline backstop as the phone) flattened into plain values SwiftUI can draw. Dates travel as ISO
 * strings (`2026-10-06`) and times as `HH:mm` so Swift formats them with the viewer's locale. Pure and tested.
 */

enum class TvCalendarBodyKind { Blank, Loading, Empty, Failed, FailedTimedOut, Content }

/** A sports game on a row: both sides, league and the state Swift words ("LIVE", "FINAL", "POSTPONED", "SCHEDULED"). */
data class TvCalendarGame(
    val gameId: String,
    val home: String?,
    val away: String?,
    val homeBadge: String?,
    val awayBadge: String?,
    val league: String?,
    /** A [com.nuvio.app.features.calendar.api.CalendarGameState] name. */
    val state: String,
    val homeScore: String?,
    val awayScore: String?,
    val progress: String?,
    /** "Watch now" is offered: live, or within 15 minutes of kick-off. */
    val canWatchNow: Boolean,
)

/** One release row. [labelKey] names a Localizable key (`calendar_label_season_finale`) or is null. */
data class TvCalendarRow(
    val id: String,
    val titleKey: String,
    val contentId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val episodeLine: String?,
    val dateIso: String?,
    val timeText: String?,
    val labelKey: String?,
    val network: String?,
    /** Non-null for a sports game; then [name] is "Home v Away" (or the event) and [episodeLine] is null. */
    val game: TvCalendarGame? = null,
)

/** [kind] is a [CalendarSectionKind] name; a DAY section carries its [dateIso]. */
data class TvCalendarSection(val id: String, val kind: String, val dateIso: String?, val rows: List<TvCalendarRow>)

data class TvCalendarDay(val dateIso: String, val day: Int, val inMonth: Boolean, val isToday: Boolean, val rows: List<TvCalendarRow>)

data class TvCalendarScreen(
    val body: TvCalendarBodyKind,
    val sections: List<TvCalendarSection>,
    /** Month view only: whole weeks Monday–Sunday. */
    val weeks: List<List<TvCalendarDay>>,
    val monthYear: Int,
    val monthNumber: Int,
    val updateFailed: Boolean,
    val refreshing: Boolean,
    val remaining: Int,
    val monthView: Boolean,
    val comingUpRow: Boolean,
    val cornerNote: Boolean,
    val windowDays: Int,
    val showShows: Boolean,
    val showMovies: Boolean,
    val showSports: Boolean,
    /** The Sports chip is offered: games are held, or the viewer turned Sports off and must be able to turn it on. */
    val sportsChip: Boolean,
    /** Millis until a Loading reaches its deadline (re-evaluate then: the UI backstop); null when nothing is pending. */
    val nextChangeInMs: Long?,
)

data class TvCalendarComingUp(val row: TvCalendarRow, val whenKind: String, val dateIso: String, val timeText: String?)

data class TvCalendarReminders(val rows: List<TvCalendarRow>, val more: Int, val episodeKeys: List<String>)

object TvCalendarPresentation {
    fun screen(state: CalendarState, nowMs: Long, anchorIso: String?): TvCalendarScreen {
        val mode = state.settings.viewMode
        val anchor = anchorIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val ui = calendarScreenUi(state, nowMs, mode, anchor)
        val body = when (val b = ui.body) {
            CalendarBody.Blank -> TvCalendarBodyKind.Blank
            is CalendarBody.Loading -> TvCalendarBodyKind.Loading
            CalendarBody.Empty -> TvCalendarBodyKind.Empty
            is CalendarBody.Failed -> if (b.timedOut) TvCalendarBodyKind.FailedTimedOut else TvCalendarBodyKind.Failed
            CalendarBody.Content -> TvCalendarBodyKind.Content
        }
        return TvCalendarScreen(
            body = body,
            sections = ui.sections.map { section ->
                val id = "${section.kind}:${section.date}"
                TvCalendarSection(id, section.kind.name, section.date?.toString(), section.items.map { row(it, id, nowMs) })
            },
            weeks = ui.month?.weeks.orEmpty().map { week ->
                week.map { day ->
                    TvCalendarDay(day.date.toString(), day.date.day, day.inMonth, day.isToday, day.items.map { row(it, day.date.toString(), nowMs) })
                }
            },
            monthYear = ui.month?.year ?: 0,
            monthNumber = ui.month?.month ?: 0,
            updateFailed = ui.updateFailed,
            refreshing = ui.refreshing,
            remaining = ui.remaining,
            monthView = mode == CalendarViewMode.MONTH,
            comingUpRow = state.settings.comingUpRow,
            cornerNote = state.settings.cornerMessage,
            windowDays = state.settings.windowDays,
            showShows = CalendarFilter.SHOWS in state.settings.filters,
            showMovies = CalendarFilter.MOVIES in state.settings.filters,
            showSports = CalendarFilter.SPORTS in state.settings.filters,
            sportsChip = state.games.isNotEmpty() || CalendarFilter.SPORTS !in state.settings.filters,
            nextChangeInMs = calendarNextUiChangeMs(state, nowMs),
        )
    }

    fun row(item: CalendarItem, scope: String, nowMs: Long = 0L): TvCalendarRow {
        val entry = item.entry
        val game = item.game
        return TvCalendarRow(
            id = "$scope|${item.title.key}|${entry?.slot}",
            titleKey = item.title.key,
            contentId = calendarContentIds(item.title).firstOrNull() ?: item.title.key,
            type = typeOf(item.title),
            name = item.title.name ?: item.title.key,
            poster = item.title.poster,
            episodeLine = episodeLine(entry?.kind, entry?.season, entry?.episode, entry?.shownTitle),
            dateIso = item.date?.toString(),
            timeText = item.time?.let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" },
            labelKey = entry?.label?.takeIf { it.name != "NONE" }?.let { "calendar_label_${it.name.lowercase()}" },
            network = item.title.network,
            game = game?.let(::gameOf)?.let { it.copy(canWatchNow = game.canWatchNow(nowMs)) },
        )
    }

    private fun gameOf(game: CalendarGameItem) = TvCalendarGame(
        gameId = game.id, home = game.home, away = game.away, homeBadge = game.homeBadge, awayBadge = game.awayBadge,
        league = game.league, state = game.state.name, homeScore = game.homeScore, awayScore = game.awayScore,
        progress = game.progress, canWatchNow = false,
    )

    fun comingUp(items: List<CalendarComingUpItem>): List<TvCalendarComingUp> = items.map { item ->
        val row = row(CalendarItem(item.title, item.entry, item.date, item.time), "coming-up")
        TvCalendarComingUp(row, item.whenKind.name, item.date.toString(), row.timeText)
    }

    /** The Home row's game cards (live first, then the next days'), after [comingUp]'s posters. */
    fun comingUpGames(games: List<CalendarComingUpGame>, zone: kotlinx.datetime.TimeZone, nowMs: Long): List<TvCalendarComingUp> =
        games.map { entry ->
            val time = calendarTimeAt(entry.game.kickoffMs, zone)
            val item = CalendarItem(entry.game.asTitle(), null, entry.date, time, entry.game)
            val row = row(item, "coming-up", nowMs)
            TvCalendarComingUp(row, entry.whenKind.name, entry.date.toString(), row.timeText)
        }

    fun reminders(card: CalendarReminderCard?, zone: kotlinx.datetime.TimeZone): TvCalendarReminders? = card?.let {
        TvCalendarReminders(
            // The episode's own day and time in the Calendar's zone (device finding: without them the row read
            // "Time not announced" for an episode whose time is known).
            rows = it.items.map { reminder ->
                val at = reminder.entry.airInstantMs
                row(
                    CalendarItem(reminder.title, reminder.entry, reminder.entry.localDate(zone), at?.let { ms -> calendarTimeAt(ms, zone) }),
                    "reminder",
                )
            },
            more = it.more,
            episodeKeys = it.episodeKeys,
        )
    }

    /** "S2 · E5 · Title" for an episode; a film's kind as a Localizable key prefix is handled by the UI. */
    fun episodeLine(kind: CalendarEntryKind?, season: Int?, episode: Int?, title: String?): String? = when (kind) {
        null -> null
        CalendarEntryKind.MOVIE_THEATRICAL -> "calendar_movie_cinema"
        CalendarEntryKind.MOVIE_DIGITAL -> "calendar_movie_digital"
        CalendarEntryKind.EPISODE -> listOfNotNull("S${season ?: 0} · E${episode ?: 0}", title).joinToString(" · ")
    }

    private fun typeOf(title: CalendarTitleRecord): String = if (title.type == CalendarTitleType.MOVIE) "movie" else "series"
}
