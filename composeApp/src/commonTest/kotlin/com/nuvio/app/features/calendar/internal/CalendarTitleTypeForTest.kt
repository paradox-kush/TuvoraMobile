package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarTitleTypeForTest {
    @Test
    fun `show types map to series and movie to movie`() {
        for (t in listOf("series", "TV", "Show", "anime")) {
            assertEquals(CalendarTitleType.SERIES, calendarTitleTypeFor(t), "type $t")
        }
        assertEquals(CalendarTitleType.MOVIE, calendarTitleTypeFor("movie"), "movie")
    }

    @Test
    fun `unfollowable types give no reminder`() {
        assertNull(calendarTitleTypeFor("tv_channel"), "channel")
        assertNull(calendarTitleTypeFor(""), "empty")
    }
}
