package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review round 11-15: section identity, the honest left-column budget, and the soccer pre-kickoff refresh plan. */
class TvGameReviewFixesTest {
    private fun chips(vararg s: TvSection) = s.map { TvChip(it, it.name) }

    // --- 1. The selection is a section, not a slot -----------------------------------------------------------------------

    @Test fun `a picked section keeps its place when earlier chips are inserted`() {
        val upcoming = chips(TvSection.LINEUPS, TvSection.STANDINGS)
        assertEquals(1, TvSectionSelection.indexOf(upcoming, TvSection.STANDINGS))
        // Kickoff: Plays / Stats / Leaders appear before Standings; the user still looks at Standings.
        val live = chips(TvSection.PLAYS, TvSection.STATS, TvSection.LEADERS, TvSection.LINEUPS, TvSection.STANDINGS)
        assertEquals(4, TvSectionSelection.indexOf(live, TvSection.STANDINGS), "must follow Standings, not stay on slot 1 (Stats)")
    }

    @Test fun `a section that disappeared falls back to the first chip`() {
        assertEquals(0, TvSectionSelection.indexOf(chips(TvSection.PLAYS, TvSection.STATS), TvSection.STANDINGS))
    }

    @Test fun `nothing selected yet is the first chip and no chips is no index`() {
        assertEquals(0, TvSectionSelection.indexOf(chips(TvSection.PLAYS, TvSection.STATS), null))
        assertEquals(0, TvSectionSelection.indexOf(emptyList(), TvSection.STATS))
    }

    @Test fun `the section to show is resolved from the list`() {
        assertEquals(TvSection.STATS, TvSectionSelection.resolve(chips(TvSection.PLAYS, TvSection.STATS), TvSection.STATS))
        assertEquals(TvSection.PLAYS, TvSectionSelection.resolve(chips(TvSection.PLAYS, TvSection.STATS), TvSection.LEADERS))
        assertNull(TvSectionSelection.resolve(emptyList(), TvSection.PLAYS))
    }

    // --- 5. The budget counts every row Swift draws -----------------------------------------------------------------------

    @Test fun `a stale win card label costs a row`() {
        val base = TvLeftColumnFit.fit(stripDp = 100, scorerLines = 0, hasLine = true, hasChart = true, hasLastPlay = true)
        val withStale = TvLeftColumnFit.fit(stripDp = 100, scorerLines = 0, hasLine = true, hasChart = true, hasLastPlay = true, hasStaleLabel = true)
        assertTrue(base.showChart, "fixture must sit right under the limit")
        assertFalse(withStale.showChart, "the extra stale row has to push the chart out")
    }

    @Test fun `football timeout dots cost scoreboard height`() {
        val base = TvLeftColumnFit.fit(stripDp = 100, scorerLines = 0, hasLine = true, hasChart = true, hasLastPlay = true)
        val dots = TvLeftColumnFit.fit(stripDp = 100, scorerLines = 0, hasLine = true, hasChart = true, hasLastPlay = true, timeoutDots = true)
        assertTrue(base.showChart)
        assertFalse(dots.showChart)
    }

    @Test fun `the field card budget counts its text rows`() {
        val full = TvFieldStrip(0.5f, null, null, null, null, null, null, true, "NYG ball", "2nd & 5 at NYG 33", false, null, 3, 3, "Drive 4 plays")
        val bare = full.copy(line = null, ballLabel = null)
        assertEquals(TvStrips.fieldDp(full), TvStrips.fieldDp(bare) + TvStrips.FIELD_TOP_ROW_DP, "the line/label row is budgeted when drawn")
        assertTrue(TvStrips.fieldDp(full) >= 80, "a full field card is ~80+ dp, not the old 60")
    }

    // --- Soccer lineups before kickoff (core's refresh flag, used by tvos' loop) -------------------------------------

    @Test fun `soccer refreshes its summary before kickoff and other sports do not`() {
        val start = 100_000_000L
        val now = start - 10 * 60_000L                       // 10 min to kickoff, inside the lead window
        fun plan(sport: String) = TvGameRefresh.plan(
            sport = sport, state = GameState.UPCOMING, startsAtMs = start, nowMs = now,
            lastEventMs = now - 120_000L, lastSummaryMs = now - 120_000L, failures = 0, config = GameDetailRefreshPolicy.Config(),
        )
        assertTrue(plan("Soccer").fetchSummary, "lineups arrive before kickoff")
        assertFalse(plan("Ice Hockey").fetchSummary)
        assertFalse(plan("American Football").fetchSummary)
    }

    @Test fun `a new play moment carries the key of its row`() {
        val prev = com.nuvio.app.features.sportsdetail.api.GameDetail(
            gameId = "g", sport = "Ice Hockey", leagueId = "1", leagueName = "NHL", state = GameState.LIVE,
            header = com.nuvio.app.features.sportsdetail.api.TeamsHeader(com.nuvio.app.features.sportsdetail.api.Side("A"), com.nuvio.app.features.sportsdetail.api.Side("B")),
            blocks = listOf(com.nuvio.app.features.sportsdetail.api.PlaysBlock(listOf(item("1")))),
        )
        val next = prev.copy(blocks = listOf(com.nuvio.app.features.sportsdetail.api.PlaysBlock(listOf(item("2"), item("1")))))
        val m = TvGameScreenModel.from(next, 1_000L, false, prev)
        val moment = m.moments.single { it.kind == TvMomentKind.NEW_PLAY }
        assertEquals(m.panels.plays.first().key, moment.key, "only the row with this key animates in")
        assertEquals("2", moment.key)
    }

    private fun item(id: String) = com.nuvio.app.features.sportsdetail.api.TimelineItem(time = "1:0$id", period = "1", side = "home", text = "play $id", kind = "shot", id = id)
}
