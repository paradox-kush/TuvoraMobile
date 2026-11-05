// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarTitleFetcher.kt.
// TheTVDB times are dropped (no key in any Tuvora build); the zone is passed in (fix 4).
package com.nuvio.app.features.calendar.internal

import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil

/** Everything the fetcher asks the network for; the test seam (a fake counts every call). */
interface CalendarRemoteSources {
    suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup.Found?
    suspend fun tvMazeShow(id: Int, nowMs: Long, zone: TimeZone): CalendarShowDates

    /** Null when Trakt does not know the show; throws [TraktUnreachable] when it cannot be reached. */
    suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates?
    suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long?
    suspend fun tmdbShow(tmdbId: Long): CalendarShowDates
    suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry>
    suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates
}

class DefaultCalendarRemoteSources(
    private val tvMaze: TvMazeCalendarClient,
    private val trakt: TraktCalendarClient,
    private val tmdb: TmdbCalendarClient,
) : CalendarRemoteSources {
    override suspend fun tvMazeLookup(ids: CalendarIds) = tvMaze.lookup(ids)
    override suspend fun tvMazeShow(id: Int, nowMs: Long, zone: TimeZone) = tvMaze.show(id, nowMs, zone)
    override suspend fun traktNextEpisode(ids: CalendarIds) = trakt.nextEpisode(ids)
    override suspend fun tmdbFind(imdb: String, type: CalendarTitleType) = tmdb.findTmdbId(imdb, type)
    override suspend fun tmdbShow(tmdbId: Long) = tmdb.show(tmdbId)
    override suspend fun tmdbSeason(tmdbId: Long, season: Int) = tmdb.season(tmdbId, season)
    override suspend fun tmdbFilm(tmdbId: Long, region: String) = tmdb.film(tmdbId, region)
}

data class CalendarFetchOutcome(
    val record: CalendarTitleRecord,
    val tvMazeIds: Map<String, CalendarTvMazeId> = emptyMap(),
) {
    val failed: Boolean get() = record.failures > 0
}

/**
 * Shows: TVmaze first (exact times), then Trakt's next episode for shows TVmaze does not know, then TMDB's plain dates;
 * TMDB's season adds finale marks when a TMDB id is known. A TVmaze date without a time takes Trakt's next-episode time
 * when that fits. Films: TMDB release dates. Any failure keeps the old dates and counts towards the back-off.
 */
