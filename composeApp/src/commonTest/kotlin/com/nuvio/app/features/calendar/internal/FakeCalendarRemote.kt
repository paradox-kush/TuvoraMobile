package com.nuvio.app.features.calendar.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.TimeZone

/** Counts every network call the Calendar makes; [fail] makes each one fail, [hang] holds each one until completed. */
internal class FakeCalendarRemote : CalendarRemoteSources {
    val calls = mutableListOf<String>()
    var fail = false
    var hang: CompletableDeferred<Unit>? = null
    val lookups = mutableMapOf<String, TvMazeLookup.Found>()
    val shows = mutableMapOf<Int, CalendarShowDates>()
    var traktNext: (CalendarIds) -> CalendarShowDates? = { null }
    var traktUnreachable = false
    val tmdbIds = mutableMapOf<String, Long>()
    val tmdbShows = mutableMapOf<Long, CalendarShowDates>()
    val tmdbSeasons = mutableMapOf<Pair<Long, Int>, List<CalendarEntry>>()
    val films = mutableMapOf<Long, CalendarShowDates>()

    private suspend fun call(name: String) {
        calls += name
        hang?.await()
        if (fail) throw CalendarFetchException("down")
    }

    override suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup.Found? {
        call("tvmaze-lookup")
        return ids.imdb?.let { lookups[it] }
    }

    override suspend fun tvMazeShow(id: Int, nowMs: Long, zone: TimeZone): CalendarShowDates {
        call("tvmaze-show")
        return shows[id] ?: CalendarShowDates()
    }

    override suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates? {
        call("trakt-next")
        if (traktUnreachable) throw TraktUnreachable("test")
        return traktNext(ids)
    }

    override suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long? {
        call("tmdb-find")
        return tmdbIds[imdb]
    }

    override suspend fun tmdbShow(tmdbId: Long): CalendarShowDates {
        call("tmdb-show")
        return tmdbShows[tmdbId] ?: CalendarShowDates()
    }

    override suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry> {
        call("tmdb-season")
        return tmdbSeasons[tmdbId to season].orEmpty()
    }

    override suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates {
        call("tmdb-film")
        return films[tmdbId] ?: CalendarShowDates()
    }
}
