@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** The hours in which Tuvora never shows an alert. Wall-clock minutes after local midnight; may span midnight. */
internal data class QuietHours(
    val enabled: Boolean = true,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 8 * 60,
) {
    /** Start equal to end is an empty range, not "all day": it never blocks anything. */
    val isActive: Boolean get() = enabled && startMinute != endMinute

    fun contains(minuteOfDay: Int): Boolean = when {
        !isActive -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    /** For a moment inside quiet hours, the local moment they end. Outside quiet hours, the moment itself. */
    fun endOfQuietFor(moment: LocalDateTime): LocalDateTime {
        val minute = moment.hour * 60 + moment.minute
        if (!contains(minute)) return moment
        val endsTomorrow = startMinute > endMinute && minute >= startMinute
        val date = if (endsTomorrow) moment.date.plus(DatePeriod(days = 1)) else moment.date
        return date.atTime(endMinute / 60, endMinute % 60)
    }
}

/** How many alerts the viewer allows. [windowDays] is a rolling window ending on the alert's day. */
internal enum class AlertCap(val maxAlerts: Int, val windowDays: Int) {
    PerDay(1, 1),
    ThreePerWeek(3, 7),
    OnePerWeek(1, 7);

    /** Alerts per week at full use; the comparison for "stricter". */
    val perWeek: Int get() = if (windowDays == 1) 7 else maxAlerts
}

/** When the alert goes out on a weekday and on a weekend day, as minutes after local midnight. */
internal data class AlertTimes(val weekdayMinute: Int, val weekendMinute: Int) {
    fun minuteFor(date: LocalDate): Int =
        if (WatchHabitPolicy.isWeekend(date)) weekendMinute else weekdayMinute

    companion object {
        /** Used while the habit is still being learned, and the stored default for "at a set time". */
        const val DefaultMinute = 9 * 60
        val Default = AlertTimes(DefaultMinute, DefaultMinute)
    }
}

internal data class AlertCandidate(
    val showKey: String,
    val showTitle: String,
    val episodeKey: String,
    val releaseDate: LocalDate,
)

internal data class GateInput(
    val now: Instant,
    val zone: TimeZone,
    val candidates: List<AlertCandidate>,
    val times: AlertTimes,
    val quietHours: QuietHours,
    val cap: AlertCap,
    /** Local dates the app was opened on. Only a day the viewer has already opened Tuvora on matters. */
    val openedDates: Set<LocalDate>,
    /** Local dates an alert was already sent on, for the rolling weekly cap. */
    val sentDates: Set<LocalDate>,
    /** Alerts in a row that were sent and not acted on. */
    val ignoredStreak: Int,
    /** True once the one-time "Sending these less often" line has been shown for the current back-off. */
    val backOffNoticeShown: Boolean,
    val horizonDays: Int = GateDefaults.HorizonDays,
)

internal data class PlannedAlert(
    val fireAt: Instant,
    val fireDate: LocalDate,
    val candidates: List<AlertCandidate>,
    /** Show this one-time "Sending these less often" line inside this alert. */
    val sendingLessNotice: Boolean,
) {
    val episodeCount: Int get() = candidates.map { it.episodeKey }.toSet().size
    val showCount: Int get() = candidates.map { it.showKey }.toSet().size
}

internal object GateDefaults {
    const val HorizonDays = 14

    /** An alert this soon or sooner is not worth scheduling; the OS may drop it as already past. */
    const val MinLeadSeconds = 60L
}

/**
 * Decides which alerts are sent and when. Pure: the caller supplies the clock, zone, history and the viewer's
 * choices, and gets back at most one planned alert per local day.
 *
 * Rules, in the order they apply:
 * 1. **Quiet hours always win.** A time inside quiet hours moves to the moment they end (a 23:00 alert with 22:00 to
 *    08:00 quiet goes to 08:00 the next morning). Quiet hours off, or start equal to end, block nothing.
 * 2. **One notification per day.** Everything released on one day, or landing on one day after a quiet-hours move,
 *    is one alert ("2 new episodes from shows you watch"), at the earliest of their times.
 * 3. **Nothing on a day the app was already opened.** Those candidates are dropped, not postponed: the viewer has
 *    seen the in-app New episodes card.
 * 4. **The viewer's cap**, as a rolling window ending on the alert's day, counting alerts already sent
 *    ([GateInput.sentDates]). Candidates over the cap are held and join the next day that has room, inside the
 *    horizon; a day that is already past, or opened, drops them.
 * 5. **Back-off.** After [BackOff.TierOneIgnored] (3) ignored alerts in a row the cap tightens to three a week;
 *    after [BackOff.TierTwoIgnored] (6) to one a week. It only ever tightens: the stricter of that and the
 *    viewer's own cap applies. The first alert sent under a tightened cap carries the one-time notice, until
 *    [GateInput.backOffNoticeShown]. Acting on an alert resets the streak (the caller's job).
 *
 * Episodes released before today, and alerts whose time has passed (or is under a minute away), are dropped.
 * DST: local times are resolved with the zone's own rules, so a 02:30 alert on a spring-forward day lands at the
 * first valid local time after the gap, and quiet hours ending at 08:00 mean 08:00 on the clock that day.
 */
internal object NotificationGatePolicy {
    fun plan(input: GateInput): List<PlannedAlert> {
        if (input.candidates.isEmpty()) return emptyList()
        val today = input.now.toLocalDateTime(input.zone).date
        val earliest = input.now.epochSeconds + GateDefaults.MinLeadSeconds
        val backOff = BackOff.effective(input.cap, input.ignoredStreak)
        val cap = backOff.cap
        val byRelease = input.candidates
            .filter { it.releaseDate >= today }
            .groupBy { it.releaseDate }

        val kept = ArrayList<PlannedAlert>()
        var held: List<AlertCandidate> = emptyList()
        for (offset in 0..input.horizonDays) {
            val releaseDate = today.plus(DatePeriod(days = offset))
            val group = held + byRelease[releaseDate].orEmpty()
            held = emptyList()
            if (group.isEmpty()) continue

            val fireLocal = fireTimeFor(releaseDate, input)
            val fireInstant = fireLocal.toInstant(input.zone)
            val fireDate = fireLocal.date
            if (fireInstant.epochSeconds < earliest) continue
            if (fireDate in input.openedDates) continue

            val sameDay = kept.indexOfFirst { it.fireDate == fireDate }
            if (sameDay >= 0) {
                val existing = kept[sameDay]
                kept[sameDay] = existing.copy(
                    fireAt = minOf(existing.fireAt, fireInstant),
                    candidates = existing.candidates + group,
                )
                continue
            }
            if (alertsInWindow(fireDate, cap, input.sentDates, kept) >= cap.maxAlerts) {
                held = group
                continue
            }
            kept += PlannedAlert(fireAt = fireInstant, fireDate = fireDate, candidates = group, sendingLessNotice = false)
        }

        if (backOff.tightened && !input.backOffNoticeShown && kept.isNotEmpty()) {
            kept[0] = kept[0].copy(sendingLessNotice = true)
        }
        return kept
    }

    /** The local moment an alert for [releaseDate] would go out, after quiet hours. */
    internal fun fireTimeFor(releaseDate: LocalDate, input: GateInput): LocalDateTime {
        val minute = input.times.minuteFor(releaseDate)
        val wanted = releaseDate.atTime(minute / 60, minute % 60)
        return input.quietHours.endOfQuietFor(wanted)
    }

    private fun alertsInWindow(
        fireDate: LocalDate,
        cap: AlertCap,
        sentDates: Set<LocalDate>,
        kept: List<PlannedAlert>,
    ): Int {
        val windowStart = fireDate.plus(DatePeriod(days = -(cap.windowDays - 1)))
        val sent = sentDates.count { it >= windowStart && it <= fireDate }
        val planned = kept.count { it.fireDate >= windowStart && it.fireDate <= fireDate }
        return sent + planned
    }
}

/** Sending less often after alerts keep being ignored. */
internal object BackOff {
    const val TierOneIgnored = 3
    const val TierTwoIgnored = 6

    data class Effective(val cap: AlertCap, val tightened: Boolean)

    fun effective(userCap: AlertCap, ignoredStreak: Int): Effective {
        val tier = when {
            ignoredStreak >= TierTwoIgnored -> AlertCap.OnePerWeek
            ignoredStreak >= TierOneIgnored -> AlertCap.ThreePerWeek
            else -> null
        }
        return if (tier != null && tier.perWeek < userCap.perWeek) Effective(tier, true) else Effective(userCap, false)
    }

    /**
     * Resolves alerts that were delivered since the app was last open. One acted on within [ActedWithinMs] of its
     * time (the viewer opened Tuvora then) resets the streak; each older one adds to it. Oldest first, so a recent
     * one still wins.
     */
    const val ActedWithinMs = 24L * 60 * 60 * 1000

    fun resolveStreak(streak: Int, deliveredAtMs: List<Long>, openedAtMs: Long): Int {
        var next = streak
        for (deliveredAt in deliveredAtMs.sorted()) {
            next = if (openedAtMs - deliveredAt <= ActedWithinMs) 0 else next + 1
        }
        return next
    }
}
