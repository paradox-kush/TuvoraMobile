package com.nuvio.app.core.ui

import com.nuvio.app.core.contracts.DetailsReminderControl
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingPickerRowsTest {
    private val watchlist = TrackingLibraryTab(
        key = "watchlist", title = "Watchlist", providerId = null, kind = TrackingLibraryTabKind.WATCHLIST,
    )

    @Test
    fun `no reminder control means lists only`() {
        assertEquals(
            listOf<TrackingPickerRow>(TrackingPickerRow.ListRow(watchlist)),
            trackingPickerRows(listOf(watchlist), null),
            "a title the Calendar cannot follow shows no reminder row",
        )
    }

    @Test
    fun `reminder row comes after the lists and mirrors on state`() {
        assertEquals(
            listOf(TrackingPickerRow.ListRow(watchlist), TrackingPickerRow.ReminderRow(on = true)),
            trackingPickerRows(listOf(watchlist), DetailsReminderControl(on = true) {}),
            "reminder row is last and carries the on state",
        )
        assertEquals(
            TrackingPickerRow.ReminderRow(on = false),
            trackingPickerRows(emptyList(), DetailsReminderControl(on = false) {}).single(),
            "the reminder row shows even before the lists have loaded",
        )
    }

    @Test
    fun `reminder row key cannot collide with a list key`() {
        assertEquals(REMINDER_ROW_KEY, TrackingPickerRow.ReminderRow(false).key, "stable lazy key")
    }
}
