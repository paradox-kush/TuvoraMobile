// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): ui/screens/calendar/CalendarUiModel.kt, with the
// Tuvora Failed state (fix 1) and the deadline backstop (fix 2). Pure: the Calendar screens on every platform render it.
package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadStatus
import kotlinx.datetime.LocalDate

/** What the Calendar page shows as its body. */
sealed interface CalendarBody {
    /** Nothing to show yet and no run has ended: render nothing (no spinner without a deadline). */
    data object Blank : CalendarBody

    /** First load in flight, nothing cached. Ends at [deadlineAtMs] whatever the work does. */
    data class Loading(val deadlineAtMs: Long) : CalendarBody

    /** Nothing followed has a date. Distinct from [Failed]: the sources answered. */
    data object Empty : CalendarBody

    /** Every fetch failed (or the deadline passed) and nothing is cached: full-page error + Retry. */
    data class Failed(val timedOut: Boolean) : CalendarBody

    data object Content : CalendarBody
}

data class CalendarScreenUi(
    val body: CalendarBody,
    val sections: List<CalendarSection>,
    val month: CalendarMonth?,
    /** Content is shown from the cache but the last refresh failed: an inline "Couldn't update · Retry". */
    val updateFailed: Boolean,
    /** A refresh is running over shown content (a thin progress line, never a blocking spinner). */
    val refreshing: Boolean,
    val remaining: Int,
)

/**
 * The page at [nowMs]. The status goes through [BoundedLoad.effectiveAt] first, so a Loading past its deadline is
 * shown as Failed even if the work never reports back (the UI holds its own backstop).
 */
fun calendarScreenUi(
    state: CalendarState,
    nowMs: Long,
    viewMode: CalendarViewMode,
    monthAnchor: LocalDate?,
): CalendarScreenUi {
    val status = BoundedLoad.effectiveAt(state.status, nowMs)
    val filters = state.settings.filters
    val titles = state.titles.filteredBy(filters)
    val games = state.games.filteredBy(filters, nowMs)
    val sections = buildCalendarSections(titles, nowMs, state.zone, state.settings.windowDays, games)
    val hasContent = sections.isNotEmpty()
    val month = if (viewMode == CalendarViewMode.MONTH) {
        buildCalendarMonth(titles, monthAnchor ?: calendarDateAt(nowMs, state.zone), nowMs, state.zone, games = games)
    } else {
        null
    }
    val body = when {
        hasContent -> CalendarBody.Content
        status is LoadStatus.Loading -> CalendarBody.Loading(status.deadlineAtMs)
        status is LoadStatus.Failed -> CalendarBody.Failed(status.timedOut)
        status is LoadStatus.Idle && state.lastOutcome == null -> CalendarBody.Blank
        else -> CalendarBody.Empty
    }
    return CalendarScreenUi(
        body = body,
        sections = sections,
        month = month,
        updateFailed = hasContent && status is LoadStatus.Failed,
        refreshing = hasContent && status is LoadStatus.Loading,
        remaining = state.remaining,
    )
}

/** Millis until the shown status can change by itself (a Loading reaching its deadline); null when it cannot. */
fun calendarNextUiChangeMs(state: CalendarState, nowMs: Long): Long? =
    (state.status as? LoadStatus.Loading)?.let { (it.deadlineAtMs - nowMs).coerceAtLeast(0L) }
