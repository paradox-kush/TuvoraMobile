// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarReminders.kt and CalendarHome.kt.
package com.nuvio.app.features.calendar.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Watched episodes and films, by every id of the title: `imdb:tt1|1|2`, `tmdb:5|movie`. */
class CalendarWatchedIndex(private val keys: Set<String> = emptySet()) {
    fun isWatched(title: CalendarTitleRecord, entry: CalendarEntry): Boolean {
        if (keys.isEmpty()) return false
        val suffix = when (entry.kind) {
            CalendarEntryKind.EPISODE -> "|${entry.season ?: return false}|${entry.episode ?: return false}"
            else -> "|movie"
        }
        return title.ids.aliases().any { "$it$suffix" in keys }
    }

    companion object {
        fun episodeKey(contentId: String, season: Int, episode: Int): Set<String> =
            CalendarIds.fromContentId(contentId).aliases().mapTo(hashSetOf()) { "$it|$season|$episode" }

        fun movieKey(contentId: String): Set<String> =
            CalendarIds.fromContentId(contentId).aliases().mapTo(hashSetOf()) { "$it|movie" }
    }
}

data class CalendarReminder(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry,
    val dueAtMs: Long,
) {
    val episodeKey: String get() = title.episodeKey(entry)
}

data class CalendarDueReminders(val items: List<CalendarReminder> = emptyList()) {
    val count: Int get() = items.size

    fun moreThan(shown: Int): Int = (count - shown).coerceAtLeast(0)
}

private const val REMINDER_DELAY_MS = CALENDAR_HOUR_MS
private const val REMINDER_MAX_AGE_MS = 14 * CALENDAR_DAY_MS

/** A shown reminder stays deduplicated for this long (per profile), then its key is forgotten. */
const val CALENDAR_SEEN_KEEP_MS = 30 * CALENDAR_DAY_MS

/**
 * An exact time is due an hour after it airs. A plain date is due at 01:00 UTC the next day, when that date has
 * begun everywhere.
 */
fun calendarReminderDueAtMs(entry: CalendarEntry): Long? {
    entry.airInstantMs?.let { return it + REMINDER_DELAY_MS }
    val date = parseCalendarDate(entry.airDate) ?: return null
    return date.plusDays(1).startOfDayMs(TimeZone.UTC) + REMINDER_DELAY_MS
}

/** Bell titles only, due within the last 14 days, newest first. A cinema date gives no reminder. */
fun dueReminders(
    titles: List<CalendarTitleRecord>,
    bells: Set<String>,
    seenEpisodeKeys: Set<String>,
    isWatched: (CalendarTitleRecord, CalendarEntry) -> Boolean,
    nowMs: Long,
): CalendarDueReminders {
    val oldest = nowMs - REMINDER_MAX_AGE_MS
    val items = titles
        .filter { title -> bells.any(title::matchesKey) }
        .flatMap { title ->
            title.entries.mapNotNull { entry ->
                if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@mapNotNull null
                val dueAt = calendarReminderDueAtMs(entry) ?: return@mapNotNull null
                if (dueAt > nowMs || dueAt < oldest) return@mapNotNull null
                if (title.episodeKey(entry) in seenEpisodeKeys || isWatched(title, entry)) return@mapNotNull null
                CalendarReminder(title, entry, dueAt)
            }
        }
        .sortedWith(compareByDescending<CalendarReminder> { it.dueAtMs }.thenBy { it.title.name.orEmpty().lowercase() })
    return CalendarDueReminders(items)
}

/** Everything that became due since the last check is one corner message; each reminder is announced once. */
class CalendarReminderBatcher {
    private val lock = SynchronizedObject()
    private val announced = mutableSetOf<String>()

    fun nextBatch(due: CalendarDueReminders): List<CalendarReminder> = synchronized(lock) {
        val batch = due.items.filter { it.episodeKey !in announced }
        batch.mapTo(announced) { it.episodeKey }
        batch
    }

    fun reset() = synchronized(lock) { announced.clear() }
}

/** Marks [episodeKeys] seen at [nowMs] and forgets keys seen more than 30 days ago. */
fun Map<String, Long>.withSeen(episodeKeys: Collection<String>, nowMs: Long): Map<String, Long> =
    prunedSeen(nowMs) + episodeKeys.associateWith { nowMs }

fun Map<String, Long>.prunedSeen(nowMs: Long): Map<String, Long> = filterValues { nowMs - it < CALENDAR_SEEN_KEEP_MS }

/** The next moment a bell title's release becomes due, or null when nothing is waiting. */
fun calendarNextReminderAtMs(
    titles: List<CalendarTitleRecord>,
    bells: Set<String>,
    seenEpisodeKeys: Set<String>,
    nowMs: Long,
): Long? = titles
    .filter { title -> bells.any(title::matchesKey) }
    .flatMap { title ->
        title.entries.mapNotNull { entry ->
            if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@mapNotNull null
            val dueAt = calendarReminderDueAtMs(entry) ?: return@mapNotNull null
            dueAt.takeIf { it > nowMs && title.episodeKey(entry) !in seenEpisodeKeys }
        }
    }
    .minOrNull()

