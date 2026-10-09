package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvGuideDataRefreshTest {
    private val window = 1_000L
    private val rows = listOf("a", "b", "c", "d")

    private fun keys(vararg ids: String) = ids.map { TvGuideEpgPrefetch.key(it, window) }.toSet()

    @Test
    fun `only a later generation counts as new data`() {
        assertFalse(TvGuideDataRefresh.changed(seenGeneration = 3, generation = 3))
        assertTrue(TvGuideDataRefresh.changed(seenGeneration = 3, generation = 4))
    }

    @Test
    fun `regression - rows stamped empty before the ingest landed are asked again`() {
        // The guide opened mid-ingest: all four rows asked, all answered empty.
        val asked = keys("a", "b", "c", "d")
        // Old behaviour: the once-only stamps made the settled request a no-op after the data landed.
        assertTrue(TvGuideEpgPrefetch.pending(rows, window, asked).isEmpty())

        val plan = TvGuideDataRefresh.plan(asked, answeredKeys = emptySet(), shownRowIds = rows, focusedId = "b", windowStartMs = window)

        // Every empty row's stamp is forgotten, so the next request asks for them again - focused first.
        assertEquals(asked, plan.dropKeys)
        assertEquals(listOf("b", "a", "c", "d"), plan.rowIds)
        assertEquals(rows.toSet(), TvGuideEpgPrefetch.pending(rows, window, asked - plan.dropKeys).map { it.substringBefore('@') }.toSet())
    }

    @Test
    fun `rows that already show a programme are left alone but the focused channel is re-asked`() {
        val asked = keys("a", "b", "c")
        val plan = TvGuideDataRefresh.plan(asked, answeredKeys = keys("a", "b"), shownRowIds = listOf("a", "b", "c"), focusedId = "a", windowStartMs = window)
        assertEquals(listOf("a", "c"), plan.rowIds)
        assertEquals(keys("a", "c"), plan.dropKeys)
        // b keeps its stamp: no request for a row that already answered.
        assertFalse(TvGuideEpgPrefetch.key("b", window) in plan.dropKeys)
    }

    @Test
    fun `an unfocused-or-unknown channel falls back to the first shown row`() {
        val plan = TvGuideDataRefresh.plan(emptySet(), emptySet(), rows, focusedId = "zzz", windowStartMs = window)
        assertEquals("a", plan.rowIds.first())
        assertTrue(plan.dropKeys.isEmpty())  // nothing was stamped, so nothing to forget
    }

    @Test
    fun `a guide that has asked nothing yet has nothing to re-ask`() {
        val plan = TvGuideDataRefresh.plan(emptySet(), emptySet(), emptyList(), focusedId = null, windowStartMs = window)
        assertTrue(plan.rowIds.isEmpty())
        assertTrue(plan.dropKeys.isEmpty())
    }

    @Test
    fun `stamps of other windows are not touched`() {
        val other = TvGuideEpgPrefetch.key("a", 9_999L)
        val plan = TvGuideDataRefresh.plan(keys("a") + other, emptySet(), listOf("a"), "a", window)
        assertEquals(keys("a"), plan.dropKeys)
    }
}
