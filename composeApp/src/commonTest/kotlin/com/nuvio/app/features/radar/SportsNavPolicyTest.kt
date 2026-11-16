package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsNavPolicyTest {
    @Test
    fun `reselecting the tab from a league page returns to the root`() {
        val s = SportsNavState(leaguePageOpen = true)
        assertTrue(SportsNavPolicy.onReselect(s).atRoot)
    }

    @Test
    fun `reselecting from the browse drill-down returns to the root`() {
        val s = SportsNavState(browsing = true, browseCategoryOpen = true)
        assertTrue(SportsNavPolicy.onReselect(s).atRoot)
    }

    @Test
    fun `back from a league page goes to the root not the previous list`() {
        val s = SportsNavState(browsing = true, browseCategoryOpen = true, leaguePageOpen = true)
        assertTrue(SportsNavPolicy.onBack(s)!!.atRoot)
    }

    @Test
    fun `back from a fixtures page goes to the root`() {
        assertTrue(SportsNavPolicy.onBack(SportsNavState(fixturesPageOpen = true))!!.atRoot)
    }

    @Test
    fun `back steps up the browse drill-down one level at a time`() {
        val inCategory = SportsNavState(browsing = true, browseCategoryOpen = true)
        val afterFirst = SportsNavPolicy.onBack(inCategory)!!
        assertEquals(SportsNavState(browsing = true), afterFirst)
        assertTrue(SportsNavPolicy.onBack(afterFirst)!!.atRoot)
    }

    @Test
    fun `back from the game screen only closes the game`() {
        val s = SportsNavState(leaguePageOpen = true, gameOpen = true)
        assertEquals(SportsNavState(leaguePageOpen = true), SportsNavPolicy.onBack(s))
    }

    @Test
    fun `back at the root is not consumed so the shell decides`() {
        assertNull(SportsNavPolicy.onBack(SportsNavPolicy.root))
    }
}
