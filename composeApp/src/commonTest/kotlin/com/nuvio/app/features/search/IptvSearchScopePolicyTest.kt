package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvSearchScopePolicyTest {
    private val rows = listOf("addon_movies", "xtream_channels", "xtream_movies", "xtream_series", "addon_series")
    private fun filter(sel: Set<IptvSearchScope>) = IptvSearchScopePolicy.filter(rows, sel) { it }

    @Test
    fun `default selection is all and keeps every row`() {
        assertEquals(rows, filter(IptvSearchScopePolicy.ALL))
    }

    @Test
    fun `one scope keeps only that IPTV row and leaves add-on rows alone`() {
        assertEquals(listOf("addon_movies", "xtream_movies", "addon_series"), filter(setOf(IptvSearchScope.MOVIES)))
    }

    @Test
    fun `multi-select keeps the union in original order`() {
        assertEquals(
            listOf("addon_movies", "xtream_channels", "xtream_series", "addon_series"),
            filter(setOf(IptvSearchScope.SERIES, IptvSearchScope.LIVE)),
        )
    }

    @Test
    fun `toggling off one of all narrows and toggling the last off goes back to all`() {
        val two = IptvSearchScopePolicy.toggle(IptvSearchScopePolicy.ALL, IptvSearchScope.LIVE)
        assertEquals(setOf(IptvSearchScope.MOVIES, IptvSearchScope.SERIES), two)
        val one = IptvSearchScopePolicy.toggle(two, IptvSearchScope.MOVIES)
        assertEquals(setOf(IptvSearchScope.SERIES), one)
        assertEquals(IptvSearchScopePolicy.ALL, IptvSearchScopePolicy.toggle(one, IptvSearchScope.SERIES))
        assertEquals(setOf(IptvSearchScope.SERIES, IptvSearchScope.LIVE), IptvSearchScopePolicy.toggle(one, IptvSearchScope.LIVE))
    }

    @Test
    fun `chips only matter when IPTV rows exist`() {
        assertTrue(IptvSearchScopePolicy.hasIptvRows(rows) { it })
        assertFalse(IptvSearchScopePolicy.hasIptvRows(listOf("addon_movies")) { it })
    }

    @Test
    fun `scope keys match the rows the Xtream search emits`() {
        assertEquals(listOf("xtream_channels", "xtream_movies", "xtream_series"), IptvSearchScope.entries.map { it.sectionKey })
    }
}
