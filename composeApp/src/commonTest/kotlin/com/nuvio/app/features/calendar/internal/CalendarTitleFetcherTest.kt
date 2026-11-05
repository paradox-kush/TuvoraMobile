// Ported from the Arc fork's CalendarTitleFetcherTest (ysosrs123/NuvioTV-Fork, GPL-3.0); the TheTVDB cases are dropped.
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

class CalendarTitleFetcherTest {
    private val now = at("2026-10-05T23:00:00Z")
    private val remote = FakeCalendarRemote()
    private val fetcher = CalendarTitleFetcher(remote, "GB", TimeZone.UTC)

    private fun show(ids: CalendarIds = CalendarIds(imdb = "tt1")) =
        CalendarTitleRecord(calendarTitleKey(CalendarTitleType.SERIES, ids)!!, CalendarTitleType.SERIES, ids)

    @Test fun tvMazeGivesExactTimesAndTheIdIsKept() = runTest {
        remote.lookups["tt1"] = TvMazeLookup.Found(55, CalendarIds(imdb = "tt1", tvdb = 9))
        remote.shows[55] = CalendarShowDates(
            status = CalendarShowStatus.RUNNING,
            entries = listOf(CalendarEntry(season = 2, episode = 1, airInstantMs = now + 2 * DAY)),
            name = "Show", network = "HBO", ids = CalendarIds(imdb = "tt1", tvdb = 9),
        )
        val outcome = fetcher.refresh(show(), emptyMap(), coveredByTrakt = false, nowMs = now)
        assertEquals(55, outcome.record.tvmazeId)
        assertEquals(9L, outcome.record.ids.tvdb)
        assertEquals("HBO", outcome.record.network)
        assertEquals(listOf(now + 2 * DAY), outcome.record.entries.map { it.airInstantMs })
        assertEquals(now + 6 * HOUR, outcome.record.nextRefreshAtMs, "next release within a week")
        assertEquals(CalendarTvMazeId(55, now), outcome.tvMazeIds["imdb:tt1"])
        assertEquals(0, outcome.record.failures)
    }

    @Test fun aKnownTvMazeIdSkipsTheLookup() = runTest {
        fetcher.refresh(show(), mapOf("imdb:tt1" to CalendarTvMazeId(7, now - DAY)), false, now)
        assertEquals(listOf("tvmaze-show"), remote.calls)
    }

    @Test fun aShowTvMazeDoesNotKnowIsNotLookedUpAgainForAMonth() = runTest {
        val outcome = fetcher.refresh(show(), mapOf("imdb:tt1" to CalendarTvMazeId(null, now - 10 * DAY)), false, now)
        assertTrue("tvmaze-lookup" !in remote.calls)
        assertEquals(listOf("trakt-next", "tmdb-find"), remote.calls)
        assertEquals(0, outcome.record.failures)
    }

    @Test fun traktsNextEpisodeCoversAShowTvMazeDoesNotKnow() = runTest {
        remote.traktNext = { CalendarShowDates(entries = listOf(CalendarEntry(season = 1, episode = 4, airInstantMs = now + DAY, source = CalendarSource.TRAKT_NEXT))) }
        val outcome = fetcher.refresh(show(), emptyMap(), false, now)
        assertEquals(listOf(CalendarSource.TRAKT_NEXT), outcome.record.sources.keys.toList())
        assertEquals(CalendarTvMazeId(null, now), outcome.tvMazeIds["imdb:tt1"])
    }

    @Test fun tmdbDatesWhenNeitherTvMazeNorTraktKnowsTheShow() = runTest {
        remote.tmdbIds["tt1"] = 300
        remote.tmdbShows[300] = CalendarShowDates(
            status = CalendarShowStatus.RUNNING,
            entries = listOf(CalendarEntry(season = 3, episode = 1, airDate = "2026-10-20", source = CalendarSource.TMDB)),
            name = "From TMDB",
        )
        val outcome = fetcher.refresh(show(), emptyMap(), false, now)
        assertEquals(300L, outcome.record.ids.tmdb)
        assertEquals("From TMDB", outcome.record.name)
        assertEquals(listOf("2026-10-20"), outcome.record.entries.map { it.airDate })
    }

