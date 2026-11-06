// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/TvMazeCalendarClient.kt,
// TraktCalendarClient.kt and TmdbCalendarClient.kt. Retrofit is replaced by the [CalendarHttp] seam.
package com.nuvio.app.features.calendar.internal

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.DeserializationStrategy

class CalendarFetchException(message: String) : RuntimeException(message)

data class CalendarHttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** One GET. Throws when the server cannot be reached; any status, 404 and 429 included, comes back as a response. */
fun interface CalendarHttp {
    suspend fun get(url: String, headers: Map<String, String>): CalendarHttpResponse
}

private fun <T> CalendarHttpResponse.foundOrNull(service: String, strategy: DeserializationStrategy<T>): T? = when {
    status in 200..299 -> calendarJson.decodeFromString(strategy, body)
    status == 404 -> null
    else -> throw CalendarFetchException("$service $status")
}

/**
 * A TVmaze lookup answers with a [Found] or null (TVmaze does not know the show). Deliberately not a sealed type with a
 * NotFound object: a singleton answer read back through a suspend call crashed Kotlin/Native (SIGSEGV on
 * iosSimulatorArm64, found by the first run of the Calendar tests).
 */
object TvMazeLookup {
    data class Found(val id: Int, val ids: CalendarIds)
}

data class CalendarShowDates(
    val status: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val entries: List<CalendarEntry> = emptyList(),
    val name: String? = null,
    val network: String? = null,
    val ids: CalendarIds = CalendarIds(),
    val poster: String? = null,
)

class TvMazeCalendarClient(
    private val http: CalendarHttp,
    private val limiter: CalendarRateLimiter,
    private val baseUrl: String = "https://api.tvmaze.com",
) {
    suspend fun lookup(ids: CalendarIds): TvMazeLookup.Found? {
        ids.imdb?.let { imdb ->
            call(LOOKUP_PERMITS, "$baseUrl/lookup/shows?imdb=$imdb").foundOrNull("TVmaze", TvMazeShowDto.serializer())
                ?.let { return it.toLookup() }
        }
        ids.tvdb?.let { tvdb ->
            call(LOOKUP_PERMITS, "$baseUrl/lookup/shows?thetvdb=$tvdb").foundOrNull("TVmaze", TvMazeShowDto.serializer())
                ?.let { return it.toLookup() }
        }
        return null
    }

    /** When the next episode is within [SEASON_FETCH_AHEAD_MS], its whole season is read too. */
    suspend fun show(id: Int, nowMs: Long, zone: TimeZone): CalendarShowDates {
        val show = call(1, "$baseUrl/shows/$id?embed[]=nextepisode&embed[]=previousepisode&embed[]=seasons")
            .foundOrNull("TVmaze", TvMazeShowDto.serializer()) ?: return CalendarShowDates()
        val next = show.embedded?.nextEpisode
        val nextAt = next?.let(::tvMazeEpisodeEntry)?.sortInstant(zone)
        val seasonEpisodes = if (next?.season != null && nextAt != null && nextAt < nowMs + SEASON_FETCH_AHEAD_MS) {
            val seasonId = show.embedded.seasons?.firstOrNull { it.number == next.season }?.id
            seasonId?.let {
                call(1, "$baseUrl/seasons/$it/episodes").foundOrNull("TVmaze", kotlinx.serialization.builtins.ListSerializer(TvMazeEpisodeDto.serializer()))
            }.orEmpty()
        } else {
            emptyList()
        }
        return tvMazeShowDates(show, seasonEpisodes)
    }

    private suspend fun call(permits: Int, url: String): CalendarHttpResponse {
        repeat(MAX_ATTEMPTS) {
            limiter.acquire(permits)
            val response = http.get(url, emptyMap())
            if (response.status != 429) return response
            limiter.pauseFor(parseRetryAfterMs(response.header("Retry-After")) ?: DEFAULT_RETRY_MS)
        }
        throw CalendarFetchException("TVmaze rate limit")
    }

    private fun TvMazeShowDto.toLookup() = TvMazeLookup.Found(
        id,
        CalendarIds(imdb = externals?.imdb?.takeIf { it.startsWith("tt") }, tvdb = externals?.thetvdb),
    )

    companion object {
        /** A lookup answers with a redirect to the show, which is a second request. */
        const val LOOKUP_PERMITS = 2
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_MS = 10_000L
        const val SEASON_FETCH_AHEAD_MS = 60 * CALENDAR_DAY_MS
    }
}

