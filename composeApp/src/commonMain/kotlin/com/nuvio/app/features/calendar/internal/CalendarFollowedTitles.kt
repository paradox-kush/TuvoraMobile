// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarFollowedTitles.kt.
package com.nuvio.app.features.calendar.internal

data class CalendarTitleCandidate(
    val ids: CalendarIds,
    val type: CalendarTitleType,
    val origin: CalendarOrigin,
    val lastActivityMs: Long? = null,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null,
)

data class CalendarFollowedTitle(
    val key: String,
    val ids: CalendarIds,
    val type: CalendarTitleType,
    val origins: Set<CalendarOrigin>,
    val lastActivityMs: Long?,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null,
    val bellOn: Boolean = false,
)

const val CALENDAR_MAX_TITLES = 300
const val CALENDAR_STALE_AFTER_MS = 365 * CALENDAR_DAY_MS

data class CalendarFollowRules(
    val hiddenTitleKeys: Set<String> = emptySet(),
    val bellOn: Set<String> = emptySet(),
    /** Content ids the viewer removed from Continue Watching; such a show is not followed unless its bell is on. */
    val removedFromContinueWatching: Set<String> = emptySet(),
    val staleAfterMs: Long = CALENDAR_STALE_AFTER_MS,
    val cap: Int = CALENDAR_MAX_TITLES,
)

enum class CalendarLeftOutReason { HIDDEN, DROPPED, REMOVED_FROM_CONTINUE_WATCHING, IDLE, FILM_NOT_SAVED, OVER_CAP }

data class CalendarLeftOut(val key: String, val name: String?, val reason: CalendarLeftOutReason)

data class CalendarFollowResult(
    val titles: List<CalendarFollowedTitle>,
    val leftOut: List<CalendarLeftOut> = emptyList(),
)

/**
 * Hidden titles are always left out. Unless the bell is on, so are dropped titles, titles removed from Continue
 * Watching, started shows idle for [CalendarFollowRules.staleAfterMs] (or with no date and not saved anywhere) and
 * films that were only watched, not saved. A show in the library is never idle, and over the cap titles with the bell
 * on go first, then the library, then the most recently active.
 */
fun followCalendarTitles(
    candidates: List<CalendarTitleCandidate>,
    rules: CalendarFollowRules,
    nowMs: Long,
): CalendarFollowResult {
    val usable = candidates.filter { !it.ids.isEmpty }
    val bellOnly = rules.bellOn.mapNotNull { key ->
        val (type, ids) = parseCalendarTitleKey(key) ?: return@mapNotNull null
        CalendarTitleCandidate(ids, type, CalendarOrigin.BELL)
    }
    val groups = groupBySharedIds(usable + bellOnly)
    val removedAliases = rules.removedFromContinueWatching.flatMapTo(mutableSetOf()) { CalendarIds.fromContentId(it).aliases() }
    val staleBefore = nowMs - rules.staleAfterMs
    val leftOut = mutableListOf<CalendarLeftOut>()

    val titles = groups.mapNotNull { group ->
        val type = group.first().type
        val ids = group.fold(CalendarIds()) { acc, candidate -> acc.merged(candidate.ids) }
        val key = calendarTitleKey(type, ids) ?: return@mapNotNull null
        val named = group.sortedBy { it.origin.ordinal }
        val name = named.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
        val bell = rules.bellOn.any { calendarKeyMatches(it, type, ids) }
        val origins = group.mapTo(linkedSetOf()) { it.origin }.apply { if (bell) add(CalendarOrigin.BELL) }
        val lastActivity = group.mapNotNull { it.lastActivityMs }.maxOrNull()
        val reason = when {
            rules.hiddenTitleKeys.any { calendarKeyMatches(it, type, ids) } -> CalendarLeftOutReason.HIDDEN
            bell -> null
            CalendarOrigin.DROPPED in origins -> CalendarLeftOutReason.DROPPED
            type == CalendarTitleType.SERIES && ids.aliases().any { it in removedAliases } ->
                CalendarLeftOutReason.REMOVED_FROM_CONTINUE_WATCHING
            CalendarOrigin.LIBRARY !in origins && origins.any { it.started } &&
                (lastActivity?.let { it < staleBefore } ?: origins.none { it.savesFilms }) -> CalendarLeftOutReason.IDLE
            type == CalendarTitleType.MOVIE && origins.none { it.savesFilms } -> CalendarLeftOutReason.FILM_NOT_SAVED
            else -> null
        }
        if (reason != null) {
            leftOut += CalendarLeftOut(key, name, reason)
            return@mapNotNull null
        }
        CalendarFollowedTitle(
            key = key,
            ids = ids,
            type = type,
            origins = origins,
            lastActivityMs = lastActivity,
            name = name,
            contentId = ids.imdb ?: named.firstNotNullOfOrNull { it.contentId?.takeIf(String::isNotBlank) }
                ?: ids.tmdb?.let { "tmdb:$it" },
            poster = named.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) },
            bellOn = bell,
        )
    }

    val ordered = titles.sortedWith(
        compareByDescending<CalendarFollowedTitle> { it.bellOn }
            .thenByDescending { CalendarOrigin.LIBRARY in it.origins }
            .thenByDescending { it.lastActivityMs ?: 0L }
            .thenBy { it.name.orEmpty().lowercase() }
            .thenBy { it.key },
    )
    val bells = ordered.count { it.bellOn }
    val room = (rules.cap - bells).coerceAtLeast(0)
    val others = ordered.filterNot { it.bellOn }
    others.drop(room).forEach { leftOut += CalendarLeftOut(it.key, it.name, CalendarLeftOutReason.OVER_CAP) }
    return CalendarFollowResult(ordered.filter { it.bellOn } + others.take(room), leftOut)
}

