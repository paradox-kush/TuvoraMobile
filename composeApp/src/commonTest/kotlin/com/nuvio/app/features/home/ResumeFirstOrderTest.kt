package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeFirstOrderTest {
    private val now = 100L * ResumeRankPolicy.DAY_MS
    private val hour = ResumeRankPolicy.HOUR_MS

    private fun movie(id: String, updatedAt: Long, position: Long = 500_000L) = WatchProgressEntry(
        contentType = "movie", parentMetaId = id, parentMetaType = "movie", videoId = id, title = id,
        lastPositionMs = position, durationMs = 1_000_000L, lastUpdatedEpochMs = updatedAt,
    )

    private fun nextUpItem(seriesId: String) = ContinueWatchingItem(
        parentMetaId = seriesId, parentMetaType = "series", videoId = "$seriesId:1:2", title = seriesId,
        subtitle = "Next Up", imageUrl = null, seasonNumber = 1, episodeNumber = 2, isNextUp = true,
        resumePositionMs = 0L, durationMs = 0L, progressFraction = 0f,
    )

    private fun build(resumeFirst: Boolean, sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT) =
        buildHomeContinueWatchingItems(
            visibleEntries = listOf(movie("film", now - 12 * hour), movie("old", now - 5 * hour, position = 980_000L)),
            nextUpItemsBySeries = mapOf("show" to ((now - 2 * hour) to nextUpItem("show"))),
            sortMode = sortMode,
            resumeFirst = resumeFirst,
            nowEpochMs = now,
        ).map(ContinueWatchingItem::parentMetaId)

    @Test
    fun `most recent first returns exactly today's order`() {
        // Regression guard: recency (newest first), untouched by the resume-first work.
        val expected = listOf("show", "old", "film")
        assertEquals(expected, build(resumeFirst = false))
        assertEquals(expected, build(resumeFirst = false, sortMode = ContinueWatchingSortMode.SPLIT_UPCOMING))
        // The parameters added for resume-first default to "off": a call that does not know about them is unchanged.
        val legacyCall = buildHomeContinueWatchingItems(
            visibleEntries = listOf(movie("film", now - 12 * hour), movie("old", now - 5 * hour, position = 980_000L)),
            nextUpItemsBySeries = mapOf("show" to ((now - 2 * hour) to nextUpItem("show"))),
        ).map(ContinueWatchingItem::parentMetaId)
        assertEquals(expected, legacyCall)
    }

    @Test
    fun `pick up where I left off puts the half watched film first and the nearly finished one last`() {
        assertEquals(listOf("film", "show", "old"), build(resumeFirst = true))
    }

    @Test
    fun `played on this device lifts a title in resume first order`() {
        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(movie("a", now - 5 * hour), movie("b", now - 8 * hour)),
            nextUpItemsBySeries = emptyMap(),
            resumeFirst = true,
            devicePlayedIds = setOf("b"),
            nowEpochMs = now,
        ).map(ContinueWatchingItem::parentMetaId)
        assertEquals(listOf("b", "a"), result)
    }
}
