// Ported in spirit from the Arc fork's CalendarUiModelTest (ysosrs123/NuvioTV-Fork, GPL-3.0); the Failed and deadline
// cases are Tuvora's fixes 1 and 2.
package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarScreenModelTest {
    private val now = at("2026-10-05T23:00:00Z")
    private val dated = showRecord("series:imdb:tt1", CalendarEntry(season = 1, episode = 1, airInstantMs = now + DAY))

    private fun ui(state: CalendarState, atMs: Long = now, mode: CalendarViewMode = CalendarViewMode.LIST) =
        calendarScreenUi(state, atMs, mode, null)

    @Test fun everyFetchFailedWithNothingCachedIsAFullPageErrorNotAllQuiet() {
        val body = ui(CalendarState(status = LoadStatus.Failed(timedOut = false), lastOutcome = CalendarRunOutcome.FAILED)).body
        assertEquals(CalendarBody.Failed(timedOut = false), body)
    }

    @Test fun aFailedRefreshOverCachedReleasesKeepsThemWithAnInlineRetry() {
        val shown = ui(CalendarState(titles = listOf(dated), status = LoadStatus.Failed(timedOut = false)))
        assertEquals(CalendarBody.Content, shown.body)
        assertTrue(shown.updateFailed)
    }

    @Test fun nothingDatedAfterAnAnswerIsEmpty() {
        assertEquals(CalendarBody.Empty, ui(CalendarState(status = LoadStatus.Empty, lastOutcome = CalendarRunOutcome.EMPTY)).body)
        assertEquals(CalendarBody.Empty, ui(CalendarState(status = LoadStatus.Idle, lastOutcome = CalendarRunOutcome.EMPTY)).body)
        assertEquals(CalendarBody.Blank, ui(CalendarState(status = LoadStatus.Idle)).body, "no run ended yet: no claim either way")
    }

    @Test fun theLoadingBodyEndsAtItsDeadlineEvenIfTheWorkNeverReports() {
        val loading = BoundedLoad.begin(com.nuvio.app.features.iptv.LoadSurface.CALENDAR, nowMs = now)
        val state = CalendarState(status = loading)
        assertEquals(CalendarBody.Loading(loading.deadlineAtMs), ui(state).body)
        assertEquals(30_000L, calendarNextUiChangeMs(state, now))
        assertEquals(CalendarBody.Failed(timedOut = true), ui(state, atMs = loading.deadlineAtMs).body)
        assertNull(calendarNextUiChangeMs(CalendarState(status = LoadStatus.Loaded), now))
    }

    @Test fun aRefreshOverShownContentIsAProgressLineNotABlockingSpinner() {
        val loading = BoundedLoad.begin(com.nuvio.app.features.iptv.LoadSurface.CALENDAR, nowMs = now)
        val shown = ui(CalendarState(titles = listOf(dated), status = loading, remaining = 3))
        assertEquals(CalendarBody.Content, shown.body)
        assertTrue(shown.refreshing)
        assertFalse(shown.updateFailed)
        assertEquals(3, shown.remaining)
    }

    @Test fun theMonthViewBuildsTheMonthOfTheAnchor() {
        val shown = calendarScreenUi(CalendarState(titles = listOf(dated)), now, CalendarViewMode.MONTH, kotlinx.datetime.LocalDate(2026, 11, 3))
        assertEquals(11, shown.month!!.month)
        assertNull(ui(CalendarState(titles = listOf(dated))).month)
    }
}
