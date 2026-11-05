// Ported from the Arc fork's TvMazeCalendarClientTest, TraktCalendarClientTest and TmdbCalendarTest
// (ysosrs123/NuvioTV-Fork, GPL-3.0). Retrofit fakes become a scripted [CalendarHttp].
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class CalendarClientsTest {
    private val now = at("2026-10-05T23:00:00Z")

    private class ScriptedHttp(private val answer: (String) -> CalendarHttpResponse) : CalendarHttp {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): CalendarHttpResponse {
            urls += url
            return answer(url)
        }
    }

    private fun ok(body: String) = CalendarHttpResponse(200, body)
    private val notFound = CalendarHttpResponse(404, "")

    private val showJson = """
        {"id":55,"name":"Night Shift","status":"Running","network":{"name":"HBO"},"externals":{"imdb":"tt1","thetvdb":9},
         "image":{"medium":"http://static.tvmaze.com/p.jpg"},
         "_embedded":{
           "previousepisode":{"season":2,"number":1,"name":"Back","airdate":"2026-10-01","airtime":"21:00","airstamp":"2026-10-02T01:00:00+00:00"},
           "nextepisode":{"season":2,"number":2,"name":"TBA","airdate":"2026-10-08","airtime":"21:00","airstamp":"2026-10-09T01:00:00+00:00"},
           "seasons":[{"id":700,"number":2}]}}
    """.trimIndent()

    private fun limiter(testNow: () -> Long) = CalendarRateLimiter(nowMs = testNow, sleep = {})

    @Test fun lookupTriesImdbThenTvdb() = runTest {
        val http = ScriptedHttp { url -> if ("thetvdb=9" in url) ok(showJson) else notFound }
        val client = TvMazeCalendarClient(http, limiter { 0L })
        val found = assertNotNull(client.lookup(CalendarIds(imdb = "tt404", tvdb = 9)))
        assertEquals(55, found.id)
        assertEquals(CalendarIds(imdb = "tt1", tvdb = 9), found.ids)
        assertEquals(2, http.urls.size)
        assertNull(TvMazeCalendarClient(ScriptedHttp { notFound }, limiter { 0L }).lookup(CalendarIds(imdb = "tt2")))
    }

    @Test fun aNextEpisodeWithinSixtyDaysReadsItsWholeSeason() = runTest {
        val season = """[{"season":2,"number":2,"airdate":"2026-10-08","airtime":"21:00","airstamp":"2026-10-09T01:00:00+00:00"},
            {"season":2,"number":3,"airdate":"2026-10-15","airtime":"","airstamp":"2026-10-16T01:00:00+00:00"},
            {"season":2,"number":4,"type":"insignificant_special","airdate":"2026-10-20"}]"""
        val http = ScriptedHttp { url -> if ("/seasons/700/episodes" in url) ok(season) else ok(showJson) }
        val dates = TvMazeCalendarClient(http, limiter { 0L }).show(55, now, TimeZone.UTC)
        assertEquals(CalendarShowStatus.RUNNING, dates.status)
        assertEquals("HBO", dates.network)
        assertEquals("https://static.tvmaze.com/p.jpg", dates.poster)
        assertEquals(listOf("S2E1", "S2E2", "S2E3"), dates.entries.map { it.slot })
        assertEquals(at("2026-10-09T01:00:00Z"), dates.entries[1].airInstantMs)
        assertNull(dates.entries[2].airInstantMs, "no air time: only the plain date is kept")
        assertEquals("2026-10-15", dates.entries[2].airDate)
        assertNull(dates.entries[1].shownTitle)
    }

    @Test fun aNextEpisodeFarAwayNeedsNoSeasonCall() = runTest {
        val far = showJson.replace("2026-10-09T01:00:00+00:00", "2027-03-09T01:00:00+00:00")
        val http = ScriptedHttp { ok(far) }
        TvMazeCalendarClient(http, limiter { 0L }).show(55, now, TimeZone.UTC)
        assertEquals(1, http.urls.size)
    }

    @Test fun aRateLimitWaitsForRetryAfterAndTriesAgain() = runTest {
        var first = true
        val http = ScriptedHttp {
            if (first) {
                first = false
                CalendarHttpResponse(429, "", mapOf("retry-after" to "2"))
            } else {
                ok(showJson)
            }
        }
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        val dates = TvMazeCalendarClient(http, limiter).show(55, now - 400 * DAY, TimeZone.UTC)
        assertEquals("Night Shift", dates.name)
        assertEquals(2, http.urls.size)
        assertTrue(testScheduler.currentTime >= 2_000L, "waited for Retry-After")
    }

    @Test fun aServerErrorIsAFailureToRetryLater() = runTest {
        assertFailsWith<CalendarFetchException> {
            TvMazeCalendarClient(ScriptedHttp { CalendarHttpResponse(500, "") }, limiter { 0L }).show(1, now, TimeZone.UTC)
        }
    }

    @Test fun tvMazeStatuses() {
        assertEquals(CalendarShowStatus.ENDED, tvMazeStatus("Ended"))
        assertEquals(CalendarShowStatus.IN_PRODUCTION, tvMazeStatus("In Development"))
        assertEquals(CalendarShowStatus.UNKNOWN, tvMazeStatus("To Be Determined"))
    }

    private val traktRow = """[{"first_aired":"2026-10-09T01:00:00.000Z","episode":{"season":3,"number":10,"title":"Last","episode_type":"series_finale"},
        "show":{"title":"Night Shift","status":"returning series","ids":{"trakt":7,"imdb":"tt1","tmdb":500,"tvdb":42}}}]"""

    @Test fun twoCallsCoverTheWeekBeforeAndTwoMonthsAhead() = runTest {
        val http = ScriptedHttp { ok(traktRow) }
        val items = TraktCalendarClient(http, { mapOf("Authorization" to "Bearer x") }, { emptyMap() })
            .myShows(LocalDate(2026, 10, 5))!!
        assertEquals(2, http.urls.size)
        assertTrue(http.urls[0].contains("/calendars/my/shows/2026-09-28/33"))
        assertTrue(http.urls[1].contains("/calendars/my/shows/2026-10-31/33"))
        val item = items.single()
        assertEquals(CalendarIds(imdb = "tt1", tmdb = 500, tvdb = 42, trakt = 7), item.ids)
        assertEquals(CalendarEpisodeLabel.SERIES_FINALE, item.entry.label)
        assertEquals(CalendarShowStatus.RUNNING, item.status)
    }

    @Test fun aFailedCallGivesNothingRatherThanHalfACalendar() = runTest {
        var calls = 0
        val http = ScriptedHttp { if (calls++ == 0) ok(traktRow) else CalendarHttpResponse(503, "") }
        assertNull(TraktCalendarClient(http, { emptyMap() }, { emptyMap() }).myShows(LocalDate(2026, 10, 5)))
        assertNull(TraktCalendarClient(ScriptedHttp { ok("[]") }, { null }, { emptyMap() }).myShows(LocalDate(2026, 10, 5)), "signed out")
    }

    @Test fun nextEpisodeByImdbIdAndNoneWhenTraktHasNone() = runTest {
        val http = ScriptedHttp { url ->
            if ("/shows/tt1/" in url) ok("""{"season":1,"number":4,"first_aired":"2026-10-10T02:00:00.000Z"}""") else notFound
        }
        val client = TraktCalendarClient(http, { null }, { emptyMap() })
        val next = client.nextEpisode(CalendarIds(imdb = "tt1"))!!.entries.single()
        assertEquals(CalendarSource.TRAKT_NEXT, next.source)
        assertEquals(at("2026-10-10T02:00:00Z"), next.airInstantMs)
        assertNull(client.nextEpisode(CalendarIds(imdb = "tt2")))
        assertNull(client.nextEpisode(CalendarIds(tmdb = 5)), "no id Trakt can read")
    }

    @Test fun traktThatCannotBeReachedSaysSoInsteadOfGivingNoAnswer() = runTest {
        assertFailsWith<TraktUnreachable> {
            TraktCalendarClient(ScriptedHttp { CalendarHttpResponse(502, "") }, { null }, { emptyMap() }).nextEpisode(CalendarIds(imdb = "tt1"))
        }
    }

    @Test fun theCalendarReplacesItsOwnDatesAndLeavesTraktNextEpisodeDates() {
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(
                CalendarSource.TRAKT to listOf(CalendarEntry(season = 3, episode = 9, airInstantMs = now + DAY, source = CalendarSource.TRAKT)),
                CalendarSource.TRAKT_NEXT to listOf(CalendarEntry(season = 3, episode = 9, airInstantMs = now + DAY, source = CalendarSource.TRAKT_NEXT)),
            ),
        )
        val item = TraktCalendarItem(
            CalendarIds(imdb = "tt1", trakt = 7), "Night Shift", CalendarShowStatus.RUNNING,
            CalendarEntry(season = 3, episode = 10, airInstantMs = now + 2 * DAY, source = CalendarSource.TRAKT),
        )
        val updated = applyTraktCalendar(listOf(record), listOf(item), now, TimeZone.UTC).single()
        assertEquals(listOf(10), updated.sources[CalendarSource.TRAKT]!!.map { it.episode })
        assertEquals(listOf(9), updated.sources[CalendarSource.TRAKT_NEXT]!!.map { it.episode })
        assertEquals(7L, updated.ids.trakt)
        assertEquals(CalendarShowStatus.RUNNING, updated.status)
        val gone = applyTraktCalendar(listOf(updated), emptyList(), now, TimeZone.UTC).single()
        assertNull(gone.sources[CalendarSource.TRAKT])
    }

    @Test fun traktStatuses() {
        assertEquals(CalendarShowStatus.CANCELLED, traktShowStatus("canceled"))
        assertEquals(CalendarShowStatus.IN_PRODUCTION, traktShowStatus("planned"))
        assertEquals(CalendarShowStatus.RUNNING, traktShowStatus("continuing"))
    }

    private fun country(code: String, vararg dates: Pair<Int, String>) =
        TmdbReleaseCountryDto(code, dates.map { (type, date) -> TmdbReleaseDateDto(type, "${date}T00:00:00.000Z") })

    @Test fun cinemaFromTheRegionAndDigitalFromTheUsWhenTheRegionHasNone() {
        val entries = tmdbFilmReleaseEntries(
            listOf(country("GB", 3 to "2026-10-20"), country("US", 3 to "2026-10-10", 4 to "2026-11-30")),
            "GB",
        )
        assertEquals(listOf(CalendarEntryKind.MOVIE_THEATRICAL to "2026-10-20", CalendarEntryKind.MOVIE_DIGITAL to "2026-11-30"), entries.map { it.kind to it.airDate })
    }

    @Test fun limitedCinemaReleaseWhenThereIsNoWideOne() {
        assertEquals(listOf("2026-10-02"), tmdbFilmReleaseEntries(listOf(country("US", 2 to "2026-10-02")), "US").map { it.airDate })
        assertEquals(listOf("2026-10-02"), tmdbFilmReleaseEntries(listOf(country("US", 2 to "2026-10-02")), "ZZ").map { it.airDate })
    }

    @Test fun tmdbShowNextAndLastEpisodeAndSeasonTypesAreRead() = runTest {
        val tv = """{"name":"Show","status":"Returning Series","poster_path":"/p.jpg","networks":[{"name":"Apple TV+"}],
            "last_episode_to_air":{"season_number":1,"episode_number":8,"air_date":"2026-09-01","episode_type":"finale"},
            "next_episode_to_air":{"season_number":2,"episode_number":1,"air_date":"2026-10-20"}}"""
        val season = """{"season_number":2,"episodes":[{"episode_number":1,"air_date":"2026-10-20"},{"episode_number":10,"air_date":"2026-12-22","episode_type":"finale"}]}"""
        val http = ScriptedHttp { url -> if ("/season/2" in url) ok(season) else ok(tv) }
        val client = TmdbCalendarClient(http, { "key" })
        val dates = client.show(300)
        assertEquals(CalendarShowStatus.RUNNING, dates.status)
        assertEquals("https://image.tmdb.org/t/p/w185/p.jpg", dates.poster)
        assertEquals(listOf(CalendarEpisodeLabel.SEASON_FINALE, CalendarEpisodeLabel.SEASON_PREMIERE), dates.entries.map { it.label })
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, client.season(300, 2).last().label)
        assertTrue(http.urls.all { "api_key=key" in it })
    }

    @Test fun withoutATmdbKeyEveryTmdbCallFails() = runTest {
        val http = ScriptedHttp { ok("{}") }
        assertFailsWith<CalendarFetchException> { TmdbCalendarClient(http, { null }).show(1) }
        assertTrue(http.urls.isEmpty(), "nothing is sent without a key")
    }
}
