package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvLiveCacheTest {
    @Test
    fun `plan becomes bytes back bytes and cache secs`() {
        assertEquals(
            listOf("demuxer-max-bytes" to "200", "demuxer-max-back-bytes" to "100", "cache-secs" to "90"),
            MpvLiveCache.properties(200, 100, 90),
        )
    }

    @Test
    fun `an unknown cache secs leaves the engine default alone and negatives clamp`() {
        assertEquals(
            listOf("demuxer-max-bytes" to "0", "demuxer-max-back-bytes" to "5"),
            MpvLiveCache.properties(-1, 5, 0),
        )
    }

    @Test
    fun `the window is the range holding the playhead`() {
        val ranges = listOf(MpvLiveCache.Range(10.0, 20.0), MpvLiveCache.Range(100.0, 160.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 140_000), MpvLiveCache.window(ranges, 140_000))
        assertEquals(LiveSeekWindow(10_000, 20_000, 15_000), MpvLiveCache.window(ranges, 15_000))
    }

    @Test
    fun `a playhead outside every range reports the newest range with the position clamped`() {
        val ranges = listOf(MpvLiveCache.Range(100.0, 160.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 160_000), MpvLiveCache.window(ranges, 170_000))
    }

    @Test
    fun `a timeline jump leaves the playhead in the later range and the window is only that range`() {
        // A loop wrap / splice: two disjoint stretches on mpv's clock, hours apart.
        val ranges = listOf(MpvLiveCache.Range(100.0, 160.0), MpvLiveCache.Range(90_000.0, 90_060.0))
        assertEquals(LiveSeekWindow(90_000_000, 90_060_000, 90_030_000), MpvLiveCache.window(ranges, 90_030_000), "later range")
    }

    @Test
    fun `a timeline jump with the playhead in the earlier range keeps that range`() {
        val ranges = listOf(MpvLiveCache.Range(100.0, 160.0), MpvLiveCache.Range(90_000.0, 90_060.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 130_000), MpvLiveCache.window(ranges, 130_000), "earlier range")
    }

    @Test
    fun `no range holding the playhead picks the one ending latest before it whatever the order`() {
        val ranges = listOf(MpvLiveCache.Range(100.0, 160.0), MpvLiveCache.Range(5_000.0, 5_060.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 160_000), MpvLiveCache.window(ranges, 200_000), "ends before the playhead")
    }

    @Test
    fun `a playhead before every range picks the nearest one`() {
        val ranges = listOf(MpvLiveCache.Range(100.0, 160.0), MpvLiveCache.Range(5_000.0, 5_060.0))
        assertEquals(LiveSeekWindow(100_000, 160_000, 100_000), MpvLiveCache.window(ranges, 50_000), "nearest ahead")
    }

    @Test
    fun `one huge range is clamped to the keep window behind its end`() {
        val ranges = listOf(MpvLiveCache.Range(0.0, 7_200.0))
        assertEquals(
            LiveSeekWindow(6_600_000, 7_200_000, 7_100_000),
            MpvLiveCache.window(ranges, 7_100_000),
            "never wider than ten minutes",
        )
        assertEquals(
            LiveSeekWindow(7_140_000, 7_200_000, 7_190_000),
            MpvLiveCache.window(ranges, 7_190_000, keepMs = 60_000),
            "custom keep",
        )
    }

    @Test
    fun `a single normal range is unchanged`() {
        assertEquals(
            LiveSeekWindow(1_000_000, 1_300_000, 1_200_000),
            MpvLiveCache.window(listOf(MpvLiveCache.Range(1_000.0, 1_300.0)), 1_200_000),
            "within keep",
        )
    }

    @Test
    fun `nothing seekable or no position is no window`() {
        assertNull(MpvLiveCache.window(emptyList(), 5_000))
        assertNull(MpvLiveCache.window(listOf(MpvLiveCache.Range(0.0, 10.0)), 0))
        assertNull(MpvLiveCache.window(listOf(MpvLiveCache.Range(5.0, 5.0), MpvLiveCache.Range(Double.NaN, 3.0)), 1_000))
    }

    @Test
    fun `behind live is the distance to the end of the window`() {
        assertEquals(20_000, LiveSeekWindow(0, 60_000, 40_000).behindLiveMs)
        assertEquals(0, LiveSeekWindow(0, 60_000, 70_000).behindLiveMs)
    }

    @Test
    fun `ranges text parses start and end seconds per range`() {
        assertEquals(
            listOf(MpvLiveCache.Range(10.5, 20.0), MpvLiveCache.Range(100.0, 160.25)),
            MpvLiveCache.parseRanges("10.5,20;100,160.25"),
        )
    }

    @Test
    fun `ranges text drops malformed entries and keeps the good ones`() {
        assertEquals(
            listOf(MpvLiveCache.Range(1.0, 2.0)),
            MpvLiveCache.parseRanges("junk;1,2;3;4,x;,"),
        )
    }

    @Test
    fun `empty or blank ranges text is no ranges`() {
        assertEquals(emptyList(), MpvLiveCache.parseRanges(""))
        assertEquals(emptyList(), MpvLiveCache.parseRanges("   "))
    }

    @Test
    fun `a bridge ranges text feeds the window`() {
        assertEquals(
            LiveSeekWindow(100_000, 160_000, 140_000),
            MpvLiveCache.window(MpvLiveCache.parseRanges("100,160"), 140_000),
        )
    }
}
