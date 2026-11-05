package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** Continuing a replay into the next programme. Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0). */
class CatchUpContinuationPolicyTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val now = 100 * hour

    private val a = CatchUpContinuationPolicy.Programme(10 * hour, 11 * hour)
    private val b = CatchUpContinuationPolicy.Programme(11 * hour, 12 * hour)
    private val windowEnd = 14 * hour

    private fun decide(
        playhead: Long,
        playing: CatchUpContinuationPolicy.Programme = a,
        next: CatchUpContinuationPolicy.Programme? = b,
        windowEnd: Long = this.windowEnd,
        now: Long = this.now,
    ) = CatchUpContinuationPolicy.decide(playing, next, playhead, windowEnd, now)

    @Test
    fun staysInsideTheProgramme() {
        assertEquals(CatchUpContinuationPolicy.Decision.Stay, decide(10 * hour + 30 * min))
    }

    @Test
    fun staysJustBeforeTheEnd() {
        assertEquals(CatchUpContinuationPolicy.Decision.Stay, decide(11 * hour - 1))
    }

    @Test
    fun movesToNextProgrammeExactlyAtTheEnd() {
        assertEquals(CatchUpContinuationPolicy.Decision.MoveToNext(b), decide(11 * hour))
    }

    @Test
    fun noNextProgrammeStaysPastTheEnd() {
        assertEquals(CatchUpContinuationPolicy.Decision.Stay, decide(11 * hour + 5 * min, next = null))
    }

    @Test
    fun nextThatIsTheSameProgrammeStays() {
        assertEquals(CatchUpContinuationPolicy.Decision.Stay, decide(11 * hour, next = a))
    }

    @Test
    fun windowEndedInsideNextStartsNewReplayAtPlayhead() {
        assertEquals(
            CatchUpContinuationPolicy.Decision.StartNewReplay(11 * hour + 20 * min),
            decide(11 * hour + 20 * min, windowEnd = 11 * hour + 20 * min),
        )
    }

    @Test
    fun windowEndedInsidePlayingProgrammeStartsNewReplay() {
        assertEquals(
            CatchUpContinuationPolicy.Decision.StartNewReplay(10 * hour + 40 * min),
            decide(10 * hour + 40 * min, windowEnd = 10 * hour + 40 * min),
        )
    }

    @Test
    fun windowEndedAtTheLiveEdgeGoesLive() {
        assertEquals(
            CatchUpContinuationPolicy.Decision.GoLive,
            decide(now - 30_000L, windowEnd = now - 30_000L),
        )
    }

    @Test
    fun windowEndedWithNothingToReplayEnds() {
        assertEquals(
            CatchUpContinuationPolicy.Decision.Ended,
            decide(20 * hour, windowEnd = 20 * hour),
        )
        assertEquals(
            CatchUpContinuationPolicy.Decision.Ended,
            decide(20 * hour, next = null, windowEnd = 19 * hour),
        )
    }

    @Test
    fun aFinishedWindowNeverStays() {
        for (p in listOf(9 * hour, 10 * hour + 5 * min, 11 * hour + 5 * min, 30 * hour, now - 10_000L, now + hour)) {
            val d = decide(p, windowEnd = p)
            assertEquals(false, d == CatchUpContinuationPolicy.Decision.Stay, "playhead $p")
        }
    }

    @Test
    fun windowEndMeansTheWindowNotTheProgramme() {
        // Past the programme's end but before the window's: that is "move on", not "window over".
        assertEquals(CatchUpContinuationPolicy.Decision.MoveToNext(b), decide(11 * hour + hour / 2, windowEnd = 12 * hour))
    }

    @Test
    fun replayWindowExtendsThreeHoursPastEnd() {
        assertEquals(a.endMs + 3 * hour, CatchUpContinuationPolicy.replayWindowEndMs(a, now))
    }

    @Test
    fun replayWindowNeverReachesPastWhatHasAired() {
        val live = CatchUpContinuationPolicy.Programme(now - hour, now + hour)
        assertEquals(now + hour, CatchUpContinuationPolicy.replayWindowEndMs(live, now), "never before its own end")
        val recent = CatchUpContinuationPolicy.Programme(now - 2 * hour, now - hour)
        assertEquals(now - 30_000L, CatchUpContinuationPolicy.replayWindowEndMs(recent, now))
    }

    @Test
    fun continueIsThreeHours() {
        assertEquals(3 * hour, CatchUpContinuationPolicy.CONTINUE_MS)
    }
}
