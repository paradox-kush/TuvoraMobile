package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Apple TV live recovery must never replay a spent Stalker link (simulator, mock portal, 11-04: the
 * guide's channel froze, the session re-opened the 401ing `stream/S2` URL, never asked for a new
 * create_link, and then showed the raw mpv line with the stream URL and token on screen).
 */
class TvLiveRelinkPolicyTest {

    @Test
    fun `a reconnect with a re-resolver mints a fresh link`() {
        assertEquals(TvLiveRelinkPolicy.Step.Remint, TvLiveRelinkPolicy.onReconnect(spentLink = true, canRemint = true, remintInFlight = false))
        assertEquals(TvLiveRelinkPolicy.Step.Remint, TvLiveRelinkPolicy.onReconnect(spentLink = false, canRemint = true, remintInFlight = false))
    }

    @Test
    fun `a spent link with no re-resolver is never replayed`() {
        assertEquals(
            TvLiveRelinkPolicy.Step.Skip,
            TvLiveRelinkPolicy.onReconnect(spentLink = true, canRemint = false, remintInFlight = false),
            "replaying a single-use create_link only 401s again; the ladder must end in Failed instead",
        )
    }

    @Test
    fun `a stable address with no re-resolver is replayed as before`() {
        assertEquals(TvLiveRelinkPolicy.Step.Replay, TvLiveRelinkPolicy.onReconnect(spentLink = false, canRemint = false, remintInFlight = false))
    }

    @Test
    fun `a reconnect while a re-mint is still out asks for nothing more`() {
        for (spent in listOf(true, false)) {
            assertEquals(
                TvLiveRelinkPolicy.Step.Skip,
                TvLiveRelinkPolicy.onReconnect(spentLink = spent, canRemint = true, remintInFlight = true),
                "one create_link at a time (spent=$spent): a portal banned an IP for fast re-mints",
            )
        }
    }

    @Test
    fun `a fresh link is opened`() {
        assertEquals(TvLiveRelinkPolicy.Step.OpenFresh, TvLiveRelinkPolicy.afterRemint(spentLink = true, gotFresh = true))
        assertEquals(TvLiveRelinkPolicy.Step.OpenFresh, TvLiveRelinkPolicy.afterRemint(spentLink = false, gotFresh = true))
    }

    @Test
    fun `a failed re-mint of a spent link opens nothing`() {
        assertEquals(
            TvLiveRelinkPolicy.Step.Skip,
            TvLiveRelinkPolicy.afterRemint(spentLink = true, gotFresh = false),
            "no fresh link: the old one is dead, so wait for the ladder's next attempt or its GiveUp",
        )
    }

    @Test
    fun `a failed re-resolve of a stable address replays it`() {
        assertEquals(TvLiveRelinkPolicy.Step.Replay, TvLiveRelinkPolicy.afterRemint(spentLink = false, gotFresh = false))
    }

    @Test
    fun `a spent link never moves to another engine lane`() {
        assertFalse(TvLiveRelinkPolicy.mayEscalateLane(spentLink = true), "the next engine would open the same dead URL")
        assertTrue(TvLiveRelinkPolicy.mayEscalateLane(spentLink = false))
    }

    @Test
    fun `the old link's end does not count against a fresh link that is still opening`() {
        // Simulator 11-04: Retry minted S20, mpv still reported S19's end, and S21 was minted 2 s later.
        assertTrue(TvLiveRelinkPolicy.isStaleEnd(isEnded = true, sinceFreshOpenMs = 2_000L))
    }

    @Test
    fun `a fresh link that never starts still ends the wait`() {
        assertFalse(TvLiveRelinkPolicy.isStaleEnd(isEnded = true, sinceFreshOpenMs = TvLiveRelinkPolicy.FRESH_OPEN_GRACE_MS))
        assertFalse(TvLiveRelinkPolicy.isStaleEnd(isEnded = true, sinceFreshOpenMs = null), "no fresh link pending: a real end")
        assertFalse(TvLiveRelinkPolicy.isStaleEnd(isEnded = false, sinceFreshOpenMs = 1_000L))
    }

    @Test
    fun `the error card never shows the engine text`() {
        val raw = "[stream] Failed to open http://127.0.0.1:8900/stream/S2/index.m3u8.\n[mpv] loading failed"
        val shown = TvLiveRelinkPolicy.viewerMessage(raw)
        assertEquals(TvLiveRelinkPolicy.CHANNEL_UNREACHABLE, shown)
        assertFalse(shown.contains("://") || shown.contains("mpv") || shown.contains("S2"), "raw engine text leaked: $shown")
    }

    @Test
    fun `a freeze with no engine error says the channel stopped`() {
        assertEquals(TvLiveRelinkPolicy.CHANNEL_STOPPED, TvLiveRelinkPolicy.viewerMessage(null))
        assertEquals(TvLiveRelinkPolicy.CHANNEL_STOPPED, TvLiveRelinkPolicy.viewerMessage("  "))
    }
}
