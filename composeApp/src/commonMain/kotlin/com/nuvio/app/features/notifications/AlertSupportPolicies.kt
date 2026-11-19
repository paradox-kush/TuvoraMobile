@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The record of when playback started on this device, kept as local wall-clock ISO strings so a later change of
 * time zone does not rewrite history. Stays on this device: never synced, never sent to analytics.
 */
internal object ViewingHistory {
    const val MaxEntries = 300
    const val DuplicateWithinMinutes = 10

    /** Appends [start] unless the last recorded start is within [DuplicateWithinMinutes] (a pause and resume). */
    fun append(existing: List<String>, start: LocalDateTime): List<String> {
        val parsed = parse(existing)
        val last = parsed.maxOrNull()
        if (last != null && last <= start && minutesBetween(last, start) < DuplicateWithinMinutes) return existing
        val oldestKept = start.date.minus(DatePeriod(days = WatchHabitPolicy.WindowDays + 7))
        return (parsed + start)
            .filter { it.date >= oldestKept }
            .sorted()
            .takeLast(MaxEntries)
            .map { it.toString() }
    }

    fun parse(raw: List<String>): List<LocalDateTime> =
        raw.mapNotNull { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Long {
        val days = from.date.daysUntil(to.date).toLong()
        return days * MinutesPerDay + (to.hour * 60 + to.minute) - (from.hour * 60 + from.minute)
    }
}

/** What the Android worker should do when an alert's time arrives. iOS cannot run code then; it reschedules on app open. */
internal sealed interface FireDecision {
    data object Send : FireDecision
    data object Skip : FireDecision
    data class DelayUntil(val at: Instant) : FireDecision
}

internal object FireTimeGate {
    /**
     * Re-checks an alert at the moment it is due: skipped if the app was opened today, delayed to the end of quiet
     * hours if the OS delivered it late into them.
     */
    fun decide(now: Instant, zone: TimeZone, quietHours: QuietHours, lastOpenedAt: Instant?): FireDecision {
        val local = now.toLocalDateTime(zone)
        if (lastOpenedAt != null && lastOpenedAt.toLocalDateTime(zone).date == local.date) return FireDecision.Skip
        if (quietHours.contains(local.hour * 60 + local.minute)) {
            return FireDecision.DelayUntil(quietHours.endOfQuietFor(local).toInstant(zone))
        }
        return FireDecision.Send
    }
}

/** When the end-of-episode "Alert me" prompt may appear. */
internal object EndOfEpisodePromptPolicy {
    /**
     * "Nearly caught up": the episode just watched is the latest one out, so the next one is unaired or does not
     * exist yet. [nextEpisodeAired] is null when there is no later episode.
     */
    fun nearlyCaughtUp(nextEpisodeAired: Boolean?): Boolean = nextEpisodeAired != true

    fun shouldOffer(
        dontAskAgain: Boolean,
        alreadyPromptedForShow: Boolean,
        alertsAlreadyOnForShow: Boolean,
        nearlyCaughtUp: Boolean,
    ): Boolean = !dontAskAgain && !alreadyPromptedForShow && !alertsAlreadyOnForShow && nearlyCaughtUp
}

/** Where a followed show came from; decides its default switch before the viewer touches it. */
internal enum class FollowSource(val wire: String) {
    Library("library"),
    ContinueWatching("continue_watching");

    companion object {
        fun fromWire(value: String?): FollowSource = entries.firstOrNull { it.wire == value } ?: Library
    }
}

internal object ShowAlertDefaults {
    /**
     * Saved shows have alerts on, as before (the master switch is still off by default). A show only watched through
     * Continue Watching is listed but off until the viewer turns it on, from the end-of-episode prompt, the bell, the
     * Home chip or Settings; the prompt is how they are asked.
     */
    fun isOn(explicit: Boolean?, source: FollowSource): Boolean = explicit ?: (source == FollowSource.Library)
}

/** Words for the alert, resolved by the platform's string resources. Suspend so the real one can use `getString`. */
internal interface AlertTemplates {
    suspend fun batchTitle(episodeCount: Int): String
    suspend fun sameShowBody(episodeCount: Int): String
    suspend fun listBody(labels: List<String>): String
    suspend fun listBodyWithMore(labels: List<String>, more: Int): String
    suspend fun lessOftenLine(): String
}

internal data class AlertContent(val title: String, val body: String)

internal object AlertContentBuilder {
    const val MaxListed = 3

