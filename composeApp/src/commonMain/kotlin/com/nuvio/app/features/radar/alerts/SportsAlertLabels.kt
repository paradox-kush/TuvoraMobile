package com.nuvio.app.features.radar.alerts

/** A chip as the hub / game page draw it: text, highlighted, tappable. */
data class AlertChip(val label: String, val selected: Boolean, val enabled: Boolean)

/**
 * Every alerts label the settings, the hub chip and the game chip show, in one pure place (text as literals: the sports
 * screens' convention). Apple TV and NuvioTV read the same strings.
 */
object SportsAlertLabels {
    const val SIGN_IN = "Sign in for alerts"

    fun level(level: AlertLevel): String = when (level) {
        AlertLevel.All -> "Everything"
        AlertLevel.StartAndFinal -> "Start and full time"
        AlertLevel.FinalOnly -> "Full time only"
        AlertLevel.Off -> "Off"
    }

    fun nextLevel(level: AlertLevel): AlertLevel = AlertLevel.entries[(level.ordinal + 1) % AlertLevel.entries.size]

    fun perHour(value: Int): String = if (value == 0) "No limit" else "$value an hour"

    fun batch(seconds: Int): String = when (seconds) {
        0 -> "Off"
        60 -> "1 min"
        else -> "${seconds / 60} min"
    }

    fun duringPlayback(value: DuringPlayback): String = when (value) {
        DuringPlayback.Always -> "Always"
        DuringPlayback.LiveTvOnly -> "Live TV only"
        DuringPlayback.Never -> "Never"
    }

    fun nextDuringPlayback(value: DuringPlayback): DuringPlayback =
        DuringPlayback.entries[(value.ordinal + 1) % DuringPlayback.entries.size]

    /** "22:00". */
    fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** Half-hour steps around the clock, like the episode alerts' quiet hours. */
    fun stepClock(minuteOfDay: Int, steps: Int): Int =
        ((minuteOfDay + steps * 30) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /** The hub header's bell chip beside "Scores shown": `Alerts on` / `Alerts off`; disabled when signed out. */
    fun hubChip(settings: SportsAlertSettings, signedIn: Boolean): AlertChip =
        if (!signedIn) AlertChip(SIGN_IN, selected = false, enabled = false)
        else AlertChip(if (settings.enabled) "Alerts on" else "Alerts off", selected = settings.enabled, enabled = true)

    /**
     * The game page chip `Alerts: Default / All / Off`, only for a game in a followed league or with a followed team
     * (null otherwise). Signed out: disabled, `Sign in for alerts`.
     */
    fun gameChip(settings: SportsAlertSettings, eventId: String, followed: Boolean, signedIn: Boolean, nowMs: Long): AlertChip? {
        if (!followed) return null
        if (!signedIn) return AlertChip(SIGN_IN, selected = false, enabled = false)
        val override = settings.gameOverride(eventId, nowMs)
        val label = when (override) {
            AlertLevel.All -> "Alerts: All"
            AlertLevel.Off -> "Alerts: Off"
            else -> "Alerts: Default"
        }
        return AlertChip(label, selected = override != null, enabled = settings.enabled)
    }

    /** The game chip's cycle: Default → All → Off → Default (null = Default). */
    fun nextGameLevel(current: AlertLevel?): AlertLevel? = when (current) {
        null -> AlertLevel.All
        AlertLevel.All -> AlertLevel.Off
        else -> null
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
