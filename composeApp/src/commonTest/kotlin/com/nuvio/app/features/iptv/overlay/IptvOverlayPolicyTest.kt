package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.features.iptv.overlay.IptvChannelOverlayPolicy.Tagged
import com.nuvio.app.features.iptv.overlay.IptvCategoryOverlayPolicy.TaggedCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class IptvOverlayPolicyTest {

    private fun ch(entity: String, idx: Int) = Tagged(entity, idx, entity)

    private val raw = listOf(ch("a", 0), ch("b", 1), ch("c", 2), ch("d", 3))

    @Test
    fun `no overlay preserves provider order`() {
        assertEquals(listOf("a", "b", "c", "d"), IptvChannelOverlayPolicy.displayed(raw, emptyMap()))
    }

    @Test
    fun `hidden channels are dropped`() {
        val overlay = mapOf("b" to ChannelOverlay(hidden = true))
        assertEquals(listOf("a", "c", "d"), IptvChannelOverlayPolicy.displayed(raw, overlay))
    }

    @Test
    fun `pinned channels sort first keeping relative order`() {
        val overlay = mapOf("c" to ChannelOverlay(pinned = true), "d" to ChannelOverlay(pinned = true))
        assertEquals(listOf("c", "d", "a", "b"), IptvChannelOverlayPolicy.displayed(raw, overlay))
    }

    @Test
    fun `manual position reorders within the unpinned group`() {
        // give 'd' position 0 so it leads the unpinned rows; others keep provider order
        val overlay = mapOf("d" to ChannelOverlay(position = 0))
        assertEquals(listOf("d", "a", "b", "c"), IptvChannelOverlayPolicy.displayed(raw, overlay))
    }

    @Test
    fun `rename is applied through withName`() {
        val overlay = mapOf("a" to ChannelOverlay(rename = "Alpha"))
        val out = IptvChannelOverlayPolicy.displayed(raw, overlay, withName = { _, n -> n })
        assertEquals(listOf("Alpha", "b", "c", "d"), out)
    }

    @Test
    fun `honorOrder false keeps provider order but still hides and renames`() {
        val overlay = mapOf("a" to ChannelOverlay(pinned = true, rename = "A"), "b" to ChannelOverlay(hidden = true))
        val out = IptvChannelOverlayPolicy.displayed(raw, overlay, honorOrder = false, withName = { _, n -> n })
        assertEquals(listOf("A", "c", "d"), out) // 'a' NOT moved to front (paging), but renamed; 'b' hidden
    }

    // --- BUG #1: the flat live guide floated pins across the WHOLE account (should be per-category) ---

    private data class CatRow(val id: String, val cat: String, val title: String)

    private val twoCategories = listOf(
        Tagged("a1", 0, CatRow("a1", "A", "A1")),
        Tagged("a2", 1, CatRow("a2", "A", "A2")),
        Tagged("b1", 2, CatRow("b1", "B", "B1")),
        Tagged("b2", 3, CatRow("b2", "B", "B2")),
    )

    @Test
    fun `displayedByCategory floats a pin only within its own category`() {
        val overlay = mapOf("b2" to ChannelOverlay(pinned = true))

        // Contrast — the flat guide floats the pin above the WHOLE account (the pin-leak bug this fixes).
        assertEquals(
            listOf("b2", "a1", "a2", "b1"),
            IptvChannelOverlayPolicy.displayed(twoCategories, overlay).map { it.id },
            "flat displayed() floats b2 to the absolute front — documents the bug",
        )

        // Per-category — b2 floats only to the top of its OWN (B) block: after A's rows, before b1.
        assertEquals(
            listOf("a1", "a2", "b2", "b1"),
            IptvChannelOverlayPolicy.displayedByCategory(twoCategories, overlay, categoryOf = { it.cat }).map { it.id },
            "b2 floats to the top of the B block only, never above category A",
        )
    }

    @Test
    fun `displayedByCategory hides and renames within a category`() {
        val overlay = mapOf(
            "a2" to ChannelOverlay(hidden = true),
            "b1" to ChannelOverlay(rename = "Renamed"),
        )
        val out = IptvChannelOverlayPolicy.displayedByCategory(
            twoCategories, overlay, categoryOf = { it.cat }, withName = { r, n -> r.copy(title = n) },
        )
        assertEquals(
            listOf("A1", "Renamed", "B2"), out.map { it.title },
            "a2 dropped inside A; b1 renamed inside B; each category keeps its own order",
        )
    }

    // --- BUG #2: the browse hub is a PAGED surface — hide + rename apply, order must NOT change ---

    private data class WindowRow(val entity: String, val title: String)

    @Test
    fun `displayedWindow hides and renames and floats a pin to the top of the window`() {
        val window = listOf(
            WindowRow("e0", "Zero"), WindowRow("e1", "One"), WindowRow("e2", "Two"), WindowRow("e3", "Three"),
        )
        val overlay = mapOf(
            // position WITHOUT a pin must NOT reorder on a paged surface — the target slot could be on a
            // page not fetched yet — so e0 stays in provider order.
            "e0" to ChannelOverlay(position = 0),
            "e1" to ChannelOverlay(hidden = true),
            "e2" to ChannelOverlay(rename = "TWO!"),
            // a pin floats to the top of the window, so it sits at the top of the browse too (matches the guide).
            "e3" to ChannelOverlay(pinned = true),
        )
        val out = IptvChannelOverlayPolicy.displayedWindow(
            rows = window, overlay = overlay, entityOf = { it.entity }, withName = { r, n -> r.copy(title = n) },
        )
        assertEquals(
            listOf("Three", "Zero", "TWO!"), out.map { it.title },
            "paged window: e3 (pinned) floats to top; e1 hidden; e2 renamed; e0 (position-only) stays in provider order",
        )
    }

    @Test
    fun `displayedWindow with an empty overlay leaves the window untouched`() {
        val window = listOf(WindowRow("e0", "Zero"), WindowRow("e1", "One"))
        assertEquals(
            window,
            IptvChannelOverlayPolicy.displayedWindow(window, emptyMap(), entityOf = { it.entity }),
            "no overlay edits — the fetched window is returned as-is",
        )
    }

    // --- pin INDICATOR: the marker the guide + hub draw reads the same overlay as the float ---

    @Test
    fun `isPinned is true only for a pinned entity`() {
        val overlay = mapOf(
            "pinned" to ChannelOverlay(pinned = true),
            "hiddenOnly" to ChannelOverlay(hidden = true),
            "renamedOnly" to ChannelOverlay(rename = "X"),
        )
        assertEquals(true, IptvChannelOverlayPolicy.isPinned(overlay, "pinned"), "pinned entity reads pinned")
        assertEquals(false, IptvChannelOverlayPolicy.isPinned(overlay, "hiddenOnly"), "an edit that isn't a pin is not pinned")
        assertEquals(false, IptvChannelOverlayPolicy.isPinned(overlay, "renamedOnly"), "a rename alone is not a pin")
    }

    @Test
    fun `isPinned is false for an unknown id or empty overlay`() {
        assertEquals(false, IptvChannelOverlayPolicy.isPinned(mapOf("a" to ChannelOverlay(pinned = true)), "missing"), "id with no overlay edit is not pinned")
        assertEquals(false, IptvChannelOverlayPolicy.isPinned(emptyMap(), "a"), "empty overlay pins nothing")
    }

    @Test
    fun `categories hide reorder and rename`() {
        val cats = listOf(
            TaggedCategory("k1", 0, "1", "News"),
            TaggedCategory("k2", 1, "2", "Sports"),
            TaggedCategory("k3", 2, "3", "Movies"),
        )
        val overlay = mapOf(
            "k3" to CategoryOverlay(pinned = true, rename = "Cinema"),
            "k1" to CategoryOverlay(hidden = true),
        )
        val out = IptvCategoryOverlayPolicy.displayed(cats, overlay, emptyList())
        assertEquals(listOf("Cinema" to "3", "Sports" to "2"), out.map { it.name to it.id })
    }

    @Test
    fun `custom groups appear above provider categories in position order`() {
        val cats = listOf(TaggedCategory("k1", 0, "1", "News"), TaggedCategory("k2", 1, "2", "Sports"))
        val groups = listOf(
            CustomGroup("g2", "live", null, "Weekend", 1, memberEntityIds = listOf("x")),
            CustomGroup("g1", "live", null, "Favourites", 0, memberEntityIds = listOf("a", "b")),
            CustomGroup("gEmpty", "live", null, "Empty", 2, memberEntityIds = emptyList()),
        )
        val out = IptvCategoryOverlayPolicy.displayed(cats, emptyMap(), groups)
        assertEquals(listOf("Favourites", "Weekend", "News", "Sports"), out.map { it.name })
        assertEquals(true, out[0].custom)
        assertEquals(listOf("a", "b"), out[0].memberEntityIds)
        assertEquals(false, out[2].custom) // empty group suppressed; News is a provider category
    }
}
