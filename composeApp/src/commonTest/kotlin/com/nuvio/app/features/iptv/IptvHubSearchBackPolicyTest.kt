package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F5 (Android) / F6 (iPhone), sim pass 2026-11-16: Back or the left-edge swipe in the hub's search mode
 * must close the search first, and only the visible hub may take that Back (the root tab host keeps a
 * visited hub composed, so a hidden hub with its search left open must never swallow another tab's Back).
 */
class IptvHubSearchBackPolicyTest {
    @Test
    fun `an open search on the visible hub consumes back`() {
        assertTrue(IptvHubSearchBackPolicy.consumesBack(searchOpen = true, searchApplies = true, screenActive = true))
    }

    @Test
    fun `a closed search leaves back to the screen below`() {
        assertFalse(IptvHubSearchBackPolicy.consumesBack(searchOpen = false, searchApplies = true, screenActive = true))
    }

    @Test
    fun `a hidden hub never takes back from the tab on screen`() {
        assertFalse(IptvHubSearchBackPolicy.consumesBack(searchOpen = true, searchApplies = true, screenActive = false))
    }

    @Test
    fun `a search that no longer applies to the section does not consume back`() {
        assertFalse(IptvHubSearchBackPolicy.consumesBack(searchOpen = true, searchApplies = false, screenActive = true))
    }
}
