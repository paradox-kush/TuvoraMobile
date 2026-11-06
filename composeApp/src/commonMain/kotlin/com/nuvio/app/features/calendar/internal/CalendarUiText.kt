package com.nuvio.app.features.calendar.internal

import androidx.compose.runtime.Composable
import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarGameState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun weekdayResource(isoDay: Int): StringResource = when (isoDay) {
    1 -> Res.string.calendar_weekday_1
    2 -> Res.string.calendar_weekday_2
    3 -> Res.string.calendar_weekday_3
    4 -> Res.string.calendar_weekday_4
    5 -> Res.string.calendar_weekday_5
    6 -> Res.string.calendar_weekday_6
    else -> Res.string.calendar_weekday_7
}

internal fun monthResource(month: Int): StringResource = when (month) {
    1 -> Res.string.calendar_month_1
    2 -> Res.string.calendar_month_2
    3 -> Res.string.calendar_month_3
    4 -> Res.string.calendar_month_4
    5 -> Res.string.calendar_month_5
    6 -> Res.string.calendar_month_6
    7 -> Res.string.calendar_month_7
    8 -> Res.string.calendar_month_8
    9 -> Res.string.calendar_month_9
    10 -> Res.string.calendar_month_10
    11 -> Res.string.calendar_month_11
    else -> Res.string.calendar_month_12
}

/** "Tue 6 October". */
@Composable
internal fun calendarDayText(date: LocalDate): String =
    "${stringResource(weekdayResource(date.dayOfWeek.isoDayNumber))} ${date.day} ${stringResource(monthResource(date.month.number))}"

/** 24-hour "21:05"; the time zone is the Calendar's. */
internal fun calendarTimeText(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

@Composable
internal fun calendarSectionTitle(section: CalendarSection): String = when (section.kind) {
    CalendarSectionKind.JUST_AIRED -> stringResource(Res.string.calendar_section_just_aired)
    CalendarSectionKind.TODAY -> stringResource(Res.string.calendar_section_today)
    CalendarSectionKind.TOMORROW -> stringResource(Res.string.calendar_section_tomorrow)
    CalendarSectionKind.DAY -> section.date?.let { calendarDayText(it) }.orEmpty()
    CalendarSectionKind.LATER -> stringResource(Res.string.calendar_section_later)
    CalendarSectionKind.NO_DATE -> stringResource(Res.string.calendar_section_no_date)
}

@Composable
internal fun calendarEntryLine(entry: CalendarEntry?): String? {
    if (entry == null) return null
    return when (entry.kind) {
        CalendarEntryKind.MOVIE_THEATRICAL -> stringResource(Res.string.calendar_movie_cinema)
        CalendarEntryKind.MOVIE_DIGITAL -> stringResource(Res.string.calendar_movie_digital)
        CalendarEntryKind.EPISODE -> {
            val code = stringResource(Res.string.calendar_episode_code, entry.season ?: 0, entry.episode ?: 0)
            listOfNotNull(code, entry.shownTitle).joinToString(" · ")
        }
    }
}

@Composable
internal fun calendarLabelText(label: CalendarEpisodeLabel): String? = when (label) {
    CalendarEpisodeLabel.NONE -> null
    CalendarEpisodeLabel.NEW_SHOW -> stringResource(Res.string.calendar_label_new_show)
    CalendarEpisodeLabel.SEASON_PREMIERE -> stringResource(Res.string.calendar_label_season_premiere)
    CalendarEpisodeLabel.MID_SEASON_PREMIERE -> stringResource(Res.string.calendar_label_mid_season_premiere)
    CalendarEpisodeLabel.MID_SEASON_FINALE -> stringResource(Res.string.calendar_label_mid_season_finale)
    CalendarEpisodeLabel.SEASON_FINALE -> stringResource(Res.string.calendar_label_season_finale)
    CalendarEpisodeLabel.SERIES_FINALE -> stringResource(Res.string.calendar_label_series_finale)
}

@Composable
internal fun calendarComingUpWhenText(item: CalendarComingUpItem): String = when (item.whenKind) {
    CalendarComingUpWhen.NEW -> stringResource(Res.string.calendar_when_new)
    CalendarComingUpWhen.TODAY -> item.time?.let(::calendarTimeText) ?: stringResource(Res.string.calendar_section_today)
    CalendarComingUpWhen.TONIGHT -> stringResource(Res.string.calendar_when_tonight) + (item.time?.let { " " + calendarTimeText(it) } ?: "")
    CalendarComingUpWhen.TOMORROW -> stringResource(Res.string.calendar_section_tomorrow)
    CalendarComingUpWhen.WEEKDAY -> stringResource(weekdayResource(item.date.dayOfWeek.isoDayNumber))
    CalendarComingUpWhen.DATE -> calendarDayText(item.date)
}

@Composable
internal fun calendarFilterText(filter: CalendarFilter): String = when (filter) {
    CalendarFilter.SHOWS -> stringResource(Res.string.calendar_filter_shows)
    CalendarFilter.MOVIES -> stringResource(Res.string.calendar_filter_movies)
    CalendarFilter.SPORTS -> stringResource(Res.string.calendar_filter_sports)
}

/** "LIVE 67'", "2 – 1 · FT", "Postponed", or nothing for a game that has not started. */
@Composable
internal fun calendarGameStatusText(game: CalendarGameItem): String? = when (game.state) {
    CalendarGameState.LIVE -> listOfNotNull(
        stringResource(Res.string.calendar_game_live),
        game.progress?.takeIf { it.isNotBlank() },
        if (game.hasScore) "${game.homeScore} – ${game.awayScore}" else null,
    ).joinToString(" · ")
    CalendarGameState.FINAL ->
        listOfNotNull(if (game.hasScore) "${game.homeScore} – ${game.awayScore}" else null, stringResource(Res.string.calendar_game_final))
            .joinToString(" · ")
    CalendarGameState.POSTPONED -> stringResource(Res.string.calendar_game_postponed)
    CalendarGameState.SCHEDULED -> null
}
