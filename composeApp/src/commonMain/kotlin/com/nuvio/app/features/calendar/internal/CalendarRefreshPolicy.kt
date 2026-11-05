// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarRefreshPolicy.kt,
// plus the Tuvora fixes: the open throttle (fix 3) and the run outcome (fix 1).
package com.nuvio.app.features.calendar.internal

import kotlinx.datetime.TimeZone

private val FAILURE_BACKOFF_MS = listOf(1 * CALENDAR_HOUR_MS, 6 * CALENDAR_HOUR_MS, 24 * CALENDAR_HOUR_MS)

/**
 * When a title is asked again: next release within 7 days every 6 hours, within 30 days daily, later or unknown and
 * still running weekly, ended or cancelled monthly. After a failed fetch: 1 hour, then 6 hours, then daily.
 * Plain dates count from midnight in [zone], the Calendar's resolved zone.
 */
fun nextRefreshAtMs(record: CalendarTitleRecord, nowMs: Long, zone: TimeZone): Long {
    if (record.failures > 0) {
        return nowMs + FAILURE_BACKOFF_MS[(record.failures - 1).coerceAtMost(FAILURE_BACKOFF_MS.lastIndex)]
    }
    val next = record.entries.mapNotNull { it.sortInstant(zone) }.filter { it >= nowMs }.minOrNull()
    val wait = when {
        next != null && next < nowMs + 7 * CALENDAR_DAY_MS -> 6 * CALENDAR_HOUR_MS
        next != null && next < nowMs + 30 * CALENDAR_DAY_MS -> 24 * CALENDAR_HOUR_MS
        next == null && record.status.isOver -> 30 * CALENDAR_DAY_MS
        else -> 7 * CALENDAR_DAY_MS
    }
    return nowMs + wait
}

fun CalendarTitleRecord.isDue(nowMs: Long): Boolean = nextRefreshAtMs <= nowMs

/** The signed-in Trakt calendar is asked at most this often. */
const val CALENDAR_TRAKT_REFRESH_MS = 6 * CALENDAR_HOUR_MS

/** Fix 3: Arc refreshed on every Calendar open. Opening now refreshes at most this often. */
const val CALENDAR_OPEN_REFRESH_MIN_MS = 15 * CALENDAR_MINUTE_MS

/** Why a refresh was asked for. Only [CALENDAR_OPENED] and [HOME_SETTLED] are throttled. */
enum class CalendarRefreshReason { HOME_SETTLED, CALENDAR_OPENED, RETRY, SETTINGS_CHANGED, BELL_CHANGED }

/**
 * Fix 3 (open throttle): an open or Home settling runs a refresh only when none has finished in this process yet, the
 * last one failed, or the last one finished at least [CALENDAR_OPEN_REFRESH_MIN_MS] ago. A Retry, a settings change
 * or a bell change always runs. A refresh that runs still asks only for due titles, so even an unthrottled run on an
 * idle slate costs no request — this keeps it from re-reading every followed source on each visit.
 */
fun calendarRefreshDue(
    reason: CalendarRefreshReason,
    lastFinishedAtMs: Long?,
    lastOutcome: CalendarRunOutcome?,
    nowMs: Long,
): Boolean = when (reason) {
    CalendarRefreshReason.RETRY, CalendarRefreshReason.SETTINGS_CHANGED, CalendarRefreshReason.BELL_CHANGED -> true
    CalendarRefreshReason.CALENDAR_OPENED, CalendarRefreshReason.HOME_SETTLED ->
        lastFinishedAtMs == null || lastOutcome == CalendarRunOutcome.FAILED ||
            nowMs - lastFinishedAtMs >= CALENDAR_OPEN_REFRESH_MIN_MS
}

/** How one refresh ended. */
enum class CalendarRunOutcome { LOADED, EMPTY, FAILED }

/**
 * Fix 1: Arc showed "all quiet" when every fetch failed. A run is FAILED when it asked the network at least once and
 * not one answer came back (every due title failed and the Trakt calendar, when asked, failed too). Otherwise it is
 * LOADED when anything is on the Calendar and EMPTY when nothing is.
 */
fun calendarRunOutcome(
    titlesAsked: Int,
    titlesFailed: Int,
    traktAsked: Boolean,
    traktFailed: Boolean,
    hasEntries: Boolean,
): CalendarRunOutcome {
    val asked = titlesAsked + (if (traktAsked) 1 else 0)
    val failed = titlesFailed + (if (traktAsked && traktFailed) 1 else 0)
    return when {
        asked > 0 && failed == asked -> CalendarRunOutcome.FAILED
        hasEntries -> CalendarRunOutcome.LOADED
        else -> CalendarRunOutcome.EMPTY
    }
}
