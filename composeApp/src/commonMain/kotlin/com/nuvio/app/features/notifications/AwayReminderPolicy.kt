@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

internal data class AwayReminderInput(
    val now: Instant,
    val zone: TimeZone,
    /** The effective setting in days; 0 is off. */
    val awayDays: Int,
    /** When Tuvora was last opened on this device. Null (never recorded) schedules nothing. */
    val lastOpenedAt: Instant?,
    /** Episodes of the shows that have alerts on, whatever their release date. */
    val candidates: List<AlertCandidate>,
    val times: AlertTimes,
    val quietHours: QuietHours,
    val cap: AlertCap,
    val openedDates: Set<LocalDate>,
    val sentDates: Set<LocalDate>,
    val ignoredStreak: Int,
    val backOffNoticeShown: Boolean,
    /** What the episode-alert gate already planned: the away reminder is fitted around it, never the other way. */
    val episodeAlerts: List<PlannedAlert>,
)

internal data class PlannedAwayReminder(
    val fireAt: Instant,
    val fireDate: LocalDate,
    /** The shows with a new episode in the absence, in the order their first new episode comes out. */
    val showTitles: List<String>,
    val sendingLessNotice: Boolean,
) {
    val showCount: Int get() = showTitles.size
}

/**
 * "Remind me what's new": one notification about the viewer's own shows after a stretch away. Pure: the caller
 * supplies the clock, the history, the viewer's choices and the episode alerts already planned.
 *
 * Rules:
 * - **Default.** No choice made: 7 days when this device may show notifications and the episode-alert switch is on,
 *   otherwise off. A choice the viewer made (including Off) always wins.
 * - **Anchored to the last open, not to now.** The reminder is due [AwayReminderInput.awayDays] after the last open,
 *   at the same learned (or set) time episode alerts use. Because it is anchored, working it out again later gives the
 *   same moment; once that moment has passed nothing is planned, so it goes out **at most once per absence** and the
 *   next one waits for the app to be opened again.
 * - **Content is decided now** (iOS cannot run code when it fires): the shows with an episode released after the day
 *   of the last open and up to the day it fires. None: nothing is scheduled.
 * - **Same gate as every alert.** Quiet hours move it to when they end; it never lands on a day the app was opened; the
 *   viewer's cap (tightened by back-off) is a rolling window over alerts and reminders together; the one-time
 *   "Sending these less often" line rides on it when no episode alert carries it.
 * - **Android may be late.** An app unused for about eight days lands in the restricted standby bucket, where the OS
 *   runs at most one job and one alarm a day, so the 14-day option can arrive late; no exact alarm is used. When it
 *   does arrive late the worker re-checks quiet hours, "opened today" and "another notification went out today".
 * - **Never two on one day.** The episode alerts are planned first and win: a day that already has one drops the
 *   reminder (it is not moved; that alert already brings the viewer back, and a pile of stragglers is what this
 *   feature must not become). A reminder that would push a cap window over its limit is dropped the same way.
 */
internal object AwayReminderPolicy {
    /** The setting in effect: the viewer's choice, else 7 days when alerts can reach this device, else off. */
    fun effective(choice: AwayReminder?, permissionGranted: Boolean, episodeAlertsOn: Boolean): AwayReminder =
        choice ?: if (permissionGranted && episodeAlertsOn) AwayReminder.Days7 else AwayReminder.Off

