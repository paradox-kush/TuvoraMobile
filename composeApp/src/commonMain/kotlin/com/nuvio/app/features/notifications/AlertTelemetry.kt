package com.nuvio.app.features.notifications

import com.nuvio.app.core.telemetry.api.TelemetryAccess

/**
 * What the alert feature tells telemetry. Every event is built here from closed vocabularies so nothing free-form can
 * reach the sink: no titles, ids, times of day or learned minutes, and no property named `code`, `state`, `url` or
 * `token` (the privacy scrubber drops those). The send policy decides what each event does: the first three are SENT
 * (a handful per person), the counters are folded into `session_health`.
 */
internal object AlertTelemetry {
    const val SETTING_CHANGED = "alert_setting_changed"
    const val PERMISSION_RESULT = "alert_permission_result"
    const val AFTERMATH = "alert_aftermath"

    const val PLANNED = "alerts_planned"
    const val SKIPPED_OPENED_TODAY = "alerts_skipped_opened_today"
    const val DELAYED_QUIET = "alerts_delayed_quiet"
    const val CAPPED = "alerts_capped"
    const val META_FETCHES = "alert_meta_fetches"

    /** The events sent one by one. */
    val sentEvents: Set<String> = setOf(SETTING_CHANGED, PERMISSION_RESULT, AFTERMATH)

    /** The events counted into `session_health`. The property `n` is how many (default 1). */
    val counterEvents: Set<String> = setOf(PLANNED, SKIPPED_OPENED_TODAY, DELAYED_QUIET, CAPPED, META_FETCHES)

    /** Where the events go. Tests replace it; the default is the telemetry port registered at start-up. */
    var sink: (String, Map<String, Any>) -> Unit = { event, props -> TelemetryAccess.emit(event, props) }

    /** Which choice changed. */
    enum class Setting(val wire: String) {
        EpisodeAlerts("episode_alerts"),
        AllPaused("all_paused"),
        PrivateNames("private_names"),
        QuietHours("quiet_hours"),
        Cap("cap"),
        TimingMode("timing_mode"),
        AwayReminder("away_reminder"),
        EndOfEpisodePrompt("end_of_episode_prompt"),
    }

    /** Which control asked for the OS permission. */
    enum class PermissionEntry(val wire: String) {
        Prompt("prompt"),
        Settings("settings"),
        Bell("bell"),
        Chip("chip"),
    }

    class Event(val name: String, val props: Map<String, Any>)

    fun onOff(on: Boolean): String = if (on) "on" else "off"

    fun capValue(cap: AlertCap): String = when (cap) {
        AlertCap.PerDay -> "per_day"
        AlertCap.ThreePerWeek -> "three_per_week"
        AlertCap.OnePerWeek -> "one_per_week"
    }

    fun awayValue(choice: AwayReminder): String = if (choice == AwayReminder.Off) "off" else "days_${choice.days}"

    fun settingChanged(setting: Setting, value: String): Event =
        Event(SETTING_CHANGED, mapOf("setting" to setting.wire, "value" to value))

    fun permissionResult(granted: Boolean, entry: PermissionEntry): Event =
        Event(PERMISSION_RESULT, mapOf("result" to if (granted) "granted" else "denied", "entry" to entry.wire))

    fun aftermath(summary: AftermathSummary): Event = Event(
        AFTERMATH,
        mapOf(
            "delivered" to summary.delivered.coerceIn(0, MaxCount),
            "acted" to summary.acted.coerceIn(0, MaxCount),
            "backoff_tier" to summary.backoffTier.coerceIn(0, 2),
        ),
    )

    /** The counters one planning pass earns. Zeroes are left out: an idle pass reports nothing. */
    fun counters(planned: Int, stats: GateStats, metaFetches: Int): List<Event> = listOf(
        PLANNED to planned,
        SKIPPED_OPENED_TODAY to stats.skippedOpenedToday,
        DELAYED_QUIET to stats.delayedQuiet,
        CAPPED to stats.capped,
        META_FETCHES to metaFetches,
    ).filter { (_, n) -> n > 0 }.map { (name, n) -> Event(name, mapOf("n" to n.coerceAtMost(MaxCount))) }

    fun send(event: Event) {
        runCatching { sink(event.name, event.props) }
    }

    fun send(events: List<Event>) = events.forEach(::send)

    private const val MaxCount = 50
}
