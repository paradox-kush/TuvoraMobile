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
    fun `saved state round-trips and junk or empty reads as all`() {
        val two = setOf(IptvSearchScope.LIVE, IptvSearchScope.SERIES)
        assertEquals(two, IptvSearchScopePolicy.decode(IptvSearchScopePolicy.encode(two)))
        assertEquals(IptvSearchScopePolicy.ALL, IptvSearchScopePolicy.decode(emptyList()))
        assertEquals(IptvSearchScopePolicy.ALL, IptvSearchScopePolicy.decode(listOf("BOGUS")))
    }

    @Test
    fun `chips stay up when the IPTV lane answered even with zero rows`() {
        val results = IptvSearchScopePolicy.Body.RESULTS
        val noResults = IptvSearchScopePolicy.Body.NO_RESULTS
        assertTrue(IptvSearchScopePolicy.chipsVisible(results, hasIptvRows = true, iptvAnswered = false))
        assertTrue(IptvSearchScopePolicy.chipsVisible(noResults, hasIptvRows = false, iptvAnswered = true), "zero hits keeps the filters")
        assertTrue(IptvSearchScopePolicy.chipsVisible(results, hasIptvRows = false, iptvAnswered = true))
        assertFalse(IptvSearchScopePolicy.chipsVisible(noResults, hasIptvRows = false, iptvAnswered = false), "no IPTV at all")
    }

    @Test
    fun `chips never show over skeletons or an error`() {
        assertFalse(IptvSearchScopePolicy.chipsVisible(IptvSearchScopePolicy.Body.SKELETON, true, true))
        assertFalse(IptvSearchScopePolicy.chipsVisible(IptvSearchScopePolicy.Body.ERROR, true, true))
    }

    @Test
    fun `body mirrors the screen order`() {
        assertEquals(IptvSearchScopePolicy.Body.SKELETON, IptvSearchScopePolicy.bodyOf(loadingOrPending = true, noRows = true, errorWithNoRows = false))
        assertEquals(IptvSearchScopePolicy.Body.SKELETON, IptvSearchScopePolicy.bodyOf(true, true, true))
        assertEquals(IptvSearchScopePolicy.Body.ERROR, IptvSearchScopePolicy.bodyOf(false, true, true))
        assertEquals(IptvSearchScopePolicy.Body.NO_RESULTS, IptvSearchScopePolicy.bodyOf(false, true, false))
        assertEquals(IptvSearchScopePolicy.Body.RESULTS, IptvSearchScopePolicy.bodyOf(true, false, false))
        assertEquals(IptvSearchScopePolicy.Body.RESULTS, IptvSearchScopePolicy.bodyOf(false, false, false))
    }

    @Test
    fun `empty note shows only when the selection hides IPTV rows that came back`() {
        val live = setOf(IptvSearchScope.LIVE)
        val noChannels = rows.filter { it != "xtream_channels" }
        assertTrue(IptvSearchScopePolicy.showEmptyNote(live, noChannels, true, false) { it })
        assertFalse(IptvSearchScopePolicy.showEmptyNote(live, noChannels, true, true) { it }, "still loading")
        assertFalse(IptvSearchScopePolicy.showEmptyNote(live, noChannels, false, false) { it }, "lane did not answer")
        assertFalse(IptvSearchScopePolicy.showEmptyNote(IptvSearchScopePolicy.ALL, noChannels, true, false) { it }, "nothing hidden")
        assertFalse(IptvSearchScopePolicy.showEmptyNote(live, rows, true, false) { it }, "an IPTV row survives")
        assertFalse(IptvSearchScopePolicy.showEmptyNote(live, emptyList<String>(), true, false) { it }, "no IPTV hits: not the filter's fault")
        assertFalse(IptvSearchScopePolicy.showEmptyNote(live, listOf("addon_movies"), true, false) { it }, "add-on rows only")
    }
}
