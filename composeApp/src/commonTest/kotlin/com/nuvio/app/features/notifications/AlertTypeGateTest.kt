@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AlertTypeGateTest {
    private val ny = TimeZone.of("America/New_York")
    private val today = LocalDate(2026, 11, 19)
    private val now: Instant = LocalDateTime(2026, 11, 19, 10, 0).toInstant(ny)

    private fun day(offset: Int) = today.plus(DatePeriod(days = offset))

    private fun candidate(offset: Int, show: String, type: AlertType = AlertType.Episode) = AlertCandidate(
        showKey = show,
        showTitle = show.uppercase(),
        episodeKey = "$show-$offset",
        releaseDate = day(offset),
        type = type,
    )

    private fun input(
        candidates: List<AlertCandidate>,
        enabledTypes: Set<AlertType> = AlertType.All,
        allPaused: Boolean = false,
        quiet: QuietHours = QuietHours(),
        opened: Set<LocalDate> = emptySet(),
        cap: AlertCap = AlertCap.PerDay,
        times: AlertTimes = AlertTimes.Default,
    ) = GateInput(
        now = now, zone = ny, candidates = candidates, times = times, quietHours = quiet, cap = cap,
        openedDates = opened, sentDates = emptySet(), ignoredStreak = 0, backOffNoticeShown = false,
        enabledTypes = enabledTypes, allPaused = allPaused,
    )

    // ---- types ----

    @Test
    fun `an unknown type string from sync is ignored and never thrown`() {
        assertNull(AlertType.fromWire("sports"))
        assertNull(AlertType.fromWire("something_new"))
        assertNull(AlertType.fromWire(null))
        assertNull(AlertType.fromWire(""))
        assertEquals(setOf(AlertType.Episode, AlertType.Away), AlertType.parseAll(listOf("episode", "sports", null, "away", "")))
    }

    @Test
    fun `known type strings round trip`() {
        AlertType.entries.forEach { assertEquals(it, AlertType.fromWire(it.wire)) }
    }

    @Test
    fun `a disabled type contributes no candidates`() {
        val candidates = listOf(candidate(1, "a"), candidate(2, "b", AlertType.Away))
        val plan = NotificationGatePolicy.plan(input(candidates, enabledTypes = setOf(AlertType.Episode)))
        assertEquals(1, plan.size)
        assertEquals(listOf("a"), plan.single().candidates.map { it.showKey })
        assertTrue(NotificationGatePolicy.plan(input(candidates, enabledTypes = emptySet())).isEmpty())
    }

    @Test
    fun `episode and away candidates on one day make one notification`() {
        val candidates = listOf(candidate(1, "a"), candidate(1, "b", AlertType.Away))
        val plan = NotificationGatePolicy.plan(input(candidates))
        assertEquals(1, plan.size, "one notification for the day")
        assertEquals(2, plan.single().candidates.size)
    }

    // ---- pause everything ----

    @Test
    fun `all paused plans nothing whatever else is on`() {
        val candidates = listOf(candidate(1, "a"), candidate(2, "b"))
        assertTrue(NotificationGatePolicy.plan(input(candidates, allPaused = true)).isEmpty())
        // The same input unpaused does plan: the pause is the only difference.
        assertEquals(2, NotificationGatePolicy.plan(input(candidates, allPaused = false)).size)
    }

    @Test
    fun `pausing leaves no enabled type`() {
        assertEquals(emptySet(), AlertType.enabled(episodeAlertsOn = true, awayReminderOn = true, allPaused = true))
        assertEquals(setOf(AlertType.Episode, AlertType.Away), AlertType.enabled(true, true, false))
        assertEquals(setOf(AlertType.Away), AlertType.enabled(false, true, false))
        assertEquals(emptySet(), AlertType.enabled(false, false, false))
    }

    // ---- stats ----

    @Test
    fun `stats count the day skipped because the app was opened`() {
        val result = NotificationGatePolicy.planWithStats(input(listOf(candidate(1, "a"), candidate(2, "b")), opened = setOf(day(1))))
        assertEquals(1, result.alerts.size)
        assertEquals(1, result.stats.skippedOpenedToday)
    }

    @Test
    fun `stats count an alert moved to the end of quiet hours`() {
        val late = AlertTimes(weekdayMinute = 23 * 60, weekendMinute = 23 * 60)
        val result = NotificationGatePolicy.planWithStats(input(listOf(candidate(1, "a")), times = late))
        assertEquals(1, result.stats.delayedQuiet)
        val plain = NotificationGatePolicy.planWithStats(input(listOf(candidate(1, "a"))))
        assertEquals(0, plain.stats.delayedQuiet)
    }

    @Test
    fun `stats count a day held back by the cap once`() {
        // Weekly cap of one: the second release day is held until the window has room, then planned on a later day.
        val result = NotificationGatePolicy.planWithStats(
            input(listOf(candidate(1, "a"), candidate(2, "b")), cap = AlertCap.OnePerWeek),
        )
        assertEquals(1, result.stats.capped)
    }
}
