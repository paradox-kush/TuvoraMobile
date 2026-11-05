package com.tuvora.tvos.screens

import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarGameState
import com.nuvio.app.features.calendar.internal.CalendarEntry
import com.nuvio.app.features.calendar.internal.CalendarFilter
import com.nuvio.app.features.calendar.internal.CalendarEpisodeLabel
import com.nuvio.app.features.calendar.internal.CalendarEntryKind
import com.nuvio.app.features.calendar.internal.CalendarIds
import com.nuvio.app.features.calendar.internal.CalendarReminder
import com.nuvio.app.features.calendar.internal.CalendarReminderCard
import com.nuvio.app.features.calendar.internal.CalendarRunOutcome
import com.nuvio.app.features.calendar.internal.CalendarSettings
import com.nuvio.app.features.calendar.internal.CalendarSource
import com.nuvio.app.features.calendar.internal.CalendarState
import com.nuvio.app.features.calendar.internal.CalendarTitleRecord
import com.nuvio.app.features.calendar.internal.CalendarTitleType
import com.nuvio.app.features.calendar.internal.CalendarViewMode
import com.nuvio.app.features.calendar.internal.parseCalendarInstantMs
import com.nuvio.app.features.iptv.LoadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

class TvCalendarPresentationTest {
    private val now = parseCalendarInstantMs("2026-10-05T23:00:00Z")!!
    private val show = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"), name = "Night Shift",
        network = "HBO",
        sources = mapOf(
            CalendarSource.TVMAZE to listOf(
                CalendarEntry(season = 2, episode = 1, title = "Back", airInstantMs = parseCalendarInstantMs("2026-10-06T01:00:00Z"), label = CalendarEpisodeLabel.SEASON_PREMIERE),
            ),
        ),
    )

    @Test fun allFailedWithNothingCachedIsTheFailedPageOnAppleTvToo() {
        val screen = TvCalendarPresentation.screen(
            CalendarState(status = LoadStatus.Failed(timedOut = false), lastOutcome = CalendarRunOutcome.FAILED, zone = TimeZone.UTC),
            now,
            null,
        )
        assertEquals(TvCalendarBodyKind.Failed, screen.body)
        assertTrue(screen.sections.isEmpty())
    }

    @Test fun aReleaseBecomesARowWithItsDayAndTimeInTheCalendarsZone() {
        val screen = TvCalendarPresentation.screen(CalendarState(titles = listOf(show), zone = TimeZone.of("Australia/Sydney")), now, null)
        assertEquals(TvCalendarBodyKind.Content, screen.body)
        val section = screen.sections.single()
        assertEquals("TODAY", section.kind)
        val row = section.rows.single()
        assertEquals("tt1", row.contentId)
        assertEquals("series", row.type)
        assertEquals("S2 · E1 · Back", row.episodeLine)
        assertEquals("2026-10-06", row.dateIso)
        assertEquals("12:00", row.timeText)
        assertEquals("calendar_label_season_premiere", row.labelKey)
        assertEquals("HBO", row.network)
    }

    @Test fun theMonthViewHasWholeWeeksAndFlagsToday() {
        val state = CalendarState(titles = listOf(show), settings = CalendarSettings(viewMode = CalendarViewMode.MONTH), zone = TimeZone.UTC)
        val screen = TvCalendarPresentation.screen(state, now, null)
        assertTrue(screen.monthView)
        assertEquals(10, screen.monthNumber)
        assertTrue(screen.weeks.all { it.size == 7 })
        assertEquals("2026-10-05", screen.weeks.flatten().single { it.isToday }.dateIso)
        assertEquals(1, screen.weeks.flatten().single { it.dateIso == "2026-10-06" }.rows.size)
    }

    @Test fun filmLinesNameTheirReleaseKindForTheUiToLocalize() {
        assertEquals("calendar_movie_digital", TvCalendarPresentation.episodeLine(CalendarEntryKind.MOVIE_DIGITAL, null, null, null))
        assertEquals("S1 · E3", TvCalendarPresentation.episodeLine(CalendarEntryKind.EPISODE, 1, 3, null))
    }

    private val game = CalendarGameItem(
        id = "g1", league = "Premier League", home = "Arsenal", away = "Chelsea",
        kickoffMs = parseCalendarInstantMs("2026-10-06T01:00:00Z")!!, state = CalendarGameState.LIVE,
        homeScore = "2", awayScore = "1", progress = "67'",
    )

    @Test fun aSportsGameBecomesARowWithBothSidesScoreAndWatchNow() {
        val screen = TvCalendarPresentation.screen(
            CalendarState(games = listOf(game), zone = TimeZone.of("Australia/Sydney"), settings = CalendarSettings()), now, null,
        )
        val row = screen.sections.single().rows.single()
        val g = row.game!!
        assertEquals("g1", g.gameId)
        assertEquals("Arsenal", g.home)
        assertEquals("LIVE", g.state)
        assertEquals("2", g.homeScore)
        assertEquals("67'", g.progress)
        assertTrue(g.canWatchNow)
        assertEquals("12:00", row.timeText)
        assertEquals("Arsenal v Chelsea", row.name)
        assertEquals(null, row.episodeLine)
    }

    @Test fun theFilterFlagsAndTheSportsFilterReachTheScreen() {
        val onlyShows = CalendarSettings(filters = setOf(CalendarFilter.SHOWS))
        val screen = TvCalendarPresentation.screen(CalendarState(titles = listOf(show), games = listOf(game), settings = onlyShows, zone = TimeZone.UTC), now, null)
        assertTrue(screen.showShows)
        assertTrue(!screen.showSports)
        assertTrue(screen.sportsChip, "the viewer can turn Sports back on")
        assertTrue(screen.sections.flatMap { it.rows }.none { it.game != null })
    }

    /** Device finding (Apple TV Home): a reminder row said "Time not announced" for an episode whose time is known. */
    @Test fun aReminderRowCarriesTheEpisodesDayAndTimeInTheCalendarsZone() {
        val entry = show.sources.getValue(CalendarSource.TVMAZE).single()
        val card = CalendarReminderCard(listOf(CalendarReminder(show, entry, dueAtMs = now)), more = 0, episodeKeys = listOf("k"))
        val row = TvCalendarPresentation.reminders(card, TimeZone.of("Australia/Sydney"))!!.rows.single()
        assertEquals("2026-10-06", row.dateIso)
        assertEquals("12:00", row.timeText, "the known air time, not 'Time not announced'")
    }

    @Test fun aReminderForADateOnlyEpisodeHasItsDayButNoTime() {
        val dated = CalendarEntry(season = 2, episode = 2, airDate = "2026-10-12")
        val card = CalendarReminderCard(listOf(CalendarReminder(show, dated, dueAtMs = now)), more = 0, episodeKeys = listOf("k"))
        val row = TvCalendarPresentation.reminders(card, TimeZone.UTC)!!.rows.single()
        assertEquals("2026-10-12", row.dateIso)
        assertEquals(null, row.timeText)
    }
}