class CalendarTitleFetcher(
    private val remote: CalendarRemoteSources,
    private val region: String,
    private val zone: TimeZone,
) {
    suspend fun refresh(
        record: CalendarTitleRecord,
        tvMazeIds: Map<String, CalendarTvMazeId>,
        coveredByTrakt: Boolean,
        nowMs: Long,
    ): CalendarFetchOutcome {
        val learned = mutableMapOf<String, CalendarTvMazeId>()
        val refreshed = try {
            when {
                record.type == CalendarTitleType.MOVIE -> refreshFilm(record, nowMs)
                coveredByTrakt && !record.needsPoster(nowMs) -> record
                else -> refreshShow(record, tvMazeIds, learned, nowMs).withPosterChecked(nowMs)
            }.copy(failures = 0, fetchedAtMs = nowMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CalendarFetchOutcome(failed(record, nowMs))
        }
        return CalendarFetchOutcome(refreshed.copy(nextRefreshAtMs = nextRefreshAtMs(refreshed, nowMs, zone)), learned)
    }

    fun failed(record: CalendarTitleRecord, nowMs: Long): CalendarTitleRecord {
        val counted = record.copy(failures = record.failures + 1)
        return counted.copy(nextRefreshAtMs = nextRefreshAtMs(counted, nowMs, zone))
    }

    private suspend fun refreshFilm(record: CalendarTitleRecord, nowMs: Long): CalendarTitleRecord {
        val withTmdb = withTmdbId(record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        return withTmdb.withSource(CalendarSource.TMDB, remote.tmdbFilm(tmdbId, region).entries, nowMs, zone)
    }

    private suspend fun refreshShow(
        record: CalendarTitleRecord,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        nowMs: Long,
    ): CalendarTitleRecord {
        val tvMazeId = record.tvmazeId ?: resolveTvMazeId(record.ids, known, learned, nowMs)
        if (tvMazeId != null) {
            val dates = remote.tvMazeShow(tvMazeId, nowMs, zone)
            val updated = record.copy(
                tvmazeId = tvMazeId,
                ids = record.ids.merged(dates.ids),
                name = record.name ?: dates.name,
                poster = record.poster ?: dates.poster,
                network = dates.network ?: record.network,
                status = dates.status,
            ).withSource(CalendarSource.TVMAZE, dates.entries, nowMs, zone)
            return withFinales(withTraktTime(updated, nowMs), nowMs)
        }
        val trakt = try {
            remote.traktNextEpisode(record.ids)
        } catch (_: TraktUnreachable) {
            if (!record.sources[CalendarSource.TRAKT_NEXT].isNullOrEmpty()) return record
            null
        }
        if (trakt != null && trakt.entries.isNotEmpty()) {
            return record.withSource(CalendarSource.TRAKT_NEXT, trakt.entries, nowMs, zone)
        }
        val withTmdb = withTmdbId(if (trakt != null) record.withoutSources(CalendarSource.TRAKT_NEXT) else record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        val dates = remote.tmdbShow(tmdbId)
        return withTmdb.copy(
            name = withTmdb.name ?: dates.name,
            poster = withTmdb.poster ?: dates.poster,
            network = dates.network ?: withTmdb.network,
            status = dates.status,
        ).withSource(CalendarSource.TMDB, dates.entries, nowMs, zone)
    }

    /** Trakt's answer is kept for [TRAKT_TIME_RECHECK_MS]; a failed ask keeps the old one. */
    private suspend fun withTraktTime(record: CalendarTitleRecord, nowMs: Long): CalendarTitleRecord {
        val next = record.sources[CalendarSource.TVMAZE].orEmpty()
            .filter { it.kind == CalendarEntryKind.EPISODE && !it.isOut(nowMs) }
            .minByOrNull { it.sortInstant(zone) ?: Long.MAX_VALUE }
        if (next == null || next.timeKnown || next.airDate == null) return record.withoutSources(CalendarSource.TRAKT_NEXT)
        val askedAt = record.sourceFetchedAtMs[CalendarSource.TRAKT_NEXT]
        val kept = record.sources[CalendarSource.TRAKT_NEXT].orEmpty()
        if (askedAt != null && nowMs - askedAt < TRAKT_TIME_RECHECK_MS && kept.any { it.slot == next.slot }) return record
        val trakt = try {
            remote.traktNextEpisode(record.ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return record
        }
        val timed = trakt?.entries?.let { calendarTraktTimeFor(next, it) } ?: return record.withoutSources(CalendarSource.TRAKT_NEXT)
        return record.withSource(CalendarSource.TRAKT_NEXT, listOf(timed), nowMs, zone)
    }

    /** One TMDB season call marks the finales of the season with the next release, when that is within 60 days. */
    private suspend fun withFinales(record: CalendarTitleRecord, nowMs: Long): CalendarTitleRecord {
        val tmdbId = record.ids.tmdb ?: return record
        val next = record.entries.firstOrNull { entry ->
            entry.kind == CalendarEntryKind.EPISODE &&
                entry.sortInstant(zone)?.let { it >= nowMs && it < nowMs + FINALE_LOOKAHEAD_MS } == true
        } ?: return record.withSource(CalendarSource.TMDB, emptyList(), nowMs, zone)
        val season = next.season ?: return record
        val finales = try {
            remote.tmdbSeason(tmdbId, season).filter { it.label != CalendarEpisodeLabel.NONE }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return record
        }
        return record.withSource(CalendarSource.TMDB, finales, nowMs, zone)
    }

    private suspend fun resolveTvMazeId(
        ids: CalendarIds,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        nowMs: Long,
    ): Int? {
        val aliases = tvMazeAliases(ids)
        if (aliases.isEmpty()) return null
        aliases.firstNotNullOfOrNull { known[it]?.id }?.let { return it }
        val missingUntil = nowMs - NOT_FOUND_RECHECK_MS
        if (aliases.all { alias -> known[alias]?.let { it.id == null && it.checkedAtMs > missingUntil } == true }) return null
        val lookup = remote.tvMazeLookup(ids)
        if (lookup == null) {
            aliases.forEach { learned[it] = CalendarTvMazeId(null, nowMs) }
            return null
        }
        tvMazeAliases(ids.merged(lookup.ids)).forEach { learned[it] = CalendarTvMazeId(lookup.id, nowMs) }
        return lookup.id
    }

    /** A show the Trakt calendar covers is still read once a month while it has no poster, for TVmaze's or TMDB's. */
    private fun CalendarTitleRecord.needsPoster(nowMs: Long): Boolean =
        poster.isNullOrBlank() && nowMs - posterCheckedAtMs >= NOT_FOUND_RECHECK_MS

    private fun CalendarTitleRecord.withPosterChecked(nowMs: Long): CalendarTitleRecord =
        if (poster.isNullOrBlank()) copy(posterCheckedAtMs = nowMs) else this

    private fun tvMazeAliases(ids: CalendarIds): List<String> =
        listOfNotNull(ids.imdb?.let { "imdb:$it" }, ids.tvdb?.let { "tvdb:$it" })

    private suspend fun withTmdbId(record: CalendarTitleRecord): CalendarTitleRecord {
        if (record.ids.tmdb != null) return record
        val imdb = record.ids.imdb ?: return record
        val tmdbId = remote.tmdbFind(imdb, record.type) ?: return record
        return record.copy(ids = record.ids.copy(tmdb = tmdbId))
    }

    companion object {
        const val NOT_FOUND_RECHECK_MS = 30 * CALENDAR_DAY_MS
        const val FINALE_LOOKAHEAD_MS = 60 * CALENDAR_DAY_MS
        const val TRAKT_TIME_RECHECK_MS = 6 * CALENDAR_HOUR_MS
    }
}

/**
 * Trakt's timed release for an episode TVmaze only dates: the same episode, or the one out within a day of that date
 * (TVmaze may number it differently), under TVmaze's number so the two merge into one release.
 */
fun calendarTraktTimeFor(dateOnly: CalendarEntry, trakt: List<CalendarEntry>): CalendarEntry? {
    val date = parseCalendarDate(dateOnly.airDate) ?: return null
    val near = trakt.filter { entry ->
        val at = entry.airInstantMs ?: return@filter false
        entry.kind == CalendarEntryKind.EPISODE && abs(date.daysUntil(calendarDateAt(at, TimeZone.UTC))) <= 1
    }
    val match = near.firstOrNull { it.slot == dateOnly.slot } ?: near.singleOrNull() ?: return null
    return match.copy(season = dateOnly.season, episode = dateOnly.episode, source = CalendarSource.TRAKT_NEXT)
}
