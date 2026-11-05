package com.nuvio.app.features.search

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX180: IPTV rows (local, fast) were held back until the slowest add-on settled — up to the 60 s
 * text-request limit. The in-flight state must show whatever is ready, IPTV included.
 */
class SearchInterimPolicyTest {

    @Test
    fun `IPTV rows publish while every add-on is still pending`() {
        val state = SearchInterimPolicy.interim(addonSections = emptyList(), iptvRows = listOf(iptv("tv")))

        assertEquals(listOf("tv"), state?.sections?.map { it.key })
        assertTrue(state!!.isLoading, "add-ons still running, so the screen keeps its loading state")
    }

    @Test
    fun `IPTV rows follow the add-on rows already arrived`() {
        val state = SearchInterimPolicy.interim(listOf(addon("a1"), addon("a2")), listOf(iptv("tv")))

        assertEquals(listOf("a1", "a2", "tv"), state?.sections?.map { it.key }, "same order as the settled state")
    }

    @Test
    fun `add-on rows alone still publish before the IPTV lane answers`() {
        val state = SearchInterimPolicy.interim(listOf(addon("a1")), iptvRows = null)

        assertEquals(listOf("a1"), state?.sections?.map { it.key })
        assertTrue(state!!.isLoading)
    }

    @Test
    fun `nothing ready publishes nothing so the current state stays`() {
        assertNull(SearchInterimPolicy.interim(emptyList(), iptvRows = null), "lane still running")
        assertNull(SearchInterimPolicy.interim(emptyList(), iptvRows = emptyList()), "lane answered with no hits")
    }

    private fun addon(key: String) = section(key, "movie")
    private fun iptv(key: String) = section(key, "movie")

    private fun section(key: String, type: String) = HomeCatalogSection(
        key = key,
        title = key,
        subtitle = "",
        addonName = key,
        target = CatalogTarget.Library(contentType = type, sectionType = "x"),
        items = listOf(MetaPreview(id = "id:$key", type = type, name = key)),
    )
}
