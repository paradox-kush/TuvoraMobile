package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePlayCountsTest {
    private val minute = 60 * 1000L
    private val day = 24 * 60 * minute
    private val t0 = 100 * day

    private fun record(list: List<PlayedChannel>, id: String, at: Long, name: String = id, logo: String? = null) =
        LivePlayCountsPolicy.recorded(list, id, name, logo, at)

    @Test
    fun `a first play creates the channel with one timestamp`() {
        val list = record(emptyList(), "a", t0, name = "Alpha", logo = "alpha.png")
        assertEquals(listOf(PlayedChannel("a", "Alpha", "alpha.png", listOf(t0))), list, "one entry")
    }

    @Test
    fun `a later play on another sitting adds a timestamp`() {
        val list = record(record(emptyList(), "a", t0), "a", t0 + day)
        assertEquals(listOf(t0, t0 + day), list.single().plays, "two separate plays")
    }

    @Test
    fun `reopening a channel within ten minutes is the same sitting`() {
        val list = record(record(record(emptyList(), "a", t0), "a", t0 + 2 * minute), "a", t0 + 9 * minute)
        assertEquals(listOf(t0), list.single().plays, "retries and reconnects add no plays")
    }

    @Test
    fun `reopening after ten minutes counts again`() {
        val list = record(record(emptyList(), "a", t0), "a", t0 + 10 * minute)
        assertEquals(2, list.single().plays.size, "ten minutes apart is a new sitting")
    }

    @Test
    fun `plays older than the suggestion window are forgotten`() {
        val old = record(emptyList(), "a", t0)
        val list = record(old, "b", t0 + 15 * day)
        assertEquals(listOf("b"), list.map { it.contentId }, "a's only play is past the window so it goes")
    }

    @Test
    fun `old plays of a channel are dropped when it is played again`() {
        val list = record(record(emptyList(), "a", t0), "a", t0 + 15 * day)
        assertEquals(listOf(t0 + 15 * day), list.single().plays, "only the new play is kept")
    }

    @Test
    fun `the store keeps the fifty most recently played channels`() {
        var list = emptyList<PlayedChannel>()
        for (i in 0 until 60) list = record(list, "c${i.toString().padStart(2, '0')}", t0 + i * minute * 20)
        assertEquals(50, list.size, "capped")
        assertEquals("c59", list.first().contentId, "newest first")
        assertNull(list.firstOrNull { it.contentId == "c00" }, "the oldest were dropped")
        assertTrue(list.any { it.contentId == "c10" }, "the 50th newest is kept")
    }

    @Test
    fun `each channel keeps its ten newest plays`() {
        var list = emptyList<PlayedChannel>()
        for (i in 0 until 14) list = record(list, "a", t0 + i * 20 * minute)
        val plays = list.single().plays
        assertEquals(10, plays.size, "capped")
        assertEquals(t0 + 13 * 20 * minute, plays.last(), "newest kept")
        assertEquals(t0 + 4 * 20 * minute, plays.first(), "oldest four dropped")
    }

    @Test
    fun `the cap never hides a channel that qualifies`() {
        assertTrue(LivePlayCountsPolicy.MAX_PLAYS_PER_CHANNEL >= PinSuggestionPolicy.MIN_PLAYS, "ten is well above three")
    }

    @Test
    fun `a launch titled with the generic name keeps the real one`() {
        val named = record(emptyList(), "a", t0, name = "Alpha HD", logo = "alpha.png")
        val list = record(named, "a", t0 + day, name = "Live TV", logo = null)
        assertEquals("Alpha HD", list.single().name, "name kept")
        assertEquals("alpha.png", list.single().logo, "logo kept")
    }

    @Test
    fun `a newer real name replaces the old one`() {
        val list = record(record(emptyList(), "a", t0, name = "Alpha"), "a", t0 + day, name = "Alpha HD")
        assertEquals("Alpha HD", list.single().name, "latest real name")
    }

    @Test
    fun `clearing wipes every count and nothing else`() {
        val state = PinSuggestionState(
            enabled = false,
            snoozedUntilMs = t0 + day,
            channels = record(emptyList(), "a", t0),
        )
        val cleared = LivePlayCountsPolicy.cleared(state)
        assertTrue(cleared.channels.isEmpty(), "counts gone")
        assertEquals(false, cleared.enabled, "the switch is a choice and stays")
        assertEquals(t0 + day, cleared.snoozedUntilMs, "the snooze stays")
    }
}
