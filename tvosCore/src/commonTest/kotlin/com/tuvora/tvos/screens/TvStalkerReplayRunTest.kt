package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.CatchUpStartupPolicy
import com.nuvio.app.features.iptv.stalker.StalkerReplayFailure
import com.tuvora.tvos.screens.TvStalkerReplayRun.Move
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Stalker archive replay watcher: one minted link, no walk; it plays or it fails with the portal's words. */
class TvStalkerReplayRunTest {
    private var clock = 0L
    private fun run() = TvStalkerReplayRun(clockMs = { clock })

    private fun TvStalkerReplayRun.tick(playing: Boolean = false, pos: Long = 0L, ended: Boolean = false, error: String? = null) =
        observe(playing, pos, ended, error)

    @Test
    fun `waiting for the first frame is not a failure`() {
        val r = run()
        assertEquals(Move.Wait, r.tick())
        clock += 5_000
        assertEquals(Move.Wait, r.tick())
    }

    @Test
    fun `a link with no frame in the startup deadline is a hang with retry`() {
        val r = run()
        r.tick()
        clock += CatchUpStartupPolicy.LINK_DEADLINE_MS
        assertEquals(Move.Failed(StalkerReplayFailure.STARTUP_HANG), r.tick())
    }

    @Test
    fun `a clock that stays under the deadline never hangs`() {
        val r = run()
        r.tick()
        clock += CatchUpStartupPolicy.LINK_DEADLINE_MS - 1
        assertEquals(Move.Wait, r.tick())
    }

    @Test
    fun `an engine error before any frame is a playback error not no recording`() {
        val failed = run().tick(error = "Failed to open")
        assertEquals(Move.Failed(StalkerReplayFailure.PLAYBACK_ERROR), failed)
    }

    @Test
    fun `an immediate end of file is a playback error`() {
        assertEquals(Move.Failed(StalkerReplayFailure.PLAYBACK_ERROR), run().tick(ended = true))
    }

    @Test
    fun `the portal session limit error has its own failure`() {
        assertEquals(Move.Failed(StalkerReplayFailure.SESSION_LIMIT), run().tick(error = "{\"error\":\"limit\"}"))
    }

    @Test
    fun `a link that played is never timed out afterwards`() {
        val r = run()
        r.tick()
        assertEquals(Move.Wait, r.tick(playing = true, pos = 1_200))
        clock += 10 * CatchUpStartupPolicy.TOTAL_BUDGET_MS
        assertEquals(Move.Wait, r.tick(playing = true, pos = 90_000))
    }

    @Test
    fun `an error after it played is a playback error and the end is done`() {
        val a = run().also { it.tick(playing = true, pos = 500) }
        assertEquals(Move.Failed(StalkerReplayFailure.PLAYBACK_ERROR), a.tick(error = "Connection reset"))
        val b = run().also { it.tick(playing = true, pos = 500) }
        assertEquals(Move.Done, b.tick(playing = false, pos = 3_600_000, ended = true))
    }

    @Test
    fun `every failure carries its own viewer words and the portal refusals are never the generic one`() {
        val texts = StalkerReplayFailure.entries.associateWith(TvStalkerReplayText::forFailure)
        assertEquals(setOf(TvStalkerReplayText.NO_ANSWER), setOf(texts[StalkerReplayFailure.NO_ANSWER], texts[StalkerReplayFailure.STARTUP_HANG]))
        assertNotEquals(texts[StalkerReplayFailure.SESSION_LIMIT], texts[StalkerReplayFailure.NOTHING_TO_PLAY])
        assertNotEquals(texts[StalkerReplayFailure.LINK_FAULT], texts[StalkerReplayFailure.PLAYBACK_ERROR])
        assertTrue(texts.values.all { it.contains("Retry") }, "every failure offers Retry in its words")
    }
}
