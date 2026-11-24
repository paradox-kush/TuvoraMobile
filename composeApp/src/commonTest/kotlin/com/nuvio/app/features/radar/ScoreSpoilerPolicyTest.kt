package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hide scores + score delay (research/hide-scores-spec-2026-11-23.md). The default is today's behaviour; hidden hides
 * live AND final scores; a delay never shows a fresher score than it allows; a game already final the first time we saw
 * it is old news and is not delayed. kotlin.test → assertEquals(expected, actual, message).
 */
class ScoreSpoilerPolicyTest {

    private fun score(h: String, a: String) = RadarLiveScore(eventId = "e1", homeScore = h, awayScore = a)

    private fun bufferOf(vararg snaps: Pair<Long, RadarLiveScore>): DelayedValueBuffer<String, RadarLiveScore> =
        DelayedValueBuffer<String, RadarLiveScore>().also { b -> snaps.forEach { (at, v) -> b.record("e1", v, at) } }

    @Test
    fun `defaults show the newest score`() {
        val settings = ScoreSpoilerSettings()
        assertTrue(settings.shown, "scores are shown by default")
        assertEquals(0, settings.delaySeconds, "no delay by default")
        val buffer = bufferOf(0L to score("0", "0"), 10_000L to score("1", "0"))
        assertEquals(
            ScoreShown(score("1", "0")),
            ScoreSpoilerPolicy.scoreFor(settings, "e1", score("1", "0"), false, buffer, 10_000L),
            "default settings show today's (newest) score",
        )
    }

    @Test
    fun `hidden hides live and final scores`() {
        val hidden = ScoreSpoilerSettings(shown = false)
        val buffer = bufferOf(0L to score("2", "1"))
        assertEquals(ScoreHidden, ScoreSpoilerPolicy.scoreFor(hidden, "e1", score("2", "1"), false, buffer, 120_000L), "live score hidden")
        assertEquals(ScoreHidden, ScoreSpoilerPolicy.scoreFor(hidden, "e1", score("2", "1"), true, buffer, 120_000L), "final score hidden")
        assertTrue(ScoreSpoilerPolicy.hidesDetails(hidden), "hidden gates the spoiler-bearing game details")
        assertTrue(!ScoreSpoilerPolicy.hidesDetails(ScoreSpoilerSettings()), "shown keeps the game details")
    }

    @Test
    fun `delay shows the older value and not the newer`() {
        val delayed = ScoreSpoilerSettings(delaySeconds = 30)
        val buffer = bufferOf(0L to score("0", "0"), 40_000L to score("1", "0"))
        // now = 50 s: the 0-0 snapshot (0 s) is 30 s old; the 1-0 one (40 s) is only 10 s old.
        assertEquals(
            ScoreShown(score("0", "0")),
            ScoreSpoilerPolicy.scoreFor(delayed, "e1", score("1", "0"), false, buffer, 50_000L),
            "a 30 s delay shows the value from 30 s ago",
        )
        assertEquals(
            ScoreShown(score("1", "0")),
            ScoreSpoilerPolicy.scoreFor(delayed, "e1", score("1", "0"), false, buffer, 70_000L),
            "the newer value shows once it is 30 s old",
        )
    }

    @Test
    fun `delay with nothing old enough is pending`() {
        val delayed = ScoreSpoilerSettings(delaySeconds = 60)
        val buffer = bufferOf(100_000L to score("1", "0"))
        assertEquals(
            ScorePending,
            ScoreSpoilerPolicy.scoreFor(delayed, "e1", score("1", "0"), false, buffer, 130_000L),
            "only a 30 s old snapshot exists under a 60 s delay",
        )
    }

    @Test
    fun `a game already final at first sight is not delayed`() {
        val delayed = ScoreSpoilerSettings(delaySeconds = 60)
        val buffer = bufferOf(100_000L to score("3", "2"))
        assertEquals(
            ScoreShown(score("3", "2")),
            ScoreSpoilerPolicy.scoreFor(delayed, "e1", score("3", "2"), true, buffer, 100_000L),
            "old news shows at once",
        )
    }

    @Test
    fun `delay 0 shows the newest`() {
        val buffer = bufferOf(0L to score("0", "0"), 1_000L to score("1", "0"))
        assertEquals(
            score("1", "0"),
            buffer.valueAt("e1", 1_000L, 0L),
            "the buffer's newest at delay 0",
        )
        assertEquals(
            ScoreShown(score("1", "0")),
            ScoreSpoilerPolicy.scoreFor(ScoreSpoilerSettings(delaySeconds = 0), "e1", score("1", "0"), false, buffer, 1_000L),
            "delay Off = newest",
        )
    }

    @Test
    fun `sanitizeDelay keeps only the allowed values`() {
        assertEquals(listOf(0, 15, 30, 60), ScoreSpoilerSettings.ALLOWED_DELAYS, "allowed delays")
        assertEquals(0, ScoreSpoilerSettings.sanitizeDelay(45), "45 is not allowed")
        assertEquals(0, ScoreSpoilerSettings.sanitizeDelay(-15), "negative is not allowed")
        assertEquals(30, ScoreSpoilerSettings.sanitizeDelay(30), "30 is allowed")
        assertEquals(0, ScoreSpoilerSettings(delaySeconds = 45).delayMs, "a bad stored delay reads as Off")
    }