    suspend fun build(alert: PlannedAlert, templates: AlertTemplates): AlertContent {
        val episodes = alert.candidates.distinctBy { it.episodeKey }
        val base = when {
            episodes.size == 1 -> AlertContent(episodes.single().showTitle, episodes.single().bodyText)
            alert.showCount == 1 -> AlertContent(episodes.first().showTitle, templates.sameShowBody(episodes.size))
            else -> {
                val labels = episodes.map { it.label }
                val body = if (labels.size <= MaxListed) {
                    templates.listBody(labels)
                } else {
                    templates.listBodyWithMore(labels.take(MaxListed), labels.size - MaxListed)
                }
                AlertContent(templates.batchTitle(episodes.size), body)
            }
        }
        return if (alert.sendingLessNotice) {
            base.copy(body = base.body + "\n" + templates.lessOftenLine())
        } else {
            base
        }
    }
}

/**
 * What changes when the app is opened: alerts that were due since the last open are counted as acted on (the app was
 * opened within a day of one) or ignored, the opened day is recorded for the gate, and the delivered alerts' days go
 * into the weekly-cap history. Pure; the repository persists the result.
 *
 * An alert counts as delivered when its time has passed while alerts were allowed to fire. On Android a worker that
 * skipped one at fire time (quiet hours, app already opened) is counted as delivered too; that can only make
 * back-off a little eager, never miss one.
 */
internal object AlertAftermath {
    const val KeepSentDays = 14
    const val KeepOpenedDays = 3

    fun onAppOpened(
        runtime: StoredAlertRuntime,
        now: Instant,
        zone: TimeZone,
        alertsCouldHaveFired: Boolean,
    ): StoredAlertRuntime {
        val nowMs = now.toEpochMilliseconds()
        val today = now.toLocalDateTime(zone).date
        val delivered = if (alertsCouldHaveFired) runtime.scheduled.filter { it.fireAtMs <= nowMs } else emptyList()
        val pending = runtime.scheduled.filter { it.fireAtMs > nowMs }

        val streak = BackOff.resolveStreak(runtime.ignoredStreak, delivered.map { it.fireAtMs }, nowMs)
        val noticeShown = when {
            delivered.isNotEmpty() && streak == 0 -> false
            delivered.any { it.withNotice } -> true
            else -> runtime.backOffNoticeShown
        }

        val deliveredDates = delivered.map { Instant.fromEpochMilliseconds(it.fireAtMs).toLocalDateTime(zone).date }
        val sentCutoff = today.minus(DatePeriod(days = KeepSentDays))
        val sent = (runtime.sentDates.mapNotNull { parseDate(it) } + deliveredDates)
            .filter { it >= sentCutoff }
            .distinct()
            .sorted()
            .map { it.toString() }

        val openedCutoff = today.minus(DatePeriod(days = KeepOpenedDays))
        val opened = (runtime.openedDates.mapNotNull { parseDate(it) } + today)
            .filter { it >= openedCutoff }
            .distinct()
            .sorted()
            .map { it.toString() }

        return runtime.copy(
            ignoredStreak = streak,
            backOffNoticeShown = noticeShown,
            sentDates = sent,
            openedDates = opened,
            scheduled = pending,
            lastOpenedAtMs = nowMs,
        )
    }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}

/** Clock text for the alert screens. Minutes after local midnight in, plain English out. */
internal object AlertClock {
    /** `22:00`, as the time steppers show it. */
    fun hhmm(minute: Int): String {
        val m = minute.mod(MinutesPerDay)
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** `8:30 pm`, `11 pm`, `12 am`: for "Usually around ..." copy. */
    fun words(minute: Int): String {
        val m = minute.mod(MinutesPerDay)
        val hour24 = m / 60
        val mins = m % 60
        val suffix = if (hour24 < 12) "am" else "pm"
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        return if (mins == 0) "$hour12 $suffix" else "$hour12:${mins.toString().padStart(2, '0')} $suffix"
    }

    const val StepMinutes = 30

    /** One step of the stepper, wrapping around midnight. */
    fun step(minute: Int, direction: Int): Int = (minute + direction * StepMinutes).mod(MinutesPerDay)
}
