package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A finished or too-old fixture must never read LIVE, however stale the livescore feed's live set
 * is. Regression for the reported "Sunday NFL game still shows LIVE on Monday" (robustness
 * inventory T1/BK1/#4): [RadarUiState.isLive] previously returned `feedConfirmed` (id in the
 * stale-served live set) with no finished-status gate and no max-window cap.
 */
class RadarLiveStatusTest {

    private val ts = "2026-08-24T18:00:00" // a Sunday 18:00 UTC kick-off
    private val nfl = RadarFixture(
        id = "nfl1",
        leagueId = "4391",
        sport = "American Football",
        home = "Chiefs",
        away = "Bills",
        ts = ts,
    )
    private val start = nfl.startEpochMs!!

    private fun state(fixture: RadarFixture) = RadarUiState(
        fixturesByLeague = mapOf("4391" to listOf(fixture)),
        liveEventIds = setOf("nfl1"),
        livescoreSports = setOf("american football"),
    )

    @Test
    fun `a finished NFL game is not live even when its id is still in the live set`() {
        val finished = nfl.copy(status = "Final")
        assertFalse(
            state(finished).isLive(finished, start + 20 * 60 * 60 * 1000L),
            "a finished strStatus must never read LIVE (the Sunday-NFL-on-Monday bug)",
        )
    }

    @Test
    fun `a day-old NFL game with no status is capped by the max-live window`() {
        assertFalse(
            state(nfl).isLive(nfl, start + 20 * 60 * 60 * 1000L),
            "a stale live-set entry must not read LIVE a day later even without a finished status",
        )
    }

    @Test
    fun `a postponed fixture is not live`() {
        val off = nfl.copy(postponed = "yes")
        assertFalse(
            state(off).isLive(off, start + 60 * 60 * 1000L),
            "a postponed fixture is never live",
        )
    }

    @Test
    fun `a genuinely in-progress NFL game is still live`() {
        assertTrue(
            state(nfl).isLive(nfl, start + 60 * 60 * 1000L),
            "the fix must not clip a genuinely live game inside its window",
        )
    }

    @Test
    fun `a feed-listed game before kickoff is not live`() {
        assertFalse(
            state(nfl).isLive(nfl, start - 4 * 60 * 60 * 1000L),
            "a fixture the feed already lists must not read LIVE before kickoff (B49)",
        )
    }

    @Test
    fun `a feed-listed game within the pre-kickoff grace still reads live`() {
        assertTrue(
            state(nfl).isLive(nfl, start - 5 * 60 * 1000L),
            "within the pre-kickoff grace a feed-confirmed fixture may read LIVE (clock skew)",
        )
    }
}
