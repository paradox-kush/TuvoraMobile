package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinSuggestionActionsTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    /** A fake of the pin seam: the "pinned" set stands in for the viewer's favourites. */
    private class FakePinPort(initiallyPinned: Set<String> = emptySet()) : LiveChannelPinPort {
        val pinned = initiallyPinned.toMutableSet()
        val writes = mutableListOf<String>()
        override fun isPinned(contentId: String) = contentId in pinned
        override fun pin(channel: PlayedChannel) {
            writes += channel.contentId
            pinned += channel.contentId
        }
    }

    private fun played(id: String) = PlayedChannel(id, id, plays = listOf(now - day, now - 2 * day, now - 3 * day))

    @Test
    fun `Pin all pins every suggested channel and reports the count`() {
        val port = FakePinPort()
        val count = PinSuggestionActions.pinAll(port, listOf(played("a"), played("b"), played("c"), played("d")))
        assertEquals(4, count, "four pinned")
        assertEquals(setOf("a", "b", "c", "d"), port.pinned, "all four are pinned")
    }

    @Test
    fun `Pin all writes the best channel last so it lands first`() {
        val port = FakePinPort()
        PinSuggestionActions.pinAll(port, listOf(played("best"), played("second"), played("third")))
        assertEquals(listOf("third", "second", "best"), port.writes, "a new favourite goes to the top, so the best goes in last")
    }

    @Test
    fun `Pin all skips a channel that is already pinned`() {
        val port = FakePinPort(initiallyPinned = setOf("b"))
        val count = PinSuggestionActions.pinAll(port, listOf(played("a"), played("b"), played("c")))
        assertEquals(2, count, "two new pins")
        assertEquals(listOf("c", "a"), port.writes, "b was not written again")
    }

    @Test
    fun `Pin all pins a channel listed twice once`() {
        val port = FakePinPort()
        assertEquals(1, PinSuggestionActions.pinAll(port, listOf(played("a"), played("a"))), "one pin")
        assertEquals(listOf("a"), port.writes, "one write")
    }

    @Test
    fun `Pin all with nothing to pin writes nothing`() {
        val port = FakePinPort()
        assertEquals(0, PinSuggestionActions.pinAll(port, emptyList()), "count")
        assertTrue(port.writes.isEmpty(), "no writes")
    }

    @Test
    fun `Pin pins one channel`() {
        val port = FakePinPort()
        assertTrue(PinSuggestionActions.pin(port, played("a")), "pinned")
        assertEquals(setOf("a"), port.pinned, "a is pinned")
    }

    @Test
    fun `Pin on an already pinned channel does nothing`() {
        val port = FakePinPort(initiallyPinned = setOf("a"))
        assertFalse(PinSuggestionActions.pin(port, played("a")), "nothing to do")
        assertTrue(port.writes.isEmpty(), "no write")
    }

    @Test
    fun `after Pin all the policy suggests nothing more`() {
        val port = FakePinPort()
        val state = PinSuggestionState(channels = listOf(played("a"), played("b"), played("c")))
        val shown = PinSuggestionPolicy.suggest(state, now, port::isPinned)
        assertEquals(3, shown.size, "three are offered")
        PinSuggestionActions.pinAll(port, shown)
        assertTrue(PinSuggestionPolicy.suggest(state, now, port::isPinned).isEmpty(), "all pinned so nothing is offered")
    }

    @Test
    fun `pinning one of three leaves two to suggest and pinning a second leaves none`() {
        val port = FakePinPort()
        val state = PinSuggestionState(channels = listOf(played("a"), played("b"), played("c")))
        PinSuggestionActions.pin(port, played("a"))
        assertEquals(listOf("b", "c"), PinSuggestionPolicy.suggest(state, now, port::isPinned).map { it.contentId }, "two left")
        PinSuggestionActions.pin(port, played("b"))
        assertTrue(PinSuggestionPolicy.suggest(state, now, port::isPinned).isEmpty(), "one left is not a pattern")
    }
}