private const val REMINDER_CHECK_MIN_MS = CALENDAR_MINUTE_MS
private const val REMINDER_CHECK_MAX_MS = 6 * CALENDAR_HOUR_MS

/**
 * How long the foreground reminder check sleeps: until the next release is due, never sooner than a minute, never
 * later than 6 hours. The loop runs only while the app is on screen (lifecycle-bound) and reads local data only.
 */
fun calendarReminderCheckDelayMs(nextMs: Long?, nowMs: Long): Long {
    val wait = nextMs?.let { it - nowMs } ?: return REMINDER_CHECK_MAX_MS
    return wait.coerceIn(REMINDER_CHECK_MIN_MS, REMINDER_CHECK_MAX_MS)
}

/** One card for everything due: up to [shown] rows, the rest counted. Every key is marked seen when dismissed. */
data class CalendarReminderCard(
    val items: List<CalendarReminder>,
    val more: Int,
    val episodeKeys: List<String>,
) {
    val single: Boolean get() = items.size == 1 && more == 0
    val total: Int get() = items.size + more
}

fun calendarReminderCard(due: CalendarDueReminders, shown: Int = 3): CalendarReminderCard? {
    if (due.count == 0) return null
    return CalendarReminderCard(due.items.take(shown), due.moreThan(shown), due.items.map { it.episodeKey })
}

enum class CalendarComingUpWhen { NEW, TODAY, TONIGHT, TOMORROW, WEEKDAY, DATE }

data class CalendarComingUpItem(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry,
    val whenKind: CalendarComingUpWhen,
    val date: LocalDate,
    val time: LocalTime?,
)

const val CALENDAR_COMING_UP_MAX = 15
private const val NEW_DAYS = 7
private const val WEEKDAY_DAYS = 6
private val TONIGHT_FROM = LocalTime(17, 0)

/**
 * The Home row: one card per title. Anything out in the last 7 days and not watched comes first, newest first; then
 * the soonest release of every other title. A cinema date is only listed before it opens.
 */
fun buildCalendarComingUp(
    titles: List<CalendarTitleRecord>,
    watched: CalendarWatchedIndex?,
    nowMs: Long,
    zone: TimeZone,
    hiddenKeys: Set<String> = emptySet(),
    max: Int = CALENDAR_COMING_UP_MAX,
): List<CalendarComingUpItem> {
    val today = calendarDateAt(nowMs, zone)
    val oldest = today.plusDays(-NEW_DAYS)
    val fresh = mutableListOf<CalendarComingUpItem>()
    val upcoming = mutableListOf<CalendarComingUpItem>()
    titles.filterNot { title -> hiddenKeys.any(title::matchesKey) }.forEach { title ->
        var newestOut: CalendarComingUpItem? = null
        var soonest: CalendarComingUpItem? = null
        title.entries.forEach { entry ->
            val date = entry.localDate(zone) ?: return@forEach
            val time = entry.airInstantMs?.let { calendarTimeAt(it, zone) }
            if (entry.isOut(nowMs)) {
                if (date < oldest || entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@forEach
                if (watched?.isWatched(title, entry) == true) return@forEach
                val item = CalendarComingUpItem(title, entry, CalendarComingUpWhen.NEW, date, time)
                if (newestOut == null || comingUpOrder.compare(item, newestOut) > 0) newestOut = item
            } else {
                val shownDate = maxOf(date, today)
                val item = CalendarComingUpItem(title, entry, comingUpWhen(shownDate, time, today), shownDate, time)
                if (soonest == null || comingUpOrder.compare(item, soonest) < 0) soonest = item
            }
        }
        newestOut?.let(fresh::add) ?: soonest?.let(upcoming::add)
    }
    return (fresh.sortedWith(comingUpOrder.reversed()) + upcoming.sortedWith(comingUpOrder)).take(max)
}

private fun comingUpWhen(date: LocalDate, time: LocalTime?, today: LocalDate): CalendarComingUpWhen = when {
    date == today -> if (time != null && time >= TONIGHT_FROM) CalendarComingUpWhen.TONIGHT else CalendarComingUpWhen.TODAY
    date == today.plusDays(1) -> CalendarComingUpWhen.TOMORROW
    date <= today.plusDays(WEEKDAY_DAYS) -> CalendarComingUpWhen.WEEKDAY
    else -> CalendarComingUpWhen.DATE
}

private val comingUpOrder: Comparator<CalendarComingUpItem> =
    compareBy<CalendarComingUpItem> { it.date }
        .thenBy { it.time == null }
        .thenBy { it.time }
        .thenBy { it.title.name.orEmpty().lowercase() }
        .thenBy { it.entry.season ?: 0 }
        .thenBy { it.entry.episode ?: 0 }

/** Ids to open a title by, best first. */
fun calendarContentIds(title: CalendarTitleRecord): List<String> =
    listOfNotNull(title.contentId, title.ids.imdb, title.ids.tmdb?.let { "tmdb:$it" }).distinct()
