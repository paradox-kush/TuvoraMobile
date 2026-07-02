package com.nuvio.app.features.radar

/**
 * Device-local display formatting for fixture times. Platform-actual because commonMain has
 * no timezone database (kotlinx-datetime is deliberately not a dependency here).
 */
internal expect object RadarTime {
    /** Wall-clock now in epoch ms (commonMain has no System.currentTimeMillis). */
    fun nowMs(): Long

    /** Local clock time, e.g. "2:00 PM" / "14:00" per device locale. */
    fun formatTime(epochMs: Long): String

    /** "Today" / "Tomorrow" / short local date like "Sat, Jul 11". */
    fun dayLabel(epochMs: Long): String
}

/** "Today 2:00 PM" style label used on match cards. */
internal fun radarWhenLabel(epochMs: Long): String =
    "${RadarTime.dayLabel(epochMs)} ${RadarTime.formatTime(epochMs)}"
