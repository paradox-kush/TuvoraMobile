// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarModels.kt.
// Ported to kotlinx-datetime; TheTVDB, add-on dates, art and the tracker-specific origins are dropped.
package com.nuvio.app.features.calendar.internal

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CalendarTitleType { SERIES, MOVIE }

/**
 * The map from the app's content type to what the Calendar can follow; null = it cannot. Pure and Compose-free, so
 * tvosCore (which compiles the calendar tests but not the Compose files) has it.
 */
internal fun calendarTitleTypeFor(type: String): CalendarTitleType? = when (type.lowercase()) {
    "series", "tv", "show", "anime" -> CalendarTitleType.SERIES
    "movie" -> CalendarTitleType.MOVIE
    else -> null
}

@Serializable
data class CalendarIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
    val trakt: Long? = null,
) {
    val isEmpty: Boolean get() = imdb == null && tmdb == null && tvdb == null && trakt == null

    fun merged(other: CalendarIds): CalendarIds = CalendarIds(
        imdb = imdb ?: other.imdb,
        tmdb = tmdb ?: other.tmdb,
        tvdb = tvdb ?: other.tvdb,
        trakt = trakt ?: other.trakt,
    )

    fun aliases(): Set<String> = buildSet {
        imdb?.let { add("imdb:$it") }
        tmdb?.let { add("tmdb:$it") }
        tvdb?.let { add("tvdb:$it") }
        trakt?.let { add("trakt:$it") }
    }

    fun sharesIdWith(other: CalendarIds): Boolean =
        (imdb != null && imdb == other.imdb) || (tmdb != null && tmdb == other.tmdb) ||
            (tvdb != null && tvdb == other.tvdb) || (trakt != null && trakt == other.trakt)

    fun primaryAlias(): String? = imdb?.let { "imdb:$it" } ?: tmdb?.let { "tmdb:$it" }
        ?: tvdb?.let { "tvdb:$it" } ?: trakt?.let { "trakt:$it" }

    companion object {
        /** `tt123`, `tt123:1:2` (an episode id), `tmdb:42`, `tvdb:7`, `trakt:9`; anything else has no usable id. */
        fun fromContentId(raw: String?): CalendarIds {
            val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return CalendarIds()
            if (value.startsWith("tt", ignoreCase = true)) return CalendarIds(imdb = value.substringBefore(':'))
            val id = value.substringAfter(':', "").substringBefore(':').trim()
            return when (value.substringBefore(':').lowercase()) {
                "imdb" -> CalendarIds(imdb = id.takeIf { it.startsWith("tt") })
                "tmdb" -> CalendarIds(tmdb = id.toLongOrNull())
                "tvdb" -> CalendarIds(tvdb = id.toLongOrNull())
                "trakt" -> CalendarIds(trakt = id.toLongOrNull())
                else -> CalendarIds()
            }
        }
    }
}

fun calendarTitleKey(type: CalendarTitleType, ids: CalendarIds): String? =
    ids.primaryAlias()?.let { "${type.name.lowercase()}:$it" }

fun parseCalendarTitleKey(key: String): Pair<CalendarTitleType, CalendarIds>? {
    val type = CalendarTitleType.entries.firstOrNull { key.startsWith("${it.name.lowercase()}:") } ?: return null
    val ids = CalendarIds.fromContentId(key.substringAfter(':'))
    return if (ids.isEmpty) null else type to ids
}

/** True when [key] names this title, also after the title has learned more ids than it had when the key was made. */
fun calendarKeyMatches(key: String, type: CalendarTitleType, ids: CalendarIds): Boolean {
    val (keyType, keyIds) = parseCalendarTitleKey(key) ?: return false
    return keyType == type && keyIds.sharesIdWith(ids)
}

/** Why a title is on the Calendar. [started]: watching began; [savesFilms]: a film from here is followed. */
@Serializable
enum class CalendarOrigin(val started: Boolean = false, val savesFilms: Boolean = false) {
    LIBRARY(savesFilms = true),
    PROGRESS(started = true),
    WATCHED(started = true),
    TRAKT_CALENDAR,
    DROPPED,
    BELL(savesFilms = true),
}

@Serializable
enum class CalendarEntryKind { EPISODE, MOVIE_THEATRICAL, MOVIE_DIGITAL }

@Serializable
enum class CalendarEpisodeLabel {
    NONE,
    NEW_SHOW,
    SEASON_PREMIERE,
    MID_SEASON_PREMIERE,
    MID_SEASON_FINALE,
    SEASON_FINALE,
    SERIES_FINALE,
}

@Serializable
enum class CalendarSource(val priority: Int) {
    TRAKT(0),
    TRAKT_NEXT(1),
    TVMAZE(2),
    TMDB(4);

