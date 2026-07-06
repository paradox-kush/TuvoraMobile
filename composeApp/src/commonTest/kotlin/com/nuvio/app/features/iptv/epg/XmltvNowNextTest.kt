package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * now/next selection from stored EPG rows ([selectNowNext]) — pure, so it's covered without the DB.
 * Rows come from the DB already start-ordered with end_ms > now (an indexed range scan), so these
 * tests feed that same shape.
 */
class XmltvNowNextTest {

    private fun row(startMs: Long, endMs: Long, title: String) =
        EpgProgrammeRow(channelId = "cnn.us", startMs = startMs, endMs = endMs, title = title, desc = null)

    @Test
    fun firstAiringRowIsNowNextIsSecond() {
        val now = 1_000_000L
        val rows = listOf(
            row(now - 500, now + 500, "Airing Now"),   // window contains now
            row(now + 500, now + 1500, "Up Next"),
            row(now + 1500, now + 2500, "Later"),
        )
        val out = selectNowNext(rows, now)
        assertEquals(3, out.size)
        assertEquals("Airing Now", out[0].title)
        assertTrue(out[0].nowPlaying)
        assertEquals("Up Next", out[1].title)
        assertFalse(out[1].nowPlaying)
        assertFalse(out[2].nowPlaying)
    }

    @Test
    fun betweenProgrammesNothingIsNowPlaying() {
        val now = 1_000_000L
        // Earliest row starts in the FUTURE (channel is between programmes) -> nothing airing.
        val rows = listOf(
            row(now + 200, now + 800, "Starts Soon"),
            row(now + 800, now + 1600, "After That"),
        )
        val out = selectNowNext(rows, now)
        assertEquals(2, out.size)
        assertFalse(out[0].nowPlaying) // not airing yet, so no "now"
        assertEquals("Starts Soon", out[0].title)
    }

    @Test
    fun emptyRowsProduceEmpty() {
        assertTrue(selectNowNext(emptyList(), 1_000_000L).isEmpty())
    }

    @Test
    fun descriptionDefaultsToEmptyString() {
        val now = 5_000L
        val out = selectNowNext(listOf(row(now - 1, now + 1, "T")), now)
        assertEquals("", out.single().description)
    }
}