/**
 * Titles the Calendar already holds from sources that could not be read this time, as candidates again, so a slow or
 * not yet loaded source does not take them off the Calendar. The follow rules still apply to them.
 */
fun carriedCalendarCandidates(
    stored: List<CalendarTitleRecord>,
    unreadOrigins: Set<CalendarOrigin>,
): List<CalendarTitleCandidate> {
    if (unreadOrigins.isEmpty()) return emptyList()
    return stored.flatMap { record ->
        record.origins.filter { it in unreadOrigins }.map { origin ->
            CalendarTitleCandidate(record.ids, record.type, origin, record.lastActivityMs, record.name, record.contentId, record.poster)
        }
    }
}

private fun groupBySharedIds(candidates: List<CalendarTitleCandidate>): List<List<CalendarTitleCandidate>> {
    val parent = IntArray(candidates.size) { it }
    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }
    val owner = HashMap<String, Int>()
    candidates.forEachIndexed { index, candidate ->
        candidate.ids.aliases().forEach { alias ->
            val key = "${candidate.type}|$alias"
            val other = owner[key]
            if (other == null) {
                owner[key] = index
            } else {
                val a = find(index)
                val b = find(other)
                if (a != b) parent[maxOf(a, b)] = minOf(a, b)
            }
        }
    }
    return candidates.indices.groupBy(::find).values.map { members -> members.map { candidates[it] } }
}

/** A followed title keeps its stored record (and key) when they share an id; a new one is due at once. */
fun reconcileCalendarRecords(
    followed: List<CalendarFollowedTitle>,
    stored: List<CalendarTitleRecord>,
): List<CalendarTitleRecord> {
    val used = BooleanArray(stored.size)
    return followed.map { title ->
        val index = stored.indices.firstOrNull { i ->
            !used[i] && stored[i].type == title.type && stored[i].ids.sharesIdWith(title.ids)
        }
        val previous = index?.let {
            used[it] = true
            stored[it]
        }
        previous?.copy(
            ids = title.ids.merged(previous.ids),
            name = title.name ?: previous.name,
            contentId = title.contentId ?: previous.contentId,
            poster = title.poster ?: previous.poster,
            origins = title.origins,
            lastActivityMs = title.lastActivityMs,
        ) ?: CalendarTitleRecord(
            key = title.key,
            type = title.type,
            ids = title.ids,
            name = title.name,
            contentId = title.contentId,
            poster = title.poster,
            origins = title.origins,
            lastActivityMs = title.lastActivityMs,
        )
    }
}