    fun plan(input: AwayReminderInput): PlannedAwayReminder? {
        val lastOpenedAt = input.lastOpenedAt ?: return null
        if (input.awayDays <= 0) return null

        val openedOn = lastOpenedAt.toLocalDateTime(input.zone).date
        val dueDate = openedOn.plus(DatePeriod(days = input.awayDays))
        val minute = input.times.minuteFor(dueDate)
        val fireLocal = input.quietHours.endOfQuietFor(dueDate.atTime(minute / 60, minute % 60))
        val fireInstant = fireLocal.toInstant(input.zone)
        val fireDate = fireLocal.date

        if (fireInstant.epochSeconds < input.now.epochSeconds + GateDefaults.MinLeadSeconds) return null
        if (fireDate in input.openedDates) return null

        val titles = input.candidates
            .filter { it.releaseDate > openedOn && it.releaseDate <= fireDate }
            .sortedBy { it.releaseDate }
            .distinctBy { it.showKey }
            .map { it.showTitle }
        if (titles.isEmpty()) return null

        if (input.episodeAlerts.any { it.fireDate == fireDate }) return null

        val backOff = BackOff.effective(input.cap, input.ignoredStreak)
        if (!fitsCap(fireDate, backOff.cap, input.sentDates, input.episodeAlerts.map { it.fireDate })) return null

        val notice = backOff.tightened && !input.backOffNoticeShown && input.episodeAlerts.isEmpty()
        return PlannedAwayReminder(fireAt = fireInstant, fireDate = fireDate, showTitles = titles, sendingLessNotice = notice)
    }

    /** Adding a notification on [fireDate] keeps every rolling window that contains it within the cap. */
    private fun fitsCap(fireDate: LocalDate, cap: AlertCap, sent: Set<LocalDate>, planned: List<LocalDate>): Boolean {
        val all = sent + planned + fireDate
        return (0 until cap.windowDays).all { offset ->
            val windowEnd = fireDate.plus(DatePeriod(days = offset))
            val windowStart = windowEnd.plus(DatePeriod(days = -(cap.windowDays - 1)))
            all.count { it >= windowStart && it <= windowEnd } <= cap.maxAlerts
        }
    }
}

/** What the phone says about showing notifications from Tuvora. */
enum class NotificationPermission { Granted, Denied, NotAsked }

/** What the "When you've been away" group shows: its choices, or the denied-permission panel. */
enum class AwayGroupState { Choices, Denied }

internal object AwayGroupPolicy {
    /**
     * Decided from the phone's own permission, not from the episode-alert switch: a refused permission shows the
     * panel (the stored choice is kept and applies once allowed); a permission not asked yet shows the choices,
     * and picking one asks for it. [episodeAlertsOn] does not change the answer: the reminder is its own choice.
     */
    @Suppress("UNUSED_PARAMETER")
    fun state(permission: NotificationPermission, episodeAlertsOn: Boolean): AwayGroupState =
        if (permission == NotificationPermission.Denied) AwayGroupState.Denied else AwayGroupState.Choices

    /** Whether picking [choice] should ask the phone for permission right now. */
    fun shouldAskOnPick(choice: AwayReminder, permission: NotificationPermission): Boolean =
        choice != AwayReminder.Off && permission == NotificationPermission.NotAsked
}

/** Words for the reminder, resolved by the platform's string resources. */
internal interface AwayTemplates {
    suspend fun titleOne(): String
    suspend fun titleMany(showCount: Int): String
    /** "[A] and [B]. Turn this off in Settings." for one or two names (one name is passed with [second] null). */
    suspend fun bodyNames(first: String, second: String?): String
    suspend fun bodyCount(): String
    suspend fun lessOftenLine(): String
}

internal object AwayReminderContentBuilder {
    /** Names are shown for this many shows or fewer; more are only counted. */
    const val MaxNamed = 2

    /** [privateNames]: shows are only counted, never named. */
    suspend fun build(plan: PlannedAwayReminder, templates: AwayTemplates, privateNames: Boolean = false): AlertContent {
        val title = if (plan.showCount == 1) templates.titleOne() else templates.titleMany(plan.showCount)
        val body = if (plan.showCount <= MaxNamed && !privateNames) {
            templates.bodyNames(plan.showTitles.first(), plan.showTitles.getOrNull(1))
        } else {
            templates.bodyCount()
        }
        return AlertContent(title, if (plan.sendingLessNotice) body + "\n" + templates.lessOftenLine() else body)
    }
}
