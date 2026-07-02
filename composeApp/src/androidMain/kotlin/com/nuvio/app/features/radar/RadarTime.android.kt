package com.nuvio.app.features.radar

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal actual object RadarTime {
    actual fun nowMs(): Long = System.currentTimeMillis()

    actual fun formatTime(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

    actual fun dayLabel(epochMs: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = epochMs }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay(target, today) -> "Today"
            sameDay(target, tomorrow) -> "Tomorrow"
            else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(epochMs))
        }
    }
}