fun tvMazeShowDates(show: TvMazeShowDto, seasonEpisodes: List<TvMazeEpisodeDto>): CalendarShowDates {
    val episodes = listOfNotNull(show.embedded?.previousEpisode, show.embedded?.nextEpisode) + seasonEpisodes
    return CalendarShowDates(
        status = tvMazeStatus(show.status),
        entries = episodes.mapNotNull(::tvMazeEpisodeEntry).distinctBy(CalendarEntry::slot),
        name = show.name,
        network = show.network?.name ?: show.webChannel?.name,
        ids = CalendarIds(imdb = show.externals?.imdb?.takeIf { it.startsWith("tt") }, tvdb = show.externals?.thetvdb),
        poster = show.image?.let { it.medium ?: it.original }?.takeIf(String::isNotBlank)?.replaceFirst("http://", "https://"),
    )
}

/** An episode with an air time keeps its exact `airstamp`; without one only the plain date is kept. */
fun tvMazeEpisodeEntry(episode: TvMazeEpisodeDto): CalendarEntry? {
    val season = episode.season ?: return null
    val number = episode.number ?: return null
    if (episode.type.equals("insignificant_special", ignoreCase = true)) return null
    val instant = episode.airstamp?.takeIf { !episode.airtime.isNullOrBlank() }?.let(::parseCalendarInstantMs)
    val date = episode.airdate?.takeIf { parseCalendarDate(it) != null }
    if (instant == null && date == null) return null
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.name?.takeIf(String::isNotBlank),
        airInstantMs = instant,
        airDate = if (instant == null) date else null,
        label = deriveEpisodeLabel(season, number),
        source = CalendarSource.TVMAZE,
    )
}

fun tvMazeStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "running" -> CalendarShowStatus.RUNNING
    "in development" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    else -> CalendarShowStatus.UNKNOWN
}

data class TraktCalendarItem(
    val ids: CalendarIds,
    val showTitle: String?,
    val status: CalendarShowStatus,
    val entry: CalendarEntry,
)

class TraktUnreachable(why: String) : RuntimeException("Trakt could not be reached: $why")

/**
 * [authorizedHeaders] is null when signed out (or the token cannot be refreshed); [publicHeaders] carries the
 * client id for the unauthenticated next-episode call.
 */
class TraktCalendarClient(
    private val http: CalendarHttp,
    private val authorizedHeaders: suspend () -> Map<String, String>?,
    private val publicHeaders: () -> Map<String, String>,
    private val baseUrl: String = "https://api.trakt.tv",
) {
    /** Today - 7 to today + 58 in two calls (Trakt allows 33 days per call); null when a call failed. */
    suspend fun myShows(todayUtc: LocalDate): List<TraktCalendarItem>? {
        val rows = mutableListOf<TraktCalendarShowDto>()
        for ((start, days) in traktCalendarRanges(todayUtc)) {
            val headers = authorizedHeaders() ?: return null
            val response = runCatching { http.get("$baseUrl/calendars/my/shows/$start/$days?extended=full", headers) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else return null }
            if (response.status !in 200..299) return null
            rows += runCatching {
                calendarJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TraktCalendarShowDto.serializer()), response.body)
            }.getOrElse { return null }
        }
        return traktCalendarItems(rows)
    }

    /**
     * Null when Trakt does not know the show, so the caller moves on to TMDB. Throws [TraktUnreachable] when Trakt
     * cannot be reached, so the caller keeps what Trakt said before.
     */
    suspend fun nextEpisode(ids: CalendarIds): CalendarShowDates? {
        val id = ids.trakt?.toString() ?: ids.imdb ?: return null
        val response = try {
            http.get("$baseUrl/shows/$id/next_episode?extended=full", publicHeaders())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw TraktUnreachable(error::class.simpleName ?: "error")
        }
        if (response.status == 404 || response.status == 204) return null
        if (response.status !in 200..299) throw TraktUnreachable("HTTP ${response.status}")
        if (response.body.isBlank()) return null
        val episode = calendarJson.decodeFromString(TraktCalendarEpisodeDto.serializer(), response.body)
        return CalendarShowDates(entries = listOfNotNull(traktEpisodeEntry(episode, null, CalendarSource.TRAKT_NEXT)))
    }
}

