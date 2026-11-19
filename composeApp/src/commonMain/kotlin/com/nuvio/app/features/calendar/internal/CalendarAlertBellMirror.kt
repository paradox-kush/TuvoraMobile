package com.nuvio.app.features.calendar.internal

import com.nuvio.app.core.contracts.ShowAlertBellMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the Calendar's bell equal to a show's alert switch in Settings > Notifications. One switch drives both, so a
 * bell turned on from a show's page alerts, and an alert turned off in Settings silences the bell. Never calls back
 * into the alert switch, so the two cannot echo.
 */
internal class CalendarAlertBellMirror(private val repository: () -> CalendarRepository) : ShowAlertBellMirror {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun mirrorBell(contentType: String, contentId: String, on: Boolean) {
        if (calendarTitleTypeFor(contentType) != CalendarTitleType.SERIES) return
        val ids = CalendarIds.fromContentId(contentId)
        if (ids.isEmpty) return
        scope.launch { runCatching { repository().setBell(CalendarTitleType.SERIES, ids, on) } }
    }
}