    @Test fun anUnreachableTraktKeepsWhatItSaidBefore() = runTest {
        remote.traktUnreachable = true
        val before = show().copy(
            sources = mapOf(CalendarSource.TRAKT_NEXT to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = now + DAY, source = CalendarSource.TRAKT_NEXT))),
        )
        val outcome = fetcher.refresh(before, mapOf("imdb:tt1" to CalendarTvMazeId(null, now)), false, now)
        assertEquals(before.sources, outcome.record.sources)
        assertTrue("tmdb-find" !in remote.calls)
    }

    @Test fun aFailureKeepsTheOldDatesAndBacksOff() = runTest {
        remote.fail = true
        val before = show().copy(
            tvmazeId = 4,
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = now + DAY))),
        )
        val outcome = fetcher.refresh(before, emptyMap(), false, now)
        assertTrue(outcome.failed)
        assertEquals(1, outcome.record.failures)
        assertEquals(before.sources, outcome.record.sources)
        assertEquals(now + HOUR, outcome.record.nextRefreshAtMs)
        val twice = fetcher.refresh(outcome.record, emptyMap(), false, now)
        assertEquals(now + 6 * HOUR, twice.record.nextRefreshAtMs)
    }

    @Test fun aShowTheTraktCalendarCoversIsNotAskedAgain() = runTest {
        val covered = show().copy(poster = "p")
        val outcome = fetcher.refresh(covered, emptyMap(), coveredByTrakt = true, nowMs = now)
        assertTrue(remote.calls.isEmpty())
        assertEquals(0, outcome.record.failures)
    }

    @Test fun filmsTakeTmdbReleaseDates() = runTest {
        remote.tmdbIds["tt9"] = 99
        remote.films[99] = CalendarShowDates(entries = listOf(CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-11-01", source = CalendarSource.TMDB)))
        val film = CalendarTitleRecord("movie:imdb:tt9", CalendarTitleType.MOVIE, CalendarIds(imdb = "tt9"))
        val outcome = fetcher.refresh(film, emptyMap(), false, now)
        assertEquals(listOf("tmdb-find", "tmdb-film"), remote.calls)
        assertEquals(listOf(CalendarEntryKind.MOVIE_DIGITAL), outcome.record.entries.map { it.kind })
    }

    @Test fun tmdbMarksTheFinalesOfTheNextSeason() = runTest {
        remote.shows[55] = CalendarShowDates(
            status = CalendarShowStatus.RUNNING,
            entries = listOf(
                CalendarEntry(season = 2, episode = 1, airInstantMs = now + DAY),
                CalendarEntry(season = 2, episode = 8, airInstantMs = now + 50 * DAY),
            ),
        )
        remote.tmdbSeasons[300L to 2] = listOf(
            CalendarEntry(season = 2, episode = 8, airDate = "2026-11-24", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB),
            CalendarEntry(season = 2, episode = 2, airDate = "2026-10-13", source = CalendarSource.TMDB),
        )
        val record = show(CalendarIds(imdb = "tt1", tmdb = 300)).copy(tvmazeId = 55)
        val outcome = fetcher.refresh(record, emptyMap(), false, now)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, outcome.record.entries.first { it.episode == 8 }.label)
        assertEquals(now + 50 * DAY, outcome.record.entries.first { it.episode == 8 }.airInstantMs)
        assertNull(outcome.record.entries.firstOrNull { it.episode == 2 }, "only finale marks come from the season call")
    }

    @Test fun traktGivesTheTimeForATvMazeDateOnlyEpisode() {
        val dateOnly = CalendarEntry(season = 1, episode = 3, airDate = "2026-10-08")
        val trakt = listOf(CalendarEntry(season = 1, episode = 4, airInstantMs = at("2026-10-09T02:00:00Z"), source = CalendarSource.TRAKT_NEXT))
        val timed = calendarTraktTimeFor(dateOnly, trakt)!!
        assertEquals(3, timed.episode)
        assertEquals(at("2026-10-09T02:00:00Z"), timed.airInstantMs)
        assertNull(calendarTraktTimeFor(dateOnly, listOf(trakt.single().copy(airInstantMs = at("2026-10-12T02:00:00Z")))))
    }
}
