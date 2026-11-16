package com.nuvio.app.features.sportsdetail.internal

/** The device's day/time formatting, as a port so the policy tests without a timezone database (the radar list's `RadarTime` implements the same two calls). */
internal interface StartTimeFormat {
    /** "Today" / "Tomorrow" / short local date, in the device zone. */
    fun dayLabel(epochMs: Long): String
    /** Local clock time, "8:15 PM" or "20:15" per device locale. */
    fun formatTime(epochMs: Long): String
}

/**
 * The start of an UPCOMING game, worded exactly like the match-list cards ("Today 8:15 PM"; the day word is the date once it is not
 * today/tomorrow) and in the device's zone - never the raw "2026-11-17 01:15 UTC" feed string. Null when [startsAt] is not a date, so the
 * caller keeps its old fallback.
 */
internal object GameStartLabel {
    fun of(startsAt: String?, format: StartTimeFormat): String? {
        val ms = Iso.parseMs(startsAt) ?: return null
        return "${format.dayLabel(ms)} ${format.formatTime(ms)}"
    }
}
