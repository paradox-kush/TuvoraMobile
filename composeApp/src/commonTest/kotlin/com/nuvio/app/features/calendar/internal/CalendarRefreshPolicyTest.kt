// Ported from the Arc fork's CalendarRefreshPolicyTest (ysosrs123/NuvioTV-Fork, GPL-3.0), plus the Tuvora fixes.
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

class CalendarRefreshPolicyTest {
    private val now = at("2026-10-05T23:00:00Z")
    private val utc = TimeZone.UTC

    private fun record(
        nextInMs: Long?,
        status: CalendarShowStatus = CalendarShowStatus.RUNNING,
        failures: Int = 0,
        pastInMs: Long? = -2 * DAY,
    ) = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
        status = status, failures = failures,
        sources = mapOf(
            CalendarSource.TVMAZE to listOfNotNull(
                pastInMs?.let { CalendarEntry(season = 1, episode = 1, airInstantMs = now + it) },
                nextInMs?.let { CalendarEntry(season = 1, episode = 2, airInstantMs = now + it) },
            ),
        ),
    )

    private fun wait(record: CalendarTitleRecord, zone: TimeZone = utc) = nextRefreshAtMs(record, now, zone) - now

    @Test fun nextReleaseWithinAWeekEverySixHours() {
        assertEquals(6 * HOUR, wait(record(6 * DAY)))
        assertEquals(6 * HOUR, wait(record(1 * HOUR)))
    }

    @Test fun nextReleaseWithinAMonthDaily() {
        assertEquals(24 * HOUR, wait(record(7 * DAY)))
        assertEquals(24 * HOUR, wait(record(29 * DAY)))
    }

    @Test fun laterOrUnknownWeeklyWhileRunning() {
        assertEquals(7 * DAY, wait(record(45 * DAY)))
        assertEquals(7 * DAY, wait(record(null)))
        assertEquals(7 * DAY, wait(record(null, CalendarShowStatus.UNKNOWN)))
    }

    @Test fun endedOrCancelledMonthly() {
        assertEquals(30 * DAY, wait(record(null, CalendarShowStatus.ENDED)))
        assertEquals(30 * DAY, wait(record(null, CalendarShowStatus.CANCELLED)))
        assertEquals(6 * HOUR, wait(record(2 * DAY, CalendarShowStatus.ENDED)))
    }

    @Test fun aPlainDateCountsFromMidnightInTheGivenZone() {
        val dated = CalendarTitleRecord(
            key = "k", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TMDB to listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-10-11"))),
        )
        assertEquals(6 * HOUR, wait(dated))
    }

    /**
     * Fix 4 regression: Arc's sortInstant defaulted to the system zone, so a viewer's zone override never reached the
     * refresh cadence. Now + 7 d is 2026-10-12T23:00Z. 2026-10-13 counts from midnight in Kiritimati (UTC+14) =
     * 2026-10-12T10:00Z, inside 7 days (6 h cadence), but from midnight in Pago Pago (UTC-11) = 2026-10-13T11:00Z,
     * outside 7 days (daily). The zone passed in must decide it.
     */
    @Test fun thePlainDateUsesThePassedZoneNotTheDevicesOne() {
        val edge = CalendarTitleRecord(
            key = "k", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TMDB to listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-10-13"))),
        )
        assertEquals(6 * HOUR, wait(edge, TimeZone.of("Pacific/Kiritimati")), "UTC+14: midnight is before now + 7 d")
        assertEquals(24 * HOUR, wait(edge, TimeZone.of("Pacific/Pago_Pago")), "UTC-11: midnight is after now + 7 d")
        val entry = edge.entries.single()
        assertEquals(at("2026-10-12T10:00:00Z"), entry.sortInstant(TimeZone.of("Pacific/Kiritimati")))
        assertEquals(at("2026-10-13T11:00:00Z"), entry.sortInstant(TimeZone.of("Pacific/Pago_Pago")))
    }

    @Test fun theOverrideZoneWinsOverTheDeviceZoneAndAnUnknownIdFallsBack() {
        val device = TimeZone.of("Europe/London")
        assertEquals(TimeZone.of("Asia/Tokyo"), resolveCalendarZone("Asia/Tokyo", device))
        assertEquals(device, resolveCalendarZone(null, device))
        assertEquals(device, resolveCalendarZone("Mars/Olympus", device))
        assertEquals(null, normalizeCalendarZoneOverride("Mars/Olympus"))
    }

    @Test fun failuresBackOffOneSixAndTwentyFourHours() {
        assertEquals(1 * HOUR, wait(record(1 * DAY, failures = 1)))
        assertEquals(6 * HOUR, wait(record(1 * DAY, failures = 2)))
        assertEquals(24 * HOUR, wait(record(1 * DAY, failures = 3)))
        assertEquals(24 * HOUR, wait(record(1 * DAY, failures = 9)))
    }

    @Test fun dueWhenTheRefreshTimeHasCome() {
        assertTrue(record(null).copy(nextRefreshAtMs = 0L).isDue(now))
        assertTrue(record(null).copy(nextRefreshAtMs = now).isDue(now))
        assertFalse(record(null).copy(nextRefreshAtMs = now + 1).isDue(now))
    }

    /** Fix 3 regression: Arc refreshed on every Calendar open. */
    @Test fun openingTheCalendarRefreshesAtMostEveryFifteenMinutes() {
        val open = CalendarRefreshReason.CALENDAR_OPENED
        assertTrue(calendarRefreshDue(open, null, null, now), "never run in this process")
        assertFalse(calendarRefreshDue(open, now - 14 * MINUTE, CalendarRunOutcome.LOADED, now), "ran 14 min ago")
        assertTrue(calendarRefreshDue(open, now - 15 * MINUTE, CalendarRunOutcome.LOADED, now), "ran 15 min ago")
        assertTrue(calendarRefreshDue(open, now - 1 * MINUTE, CalendarRunOutcome.FAILED, now), "the last run failed")
        assertFalse(calendarRefreshDue(CalendarRefreshReason.HOME_SETTLED, now - MINUTE, CalendarRunOutcome.EMPTY, now))
    }

    @Test fun retryAndSettingsAndBellChangesAlwaysRun() {
        listOf(CalendarRefreshReason.RETRY, CalendarRefreshReason.SETTINGS_CHANGED, CalendarRefreshReason.BELL_CHANGED).forEach {
            assertTrue(calendarRefreshDue(it, now, CalendarRunOutcome.LOADED, now), "$it")
        }
    }

    /** Fix 1 regression: Arc showed "all quiet" when every fetch failed. */
    @Test fun aRunWhereEveryFetchFailedIsFailedNeverEmpty() {
        assertEquals(CalendarRunOutcome.FAILED, calendarRunOutcome(3, 3, traktAsked = false, traktFailed = false, hasEntries = false))
        assertEquals(CalendarRunOutcome.FAILED, calendarRunOutcome(3, 3, traktAsked = true, traktFailed = true, hasEntries = true))
        assertEquals(CalendarRunOutcome.FAILED, calendarRunOutcome(0, 0, traktAsked = true, traktFailed = true, hasEntries = false))
    }

    @Test fun anyAnswerMakesTheRunLoadedOrEmpty() {
        assertEquals(CalendarRunOutcome.LOADED, calendarRunOutcome(3, 2, traktAsked = false, traktFailed = false, hasEntries = true))
        assertEquals(CalendarRunOutcome.EMPTY, calendarRunOutcome(3, 2, traktAsked = false, traktFailed = false, hasEntries = false))
        assertEquals(CalendarRunOutcome.LOADED, calendarRunOutcome(3, 3, traktAsked = true, traktFailed = false, hasEntries = true))
        assertEquals(CalendarRunOutcome.EMPTY, calendarRunOutcome(0, 0, traktAsked = false, traktFailed = false, hasEntries = false))
        assertEquals(CalendarRunOutcome.LOADED, calendarRunOutcome(0, 0, traktAsked = false, traktFailed = false, hasEntries = true))
    }
}
