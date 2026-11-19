package com.nuvio.app.features.notifications

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlin.math.pow

/**
 * Works out, from when this phone started playback in the past, the time of day the viewer usually starts watching,
 * so an episode alert can arrive a little before it. Pure: no clock, no storage, no platform. The inputs are local
 * wall-clock times recorded on this device only; nothing here is ever synced or sent anywhere.
 *
 * Rules (each one is a constant below, so a change is one line and the tests name it):
 * - **One count per viewing session.** Playback starts that follow each other by [SessionGapMinutes] (2 hours) or
 *   less are one session, so a binge or a skipped-to-next-episode is one data point, not six. A session's time is
 *   its first start.
 * - **Recent weeks count more.** A session's weight halves every [HalfLifeDays] (21 days, three weeks); sessions
 *   older than [WindowDays] (12 weeks) are ignored.
 * - **Weekday and weekend are separate.** Saturday and Sunday are the weekend. A day type with fewer than
 *   [MinSessionsPerDayType] sessions on [MinDaysPerDayType] different days borrows the all-days time instead of
 *   guessing from one evening.
 * - **Still learning** until there are at least [MinSessions] (6) sessions on at least [MinDistinctDays] (4)
 *   different days inside the window.
 * - **The usual time is the weighted median**, not the mean: one late-night outlier does not drag it. Starts
 *   before [NightShiftBeforeMinute] (04:00) count as the late end of the previous evening, so 23:50 and 00:10 agree.
 * - **The alert is [LeadMinutes] (20) before the usual time**, rounded to 5 minutes.
 */
internal object WatchHabitPolicy {
    const val SessionGapMinutes = 120
    const val HalfLifeDays = 21
    const val WindowDays = 84
    const val MinSessions = 6
    const val MinDistinctDays = 4
    const val MinSessionsPerDayType = 3
    const val MinDaysPerDayType = 2
    const val LeadMinutes = 20
    const val NightShiftBeforeMinute = 4 * 60
    private const val MinutesPerDay = 24 * 60
    private const val RoundToMinutes = 5

    /** What the device has learned so far. */
    sealed interface Habit {
        /** Not enough history yet. [sessions] and [days] are what there is, for a "learning" line. */
        data class StillLearning(val sessions: Int, val days: Int) : Habit

        /** Minutes after local midnight, in 0 until 1440. [usual*] is when viewing starts; [alert*] is when to send. */
        data class Ready(
            val usualWeekdayMinute: Int,
            val usualWeekendMinute: Int,
        ) : Habit {
            val alertWeekdayMinute: Int get() = alertMinuteFor(usualWeekdayMinute)
            val alertWeekendMinute: Int get() = alertMinuteFor(usualWeekendMinute)
        }
    }

    fun alertMinuteFor(usualMinute: Int): Int =
        (usualMinute - LeadMinutes).mod(MinutesPerDay)

    /** Collapses raw playback starts into one start per viewing session, oldest first. */
    fun sessionsOf(starts: List<LocalDateTime>): List<LocalDateTime> {
        val ordered = starts.sorted()
        val sessions = ArrayList<LocalDateTime>()
        var previous: LocalDateTime? = null
        for (start in ordered) {
            val last = previous
            if (last == null || minutesBetween(last, start) > SessionGapMinutes) sessions += start
            previous = start
        }
        return sessions
    }

    fun learn(starts: List<LocalDateTime>, today: LocalDate): Habit {
        val sessions = sessionsOf(starts).filter { session ->
            val age = session.date.daysUntil(today)
            age in 0..WindowDays
        }
        val days = sessions.map { it.date }.toSet().size
        if (sessions.size < MinSessions || days < MinDistinctDays) {
            return Habit.StillLearning(sessions.size, days)
        }
        val weighted = sessions.map { Weighted(shiftedMinute(it), weightOf(it.date.daysUntil(today)), it) }
        val overall = weightedMedian(weighted)
        val weekday = estimateFor(weighted.filter { !isWeekend(it.session.date) }) ?: overall
        val weekend = estimateFor(weighted.filter { isWeekend(it.session.date) }) ?: overall
        return Habit.Ready(
            usualWeekdayMinute = roundedWithinDay(weekday),
            usualWeekendMinute = roundedWithinDay(weekend),
        )
    }

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private class Weighted(val minute: Int, val weight: Double, val session: LocalDateTime)

    private fun estimateFor(group: List<Weighted>): Int? {
        if (group.size < MinSessionsPerDayType) return null
        if (group.map { it.session.date }.toSet().size < MinDaysPerDayType) return null
        return weightedMedian(group)
    }

    private fun weightOf(ageDays: Int): Double = 0.5.pow(ageDays.toDouble() / HalfLifeDays)

    private fun shiftedMinute(session: LocalDateTime): Int {
        val minute = session.hour * 60 + session.minute
        return if (minute < NightShiftBeforeMinute) minute + MinutesPerDay else minute
    }

    /** The smallest minute at which at least half of the total weight lies at or below it. */
    private fun weightedMedian(items: List<Weighted>): Int {
        val ordered = items.sortedBy { it.minute }
        val half = ordered.sumOf { it.weight } / 2.0
        var running = 0.0
        for (item in ordered) {
            running += item.weight
            if (running >= half) return item.minute
        }
        return ordered.last().minute
    }

    private fun roundedWithinDay(shiftedMinute: Int): Int {
        val rounded = ((shiftedMinute + RoundToMinutes / 2) / RoundToMinutes) * RoundToMinutes
        return rounded.mod(MinutesPerDay)
    }

    private fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Long {
        val dayDelta = from.date.daysUntil(to.date).toLong()
        return dayDelta * MinutesPerDay + (to.hour * 60 + to.minute) - (from.hour * 60 + from.minute)
    }
}