fun traktCalendarRanges(todayUtc: LocalDate): List<Pair<LocalDate, Int>> =
    listOf(todayUtc.plusDays(-7) to TRAKT_CALENDAR_DAYS, todayUtc.plusDays(26) to TRAKT_CALENDAR_DAYS)

private const val TRAKT_CALENDAR_DAYS = 33

fun traktCalendarItems(rows: List<TraktCalendarShowDto>): List<TraktCalendarItem> = rows.mapNotNull { row ->
    val ids = row.show?.ids?.toCalendarIds() ?: return@mapNotNull null
    if (ids.isEmpty) return@mapNotNull null
    val entry = row.episode?.let { traktEpisodeEntry(it, row.firstAired) } ?: return@mapNotNull null
    TraktCalendarItem(ids, row.show.title, traktShowStatus(row.show.status), entry)
}.distinctBy { it.ids.primaryAlias() + "|" + it.entry.slot }

fun traktEpisodeEntry(
    episode: TraktCalendarEpisodeDto,
    rowFirstAired: String?,
    source: CalendarSource = CalendarSource.TRAKT,
): CalendarEntry? {
    val season = episode.season ?: return null
    val number = episode.number ?: return null
    val instant = parseCalendarInstantMs(episode.firstAired ?: rowFirstAired) ?: return null
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.title?.takeIf(String::isNotBlank),
        airInstantMs = instant,
        label = traktEpisodeLabel(episode.episodeType, season, number),
        source = source,
    )
}

fun traktShowStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "returning series", "continuing" -> CalendarShowStatus.RUNNING
    "in production", "planned", "upcoming", "pilot" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    "canceled", "cancelled" -> CalendarShowStatus.CANCELLED
    else -> CalendarShowStatus.UNKNOWN
}

private fun TraktIdsDto.toCalendarIds() = CalendarIds(
    imdb = imdb?.takeIf { it.startsWith("tt") },
    tmdb = tmdb,
    tvdb = tvdb,
    trakt = trakt,
)

fun traktCalendarCandidates(items: List<TraktCalendarItem>): List<CalendarTitleCandidate> =
    items.distinctBy { it.ids.primaryAlias() }.map { item ->
        CalendarTitleCandidate(item.ids, CalendarTitleType.SERIES, CalendarOrigin.TRAKT_CALENDAR, name = item.showTitle)
    }

/**
 * The calendar's releases replace every show's [CalendarSource.TRAKT] dates. Trakt's next episode, asked for a show
 * TVmaze does not know, is kept apart as [CalendarSource.TRAKT_NEXT] and is not touched.
 */
fun applyTraktCalendar(
    records: List<CalendarTitleRecord>,
    items: List<TraktCalendarItem>,
    nowMs: Long,
    zone: TimeZone,
): List<CalendarTitleRecord> = records.map { record ->
    if (record.type != CalendarTitleType.SERIES) return@map record
    val mine = items.filter { it.ids.sharesIdWith(record.ids) }
    if (mine.isEmpty()) return@map record.withoutSources(CalendarSource.TRAKT)
    val status = if (record.status == CalendarShowStatus.UNKNOWN) mine.first().status else record.status
    record.copy(ids = mine.fold(record.ids) { ids, item -> ids.merged(item.ids) }, status = status)
        .withSource(CalendarSource.TRAKT, mine.map(TraktCalendarItem::entry), nowMs, zone)
}

fun CalendarTitleRecord.isCoveredByTrakt(traktCalendarFetchedAtMs: Long, nowMs: Long): Boolean =
    nowMs - traktCalendarFetchedAtMs < CALENDAR_TRAKT_REFRESH_MS * 2 &&
        sources[CalendarSource.TRAKT].orEmpty().any { (it.airInstantMs ?: 0L) > nowMs }