    val isTrakt: Boolean get() = this == TRAKT || this == TRAKT_NEXT
}

@Serializable
enum class CalendarShowStatus {
    RUNNING,
    IN_PRODUCTION,
    ENDED,
    CANCELLED,
    UNKNOWN;

    val isOver: Boolean get() = this == ENDED || this == CANCELLED
    val mayReturn: Boolean get() = this == RUNNING || this == IN_PRODUCTION
}

/**
 * One dated release: an episode, or a film's cinema or digital date. [airInstantMs] is set only when the source gave an
 * exact time; otherwise [airDate] holds the plain date as given and the time is "not announced".
 */
@Serializable
data class CalendarEntry(
    @SerialName("k") val kind: CalendarEntryKind = CalendarEntryKind.EPISODE,
    @SerialName("s") val season: Int? = null,
    @SerialName("e") val episode: Int? = null,
    @SerialName("t") val title: String? = null,
    @SerialName("at") val airInstantMs: Long? = null,
    @SerialName("d") val airDate: String? = null,
    @SerialName("l") val label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE,
    @SerialName("src") val source: CalendarSource = CalendarSource.TVMAZE,
) {
    val timeKnown: Boolean get() = airInstantMs != null

    val slot: String
        get() = if (kind == CalendarEntryKind.EPISODE) "S${season ?: 0}E${episode ?: 0}" else kind.name

    fun localDate(zone: TimeZone): LocalDate? =
        airInstantMs?.let { calendarDateAt(it, zone) } ?: parseCalendarDate(airDate)

    /** An exact time is out when it airs; a plain date once that date is over in UTC (next day 00:00 UTC). */
    fun isOut(nowMs: Long): Boolean {
        airInstantMs?.let { return it <= nowMs }
        val date = parseCalendarDate(airDate) ?: return false
        return date.plusDays(1).startOfDayMs(TimeZone.UTC) <= nowMs
    }

    /** The episode name, without placeholders such as "TBA". */
    val shownTitle: String?
        get() = title?.trim()?.takeIf { it.isNotEmpty() && it.uppercase() !in PLACEHOLDER_TITLES }

    /**
     * When the release sorts, as epoch ms. A plain date counts from midnight in [zone], so it sorts with the day it is
     * shown under. Deliberately no default zone: every caller passes the Calendar's resolved zone (the viewer's
     * override, else the device's) — Arc defaulted to the system zone, which ignored the override (fix 4).
     */
    fun sortInstant(zone: TimeZone): Long? = airInstantMs ?: parseCalendarDate(airDate)?.startOfDayMs(zone)
}

private val PLACEHOLDER_TITLES = setOf("TBA", "TBD", "TBC", "TBA.", "TBD.", "TO BE ANNOUNCED", "TO BE DETERMINED")

@Serializable
data class CalendarTitleRecord(
    val key: String,
    val type: CalendarTitleType,
    val ids: CalendarIds,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null,
    val origins: Set<CalendarOrigin> = emptySet(),
    val lastActivityMs: Long? = null,
    val tvmazeId: Int? = null,
    val status: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val network: String? = null,
    val sources: Map<CalendarSource, List<CalendarEntry>> = emptyMap(),
    val sourceFetchedAtMs: Map<CalendarSource, Long> = emptyMap(),
    val fetchedAtMs: Long = 0L,
    val nextRefreshAtMs: Long = 0L,
    val failures: Int = 0,
    val posterCheckedAtMs: Long = 0L,
) {
    /** One entry per release (see [mergeCalendarEntries]), in the zone-free storage order. */
    val entries: List<CalendarEntry> by lazy { mergeCalendarEntries(sources.values.flatten(), sourceFetchedAtMs) }

    fun episodeKey(entry: CalendarEntry): String = "$key|${entry.slot}"

    fun matchesKey(key: String): Boolean = key == this.key || calendarKeyMatches(key, type, ids)
}

@Serializable
data class CalendarTvMazeId(val id: Int? = null, val checkedAtMs: Long = 0L)

@Serializable
data class CalendarSnapshot(
    val version: Int = CURRENT_VERSION,
    val titles: List<CalendarTitleRecord> = emptyList(),
    val tvmazeIds: Map<String, CalendarTvMazeId> = emptyMap(),
    val traktCalendarFetchedAtMs: Long = 0L,
    /** Reminder episode keys already shown, with when; pruned after [CALENDAR_SEEN_KEEP_MS]. */
    val seenReminders: Map<String, Long> = emptyMap(),
    val updatedAtMs: Long = 0L,
    /** How the last refresh ended, kept so a title still in failure back-off shows Failed again after a restart. */
    val lastOutcome: CalendarRunOutcome? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
