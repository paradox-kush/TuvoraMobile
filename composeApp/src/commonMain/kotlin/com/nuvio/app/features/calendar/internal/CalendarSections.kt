// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarSections.kt (list view) and
// the month grid of ui/screens/calendar/CalendarMonthView.kt, rewritten as a pure model.
package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.calendar.api.CalendarGameItem
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

enum class CalendarSectionKind { JUST_AIRED, TODAY, TOMORROW, DAY, LATER, NO_DATE }

data class CalendarItem(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry?,
    val date: LocalDate?,
    val time: LocalTime?,
    /** Set for a sports game; then [title] is only a stand-in (see [asTitle]) and [entry] is null. */
    val game: CalendarGameItem? = null,
) {
    val episodeKey: String? get() = entry?.let(title::episodeKey)
}

data class CalendarSection(
    val kind: CalendarSectionKind,
    val date: LocalDate?,
    val items: List<CalendarItem>,
)

const val CALENDAR_DEFAULT_WINDOW_DAYS = 30
val CALENDAR_WINDOW_OPTIONS = listOf(14, 30, 60)
private const val JUST_AIRED_DAYS = 7
private const val LATER_MONTHS = 12

fun normalizeCalendarWindowDays(days: Int?): Int = days?.takeIf { it in CALENDAR_WINDOW_OPTIONS } ?: CALENDAR_DEFAULT_WINDOW_DAYS

/** Every dated release of [titles] as items in [zone]; a release whose date has passed but is not out yet shows today. */
fun calendarItems(titles: List<CalendarTitleRecord>, nowMs: Long, zone: TimeZone): List<CalendarItem> {
    val today = calendarDateAt(nowMs, zone)
    return titles.flatMap { title ->
        title.entries.mapNotNull { entry ->
            val given = entry.localDate(zone) ?: return@mapNotNull null
            val date = if (given < today && !entry.isOut(nowMs)) today else given
            val time = entry.airInstantMs?.let { calendarTimeAt(it, zone) }
            CalendarItem(title, entry, date, time)
        }
    }
}

/**
 * Days are counted in [zone] and the window counts today as its first day. A plain date stays on the date as given.
 * Later holds each title's next release after the window; No date yet holds running shows with nothing dated.
 */
fun buildCalendarSections(
    titles: List<CalendarTitleRecord>,
    nowMs: Long,
    zone: TimeZone,
    windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS,
    games: List<CalendarGameItem> = emptyList(),
): List<CalendarSection> {
    val today = calendarDateAt(nowMs, zone)
    val firstDay = today.plusDays(-JUST_AIRED_DAYS)
    val windowEnd = today.plusDays(windowDays.coerceAtLeast(1))
    val laterEnd = today.plusMonths(LATER_MONTHS)

    val dated = mutableListOf<CalendarItem>()
    val later = mutableListOf<CalendarItem>()
    val undated = mutableListOf<CalendarItem>()
    titles.forEach { title ->
        val items = calendarItems(listOf(title), nowMs, zone)
        items.filterTo(dated) { it.date!! >= firstDay && it.date < windowEnd }
        items.filter { it.date!! >= windowEnd && it.date <= laterEnd }.minWithOrNull(itemOrder)?.let(later::add)
        if (title.type == CalendarTitleType.SERIES && title.status.mayReturn && items.none { it.date!! >= today }) {
            undated += CalendarItem(title, null, null, null)
        }
    }

    dated += calendarGameItems(games, nowMs, zone, firstDay, windowEnd)

    val sections = mutableListOf<CalendarSection>()
    val byDay = dated.groupBy { it.date!! }
    byDay.filterKeys { it < today }.entries.sortedByDescending { it.key }.map { it.value }
        .flatMap { day -> day.sortedWith(itemOrder.reversed()) }
        .takeIf { it.isNotEmpty() }
        ?.let { sections += CalendarSection(CalendarSectionKind.JUST_AIRED, null, it) }
    byDay.filterKeys { it >= today }.entries.sortedBy { it.key }.forEach { (day, items) ->
        val kind = when (day) {
            today -> CalendarSectionKind.TODAY
            today.plusDays(1) -> CalendarSectionKind.TOMORROW
            else -> CalendarSectionKind.DAY
        }
        sections += CalendarSection(kind, day, items.sortedWith(itemOrder))
    }
    if (later.isNotEmpty()) sections += CalendarSection(CalendarSectionKind.LATER, null, later.sortedWith(itemOrder))
    if (undated.isNotEmpty()) {
        sections += CalendarSection(CalendarSectionKind.NO_DATE, null, undated.sortedBy { it.title.name.orEmpty().lowercase() })
    }
    return sections
}

internal val itemOrder: Comparator<CalendarItem> =
    compareBy<CalendarItem> { it.date }
        .thenBy { it.time == null }
        .thenBy { it.game?.kickoffMs ?: it.entry?.airInstantMs ?: 0L }
        .thenBy { it.title.name.orEmpty().lowercase() }
        .thenBy { it.entry?.season ?: 0 }
        .thenBy { it.entry?.episode ?: 0 }

data class CalendarMonthDay(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val items: List<CalendarItem>,
)

data class CalendarMonth(
    val year: Int,
    /** 1..12 */
    val month: Int,
    /** Whole weeks, each 7 days starting on [weekStart]; days outside the month are present with inMonth = false. */
    val weeks: List<List<CalendarMonthDay>>,
)

/** The month of [anyDay] as a grid of whole weeks, each day holding its releases in [zone]. */
fun buildCalendarMonth(
    titles: List<CalendarTitleRecord>,
    anyDay: LocalDate,
    nowMs: Long,
    zone: TimeZone,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
    games: List<CalendarGameItem> = emptyList(),
): CalendarMonth {
    val today = calendarDateAt(nowMs, zone)
    val first = LocalDate(anyDay.year, anyDay.month, 1)
    val lead = (first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
    val gridStart = first.plusDays(-lead)
    val nextMonthFirst = first.plusMonths(1)
    val gridEnd = nextMonthFirst.plusDays(7)
    val byDay = (calendarItems(titles, nowMs, zone) + calendarGameItems(games, nowMs, zone, gridStart, gridEnd))
        .filter { it.date != null && it.date >= gridStart && it.date < gridEnd }
        .groupBy { it.date!! }
    val weeks = mutableListOf<List<CalendarMonthDay>>()
    var cursor = gridStart
    while (cursor < nextMonthFirst) {
        weeks += (0 until 7).map { offset ->
            val day = cursor.plusDays(offset)
            CalendarMonthDay(day, day.month == first.month, day == today, byDay[day].orEmpty().sortedWith(itemOrder))
        }
        cursor = cursor.plusDays(7)
    }
    return CalendarMonth(first.year, first.month.number, weeks)
}

/** The first day of the month [months] before (negative) or after [anyDay]'s month. */
fun calendarShiftMonth(anyDay: LocalDate, months: Int): LocalDate = LocalDate(anyDay.year, anyDay.month, 1).plusMonths(months)
