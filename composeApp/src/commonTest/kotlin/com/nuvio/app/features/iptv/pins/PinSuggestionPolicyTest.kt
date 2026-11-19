package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinSuggestionPolicyTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    /** A channel played [daysAgo] days before [now], one play per entry (spaced far apart, so each is its own sitting). */
    private fun channel(id: String, vararg daysAgo: Int, name: String = id) =
        PlayedChannel(contentId = id, name = name, plays = daysAgo.map { now - it * day }.sorted())

    private fun state(vararg channels: PlayedChannel, enabled: Boolean = true, snoozedUntilMs: Long = 0L) =
        PinSuggestionState(enabled = enabled, snoozedUntilMs = snoozedUntilMs, channels = channels.toList())

    private fun ids(result: List<PlayedChannel>) = result.map { it.contentId }

    @Test
    fun `the documented thresholds are three plays in fourteen days and a fourteen day snooze`() {
        assertEquals(3, PinSuggestionPolicy.MIN_PLAYS, "min plays")
        assertEquals(14, PinSuggestionPolicy.WINDOW_DAYS, "window")
        assertEquals(2, PinSuggestionPolicy.MIN_SUGGESTIONS, "min channels")
        assertEquals(4, PinSuggestionPolicy.MAX_SUGGESTIONS, "max channels")
        assertEquals(14, PinSuggestionPolicy.SNOOZE_DAYS, "snooze")
    }

    @Test
    fun `a channel with exactly three plays in the window qualifies`() {
        val result = PinSuggestionPolicy.suggest(state(channel("a", 1, 2, 3), channel("b", 4, 5, 6)), now)
        assertEquals(listOf("a", "b"), ids(result), "both channels have three plays")
    }

    @Test
    fun `a channel with two plays does not qualify`() {
        val result = PinSuggestionPolicy.suggest(state(channel("a", 1, 2, 3), channel("b", 1, 2), channel("c", 4, 5, 6)), now)
        assertEquals(listOf("a", "c"), ids(result), "b has only two plays")
    }

    @Test
    fun `plays older than the window do not count`() {
        val result = PinSuggestionPolicy.suggest(state(channel("a", 1, 2, 3), channel("b", 1, 2, 15), channel("c", 3, 4, 5)), now)
        assertEquals(listOf("a", "c"), ids(result), "b has two plays inside the window and one outside")
    }

    @Test
    fun `a play exactly fourteen days old still counts and one millisecond older does not`() {
        val edge = PlayedChannel("edge", "edge", plays = listOf(now - 14 * day, now - 2 * day, now - 1 * day))
        val over = PlayedChannel("over", "over", plays = listOf(now - 14 * day - 1, now - 2 * day, now - 1 * day))
        assertEquals(3, PinSuggestionPolicy.playsInWindow(edge, now), "edge play counts")
        assertEquals(2, PinSuggestionPolicy.playsInWindow(over, now), "play one ms past the window does not")
    }

    @Test
    fun `plays stamped in the future are ignored`() {
        val skewed = PlayedChannel("a", "a", plays = listOf(now - day, now + day, now + 2 * day))
        assertEquals(1, PinSuggestionPolicy.playsInWindow(skewed, now), "only the real play counts")
    }

    @Test
    fun `channels the viewer already pinned are never suggested`() {
        val s = state(channel("a", 1, 2, 3), channel("b", 1, 2, 3), channel("c", 1, 2, 3))
        val result = PinSuggestionPolicy.suggest(s, now) { it == "b" }
        assertEquals(listOf("a", "c"), ids(result), "b is skipped")
    }

    @Test
    fun `pinned channels are removed before the limit so they never take a slot`() {
        val s = state(
            channel("p", 1, 1, 1, 1, 1, 1),
            channel("a", 1, 2, 3, 4, 5),
            channel("b", 1, 2, 3, 4),
            channel("c", 1, 2, 3),
            channel("d", 2, 3, 4),
        )
        val result = PinSuggestionPolicy.suggest(s, now) { it == "p" }
        assertEquals(listOf("a", "b", "c", "d"), ids(result), "four unpinned channels fill the slots")
    }

    @Test
    fun `never more than four are suggested`() {
        val s = state(*(1..7).map { channel("c$it", 1, 2, 3) }.toTypedArray())
        assertEquals(4, PinSuggestionPolicy.suggest(s, now).size, "capped at four")
    }

    @Test
    fun `channels are ranked by plays in the window then by most recent play then by id`() {
        val s = state(
            channel("few", 1, 2, 3),
            channel("many", 5, 6, 7, 8, 9),
            channel("recentTie", 1, 4, 5, 6),
            channel("olderTie", 2, 4, 5, 6),
            channel("aTie", 2, 4, 5, 6),
        )
        val result = PinSuggestionPolicy.suggest(s, now)
        assertEquals(listOf("many", "recentTie", "aTie", "olderTie"), ids(result), "plays, then recency, then id; the weakest drops")
    }

    @Test
    fun `nothing is suggested when only one channel qualifies`() {
        val result = PinSuggestionPolicy.suggest(state(channel("a", 1, 2, 3), channel("b", 1, 2)), now)
        assertTrue(result.isEmpty(), "one channel is not a pattern")
    }

    @Test
    fun `nothing is suggested when skipping leaves fewer than two`() {
        val s = state(channel("a", 1, 2, 3), channel("b", 1, 2, 3))
        assertTrue(PinSuggestionPolicy.suggest(s, now) { it == "a" }.isEmpty(), "only b is left")
    }

    @Test
    fun `nothing is suggested with no history`() {
        assertTrue(PinSuggestionPolicy.suggest(PinSuggestionState(), now).isEmpty(), "empty state")
    }

    @Test
    fun `nothing is suggested while the switch is off`() {
        val s = state(channel("a", 1, 2, 3), channel("b", 1, 2, 3), enabled = false)
        assertTrue(PinSuggestionPolicy.suggest(s, now).isEmpty(), "switch off wins")
    }

    @Test
    fun `nothing is suggested while a Not now is running`() {
        val s = state(channel("a", 1, 2, 3), channel("b", 1, 2, 3), snoozedUntilMs = now + 1)
        assertTrue(PinSuggestionPolicy.suggest(s, now).isEmpty(), "snoozed")
    }

    @Test
    fun `suggestions return exactly when the snooze ends`() {
        val s = state(channel("a", 1, 2, 3), channel("b", 1, 2, 3), snoozedUntilMs = now)
        assertEquals(listOf("a", "b"), ids(PinSuggestionPolicy.suggest(s, now)), "at the snooze end time")
    }

    @Test
    fun `Not now snoozes for fourteen days from now`() {
        val snoozed = PinSuggestionPolicy.snoozed(state(channel("a", 1, 2, 3), channel("b", 1, 2, 3)), now)
        assertEquals(now + 14 * day, snoozed.snoozedUntilMs, "snooze end")
        assertTrue(PinSuggestionPolicy.suggest(snoozed, now + 14 * day - 1).isEmpty(), "still snoozed a moment before")
    }

    @Test
    fun `Not now keeps the play counts and the switch`() {
        val before = state(channel("a", 1, 2, 3), channel("b", 1, 2, 3))
        val after = PinSuggestionPolicy.snoozed(before, now)
        assertEquals(before.channels, after.channels, "counts untouched")
        assertTrue(after.enabled, "switch untouched")
    }

    @Test
    fun `Stop suggesting turns the switch off so nothing is suggested again`() {
        val stopped = PinSuggestionPolicy.stopped(state(channel("a", 1, 2, 3), channel("b", 1, 2, 3)))
        assertEquals(false, stopped.enabled, "switch off")
        assertTrue(PinSuggestionPolicy.suggest(stopped, now + 365 * day).isEmpty(), "never again, whatever the date")
    }

    @Test
    fun `turning the switch back on clears an old snooze`() {
        val snoozed = PinSuggestionPolicy.snoozed(state(channel("a", 1, 2, 3), channel("b", 1, 2, 3)), now)
        val off = PinSuggestionPolicy.withEnabled(snoozed, false)
        assertEquals(snoozed.snoozedUntilMs, off.snoozedUntilMs, "turning off leaves the snooze")
        val on = PinSuggestionPolicy.withEnabled(off, true)
        assertEquals(0L, on.snoozedUntilMs, "turning on starts clean")
        assertEquals(listOf("a", "b"), ids(PinSuggestionPolicy.suggest(on, now)), "suggestions are back")
    }
}
