// Ported from the Arc fork's CalendarSectionsTest and ComingUp tests (ysosrs123/NuvioTV-Fork, GPL-3.0), plus the month grid.
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class CalendarSectionsTest {
    private val sydney = TimeZone.of("Australia/Sydney")
    private val newYork = TimeZone.of("America/New_York")
    /** Tue 6 Oct 2026, 10:00 in Sydney (AEDT, two days after the change from AEST). */
    private val now = at("2026-10-05T23:00:00Z")

    private fun title(
        key: String,
        vararg entries: CalendarEntry,
        status: CalendarShowStatus = CalendarShowStatus.RUNNING,
        type: CalendarTitleType = CalendarTitleType.SERIES,
    ) = CalendarTitleRecord(
        key = key, type = type, ids = CalendarIds(imdb = key), name = key, status = status,
        sources = mapOf(CalendarSource.TVMAZE to entries.toList()),
    )

    private fun aired(instant: String, season: Int = 1, episode: Int = 1) =
        CalendarEntry(season = season, episode = episode, airInstantMs = at(instant))

    private fun on(date: String, season: Int = 1, episode: Int = 1) =
        CalendarEntry(season = season, episode = episode, airDate = date, source = CalendarSource.TMDB)

    private fun List<CalendarSection>.dayOf(key: String): LocalDate? = flatMap { it.items }.firstOrNull { it.title.key == key }?.date

    @Test fun aUsNinePmEasternShowLandsOnTheNextDayInSydney() {
        // Mon 5 Oct 21:00 EDT = Tue 6 Oct 01:00 UTC = Tue 6 Oct 12:00 AEDT.
        val entry = aired("2026-10-06T01:00:00Z")
        val today = buildCalendarSections(listOf(title("tt1", entry)), now, sydney).single()
        assertEquals(CalendarSectionKind.TODAY, today.kind)
        assertEquals(LocalDate(2026, 10, 6), today.date)
        assertEquals(LocalTime(12, 0), today.items.single().time)

        val inNewYork = buildCalendarSections(listOf(title("tt1", entry)), now, newYork)
        assertEquals(LocalDate(2026, 10, 5), inNewYork.dayOf("tt1"))
        assertEquals(LocalTime(21, 0), inNewYork.single().items.single().time)
    }

    @Test fun dayBoundariesFollowTheDaylightSavingChangeInSydney() {
        val titles = listOf(
            title("before", aired("2026-10-03T13:59:00Z")), // Sat 3 Oct 23:59 AEST
            title("earlySunday", aired("2026-10-03T15:30:00Z")), // Sun 4 Oct 01:30 AEST
            title("afterChange", aired("2026-10-03T16:30:00Z")), // Sun 4 Oct 03:30 AEDT
            title("lateSunday", aired("2026-10-04T12:59:00Z")), // Sun 4 Oct 23:59 AEDT
            title("mondayStart", aired("2026-10-04T13:30:00Z")), // Mon 5 Oct 00:30 AEDT
        )
        val sections = buildCalendarSections(titles, now, sydney)
        assertEquals(LocalDate(2026, 10, 3), sections.dayOf("before"))
        assertEquals(LocalDate(2026, 10, 4), sections.dayOf("earlySunday"))
        assertEquals(LocalDate(2026, 10, 4), sections.dayOf("afterChange"))
        assertEquals(LocalDate(2026, 10, 4), sections.dayOf("lateSunday"))
        assertEquals(LocalDate(2026, 10, 5), sections.dayOf("mondayStart"))
        val times = sections.first { it.kind == CalendarSectionKind.JUST_AIRED }.items.associate { it.title.key to it.time }
        assertEquals(LocalTime(1, 30), times["earlySunday"])
        assertEquals(LocalTime(3, 30), times["afterChange"])
        assertEquals(LocalTime(0, 30), times["mondayStart"])
    }

    @Test fun justAiredHoldsTheSevenDaysBeforeTodayNewestFirst() {
        val titles = listOf(
            title("tooOld", on("2026-09-28")),
            title("sevenDays", on("2026-09-29")),
            title("yesterday", aired("2026-10-05T09:00:00Z")),
            title("twoDays", aired("2026-10-04T09:00:00Z")),
        )
        val sections = buildCalendarSections(titles, now, sydney)
        assertEquals(listOf(CalendarSectionKind.JUST_AIRED, CalendarSectionKind.NO_DATE), sections.map { it.kind })
        assertEquals(listOf("yesterday", "twoDays", "sevenDays"), sections.first().items.map { it.title.key })
    }

    @Test fun aPlainDateStaysOnItsDateAndHasNoTime() {
        val tomorrow = buildCalendarSections(listOf(title("tt1", on("2026-10-07"))), now, sydney).single()
        assertEquals(CalendarSectionKind.TOMORROW, tomorrow.kind)
        assertNull(tomorrow.items.single().time)
        assertEquals(LocalDate(2026, 10, 7), buildCalendarSections(listOf(title("tt1", on("2026-10-07"))), now, newYork).dayOf("tt1"))
    }

    @Test fun withinADayTimesComeFirstInOrderThenReleasesWithoutATime() {
        val titles = listOf(
            title("noTime", on("2026-10-08")),
            title("evening", aired("2026-10-08T09:30:00Z")), // 20:30
            title("noon", aired("2026-10-08T01:00:00Z")), // 12:00
        )
        val day = buildCalendarSections(titles, now, sydney).single()
        assertEquals(CalendarSectionKind.DAY, day.kind)
        assertEquals(LocalDate(2026, 10, 8), day.date)
        assertEquals(listOf("noon", "evening", "noTime"), day.items.map { it.title.key })
    }

    @Test fun laterHoldsEachTitlesNextReleaseAfterTheWindowAndNoDateHoldsRunningShowsWithNothingDated() {
        val titles = listOf(
            title("inWindow", on("2026-10-15"), on("2026-11-20", episode = 2)),
            title("beyond", on("2026-11-01"), on("2026-11-08", episode = 2), on("2026-11-15", episode = 3)),
            title("tooFar", on("2027-12-01")),
            title("returning"),
            title("inProduction", status = CalendarShowStatus.IN_PRODUCTION),
            title("ended", status = CalendarShowStatus.ENDED),
            title("unknown", status = CalendarShowStatus.UNKNOWN),
            title("aired", on("2026-10-02")),
        )
        val sections = buildCalendarSections(titles, now, sydney, windowDays = 14)
        assertEquals(
            listOf(CalendarSectionKind.JUST_AIRED, CalendarSectionKind.DAY, CalendarSectionKind.LATER, CalendarSectionKind.NO_DATE),
            sections.map { it.kind },
        )
        val later = sections.first { it.kind == CalendarSectionKind.LATER }
        assertEquals(listOf("beyond" to 1, "inWindow" to 2), later.items.map { it.title.key to it.entry!!.episode })
        val noDate = sections.first { it.kind == CalendarSectionKind.NO_DATE }
        assertEquals(listOf("aired", "inProduction", "returning"), noDate.items.map { it.title.key })
        assertTrue(noDate.items.all { it.entry == null })
    }

    @Test fun windowOptionsFallBackToThirtyDays() {
        assertEquals(14, normalizeCalendarWindowDays(14))
        assertEquals(30, normalizeCalendarWindowDays(7))
        assertEquals(30, normalizeCalendarWindowDays(null))
    }

    @Test fun theMonthGridHasWholeWeeksFromMondayWithEachDaysReleases() {
        val titles = listOf(
            title("first", on("2026-10-01")),
            title("today", aired("2026-10-06T01:00:00Z")),
            title("nextMonth", on("2026-11-02")),
        )
        val month = buildCalendarMonth(titles, LocalDate(2026, 10, 17), now, sydney)
        assertEquals(2026, month.year)
        assertEquals(10, month.month)
        assertEquals(5, month.weeks.size) // Mon 28 Sep .. Sun 1 Nov
        assertTrue(month.weeks.all { it.size == 7 })
        assertEquals(LocalDate(2026, 9, 28), month.weeks.first().first().date)
        val days = month.weeks.flatten()
        assertEquals(listOf("first"), days.first { it.date == LocalDate(2026, 10, 1) }.items.map { it.title.key })
        val todayCell = days.single { it.isToday }
        assertEquals(LocalDate(2026, 10, 6), todayCell.date)
        assertEquals(listOf("today"), todayCell.items.map { it.title.key })
        assertTrue(!days.first().inMonth)
        assertEquals(LocalDate(2026, 11, 1), calendarShiftMonth(LocalDate(2026, 10, 31), 1))
        assertEquals(LocalDate(2026, 9, 1), calendarShiftMonth(LocalDate(2026, 10, 31), -1))
    }

    @Test fun comingUpPutsNewUnwatchedFirstThenTheSoonestReleaseOfEachTitle() {
        val titles = listOf(
            title("tt1", aired("2026-10-04T09:00:00Z", episode = 3), aired("2026-10-11T09:00:00Z", episode = 4)),
            title("tt2", aired("2026-10-04T09:00:00Z", episode = 5), aired("2026-10-08T09:00:00Z", episode = 6)),
            title("tt3", aired("2026-10-06T08:00:00Z")), // 19:00 AEDT
            title("tt4", on("2026-10-07")),
            title("tt5", on("2026-10-07")),
            title("tt6", CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-01"), type = CalendarTitleType.MOVIE),
        )
        val watched = CalendarWatchedIndex(CalendarWatchedIndex.episodeKey("tt2", 1, 5))
        val row = buildCalendarComingUp(titles, watched, now, sydney, hiddenKeys = setOf("series:imdb:tt5"))
        assertEquals(listOf("tt1", "tt3", "tt4", "tt2"), row.map { it.title.key }, "new first, then soonest; tt2's new episode is watched")
        assertEquals(CalendarComingUpWhen.NEW, row[0].whenKind)
        assertEquals(CalendarComingUpWhen.TONIGHT, row[1].whenKind)
        assertEquals(CalendarComingUpWhen.TOMORROW, row[2].whenKind)
        assertEquals(CalendarComingUpWhen.WEEKDAY, row[3].whenKind)
        assertEquals(6, row[3].entry.episode)
    }
}