    @Test
    fun `nextDelay cycles Off 15 30 60 Off`() {
        assertEquals(15, ScoreSpoilerSettings.nextDelay(0), "Off -> 15")
        assertEquals(30, ScoreSpoilerSettings.nextDelay(15), "15 -> 30")
        assertEquals(60, ScoreSpoilerSettings.nextDelay(30), "30 -> 60")
        assertEquals(0, ScoreSpoilerSettings.nextDelay(60), "60 -> Off")
        assertEquals(15, ScoreSpoilerSettings.nextDelay(45), "a bad value restarts the cycle at 15")
    }

    @Test
    fun `chip labels say the state`() {
        assertEquals("Scores shown", ScoreSpoilerSettings().toggleLabel, "shown chip")
        assertEquals("Scores hidden", ScoreSpoilerSettings(shown = false).toggleLabel, "hidden chip")
        assertEquals("Delay: Off", ScoreSpoilerSettings().delayLabel, "delay off chip")
        assertEquals("Delay: 15 s", ScoreSpoilerSettings(delaySeconds = 15).delayLabel, "15 s chip")
        assertEquals("Delay: 60 s", ScoreSpoilerSettings(delaySeconds = 60).delayLabel, "60 s chip")
    }

    @Test
    fun `buffer dedupes identical values`() {
        val buffer = DelayedValueBuffer<String, RadarLiveScore>()
        buffer.record("e1", score("0", "0"), 0L)
        buffer.record("e1", score("0", "0"), 15_000L)
        buffer.record("e1", score("0", "0"), 30_000L)
        assertEquals(1, buffer.snapshotCount("e1"), "an unchanged value is one snapshot")
        assertEquals(score("0", "0"), buffer.valueAt("e1", 30_000L, 30_000L), "the deduped snapshot keeps its first time")
    }

    @Test
    fun `buffer stays bounded by time`() {
        val buffer = DelayedValueBuffer<String, RadarLiveScore>()
        repeat(50) { i -> buffer.record("e1", score("$i", "0"), i * 15_000L) }
        // At 735 s: the 60 s anchor (675 s) plus the four newer ones.
        assertEquals(5, buffer.snapshotCount("e1"), "only one delay window is kept")
        val now = 49 * 15_000L
        // 60 s ago = snapshot 45; it must still be answerable after the pruning.
        assertEquals(score("45", "0"), buffer.valueAt("e1", now, 60_000L), "the 60 s-old snapshot survives pruning")
    }

    @Test
    fun `a snapshot every 5 s for 3 minutes still answers 60 s ago`() {
        val buffer = DelayedValueBuffer<String, RadarLiveScore>()
        // 37 distinct snapshots at 0..180 s: far more than fit in one 60 s window at 5 s spacing.
        for (i in 0..36) buffer.record("e1", score("$i", "0"), i * 5_000L)
        val now = 180_000L
        assertEquals(score("24", "0"), buffer.valueAt("e1", now, 60_000L), "the snapshot from 120 s (60 s ago) is on screen, not a frozen anchor")
        assertEquals(score("30", "0"), buffer.valueAt("e1", now, 30_000L), "30 s ago is the 150 s snapshot")
        assertEquals(score("36", "0"), buffer.valueAt("e1", now, 0L), "no delay is the newest")
    }

    @Test
    fun `buffer prunes snapshots older than the 60 s anchor`() {
        val buffer = DelayedValueBuffer<String, RadarLiveScore>()
        buffer.record("e1", score("0", "0"), 0L)
        buffer.record("e1", score("1", "0"), 10_000L)
        buffer.record("e1", score("2", "0"), 200_000L)
        // At 200 s the newest snapshot old enough for 60 s is 1-0 (10 s); 0-0 is no longer needed.
        assertEquals(2, buffer.snapshotCount("e1"), "only the 60 s anchor and newer are kept")
    }

    @Test
    fun `nextChangeAtMs is when the pending newer snapshot becomes due`() {
        val buffer = bufferOf(0L to score("0", "0"), 40_000L to score("1", "0"))
        assertEquals(70_000L, buffer.nextChangeAtMs(50_000L, 30_000L), "1-0 recorded at 40 s is due at 70 s under a 30 s delay")
        assertNull(buffer.nextChangeAtMs(80_000L, 30_000L), "nothing pending once every snapshot is due")
        assertNull(buffer.nextChangeAtMs(50_000L, 0L), "no delay, nothing to wait for")
    }

    @Test
    fun `slots render hidden as nothing and pending as dashes`() {
        assertEquals(ScoreSlots(null, null), ScoreHidden.slots(), "hidden shows no numbers")
        assertEquals(ScoreSlots("–", "–", pending = true), ScorePending.slots(), "pending shows dashes")
        assertEquals(ScoreSlots("2", "1"), ScoreShown(score("2", "1")).slots(), "shown shows the pair")
        assertTrue(!ScorePending.slots().comparable, "dashes never dim a side")
    }
}
