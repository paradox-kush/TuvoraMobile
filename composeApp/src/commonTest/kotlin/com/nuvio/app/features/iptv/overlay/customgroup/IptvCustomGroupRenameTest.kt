package com.nuvio.app.features.iptv.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** UX143: a channel has one display name in provider and custom-group rows. */
class IptvCustomGroupRenameTest {
    private data class Card(val id: String, val name: String)
    private val raw = listOf(Card("news", "News HD"), Card("sport", "Sport HD"), Card("film", "Film HD"))
    private fun shown(edits: Map<String, ChannelOverlay>, items: List<Card> = raw) =
        IptvChannelQuickActionsPolicy.visibleInHub(
            items, edits, withName = { card, name -> card.copy(name = name) },
            entityOf = { it.id.takeUnless { id -> id == "unknown" } },
        )

    @Test
    fun `rename applies in a custom group without moving a pinned member`() {
        val edits = mapOf("sport" to ChannelOverlay(rename = "My Sport", pinned = true, position = 0))
        assertEquals(listOf(raw[0], Card("sport", "My Sport"), raw[2]), shown(edits))
    }

    @Test
    fun `rename and its undo repaint the same cached raw row`() {
        assertEquals("My News", shown(mapOf("news" to ChannelOverlay(rename = "My News")))[0].name)
        assertEquals(raw, shown(mapOf("news" to ChannelOverlay(rename = null))))
        assertSame(raw, shown(emptyMap()))
        assertEquals("News HD", raw[0].name)
    }

    @Test
    fun `hide and rename combine and unhide restores the member in group order`() {
        val edits = mapOf("news" to ChannelOverlay(hidden = true), "film" to ChannelOverlay(rename = "Cinema"))
        assertEquals(listOf(raw[1], Card("film", "Cinema")), shown(edits))
        assertEquals(listOf(raw[0], raw[1], Card("film", "Cinema")), shown(edits + ("news" to ChannelOverlay(hidden = false))))
    }

    @Test
    fun `unknown identities and unrelated edits leave the original row alone`() {
        val unknown = listOf(Card("unknown", "Mystery"))
        assertSame(unknown, shown(mapOf("unknown" to ChannelOverlay(rename = "Wrong", hidden = true)), unknown))
        assertSame(raw, shown(mapOf("elsewhere" to ChannelOverlay(rename = "Elsewhere"))))
    }

    @Test
    fun `blank rename and pin only edits preserve the custom group`() {
        assertSame(raw, shown(mapOf("sport" to ChannelOverlay(rename = "  "))))
        assertSame(raw, shown(mapOf("film" to ChannelOverlay(pinned = true, position = 0))))
    }
}
