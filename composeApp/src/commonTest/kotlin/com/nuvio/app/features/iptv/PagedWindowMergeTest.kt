package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The endless-scroll loop guard (mergePagedWindow). A live category on one playlist kept
 * "rotating on a loop": loadMore appended each fetched window with no dedup and re-fired on
 * every size change, so a window that returned rows already loaded (a stale index / a tied
 * ORDER BY overlapping its pages) grew the list forever. The merge must (a) drop already-seen
 * ids and (b) end paging the moment a window contributes nothing new.
 */
class PagedWindowMergeTest {

    private data class Row(val id: String)

    private fun simulateEndlessScroll(pages: List<Pair<List<Row>, Boolean>>): List<Row> {
        // Mirrors the row's LaunchedEffect(items.size) loop: keep calling loadMore while hasMore.
        var items = emptyList<Row>()
        var hasMore = true
        var guard = 0
        var page = 0
        while (hasMore) {
            if (guard++ > 10_000) error("did not terminate — loop guard failed")
            val (more, claimHasMore) = pages.getOrElse(page) { emptyList<Row>() to false }
            page++
            val (merged, stillMore) = mergePagedWindow(items, more, claimHasMore) { it.id }
            items = merged
            hasMore = stillMore
        }
        return items
    }

    @Test
    fun `a window repeating already-loaded rows terminates instead of looping`() {
        val a = Row("a"); val b = Row("b")
        // Every page returns the SAME two rows and claims there's more — the exact stale-index
        // overlap that spun forever before the guard.
        val repeating = List(50) { listOf(a, b) to true }
        val result = simulateEndlessScroll(repeating)
        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `a partially overlapping window keeps only the new rows`() {
        val (a, b, c, d) = listOf(Row("a"), Row("b"), Row("c"), Row("d"))
        val pages = listOf(
            listOf(a, b) to true,
            listOf(b, c) to true,   // b overlaps
            listOf(c, d) to true,   // c overlaps
            emptyList<Row>() to false,
        )
        assertEquals(listOf("a", "b", "c", "d"), simulateEndlessScroll(pages).map { it.id })
    }

    @Test
    fun `no new ids ends paging even when the fetch still claims hasMore`() {
        val a = Row("a")
        val (items, hasMore) = mergePagedWindow(listOf(a), listOf(a), hasMore = true) { it.id }
        assertEquals(listOf("a"), items.map { it.id })
        assertFalse(hasMore)
    }

    @Test
    fun `genuinely new rows keep paging open`() {
        val (items, hasMore) = mergePagedWindow(listOf(Row("a")), listOf(Row("b")), hasMore = true) { it.id }
        assertEquals(listOf("a", "b"), items.map { it.id })
        assertTrue(hasMore)
    }

    // B282: a provider page that lists the same stream twice put two grid items under one LazyGrid
    // key, and Compose crashed ("Key already used" / an item measured twice) seconds after the IPTV
    // tab opened. The first load deduped (08-21 fix); the load-more merge only checked ids already
    // on screen, so a repeat INSIDE the new window got through.
    @Test
    fun `a window repeating an id inside itself adds it once`() {
        val (items, hasMore) = mergePagedWindow(
            listOf(Row("a")),
            listOf(Row("b"), Row("c"), Row("b")),
            hasMore = true,
        ) { it.id }
        assertEquals(listOf("a", "b", "c"), items.map { it.id })
        assertTrue(hasMore)
    }

    @Test
    fun `merged ids are always unique so they are safe as LazyGrid keys`() {
        val pages = listOf(
            listOf(Row("a"), Row("a")) to true,
            listOf(Row("b"), Row("a"), Row("b"), Row("c")) to true,
            emptyList<Row>() to false,
        )
        val ids = simulateEndlessScroll(pages).map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(listOf("a", "b", "c"), ids)
    }
}
