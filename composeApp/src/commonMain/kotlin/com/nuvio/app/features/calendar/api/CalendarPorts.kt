package com.nuvio.app.features.calendar.api

import kotlinx.coroutines.flow.StateFlow

/*
 * The Calendar's ports: what it reads from the rest of the app. Consumer-owned, neutral value types — never a foreign
 * data type — and implemented ONLY at the composition root (FeatureContributions.kt), which is the one place allowed to
 * name the library / watch-progress / watched / Trakt / TMDB / profile objects (rules doc: "ports, not globals").
 */

enum class CalendarFollowType { SERIES, MOVIE }

/** Why something in the app makes a title followed. */
enum class CalendarFollowOrigin { LIBRARY, PROGRESS, WATCHED, DROPPED }

/** One title something in the app follows. [contentId] is the app's id (`tt…`, `tmdb:…`); the typed ids are extras. */
data class CalendarFollowItem(
    val contentId: String,
    val type: CalendarFollowType,
    val origin: CalendarFollowOrigin,
    val name: String? = null,
    val poster: String? = null,
    val lastActivityMs: Long? = null,
    val imdbId: String? = null,
    val tmdbId: Long? = null,
    val traktId: Long? = null,
)

data class CalendarWatchedEpisode(val contentId: String, val season: Int, val episode: Int)

/** A local read of everything the Calendar follows. No network. */
data class CalendarFollowedRead(
    val items: List<CalendarFollowItem> = emptyList(),
    val watchedEpisodes: List<CalendarWatchedEpisode> = emptyList(),
    val watchedMovies: List<String> = emptyList(),
    /** Content ids removed from Continue Watching. */
    val removedFromContinueWatching: Set<String> = emptySet(),
    /** Sources not loaded yet: the titles they gave last time stay on the Calendar. */
    val unreadOrigins: Set<CalendarFollowOrigin> = emptySet(),
)

fun interface CalendarFollowedSources {
    suspend fun read(): CalendarFollowedRead
}

/** Who is watching and which credentials the Calendar may use. */
interface CalendarAccounts {
    /** The active profile; a change reloads the Calendar for that profile. */
    val activeProfileId: StateFlow<Int>

    /** TMDB key (the viewer's own, else the app's); null or blank → TMDB is skipped. */
    fun tmdbApiKey(): String?

    fun traktSignedIn(): Boolean

    /** Bearer + client headers for the signed-in calendar; null when signed out or the token cannot be refreshed. */
    suspend fun traktAuthorizedHeaders(): Map<String, String>?

    /** Client headers for Trakt's public endpoints. */
    fun traktPublicHeaders(): Map<String, String>

    /** The device's region (ISO 3166-1 alpha-2) for film release dates. */
    fun region(): String
}

/**
 * The ISO 3166-1 alpha-2 region of the first language tag that carries one (`en-GB`, `zh-Hans_CN`), else [fallback].
 * Pure, so the composition root can feed it whatever tags the platform offers.
 */
fun calendarRegionFromLanguageTags(tags: List<String>, fallback: String = "US"): String =
    tags.firstNotNullOfOrNull { tag ->
        tag.split('-', '_').drop(1).lastOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
    } ?: fallback
