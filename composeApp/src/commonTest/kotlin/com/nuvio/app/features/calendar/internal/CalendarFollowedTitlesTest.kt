// Ported from the Arc fork's CalendarFollowedTitlesTest (ysosrs123/NuvioTV-Fork, GPL-3.0). Arc's tracker-specific
// origins (Simkl, MDBList, Trakt watched/watchlist) map to ours: their items reach the Calendar as library / progress /
// watched items of whichever tracker is the active source.
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarFollowedTitlesTest {
    private val now = at("2026-10-05T23:00:00Z")
    private fun daysAgo(days: Int) = now - days * DAY

    private fun follow(candidates: List<CalendarTitleCandidate>, rules: CalendarFollowRules = CalendarFollowRules()) =
        followCalendarTitles(candidates, rules, now).titles

    private fun show(origin: CalendarOrigin, ids: CalendarIds, atMs: Long? = null, name: String? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.SERIES, origin, atMs, name)

    private fun film(origin: CalendarOrigin, ids: CalendarIds, atMs: Long? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.MOVIE, origin, atMs, "Film")

    @Test fun sourcesSharingAnyIdBecomeOneTitleWithAllIdsAndOrigins() {
        val title = follow(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1"), daysAgo(40), "Night Shift"),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt1", trakt = 7), daysAgo(3)),
                show(CalendarOrigin.PROGRESS, CalendarIds(trakt = 7, tmdb = 500), daysAgo(10)),
                show(CalendarOrigin.TRAKT_CALENDAR, CalendarIds(tmdb = 500, tvdb = 42)),
            ),
        ).single()
        assertEquals(CalendarIds(imdb = "tt1", tmdb = 500, tvdb = 42, trakt = 7), title.ids)
        assertEquals(
            setOf(CalendarOrigin.LIBRARY, CalendarOrigin.WATCHED, CalendarOrigin.PROGRESS, CalendarOrigin.TRAKT_CALENDAR),
            title.origins,
        )
        assertEquals(daysAgo(3), title.lastActivityMs)
        assertEquals("Night Shift", title.name)
        assertEquals("series:imdb:tt1", title.key)
        assertEquals("tt1", title.contentId)
    }

    @Test fun aFilmAndAShowWithTheSameTmdbNumberStayApart() {
        assertEquals(2, follow(listOf(show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1)), film(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1)))).size)
    }

    @Test fun startedShowsUntouchedForAYearAreLeftOutUnlessTheBellIsOn() {
        val old = show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1"), daysAgo(400))
        val oldWithBell = show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt2"), daysAgo(400))
        val recent = show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt3"), daysAgo(300))
        val oldButInTheLibrary = listOf(
            show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt4"), daysAgo(500)),
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4"), daysAgo(20)),
        )
        val savedLongAgo = show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt5"), daysAgo(900))
        val keys = follow(
            listOf(old, oldWithBell, recent, savedLongAgo) + oldButInTheLibrary,
            CalendarFollowRules(bellOn = setOf("series:imdb:tt2")),
        ).map { it.key }.toSet()
        assertEquals(setOf("series:imdb:tt2", "series:imdb:tt3", "series:imdb:tt4", "series:imdb:tt5"), keys)
    }

    @Test fun droppedRemovedFromContinueWatchingAndHiddenTitlesAreLeftOut() {
        val titles = follow(
            listOf(
                show(CalendarOrigin.PROGRESS, CalendarIds(trakt = 1, imdb = "tt1")),
                show(CalendarOrigin.DROPPED, CalendarIds(trakt = 1)),
                show(CalendarOrigin.PROGRESS, CalendarIds(tmdb = 2)),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt3")),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4")),
            ),
            CalendarFollowRules(removedFromContinueWatching = setOf("tmdb:2"), hiddenTitleKeys = setOf("series:imdb:tt3")),
        )
        assertEquals(listOf("series:imdb:tt4"), titles.map { it.key })
    }

    @Test fun aHiddenTitleStaysHiddenWhenItsKeyUsesAnotherOfItsIds() {
        assertTrue(
            follow(
                listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt9", tmdb = 9))),
                CalendarFollowRules(hiddenTitleKeys = setOf("series:tmdb:9")),
            ).isEmpty(),
        )
    }

    @Test fun filmsCountWhenSavedNotWhenOnlyWatched() {
        val titles = follow(listOf(film(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1")), film(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt2"))))
        assertEquals(listOf("movie:imdb:tt2"), titles.map { it.key })
    }

    @Test fun aBellOnATitleNoSourceHasStillFollowsIt() {
        val title = follow(emptyList(), CalendarFollowRules(bellOn = setOf("series:tmdb:77"))).single()
        assertTrue(title.bellOn)
        assertEquals(CalendarIds(tmdb = 77), title.ids)
        assertEquals(setOf(CalendarOrigin.BELL), title.origins)
    }

    @Test fun capKeepsBellTitlesAndTheMostRecentlyActive() {
        val candidates = (1..10).map { show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt$it"), daysAgo(it)) } +
            show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt99"), daysAgo(200))
        val titles = follow(candidates, CalendarFollowRules(bellOn = setOf("series:imdb:tt99"), cap = 4))
        assertEquals(listOf("series:imdb:tt99", "series:imdb:tt1", "series:imdb:tt2", "series:imdb:tt3"), titles.map { it.key })
    }

    @Test fun overTheCapTheLibraryComesBeforeMoreRecentlyWatchedShows() {
        val candidates = (1..5).map { show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt$it"), daysAgo(it)) } +
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt50"), daysAgo(500)) +
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt60"), daysAgo(600))
        val titles = follow(candidates, CalendarFollowRules(cap = 3))
        assertEquals(listOf("series:imdb:tt50", "series:imdb:tt60", "series:imdb:tt1"), titles.map { it.key })
    }

    @Test fun capOfThreeHundredByDefault() {
        val titles = follow((1..450).map { show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = it.toLong()), daysAgo(it)) })
        assertEquals(CALENDAR_MAX_TITLES, titles.size)
        assertEquals("series:tmdb:1", titles.first().key)
    }

    @Test fun reconcileKeepsTheStoredKeyAndDatesAndDropsTitlesNoLongerFollowed() {
        val stored = listOf(
            CalendarTitleRecord(
                key = "series:tmdb:5", type = CalendarTitleType.SERIES, ids = CalendarIds(tmdb = 5),
                tvmazeId = 12, nextRefreshAtMs = 1234L, status = CalendarShowStatus.RUNNING,
            ),
            CalendarTitleRecord(key = "series:imdb:tt8", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt8")),
        )
        val followed = follow(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt5", tmdb = 5), name = "Five"), show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt6"))),
        )
        val records = reconcileCalendarRecords(followed, stored)
        val five = records.first { it.ids.tmdb == 5L }
        assertEquals("series:tmdb:5", five.key)
        assertEquals(12, five.tvmazeId)
        assertEquals(1234L, five.nextRefreshAtMs)
        assertEquals("tt5", five.ids.imdb)
        assertEquals("Five", five.name)
        assertEquals(0L, records.first { it.ids.imdb == "tt6" }.nextRefreshAtMs)
        assertFalse(records.any { it.key == "series:imdb:tt8" })
    }

    @Test fun titleKeysParseBackAndMatchByAnySharedId() {
        assertEquals(CalendarTitleType.SERIES to CalendarIds(imdb = "tt1"), parseCalendarTitleKey("series:imdb:tt1"))
        assertEquals(CalendarTitleType.MOVIE to CalendarIds(tmdb = 3), parseCalendarTitleKey("movie:tmdb:3"))
        assertNull(parseCalendarTitleKey("anime:tmdb:3"))
        assertNull(parseCalendarTitleKey("series:title:x"))
        assertTrue(calendarKeyMatches("series:tmdb:3", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 3)))
        assertFalse(calendarKeyMatches("movie:tmdb:3", CalendarTitleType.SERIES, CalendarIds(tmdb = 3)))
    }

    @Test fun contentIdsOfEveryShapeGiveTheirIds() {
        assertEquals(CalendarIds(imdb = "tt7"), CalendarIds.fromContentId("tt7:1:2"))
        assertEquals(CalendarIds(tmdb = 42), CalendarIds.fromContentId("tmdb:42"))
        assertEquals(CalendarIds(trakt = 9), CalendarIds.fromContentId("trakt:9"))
        assertTrue(CalendarIds.fromContentId("kitsu:1").isEmpty)
        assertTrue(CalendarIds.fromContentId("  ").isEmpty)
    }

    @Test fun everyTitleLeftOutIsReportedWithItsReason() {
        val result = followCalendarTitles(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1"), daysAgo(1), "Hidden One"),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt2"), daysAgo(2), "Dropped One"),
                show(CalendarOrigin.DROPPED, CalendarIds(imdb = "tt2")),
                show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt3"), daysAgo(3), "Removed One"),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt4"), daysAgo(400), "Idle One"),
                film(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt5"), daysAgo(5)),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt6"), daysAgo(6), "Kept"),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt7"), daysAgo(7), "Past The Cap"),
            ),
            CalendarFollowRules(hiddenTitleKeys = setOf("series:imdb:tt1"), removedFromContinueWatching = setOf("tt3"), cap = 1),
            now,
        )
        assertEquals(listOf("series:imdb:tt6"), result.titles.map { it.key })
        assertEquals(
            mapOf(
                "series:imdb:tt1" to CalendarLeftOutReason.HIDDEN,
                "series:imdb:tt2" to CalendarLeftOutReason.DROPPED,
                "series:imdb:tt3" to CalendarLeftOutReason.REMOVED_FROM_CONTINUE_WATCHING,
                "series:imdb:tt4" to CalendarLeftOutReason.IDLE,
                "movie:imdb:tt5" to CalendarLeftOutReason.FILM_NOT_SAVED,
                "series:imdb:tt7" to CalendarLeftOutReason.OVER_CAP,
            ),
            result.leftOut.associate { it.key to it.reason },
        )
        assertEquals("Dropped One", result.leftOut.first { it.key == "series:imdb:tt2" }.name)
    }

    @Test fun titlesFromASourceThatWasNotReadComeBackAsCandidates() {
        val stored = listOf(
            CalendarTitleRecord(
                "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", trakt = 4), name = "Tulsa",
                origins = setOf(CalendarOrigin.WATCHED, CalendarOrigin.TRAKT_CALENDAR), lastActivityMs = daysAgo(30),
            ),
            CalendarTitleRecord("series:imdb:tt2", CalendarTitleType.SERIES, CalendarIds(imdb = "tt2"), origins = setOf(CalendarOrigin.PROGRESS)),
        )
        assertTrue(carriedCalendarCandidates(stored, emptySet()).isEmpty())
        val carried = carriedCalendarCandidates(stored, setOf(CalendarOrigin.WATCHED, CalendarOrigin.LIBRARY))
        assertEquals(1, carried.size)
        assertEquals(CalendarOrigin.WATCHED, carried.single().origin)
        assertEquals(daysAgo(30), carried.single().lastActivityMs)
        assertEquals("Tulsa", carried.single().name)

        val hidden = followCalendarTitles(carried, CalendarFollowRules(hiddenTitleKeys = setOf("series:imdb:tt1")), now)
        assertTrue(hidden.titles.isEmpty())
        assertEquals(CalendarLeftOutReason.HIDDEN, hidden.leftOut.single().reason)
    }

    @Test fun startedShowsWithNoDateCountAsIdleUnlessSavedOrBelled() {
        val result = followCalendarTitles(
            listOf(
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt1"), name = "Imported"),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt2")),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt2")),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt3")),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4")),
            ),
            CalendarFollowRules(bellOn = setOf("series:imdb:tt3")),
            now,
        )
        assertEquals(setOf("series:imdb:tt2", "series:imdb:tt3", "series:imdb:tt4"), result.titles.map { it.key }.toSet())
        assertEquals(CalendarLeftOut("series:imdb:tt1", "Imported", CalendarLeftOutReason.IDLE), result.leftOut.single())
    }

    @Test fun aShowInTheLibraryIsNeverIdle() {
        val result = followCalendarTitles(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt9"), daysAgo(900), "Back After Two Years"),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt9"), daysAgo(700)),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt8"), daysAgo(700)),
            ),
            CalendarFollowRules(),
            now,
        )
        assertEquals(listOf("Back After Two Years"), result.titles.map { it.name })
        assertEquals(listOf("series:imdb:tt8" to CalendarLeftOutReason.IDLE), result.leftOut.map { it.key to it.reason })
    }
}
