package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreferredFilterPolicyTest {
    private val groups = listOf(
        AddonStreamGroup(addonName = "A", addonId = "a", streams = emptyList(), isLoading = false),
        AddonStreamGroup(addonName = "B", addonId = "b", streams = emptyList(), isLoading = false),
    )

    @Test
    fun `first load applies the last played source`() {
        assertTrue(PreferredFilterPolicy.shouldApply(filterTouched = false, preferredAddonId = "b", groups = groups))
    }

    @Test
    fun `waits until the preferred source has a group`() {
        assertFalse(PreferredFilterPolicy.shouldApply(false, "c", groups))
        assertFalse(PreferredFilterPolicy.shouldApply(false, "a", emptyList()))
    }

    @Test
    fun `re-entry after playback keeps the chip the user left selected`() {
        assertFalse(PreferredFilterPolicy.shouldApply(filterTouched = true, preferredAddonId = "b", groups = groups))
    }

    @Test
    fun `no stored source means nothing to apply`() {
        assertFalse(PreferredFilterPolicy.shouldApply(false, null, groups))
    }

    @Test
    fun `selecting a filter marks it touched and a fresh load clears it`() {
        val fresh = StreamsUiState(requestToken = "t")
        assertFalse(fresh.filterTouched)
        val touched = fresh.withSelectedFilter(null)
        assertTrue(touched.filterTouched)
        assertEquals(null, touched.selectedFilter)
        assertEquals("a", fresh.withSelectedFilter("a").selectedFilter)
    }
}
