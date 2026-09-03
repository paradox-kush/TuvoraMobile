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
