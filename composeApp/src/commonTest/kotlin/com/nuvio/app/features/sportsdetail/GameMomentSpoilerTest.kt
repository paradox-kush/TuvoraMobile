package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.GameMomentTracker
import com.nuvio.app.features.sportsdetail.internal.GameSpoilerGate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Score delay / Hide scores must never fake a moment: the "–" placeholder turning into the delayed score, or a reveal,
 * is a change of what is SHOWN, not a goal. kotlin.test → assertEquals(expected, actual, message).
 */
class GameMomentSpoilerTest {

    private fun game(home: String?, away: String?, playId: String = "p1") = GameDetail(
        gameId = "tsdb:1", sport = "soccer", leagueId = "4328", leagueName = "Premier League", state = GameState.LIVE,
        header = TeamsHeader(Side("Arsenal", score = home), Side("Chelsea", score = away)),
        live = LiveInfo(clock = "67'"),
        blocks = listOf(PlaysBlock(listOf(TimelineItem(time = "23'", text = "Goal Saka", kind = "goal", id = playId)))),
    )

    @Test
    fun `the pending placeholder becoming a score is not a score change`() {
        assertEquals(emptyList(), GameMomentPolicy.diff(game("–", "–"), game("2", "0")), "– to 2 is the delay falling due")
    }

    @Test
    fun `a placeholder on one side suppresses both sides`() {
        assertEquals(emptyList(), GameMomentPolicy.diff(game("1", "–"), game("2", "0")), "never half a placeholder")
    }

    @Test
    fun `pending falling due through the gate animates nothing`() {
        val gate = GameSpoilerGate()
        val delay = ScoreSpoilerSettings(delaySeconds = 30)
        val first = game("2", "0")
        gate.record(first, 0L)
        val tracker = GameMomentTracker()
        tracker.accept(gate.view(first, delay, 10_000L).detail)          // pending: "–" and no plays
        val due = tracker.accept(gate.view(first, delay, 31_000L).detail) // the 30 s-old snapshot is now due
        assertEquals(emptyList(), due.moments, "no goal animation, no spoken score, no new-play pulse")
    }

    @Test
    fun `revealing hidden scores animates nothing`() {
        val gate = GameSpoilerGate()
        val newest = game("2", "0")
        gate.record(newest, 0L)
        val tracker = GameMomentTracker()
        tracker.accept(gate.view(newest, ScoreSpoilerSettings(shown = false), 0L).detail)
        val revealed = tracker.accept(gate.view(newest, ScoreSpoilerSettings(), 1_000L).detail)
        assertEquals(emptyList(), revealed.moments, "a reveal is not a new play")
    }

    @Test
    fun `hiding after a shown snapshot animates nothing`() {
        val gate = GameSpoilerGate()
        val newest = game("2", "0")
        gate.record(newest, 0L)
        val shown = gate.view(newest, ScoreSpoilerSettings(), 0L).detail
        val hidden = gate.view(newest, ScoreSpoilerSettings(shown = false), 1_000L).detail
        assertEquals(emptyList(), GameMomentPolicy.diff(shown, hidden), "hiding is not a moment")
    }

    @Test
    fun `a real goal while shown still animates`() {
        assertEquals(1, GameMomentPolicy.diff(game("1", "0"), game("2", "0")).count { it is com.nuvio.app.features.sportsdetail.internal.Moment.ScoreChange }, "1-0 to 2-0 is a goal")
    }

    @Test
    fun `turning the delay off while the page is open animates nothing`() {
        val gate = GameSpoilerGate()
        gate.record(game("0", "0", playId = "p0"), 0L)
        val newest = game("1", "0", playId = "p1")
        gate.record(newest, 40_000L)
        val tracker = GameMomentTracker()
        tracker.accept(gate.view(newest, ScoreSpoilerSettings(delaySeconds = 30), 50_000L).detail) // 0-0 from 30 s ago
        val off = tracker.accept(gate.view(newest, ScoreSpoilerSettings(), 51_000L).detail)        // jumps to the live 1-0
        assertEquals(emptyList(), off.moments, "a delay change is a change of what is shown, not a goal")
    }

    @Test
    fun `turning a delay on while the page is open never animates a backwards goal`() {
        val gate = GameSpoilerGate()
        gate.record(game("0", "0", playId = "p0"), 0L)
        val newest = game("1", "0", playId = "p1")
        gate.record(newest, 40_000L)
        val tracker = GameMomentTracker()
        tracker.accept(gate.view(newest, ScoreSpoilerSettings(), 50_000L).detail)                   // live 1-0
        val on = tracker.accept(gate.view(newest, ScoreSpoilerSettings(delaySeconds = 30), 51_000L).detail) // back to 0-0
        assertEquals(emptyList(), on.moments, "1-0 back to 0-0 is not a score change")
    }
}
