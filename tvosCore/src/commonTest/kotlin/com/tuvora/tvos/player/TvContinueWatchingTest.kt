package com.tuvora.tvos.player

import com.tuvora.tvos.screens.TvContinueWatching
import com.tuvora.tvos.screens.TvCwItem
import kotlin.test.Test
import kotlin.test.assertEquals

class TvContinueWatchingTest {
    private fun item(series: String, video: String, updated: Long, nextUp: Boolean = false, pos: Long = 0, dur: Long = 0, s: Int? = null, e: Int? = null) =
        TvCwItem(series, "series", video, "T", null, s, e, null, null, null, pos, dur, updated, nextUp)

    @Test
    fun `newest first and next up hidden for a series already in progress`() {
        val merged = TvContinueWatching.merge(
            inProgress = listOf(item("a", "a:1:2", 10), item("b", "b:1:1", 30)),
            nextUp = listOf(item("a", "a:1:3", 50, nextUp = true), item("c", "c:2:1", 20, nextUp = true)),
        )
        assertEquals(listOf("b", "c", "a"), merged.map { it.parentMetaId })
    }

    @Test
    fun `the row is capped`() {
        val many = (0 until 60).map { item("s$it", "v$it", it.toLong()) }
        assertEquals(TvContinueWatching.MAX_ITEMS, TvContinueWatching.merge(many, emptyList()).size)
    }

    @Test
    fun `badges read like the TV app`() {
        assertEquals("8m left", TvContinueWatching.badge(item("a", "v", 0, pos = 52 * 60_000L, dur = 60 * 60_000L)))
        assertEquals("1h 49m left", TvContinueWatching.badge(item("a", "v", 0, pos = 11 * 60_000L, dur = 120 * 60_000L)))
        assertEquals("Next Up", TvContinueWatching.badge(item("a", "v", 0, nextUp = true)))
        assertEquals("S2 E1", TvContinueWatching.episodeLabel(item("a", "v", 0, s = 2, e = 1)))
    }

    @Test
    fun `minutes left is null when there is nothing to count down`() {
        assertEquals(109, TvContinueWatching.minutesLeft(item("a", "v", 0, pos = 11 * 60_000L, dur = 120 * 60_000L)))
        assertEquals(null, TvContinueWatching.minutesLeft(item("a", "v", 0, nextUp = true, dur = 60 * 60_000L)))
        assertEquals(null, TvContinueWatching.minutesLeft(item("a", "v", 0, pos = 10L)))
        assertEquals(null, TvContinueWatching.minutesLeft(item("a", "v", 0, pos = 60 * 60_000L, dur = 60 * 60_000L)))
    }

    @Test
    fun `resume first puts a half watched title ahead of a fresher next up`() {
        val now = 100L * 24 * 3_600_000
        val hour = 3_600_000L
        val merged = TvContinueWatching.merge(
            inProgress = listOf(item("film", "film", now - 12 * hour, pos = 500, dur = 1000)),
            nextUp = listOf(item("show", "show:1:2", now - 2 * hour, nextUp = true)),
            resumeFirst = true,
            nowMs = now,
        )
        assertEquals(listOf("film", "show"), merged.map { it.parentMetaId })
    }

    @Test
    fun `resume first off keeps newest first`() {
        val now = 100L * 24 * 3_600_000
        val hour = 3_600_000L
        val merged = TvContinueWatching.merge(
            inProgress = listOf(item("film", "film", now - 12 * hour, pos = 500, dur = 1000)),
            nextUp = listOf(item("show", "show:1:2", now - 2 * hour, nextUp = true)),
            resumeFirst = false,
            nowMs = now,
        )
        assertEquals(listOf("show", "film"), merged.map { it.parentMetaId })
    }
}
