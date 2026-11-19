package com.nuvio.app.features.notifications

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchHabitPolicyTest {
    // 2026-11-19 is a Thursday; 21 and 22 are the weekend.
    private val today = LocalDate(2026, 11, 19)

    private fun at(daysAgo: Int, hour: Int, minute: Int = 0): LocalDateTime {
        val date = LocalDate.fromEpochDays(today.toEpochDays() - daysAgo)
        return LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute)
    }

    private fun ready(starts: List<LocalDateTime>): WatchHabitPolicy.Habit.Ready {
        val habit = WatchHabitPolicy.learn(starts, today)
        assertIs<WatchHabitPolicy.Habit.Ready>(habit, "expected a learned habit")
        return habit
    }

    @Test
    fun `no history is still learning`() {
        assertEquals(WatchHabitPolicy.Habit.StillLearning(0, 0), WatchHabitPolicy.learn(emptyList(), today))
    }

    @Test
    fun `five sessions on five days is still learning`() {
        val starts = (1..5).map { at(it, 20) }
        assertEquals(WatchHabitPolicy.Habit.StillLearning(5, 5), WatchHabitPolicy.learn(starts, today))
    }

    @Test
    fun `six sessions on only three days is still learning`() {
        val starts = listOf(at(1, 9), at(1, 20), at(2, 9), at(2, 20), at(3, 9), at(3, 20))
        assertEquals(WatchHabitPolicy.Habit.StillLearning(6, 3), WatchHabitPolicy.learn(starts, today))
    }

    @Test
    fun `six sessions on four days is ready`() {
        val starts = listOf(at(1, 20), at(1, 9), at(2, 20), at(3, 20), at(4, 20), at(5, 20))
        assertIs<WatchHabitPolicy.Habit.Ready>(WatchHabitPolicy.learn(starts, today))
    }

    @Test
    fun `playback starts within two hours are one session`() {
        val starts = listOf(at(1, 20, 0), at(1, 21, 30), at(1, 23, 0), at(1, 23, 59))
        assertEquals(listOf(at(1, 20, 0)), WatchHabitPolicy.sessionsOf(starts))
    }

    @Test
    fun `a gap of exactly two hours joins and one minute more splits`() {
        assertEquals(1, WatchHabitPolicy.sessionsOf(listOf(at(1, 18, 0), at(1, 20, 0))).size, "120 minutes joins")
        assertEquals(2, WatchHabitPolicy.sessionsOf(listOf(at(1, 18, 0), at(1, 20, 1))).size, "121 minutes splits")
    }

    @Test
    fun `sessions across midnight keep their gap`() {
        val starts = listOf(at(2, 23, 30), at(1, 0, 30))
        assertEquals(1, WatchHabitPolicy.sessionsOf(starts).size)
    }

    @Test
    fun `a binge counts once toward the six sessions`() {
        val binge = (0..9).map { at(1, 20 + (it * 10) / 60, (it * 10) % 60) }
        val starts = binge + listOf(at(2, 20), at(3, 20), at(4, 20), at(5, 20))
        assertEquals(WatchHabitPolicy.Habit.StillLearning(5, 5), WatchHabitPolicy.learn(starts, today))
    }

    @Test
    fun `the alert is twenty minutes before the usual time`() {
        val starts = (1..8).map { at(it, 20) }
        val habit = ready(starts)
        assertEquals(20 * 60, habit.usualWeekdayMinute)
        assertEquals(19 * 60 + 40, habit.alertWeekdayMinute)
    }

    @Test
    fun `an alert before midnight wraps for a just after midnight habit`() {
        assertEquals(23 * 60 + 50, WatchHabitPolicy.alertMinuteFor(10))
    }

    @Test
    fun `the usual time is rounded to five minutes`() {
        val starts = (1..8).map { at(it, 20, 13) }
        assertEquals(20 * 60 + 15, ready(starts).usualWeekdayMinute)
    }

    @Test
    fun `weekday and weekend are learned separately`() {
        // Thu 19 is today. Weekdays at 20:00, weekend days (Sat 14, Sun 15, Sat 7, Sun 8) at 11:00.
        val weekdays = listOf(at(1, 20), at(2, 20), at(3, 20), at(7, 20), at(8, 20), at(9, 20))
        val weekends = listOf(at(5, 11), at(4, 11), at(12, 11), at(11, 11))
        val habit = ready(weekdays + weekends)
        assertEquals(20 * 60, habit.usualWeekdayMinute)
        assertEquals(11 * 60, habit.usualWeekendMinute)
    }

    @Test
    fun `a day type with too little history borrows the overall time`() {
        // Seven weekday evenings and a single Saturday morning: the weekend borrows the overall (evening) time.
        val weekdays = listOf(at(1, 20), at(2, 20), at(3, 20), at(7, 20), at(8, 20), at(9, 20), at(10, 20))
        val oneSaturday = listOf(at(5, 11))
        val habit = ready(weekdays + oneSaturday)
        assertEquals(20 * 60, habit.usualWeekendMinute)
    }

    @Test
    fun `a lone late night outlier does not move the usual time`() {
        val starts = (1..7).map { at(it, 20) } + at(2, 2, 30)
        assertEquals(20 * 60, ready(starts).usualWeekdayMinute)
    }

    @Test
    fun `recent weeks count more than old ones`() {
        // Five old sessions at 21:00 around nine weeks ago, four recent ones at 19:00. Unweighted the median is 21:00.
        val old = (63..67).map { at(it, 21) }
        val recent = listOf(at(1, 19), at(2, 19), at(3, 19), at(4, 19))
        assertEquals(19 * 60, ready(old + recent).usualWeekdayMinute)
    }

    @Test
    fun `sessions older than twelve weeks are ignored`() {
        val ancient = (85..95).map { at(it, 21) }
        val habit = WatchHabitPolicy.learn(ancient, today)
        assertEquals(WatchHabitPolicy.Habit.StillLearning(0, 0), habit)
    }

    @Test
    fun `a session on day 84 still counts and day 85 does not`() {
        assertEquals(WatchHabitPolicy.Habit.StillLearning(1, 1), WatchHabitPolicy.learn(listOf(at(84, 20)), today))
        assertEquals(WatchHabitPolicy.Habit.StillLearning(0, 0), WatchHabitPolicy.learn(listOf(at(85, 20)), today))
    }

    @Test
    fun `starts just after midnight agree with late evening ones`() {
        val starts = listOf(
            at(1, 23, 50), at(2, 23, 50), at(3, 23, 50), at(4, 23, 50), at(6, 0, 10), at(8, 0, 10), at(10, 0, 10),
        )
        val habit = ready(starts)
        assertTrue(
            habit.usualWeekdayMinute == 23 * 60 + 50 || habit.usualWeekdayMinute == 10,
            "the usual time sits at the midnight boundary, not at midday: ${habit.usualWeekdayMinute}",
        )
    }

    @Test
    fun `future dated sessions are ignored`() {
        val future = (1..8).map { at(-it, 20) }
        assertEquals(WatchHabitPolicy.Habit.StillLearning(0, 0), WatchHabitPolicy.learn(future, today))
    }
}
