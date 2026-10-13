package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.XtreamProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuideNowNextPolicyTest {
    private fun programme(title: String, start: Long, end: Long, markedNow: Boolean = false) =
        XtreamProgram(title, "", start, end, markedNow)
    private val first = programme("First", 0, 100, markedNow = true)
    private val second = programme("Second", 100, 200)
    private val third = programme("Third", 200, 300)

    @Test fun boundaryPromotesNextDespiteStaleNowFlag() {
        val result = GuideNowNextPolicy.select(listOf(first, second, third), 100)
        assertEquals(second, result.now)
        assertEquals(third, result.next)
    }
    @Test fun beforeBoundaryKeepsCurrent() {
        val result = GuideNowNextPolicy.select(listOf(first, second), 99)
        assertEquals(first, result.now)
        assertEquals(second, result.next)
    }
    @Test fun scheduleGapDoesNotPresentEndedProgrammeAsCurrent() {
        val later = programme("Later", 150, 200)
        val result = GuideNowNextPolicy.select(listOf(first, later), 125)
        assertNull(result.now)
        assertEquals(later, result.next)
    }
    @Test fun beforeFirstProgrammeShowsUpcomingOnly() {
        val result = GuideNowNextPolicy.select(listOf(second, third), 50)
        assertNull(result.now)
        assertEquals(second, result.next)
    }
    @Test fun afterLastProgrammeDoesNotResurrectFirst() {
        val result = GuideNowNextPolicy.select(listOf(first, second), 200)
        assertNull(result.now)
        assertNull(result.next)
    }
    @Test fun emptyScheduleIsEmpty() {
        val result = GuideNowNextPolicy.select(emptyList(), 100)
        assertNull(result.now)
        assertNull(result.next)
    }
    @Test fun unorderedRowsUseTheirTimeRanges() {
        val result = GuideNowNextPolicy.select(listOf(third, first, second), 100)
        assertEquals(second, result.now)
        assertEquals(third, result.next)
    }
}
