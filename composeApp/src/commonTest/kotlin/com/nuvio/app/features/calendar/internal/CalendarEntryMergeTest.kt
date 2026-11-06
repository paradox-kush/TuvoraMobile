// Ported from the Arc fork's CalendarEntryMergeTest (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

class CalendarEntryMergeTest {
    private val now = at("2026-10-05T23:00:00Z")
    private val sixAmUtc = at("2026-10-10T06:00:00Z")
    private val utc = TimeZone.UTC

    @Test fun anExactTimeBeatsAPlainDateFromAHigherSource() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 2, episode = 5, airDate = "2026-10-09", source = CalendarSource.TMDB, title = "From TMDB"),
                CalendarEntry(season = 2, episode = 5, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE),
            ),
        ).single()
        assertEquals(sixAmUtc, merged.airInstantMs)
        assertNull(merged.airDate)
        assertEquals(CalendarSource.TVMAZE, merged.source)
        assertEquals("From TMDB", merged.title)
    }

    @Test fun traktsEpisodeTypeBeatsEveryOtherLabelAndTraktsTimeBeatsTvMazes() {
        val trakt = CalendarEntry(
            season = 3, episode = 10, airInstantMs = sixAmUtc + 60_000, label = CalendarEpisodeLabel.SERIES_FINALE,
            source = CalendarSource.TRAKT,
        )
        val tvMaze = CalendarEntry(season = 3, episode = 10, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE, title = "Last")
        val tmdb = CalendarEntry(season = 3, episode = 10, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
        val merged = mergeCalendarEntries(listOf(tvMaze, tmdb, trakt)).single()
        assertEquals(CalendarEpisodeLabel.SERIES_FINALE, merged.label)
        assertEquals(sixAmUtc + 60_000, merged.airInstantMs)
        assertEquals("Last", merged.title)
    }

    @Test fun aStandardTraktEpisodeTypeOverridesATmdbFinaleMark() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 8, airInstantMs = sixAmUtc, label = CalendarEpisodeLabel.NONE, source = CalendarSource.TRAKT),
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB),
            ),
        ).single()
        assertEquals(CalendarEpisodeLabel.NONE, merged.label)
    }

    @Test fun tmdbFinaleMarksTvMazeEpisodesWithoutChangingTheirTime() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 8, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE),
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB),
            ),
        ).single()
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, merged.label)
        assertEquals(sixAmUtc, merged.airInstantMs)
    }

    @Test fun noSourceWithATimeMeansNoTime() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-11", source = CalendarSource.TMDB),
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-10", source = CalendarSource.TVMAZE),
            ),
        ).single()
        assertNull(merged.airInstantMs)
        assertEquals("2026-10-10", merged.airDate)
        assertEquals(false, merged.timeKnown)
    }

    @Test fun differentReleasesStaySeparateInDateOrder() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 3, airDate = "2026-10-20"),
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-13"),
                CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-15"),
            ),
        )
        assertEquals(listOf("S1E2", "MOVIE_DIGITAL", "S1E3"), merged.map { it.slot })
    }

    @Test fun labels() {
        assertEquals(CalendarEpisodeLabel.NEW_SHOW, deriveEpisodeLabel(1, 1))
        assertEquals(CalendarEpisodeLabel.SEASON_PREMIERE, deriveEpisodeLabel(4, 1))
        assertEquals(CalendarEpisodeLabel.NONE, deriveEpisodeLabel(4, 2))
        assertEquals(CalendarEpisodeLabel.NONE, deriveEpisodeLabel(0, 1))
        assertEquals(CalendarEpisodeLabel.MID_SEASON_FINALE, traktEpisodeLabel("mid_season_finale", 2, 8))
        assertEquals(CalendarEpisodeLabel.NONE, traktEpisodeLabel("standard", 2, 1))
        assertEquals(CalendarEpisodeLabel.SEASON_PREMIERE, traktEpisodeLabel(null, 2, 1))
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, tmdbEpisodeLabel("finale"))
        assertEquals(CalendarEpisodeLabel.MID_SEASON_FINALE, tmdbEpisodeLabel("mid_season"))
        assertEquals(CalendarEpisodeLabel.NONE, tmdbEpisodeLabel("standard"))
    }

    @Test fun aTitleKeepsFifteenDaysBackAYearAheadAndFortyReleasesAcrossAllSources() {
        fun daily(source: CalendarSource, label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE) = (-30..400).map { day ->
            CalendarEntry(season = 1, episode = day + 31, airInstantMs = now + day * DAY, label = label, source = source)
        }
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(
                CalendarSource.TRAKT to daily(CalendarSource.TRAKT),
                CalendarSource.TVMAZE to daily(CalendarSource.TVMAZE),
                CalendarSource.TMDB to daily(CalendarSource.TMDB, CalendarEpisodeLabel.SEASON_FINALE).drop(100),
            ),
        ).bounded(now, utc)
        assertEquals(CALENDAR_MAX_ENTRIES_PER_TITLE, record.entries.size)
        assertEquals(now - 15 * DAY, record.entries.first().airInstantMs)
        assertTrue(record.sources.values.all { it.size <= CALENDAR_MAX_ENTRIES_PER_TITLE })
        assertEquals(setOf(CalendarSource.TRAKT, CalendarSource.TVMAZE), record.sources.keys)
        assertEquals(0, keptCalendarEntries(listOf(CalendarEntry(airDate = "2028-01-01")), now, utc).size)
    }

    @Test fun theTimeFetchedLastWinsWhenTwoSourcesGiveOne() {
        val traktOld = CalendarEntry(season = 1, episode = 5, airInstantMs = sixAmUtc, source = CalendarSource.TRAKT, label = CalendarEpisodeLabel.SEASON_FINALE)
        val tvMazeMoved = CalendarEntry(season = 1, episode = 5, airInstantMs = sixAmUtc + DAY, source = CalendarSource.TVMAZE)
        val merged = mergeCalendarEntries(
            listOf(traktOld, tvMazeMoved),
            mapOf(CalendarSource.TRAKT to 1_000L, CalendarSource.TVMAZE to 2_000L),
        ).single()
        assertEquals(sixAmUtc + DAY, merged.airInstantMs)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, merged.label)
        val traktNewer = mergeCalendarEntries(
            listOf(traktOld, tvMazeMoved),
            mapOf(CalendarSource.TRAKT to 3_000L, CalendarSource.TVMAZE to 2_000L),
        ).single()
        assertEquals(sixAmUtc, traktNewer.airInstantMs)
    }

    @Test fun replacingASourceDropsAnEpisodeThatMoved() {
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = sixAmUtc))),
        ).withSource(CalendarSource.TVMAZE, listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = sixAmUtc + HOUR)), now, utc)
        assertEquals(listOf(sixAmUtc + HOUR), record.entries.map { it.airInstantMs })
        assertEquals(now, record.sourceFetchedAtMs[CalendarSource.TVMAZE])
        assertTrue(record.withSource(CalendarSource.TVMAZE, emptyList(), now, utc).sources.isEmpty())
    }

    @Test fun placeholderEpisodeNamesAreNotShown() {
        assertNull(CalendarEntry(title = "TBA").shownTitle)
        assertNull(CalendarEntry(title = " to be announced ").shownTitle)
        assertEquals("Pilot", CalendarEntry(title = "Pilot").shownTitle)
    }

    @Test fun aPlainDateIsOutOnceItIsOverInUtc() {
        val entry = CalendarEntry(airDate = "2026-10-05")
        assertTrue(!entry.isOut(at("2026-10-05T23:59:59Z")))
        assertTrue(entry.isOut(at("2026-10-06T00:00:00Z")))
        assertTrue(CalendarEntry(airInstantMs = now).isOut(now))
    }
}
