package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumePromptPickerTest {
    private val now = 100L * ResumeRankPolicy.DAY_MS
    private val hour = ResumeRankPolicy.HOUR_MS

    private fun movie(id: String, updatedAt: Long, position: Long) = WatchProgressEntry(
        contentType = "movie", parentMetaId = id, parentMetaType = "movie", videoId = id, title = id,
        lastPositionMs = position, durationMs = 1_000_000L, lastUpdatedEpochMs = updatedAt,
    )

    private fun row(resumeFirst: Boolean) = buildHomeContinueWatchingItems(
        visibleEntries = listOf(
            movie("film", now - 12 * hour, position = 500_000L),
            movie("barelyStarted", now - 1 * hour, position = 20_000L),
        ),
        nextUpItemsBySeries = emptyMap(),
        resumeFirst = resumeFirst,
        nowEpochMs = now,
    )

    private fun nextUp(id: String, released: String?) = ContinueWatchingItem(
        parentMetaId = id, parentMetaType = "series", videoId = "$id:1:2", title = id, subtitle = "Next Up",
        imageUrl = null, seasonNumber = 1, episodeNumber = 2, isNextUp = true, released = released,
        resumePositionMs = 0L, durationMs = 0L, progressFraction = 0f,
    )

    @Test
    fun `resume first order offers the half watched film not the barely started recent title`() {
        val picked = ResumePromptPicker.pick(row(resumeFirst = true), now)
        assertEquals("film", picked?.parentMetaId, "prompt must follow the ranked row")
    }

    @Test
    fun `most recent first order offers the most recent title`() {
        val picked = ResumePromptPicker.pick(row(resumeFirst = false), now)
        assertEquals("barelyStarted", picked?.parentMetaId)
    }

    @Test
    fun `empty row offers nothing`() {
        assertNull(ResumePromptPicker.pick(emptyList(), now))
    }

    @Test
    fun `a not yet aired next up at the head is skipped`() {
        val unaired = nextUp("future", released = "2099-01-01")
        val film = row(resumeFirst = true).first()
        assertEquals(film.parentMetaId, ResumePromptPicker.pick(listOf(unaired, film), now)?.parentMetaId)
    }

    @Test
    fun `a row of only unaired next ups offers nothing`() {
        assertNull(ResumePromptPicker.pick(listOf(nextUp("future", released = "2099-01-01")), now))
    }
}