/** [apiKey] null or blank means TMDB is unavailable: every call then fails like an unreachable server. */
class TmdbCalendarClient(
    private val http: CalendarHttp,
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    private fun url(path: String, query: String = ""): String {
        val key = apiKey()?.trim()?.takeIf(String::isNotEmpty) ?: throw CalendarFetchException("TMDB key missing")
        return "$baseUrl$path?api_key=$key$query"
    }

    suspend fun findTmdbId(imdb: String, type: CalendarTitleType): Long? {
        val body = http.get(url("/find/$imdb", "&external_source=imdb_id"), emptyMap())
            .foundOrNull("TMDB", TmdbFindDto.serializer()) ?: return null
        val results = if (type == CalendarTitleType.MOVIE) body.movieResults else body.tvResults
        return results?.firstOrNull()?.id
    }

    suspend fun film(tmdbId: Long, region: String): CalendarShowDates {
        val results = http.get(url("/movie/$tmdbId/release_dates"), emptyMap())
            .foundOrNull("TMDB", TmdbReleaseDatesDto.serializer())?.results.orEmpty()
        return CalendarShowDates(entries = tmdbFilmReleaseEntries(results, region))
    }

    suspend fun show(tmdbId: Long): CalendarShowDates {
        val details = http.get(url("/tv/$tmdbId"), emptyMap()).foundOrNull("TMDB", TmdbTvDetailsDto.serializer())
            ?: return CalendarShowDates()
        return CalendarShowDates(
            status = tmdbShowStatus(details.status),
            entries = listOfNotNull(details.lastEpisodeToAir, details.nextEpisodeToAir).mapNotNull(::tmdbEpisodeEntry),
            name = details.name,
            network = details.networks?.firstOrNull()?.name,
            poster = tmdbPosterUrl(details.posterPath),
        )
    }

    suspend fun season(tmdbId: Long, season: Int): List<CalendarEntry> {
        val body = http.get(url("/tv/$tmdbId/season/$season"), emptyMap()).foundOrNull("TMDB", TmdbSeasonDto.serializer())
            ?: return emptyList()
        return body.episodes.orEmpty().mapNotNull { episode ->
            tmdbEpisodeEntry(episode.copy(seasonNumber = episode.seasonNumber ?: body.seasonNumber ?: season))
        }
    }
}

fun tmdbEpisodeEntry(episode: TmdbEpisodeToAirDto): CalendarEntry? {
    val season = episode.seasonNumber ?: return null
    val number = episode.episodeNumber ?: return null
    val date = episode.airDate?.trim()?.takeIf { parseCalendarDate(it) != null } ?: return null
    val finale = tmdbEpisodeLabel(episode.episodeType)
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.name?.takeIf(String::isNotBlank),
        airDate = date,
        label = if (finale != CalendarEpisodeLabel.NONE) finale else deriveEpisodeLabel(season, number),
        source = CalendarSource.TMDB,
    )
}

fun tmdbPosterUrl(path: String?): String? =
    path?.trim()?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w185$it" }

fun tmdbShowStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "returning series" -> CalendarShowStatus.RUNNING
    "in production", "planned", "pilot" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    "canceled", "cancelled" -> CalendarShowStatus.CANCELLED
    else -> CalendarShowStatus.UNKNOWN
}

/**
 * A film's cinema date (type 3, or 2 when there is no 3) and digital date (type 4), each from [region] when it has
 * one, else from the US. Dates only.
 */
fun tmdbFilmReleaseEntries(results: List<TmdbReleaseCountryDto>, region: String): List<CalendarEntry> {
    fun datesOf(country: String, type: Int): List<String> = results
        .filter { it.iso31661.equals(country, ignoreCase = true) }
        .flatMap { it.releaseDates.orEmpty() }
        .filter { it.type == type }
        .mapNotNull { item -> item.releaseDate?.take(10)?.takeIf { parseCalendarDate(it) != null } }
        .sorted()

    fun firstDate(vararg types: Int): String? {
        for (country in listOf(region, FALLBACK_REGION).distinct()) {
            for (type in types) datesOf(country, type).firstOrNull()?.let { return it }
        }
        return null
    }

    return listOfNotNull(
        firstDate(TYPE_THEATRICAL, TYPE_THEATRICAL_LIMITED)?.let {
            CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = it, source = CalendarSource.TMDB)
        },
        firstDate(TYPE_DIGITAL)?.let {
            CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = it, source = CalendarSource.TMDB)
        },
    )
}

private const val FALLBACK_REGION = "US"
private const val TYPE_THEATRICAL_LIMITED = 2
private const val TYPE_THEATRICAL = 3
private const val TYPE_DIGITAL = 4
