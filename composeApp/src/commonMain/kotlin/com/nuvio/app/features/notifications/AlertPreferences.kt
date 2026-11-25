package com.nuvio.app.features.notifications

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** When alerts go out: at the time worked out from viewing on this phone, or at a time the viewer set. */
enum class AlertTimingMode(val wire: String) {
    Learned("learned"),
    SetTime("set_time");

    companion object {
        fun fromWire(value: String?): AlertTimingMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * "Remind me what's new": one notification after this many days without opening Tuvora. [Off] is a choice the viewer
 * made; no choice at all (null in [AlertPreferences.awayReminder]) takes the default from [AwayReminderPolicy.effective].
 */
enum class AwayReminder(val days: Int) {
    Off(0),
    Days3(3),
    Days7(7),
    Days14(14);

    companion object {
        /** Only the four listed values are valid; anything else (a stranger's 5, a string, a negative) is not a choice. */
        fun fromDays(days: Int?): AwayReminder? = entries.firstOrNull { it.days == days }
    }
}

/** Every choice the viewer can make about alerts, with the defaults a new install gets. */
data class AlertPreferences(
    val timingMode: AlertTimingMode = AlertTimingMode.Learned,
    /** Minutes after local midnight; used when [timingMode] is [AlertTimingMode.SetTime]. */
    val setTimeMinute: Int = AlertTimes.DefaultMinute,
    val quietHours: QuietHours = QuietHours(),
    val cap: AlertCap = AlertCap.PerDay,
    /** The end-of-episode prompt is never shown while this is on. */
    val dontAskAgain: Boolean = false,
    /** The away reminder the viewer picked, or null for "never chose": then the default applies (see [AwayReminderPolicy.effective]). */
    val awayReminder: AwayReminder? = null,
    /** "Pause everything": no alerts and no reminders. Every other choice is kept for when it is switched off. */
    val allPaused: Boolean = false,
    /** "Keep show names private": notifications say "a show you follow" instead of the name. */
    val privateNames: Boolean = false,
) {
    /** The send times in effect: the set time, the learned habit, or 09:00 while the habit is still being learned. */
    internal fun timesFor(habit: WatchHabitPolicy.Habit): AlertTimes = when (timingMode) {
        AlertTimingMode.SetTime -> AlertTimes(setTimeMinute, setTimeMinute)
        AlertTimingMode.Learned -> when (habit) {
            is WatchHabitPolicy.Habit.Ready -> AlertTimes(habit.alertWeekdayMinute, habit.alertWeekendMinute)
            is WatchHabitPolicy.Habit.StillLearning -> AlertTimes.Default
        }
    }
}

@Serializable
internal data class StoredAlertPreferences(
    val timingMode: String = AlertTimingMode.Learned.wire,
    val setTimeMinute: Int = AlertTimes.DefaultMinute,
    val quietEnabled: Boolean = true,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 8 * 60,
    val cap: String = AlertCap.PerDay.name,
    val dontAskAgain: Boolean = false,
    /** [AwayReminder.days], or null when the viewer never chose (an invalid stored value reads the same). */
    val awayReminderDays: Int? = null,
    /** Absent in payloads written before these choices existed: they decode as off. */
    val allPaused: Boolean = false,
    val privateNames: Boolean = false,
) {
    fun toModel(): AlertPreferences = AlertPreferences(
        timingMode = AlertTimingMode.fromWire(timingMode) ?: AlertTimingMode.Learned,
        setTimeMinute = setTimeMinute.coerceIn(0, MinutesPerDay - 1),
        quietHours = QuietHours(
            enabled = quietEnabled,
            startMinute = quietStartMinute.coerceIn(0, MinutesPerDay - 1),
            endMinute = quietEndMinute.coerceIn(0, MinutesPerDay - 1),
        ),
        cap = AlertCap.entries.firstOrNull { it.name == cap } ?: AlertCap.PerDay,
        dontAskAgain = dontAskAgain,
        awayReminder = AwayReminder.fromDays(awayReminderDays),
        allPaused = allPaused,
        privateNames = privateNames,
    )

    companion object {
        fun of(model: AlertPreferences) = StoredAlertPreferences(
            timingMode = model.timingMode.wire,
            setTimeMinute = model.setTimeMinute,
            quietEnabled = model.quietHours.enabled,
            quietStartMinute = model.quietHours.startMinute,
            quietEndMinute = model.quietHours.endMinute,
            cap = model.cap.name,
            dontAskAgain = model.dontAskAgain,
            awayReminderDays = model.awayReminder?.days,
            allPaused = model.allPaused,
            privateNames = model.privateNames,
        )
    }
}

internal const val MinutesPerDay = 24 * 60

/**
 * The viewer's alert choices as they travel in the settings blob (`notifications_settings.alert_preferences`).
 * Tolerant by design: the website and older apps write this blob too, and a strict decode of one odd value once
 * threw away a whole settings pull. Each key is applied only when it is present and valid; anything else keeps
 * what this device already has. Learned habit, viewing history, per-show switches and the ignored count never
 * travel: they describe this phone.
 */
internal object AlertPreferencesSyncCodec {
    /** `away_reminder_days` is written only once the viewer has chosen: an unchosen default depends on this device's permission, so it must not travel. */
    fun encode(preferences: AlertPreferences): JsonObject = JsonObject(
        listOfNotNull(
            preferences.awayReminder?.let { "away_reminder_days" to JsonPrimitive(it.days) },
        ).toMap() + mapOf(
            "timing_mode" to JsonPrimitive(preferences.timingMode.wire),
            "set_time_minute" to JsonPrimitive(preferences.setTimeMinute),
            "quiet_hours_enabled" to JsonPrimitive(preferences.quietHours.enabled),
            "quiet_hours_start_minute" to JsonPrimitive(preferences.quietHours.startMinute),
            "quiet_hours_end_minute" to JsonPrimitive(preferences.quietHours.endMinute),
            "cap" to JsonPrimitive(preferences.cap.name),
            "dont_ask_again" to JsonPrimitive(preferences.dontAskAgain),
            "all_paused" to JsonPrimitive(preferences.allPaused),
            "private_names" to JsonPrimitive(preferences.privateNames),
        ),
    )

    fun applyOver(current: AlertPreferences, remote: JsonObject): AlertPreferences {
        if (remote.isEmpty()) return current
        fun minute(key: String): Int? = (remote[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?.takeIf { it in 0 until MinutesPerDay }
        fun flag(key: String): Boolean? = (remote[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val mode = AlertTimingMode.fromWire((remote["timing_mode"] as? JsonPrimitive)?.contentOrNull)
        val cap = AlertCap.entries.firstOrNull { it.name == (remote["cap"] as? JsonPrimitive)?.contentOrNull }
        return current.copy(
            timingMode = mode ?: current.timingMode,
            setTimeMinute = minute("set_time_minute") ?: current.setTimeMinute,
            quietHours = current.quietHours.copy(
                enabled = flag("quiet_hours_enabled") ?: current.quietHours.enabled,
                startMinute = minute("quiet_hours_start_minute") ?: current.quietHours.startMinute,
                endMinute = minute("quiet_hours_end_minute") ?: current.quietHours.endMinute,
            ),
            cap = cap ?: current.cap,
            dontAskAgain = flag("dont_ask_again") ?: current.dontAskAgain,
            allPaused = flag("all_paused") ?: current.allPaused,
            privateNames = flag("private_names") ?: current.privateNames,
            awayReminder = AwayReminder.fromDays(
                (remote["away_reminder_days"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            ) ?: current.awayReminder,
        )
    }
}

/** Learned alert time falls inside the viewer's quiet hours, so alerts go out when quiet hours end instead. */
data class QuietHoursConflict(val usualMinute: Int, val movedToMinute: Int)

internal object AlertTimingExplainer {
    /**
     * The conflict to tell the viewer about, or null. Looks at the time the alert would be sent (usual time minus
     * the lead) on weekdays and weekends; reports the first that lands inside quiet hours.
     */
    fun conflict(habit: WatchHabitPolicy.Habit, preferences: AlertPreferences): QuietHoursConflict? {
        if (preferences.timingMode != AlertTimingMode.Learned) return null
        val ready = habit as? WatchHabitPolicy.Habit.Ready ?: return null
        val pairs = listOf(
            ready.usualWeekdayMinute to ready.alertWeekdayMinute,
            ready.usualWeekendMinute to ready.alertWeekendMinute,
        )
        val hit = pairs.firstOrNull { (_, alert) -> preferences.quietHours.contains(alert) } ?: return null
        return QuietHoursConflict(usualMinute = hit.first, movedToMinute = preferences.quietHours.endMinute)
    }
}
