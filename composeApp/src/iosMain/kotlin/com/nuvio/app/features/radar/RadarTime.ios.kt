package com.nuvio.app.features.radar

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

internal actual object RadarTime {
    actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    actual fun formatTime(epochMs: Long): String {
        val formatter = NSDateFormatter().apply {
            dateFormat = "h:mm a"
        }
        return formatter.stringFromDate(epochMs.toNsDate())
    }

    actual fun dayLabel(epochMs: Long): String {
        val calendar = NSCalendar.currentCalendar
        val date = epochMs.toNsDate()
        return when {
            calendar.isDateInToday(date) -> "Today"
            calendar.isDateInTomorrow(date) -> "Tomorrow"
            else -> NSDateFormatter().apply { dateFormat = "EEE, MMM d" }.stringFromDate(date)
        }
    }
}

private fun Long.toNsDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(this / 1000.0)
