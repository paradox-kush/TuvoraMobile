package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvTopShelfPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun item(id: String, nextUp: Boolean = false, airs: Long? = null) = TvCwItem(
        parentMetaId = id, parentMetaType = "series", videoId = "$id:1:1", title = id, episodeTitle = null,
        seasonNumber = 1, episodeNumber = 1, artwork = null, logo = null, background = null,
        positionMs = 0, durationMs = 0, lastUpdatedEpochMs = 0, isNextUp = nextUp, releasedAtMs = airs,
    )

    @Test
    fun `continue watching keeps the app order and caps at twelve`() {
        val items = (1..20).map { item("s$it") }
        val picked = TvTopShelfPolicy.continueWatching(items)
        assertEquals(items.take(12).map { it.parentMetaId }, picked.map { it.parentMetaId }, "first twelve in the app's order")
    }

    @Test
    fun `new episodes keeps next ups aired in the last 14 days newest first`() {
        val items = listOf(
            item("old", nextUp = true, airs = now - 20 * day),
            item("recent", nextUp = true, airs = now - 2 * day),
            item("yesterday", nextUp = true, airs = now - day),
        )
        assertEquals(listOf("yesterday", "recent"), TvTopShelfPolicy.recentlyAiredEpisodes(items, now).map { it.parentMetaId }, "older than 14 days dropped")
    }

    @Test
    fun `new episodes writes unaired next ups so the extension can show them at air time`() {
        val items = listOf(item("tonight", nextUp = true, airs = now + 3 * 60 * 60 * 1000))
        assertEquals(listOf("tonight"), TvTopShelfPolicy.recentlyAiredEpisodes(items, now).map { it.parentMetaId }, "unaired is written")
    }

    @Test
    fun `new episodes ignores far future items resume items and items without an air date`() {
        val items = listOf(
            item("farFuture", nextUp = true, airs = now + 30 * day),
            item("resume", nextUp = false, airs = now - day),
            item("undated", nextUp = true, airs = null),
        )
        assertEquals(emptyList(), TvTopShelfPolicy.recentlyAiredEpisodes(items, now).map { it.parentMetaId }, "none qualify")
    }

    @Test
    fun `new episodes caps at six`() {
        val items = (1..10).map { item("s$it", nextUp = true, airs = now - it * 1000L) }
        assertEquals(6, TvTopShelfPolicy.recentlyAiredEpisodes(items, now).size, "capped")
    }

    @Test
    fun `recent channels cap at six in order`() {
        assertEquals((1..6).toList(), TvTopShelfPolicy.recentChannels((1..9).toList()), "first six")
    }
}
