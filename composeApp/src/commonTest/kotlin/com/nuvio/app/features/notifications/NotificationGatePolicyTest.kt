@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationGatePolicyTest {
    private val ny = TimeZone.of("America/New_York")

    // Thursday 2026-11-19, 10:00 in New York.
    private val today = LocalDate(2026, 11, 19)
    private val now = local(today, 10, 0)

    private fun local(date: LocalDate, hour: Int, minute: Int = 0, zone: TimeZone = ny): Instant =
        LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute).toInstant(zone)

    private fun day(offset: Int): LocalDate = today.plus(DatePeriod(days = offset))

    private fun candidate(
        offset: Int,
        show: String = "a",
        episode: String = "$show-$offset",
    ) = AlertCandidate(showKey = show, showTitle = show.uppercase(), episodeKey = episode, releaseDate = day(offset))

    private fun input(
        candidates: List<AlertCandidate>,
        times: AlertTimes = AlertTimes.Default,
        quiet: QuietHours = QuietHours(),
        cap: AlertCap = AlertCap.PerDay,
        opened: Set<LocalDate> = emptySet(),
        sent: Set<LocalDate> = emptySet(),
        streak: Int = 0,
        noticeShown: Boolean = false,
        now: Instant = this.now,
        zone: TimeZone = ny,
        horizon: Int = 14,
    ) = GateInput(now, zone, candidates, times, quiet, cap, opened, sent, streak, noticeShown, horizon)

    private fun plan(input: GateInput) = NotificationGatePolicy.plan(input)

    private fun minute(h: Int, m: Int = 0) = h * 60 + m

    // ---- quiet hours ----

    @Test
    fun `midnight spanning quiet hours cover both sides of midnight`() {
        val quiet = QuietHours()
        assertFalse(quiet.contains(minute(21, 59)), "21:59")
        assertTrue(quiet.contains(minute(22)), "22:00 is quiet")
        assertTrue(quiet.contains(minute(23, 59)), "23:59")
        assertTrue(quiet.contains(0), "00:00")
        assertTrue(quiet.contains(minute(7, 59)), "07:59")
        assertFalse(quiet.contains(minute(8)), "08:00 is not quiet")
    }

    @Test
    fun `same day quiet hours are start inclusive and end exclusive`() {
        val quiet = QuietHours(startMinute = minute(13), endMinute = minute(15))
        assertFalse(quiet.contains(minute(12, 59)))
        assertTrue(quiet.contains(minute(13)))
        assertTrue(quiet.contains(minute(14, 59)))
        assertFalse(quiet.contains(minute(15)))
    }

    @Test
    fun `quiet hours switched off block nothing`() {
        assertFalse(QuietHours(enabled = false).contains(minute(23)))
        assertFalse(QuietHours(enabled = false).isActive)
    }

    @Test
    fun `quiet hours with equal start and end block nothing`() {
        val quiet = QuietHours(startMinute = minute(8), endMinute = minute(8))
        assertFalse(quiet.isActive)
        for (m in listOf(0, minute(8), minute(12), minute(23, 59))) assertFalse(quiet.contains(m), "minute $m")
    }

    @Test
    fun `an evening time moves to the next morning`() {
        val planned = plan(input(listOf(candidate(1)), times = AlertTimes(minute(23), minute(23))))
        assertEquals(1, planned.size)
        assertEquals(local(day(2), 8), planned.single().fireAt, "released Fri 23:00 goes out Sat 08:00")
        assertEquals(day(2), planned.single().fireDate)
    }

    @Test
    fun `an early morning time moves to the end the same day`() {
        val planned = plan(input(listOf(candidate(1)), times = AlertTimes(minute(3), minute(3))))
        assertEquals(local(day(1), 8), planned.single().fireAt)
    }

    @Test
    fun `a time exactly at the end of quiet hours is sent as chosen`() {
        val planned = plan(input(listOf(candidate(1)), times = AlertTimes(minute(8), minute(8))))
        assertEquals(local(day(1), 8), planned.single().fireAt)
    }

    @Test
    fun `a time exactly at the start of quiet hours is moved`() {
        val planned = plan(input(listOf(candidate(1)), times = AlertTimes(minute(22), minute(22))))
        assertEquals(local(day(2), 8), planned.single().fireAt)
    }

    @Test
    fun `quiet hours off lets a late alert through`() {
        val planned = plan(
            input(listOf(candidate(1)), times = AlertTimes(minute(23), minute(23)), quiet = QuietHours(enabled = false)),
        )
        assertEquals(local(day(1), 23), planned.single().fireAt)
    }

    @Test
    fun `a same day quiet range moves a midday time to its end`() {
        val planned = plan(
            input(
                listOf(candidate(1)),
                times = AlertTimes(minute(14), minute(14)),
                quiet = QuietHours(startMinute = minute(13), endMinute = minute(15)),
            ),
        )
        assertEquals(local(day(1), 15), planned.single().fireAt)
    }

    @Test
    fun `an alert pushed by quiet hours onto another day merges into that day`() {
        // Fri 23:00 weekday time moves to Sat 08:00; Sat's own weekend time is 08:30. One alert, earliest time.
        val planned = plan(
            input(
                listOf(candidate(1, "a"), candidate(2, "b")),
                times = AlertTimes(weekdayMinute = minute(23), weekendMinute = minute(8, 30)),
            ),
        )
        assertEquals(1, planned.size)
        assertEquals(local(day(2), 8), planned.single().fireAt)
        assertEquals(2, planned.single().episodeCount)
    }

    // ---- batching ----

    @Test
    fun `no candidates plans nothing`() {
        assertEquals(emptyList(), plan(input(emptyList())))
    }

    @Test
    fun `one episode is one alert at the chosen time`() {
        val planned = plan(input(listOf(candidate(1))))
        assertEquals(1, planned.size)
        assertEquals(local(day(1), 9), planned.single().fireAt)
        assertEquals(1, planned.single().episodeCount)
        assertFalse(planned.single().sendingLessNotice)
    }

    @Test
    fun `episodes from two shows on one day become one alert`() {
        val planned = plan(input(listOf(candidate(1, "a"), candidate(1, "b"))))
        assertEquals(1, planned.size)
        assertEquals(2, planned.single().episodeCount)
        assertEquals(2, planned.single().showCount)
    }

    @Test
    fun `two episodes of one show on one day are one alert with one show`() {
        val planned = plan(input(listOf(candidate(1, "a", "a-1"), candidate(1, "a", "a-2"))))
        assertEquals(1, planned.size)
        assertEquals(2, planned.single().episodeCount)
        assertEquals(1, planned.single().showCount)
    }

    @Test
    fun `episodes on different days are separate alerts in date order`() {
        val planned = plan(input(listOf(candidate(3, "b"), candidate(1, "a"))))
        assertEquals(listOf(day(1), day(3)), planned.map { it.fireDate })
    }

    // ---- opened today and past times ----

    @Test
    fun `nothing is sent on a day the app was already opened`() {
        val planned = plan(input(listOf(candidate(0)), times = AlertTimes(minute(19), minute(19)), opened = setOf(today)))
        assertEquals(emptyList(), planned)
    }

    @Test
    fun `an alert later today goes out when the app was not opened today`() {
        val planned = plan(input(listOf(candidate(0)), times = AlertTimes(minute(19), minute(19))))
        assertEquals(local(today, 19), planned.single().fireAt)
    }

    @Test
    fun `opening the app today does not suppress tomorrow`() {
        val planned = plan(input(listOf(candidate(1)), opened = setOf(today)))
        assertEquals(1, planned.size)
    }

    @Test
    fun `a time that has passed today is dropped`() {
        assertEquals(emptyList(), plan(input(listOf(candidate(0)), times = AlertTimes(minute(9), minute(9)))))
    }

    @Test
    fun `a time under a minute away is dropped and just over a minute is kept`() {
        val almost = input(listOf(candidate(0)), times = AlertTimes(minute(10), minute(10)), now = local(today, 9, 59).let { Instant.fromEpochSeconds(it.epochSeconds + 30) })
        assertEquals(emptyList(), plan(almost), "30 seconds away")
        val enough = input(listOf(candidate(0)), times = AlertTimes(minute(10), minute(10)), now = local(today, 9, 58, ny))
        assertEquals(1, plan(enough).size, "two minutes away")
    }

    @Test
    fun `episodes released before today are dropped`() {
        assertEquals(emptyList(), plan(input(listOf(candidate(-1)))))
    }

    @Test
    fun `episodes beyond the horizon are not planned`() {
        assertEquals(emptyList(), plan(input(listOf(candidate(15)))))
        assertEquals(1, plan(input(listOf(candidate(14)))).size)
    }

    // ---- caps ----

    @Test
    fun `a daily cap sends one alert a day`() {
        val planned = plan(input((1..4).map { candidate(it, "s$it") }))
        assertEquals(4, planned.size)
    }

    @Test
    fun `an alert already sent that day holds a daily cap candidate to the next day`() {
        val planned = plan(input(listOf(candidate(1)), sent = setOf(day(1))))
        assertEquals(listOf(day(2)), planned.map { it.fireDate })
    }

    @Test
    fun `three a week sends three then holds the rest to the first day with room`() {
        val candidates = (1..5).map { candidate(it, "s$it") }
        val planned = plan(input(candidates, cap = AlertCap.ThreePerWeek))
        assertEquals(listOf(day(1), day(2), day(3), day(8)), planned.map { it.fireDate })
        assertEquals(2, planned.last().episodeCount, "the two held days arrive together")
    }

    @Test
    fun `one a week sends the first and holds the others a week`() {
        val planned = plan(input((1..3).map { candidate(it, "s$it") }, cap = AlertCap.OnePerWeek))
        assertEquals(listOf(day(1), day(8)), planned.map { it.fireDate })
        assertEquals(2, planned.last().episodeCount)
    }

    @Test
    fun `alerts already sent this week count against the weekly cap`() {
        val planned = plan(input(listOf(candidate(1)), cap = AlertCap.OnePerWeek, sent = setOf(day(-2))))
        assertEquals(emptyList(), planned.filter { it.fireDate < day(5) }, "still inside the 7 day window")
        assertEquals(listOf(day(5)), planned.map { it.fireDate }, "first day the sent alert leaves the window")
    }

    @Test
    fun `a held candidate beyond the horizon is dropped`() {
        val planned = plan(input(listOf(candidate(1), candidate(2)), cap = AlertCap.OnePerWeek, horizon = 5))
        assertEquals(listOf(day(1)), planned.map { it.fireDate })
    }

    // ---- back-off ----

    @Test
    fun `fewer than three ignored changes nothing`() {
        val planned = plan(input((1..4).map { candidate(it, "s$it") }, streak = 2))
        assertEquals(4, planned.size)
        assertTrue(planned.none { it.sendingLessNotice })
    }

    @Test
    fun `three ignored tightens a daily cap to three a week and says so once`() {
        val planned = plan(input((1..5).map { candidate(it, "s$it") }, streak = 3))
        assertEquals(listOf(day(1), day(2), day(3), day(8)), planned.map { it.fireDate })
        assertEquals(listOf(true, false, false, false), planned.map { it.sendingLessNotice })
    }

    @Test
    fun `six ignored tightens to one a week`() {
        val planned = plan(input((1..3).map { candidate(it, "s$it") }, streak = 6))
        assertEquals(listOf(day(1), day(8)), planned.map { it.fireDate })
        assertTrue(planned.first().sendingLessNotice)
    }

    @Test
    fun `the notice is not repeated once it has been shown`() {
        val planned = plan(input(listOf(candidate(1)), streak = 4, noticeShown = true))
        assertFalse(planned.single().sendingLessNotice)
    }

    @Test
    fun `back-off never loosens a stricter user cap and then has no notice`() {
        val planned = plan(input((1..3).map { candidate(it, "s$it") }, cap = AlertCap.OnePerWeek, streak = 9))
        assertEquals(listOf(day(1), day(8)), planned.map { it.fireDate })
        assertTrue(planned.none { it.sendingLessNotice })
    }

    @Test
    fun `three ignored with a three a week cap does not tighten it`() {
        val effective = BackOff.effective(AlertCap.ThreePerWeek, 3)
        assertEquals(AlertCap.ThreePerWeek, effective.cap)
        assertFalse(effective.tightened)
    }

    @Test
    fun `back-off tiers`() {
        assertEquals(AlertCap.PerDay, BackOff.effective(AlertCap.PerDay, 2).cap)
        assertEquals(AlertCap.ThreePerWeek, BackOff.effective(AlertCap.PerDay, 3).cap)
        assertEquals(AlertCap.ThreePerWeek, BackOff.effective(AlertCap.PerDay, 5).cap)
        assertEquals(AlertCap.OnePerWeek, BackOff.effective(AlertCap.PerDay, 6).cap)
        assertEquals(AlertCap.OnePerWeek, BackOff.effective(AlertCap.ThreePerWeek, 6).cap)
    }

    @Test
    fun `the notice is carried only when there is an alert to carry it`() {
        assertEquals(emptyList(), plan(input(emptyList(), streak = 5)))
    }

    // ---- ignored streak ----

    @Test
    fun `an alert opened within a day resets the streak`() {
        val hour = 3_600_000L
        assertEquals(0, BackOff.resolveStreak(2, listOf(0L), openedAtMs = 5 * hour))
    }

    @Test
    fun `an alert opened after a day adds to the streak`() {
        val hour = 3_600_000L
        assertEquals(3, BackOff.resolveStreak(2, listOf(0L), openedAtMs = 25 * hour))
    }

    @Test
    fun `exactly twenty four hours still counts as acted on`() {
        assertEquals(0, BackOff.resolveStreak(4, listOf(0L), openedAtMs = BackOff.ActedWithinMs))
    }

    @Test
    fun `several old alerts add up and a recent one still resets`() {
        val day = 24L * 3_600_000L
        assertEquals(2, BackOff.resolveStreak(0, listOf(0L, day), openedAtMs = 5 * day))
        assertEquals(0, BackOff.resolveStreak(0, listOf(0L, day, 4 * day + day / 2), openedAtMs = 5 * day))
    }

    @Test
    fun `no delivered alerts leaves the streak alone`() {
        assertEquals(4, BackOff.resolveStreak(4, emptyList(), openedAtMs = 1L))
    }

    // ---- daylight saving and zones ----

    @Test
    fun `a time inside the spring forward gap lands on the first valid time after it`() {
        // 2026-03-08 02:30 does not exist in New York; 02:30 EST would be 07:30 UTC, which is 03:30 EDT.
        val march6 = LocalDate(2026, 3, 6)
        val gateInput = GateInput(
            now = local(march6, 12),
            zone = ny,
            candidates = listOf(AlertCandidate("a", "A", "a-1", LocalDate(2026, 3, 8))),
            times = AlertTimes(minute(2, 30), minute(2, 30)),
            quietHours = QuietHours(enabled = false),
            cap = AlertCap.PerDay,
            openedDates = emptySet(),
            sentDates = emptySet(),
            ignoredStreak = 0,
            backOffNoticeShown = false,
        )
        val planned = plan(gateInput).single()
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), planned.fireAt)
        assertEquals(LocalDate(2026, 3, 8), planned.fireDate)
    }

    @Test
    fun `quiet hours ending at eight mean eight on the clock across spring forward`() {
        // Sat 7 Mar 23:00 is quiet; Sunday 8 Mar 08:00 EDT is 12:00 UTC (the night was an hour short).
        val gateInput = GateInput(
            now = local(LocalDate(2026, 3, 6), 12),
            zone = ny,
            candidates = listOf(AlertCandidate("a", "A", "a-1", LocalDate(2026, 3, 7))),
            times = AlertTimes(minute(23), minute(23)),
            quietHours = QuietHours(),
            cap = AlertCap.PerDay,
            openedDates = emptySet(),
            sentDates = emptySet(),
            ignoredStreak = 0,
            backOffNoticeShown = false,
        )
        assertEquals(Instant.parse("2026-03-08T12:00:00Z"), plan(gateInput).single().fireAt)
    }

    @Test
    fun `quiet hours ending at eight mean eight on the clock across fall back`() {
        // Sat 31 Oct 23:00 is quiet; Sunday 1 Nov 08:00 EST is 13:00 UTC (the night was an hour long).
        val gateInput = GateInput(
            now = local(LocalDate(2026, 10, 30), 12),
            zone = ny,
            candidates = listOf(AlertCandidate("a", "A", "a-1", LocalDate(2026, 10, 31))),
            times = AlertTimes(minute(23), minute(23)),
            quietHours = QuietHours(),
            cap = AlertCap.PerDay,
            openedDates = emptySet(),
            sentDates = emptySet(),
            ignoredStreak = 0,
            backOffNoticeShown = false,
        )
        assertEquals(Instant.parse("2026-11-01T13:00:00Z"), plan(gateInput).single().fireAt)
    }

    @Test
    fun `an alert on the fall back day keeps its local time`() {
        val gateInput = GateInput(
            now = local(LocalDate(2026, 10, 30), 12),
            zone = ny,
            candidates = listOf(AlertCandidate("a", "A", "a-1", LocalDate(2026, 11, 1))),
            times = AlertTimes(minute(19, 40), minute(19, 40)),
            quietHours = QuietHours(),
            cap = AlertCap.PerDay,
            openedDates = emptySet(),
            sentDates = emptySet(),
            ignoredStreak = 0,
            backOffNoticeShown = false,
        )
        val planned = plan(gateInput).single()
        assertEquals(Instant.parse("2026-11-02T00:40:00Z"), planned.fireAt, "19:40 EST")
        assertEquals(LocalDate(2026, 11, 1), planned.fireAt.toLocalDateTime(ny).date)
    }

    @Test
    fun `the same candidates fire at different instants in different zones`() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val a = plan(input(listOf(candidate(1))))
        val b = plan(input(listOf(candidate(1)), zone = tokyo, now = local(today, 10, 0, tokyo)))
        assertEquals(local(day(1), 9, 0, ny), a.single().fireAt)
        assertEquals(local(day(1), 9, 0, tokyo), b.single().fireAt)
    }

    @Test
    fun `weekend days use the weekend time`() {
        // day(2) is Saturday.
        val planned = plan(input(listOf(candidate(2)), times = AlertTimes(minute(19), minute(11))))
        assertEquals(local(day(2), 11), planned.single().fireAt)
    }
}
