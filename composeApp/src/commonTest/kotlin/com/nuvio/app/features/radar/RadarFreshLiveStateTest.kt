package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sports hub card read LIVE (and a minute that does not fit hockey, "LIVE 20") long after the game was final: the
 * radar feed lags and its progress is minutes elapsed. The card now prefers the freshest known state — the game screen's
 * ESPN-backed state when it has one — and otherwise applies a per-sport max duration so LIVE can never linger.
 */
class RadarFreshLiveStateTest {
    private val hockey = RadarFixture(id = "nhl1", leagueId = "4380", sport = "Ice Hockey", home = "Washington Capitals", away = "Florida Panthers", ts = "2026-11-07T17:30:00")
    private val start = hockey.startEpochMs!!
    private val hour = 3_600_000L
    private val minute = 60_000L

    private fun state(fresh: Map<String, FreshFixtureState> = emptyMap(), feedLive: Boolean = true) = RadarUiState(
        fixturesByLeague = mapOf("4380" to listOf(hockey)),
        liveEventIds = if (feedLive) setOf("nhl1") else emptySet(),
        livescoreSports = setOf("ice hockey"),
        freshStates = fresh,
    )

    @Test fun `a final fresh state beats a feed that still lists the game`() {
        val now = start + 3 * hour
        assertTrue(state().isLive(hockey, now), "precondition: the stale feed alone says live")
        assertFalse(state(mapOf("nhl1" to FreshFixtureState(live = false, periodLabel = null, atMs = now - minute))).isLive(hockey, now))
    }

    @Test fun `a final fresh state is sticky`() {
        val fresh = mapOf("nhl1" to FreshFixtureState(live = false, periodLabel = null, atMs = start + 2 * hour))
        assertFalse(state(fresh).isLive(hockey, start + 2 * hour + 30 * minute))
    }

    @Test fun `a recent live fresh state keeps the card live even when the feed dropped it`() {
        val now = start + 90 * minute
        assertFalse(state(feedLive = false).isLive(hockey, now))
        assertTrue(state(mapOf("nhl1" to FreshFixtureState(true, "2nd", now - minute)), feedLive = false).isLive(hockey, now))
    }

    @Test fun `a stale live fresh state is ignored`() {
        val now = start + 90 * minute
        val old = mapOf("nhl1" to FreshFixtureState(true, "2nd", now - 10 * minute))
        assertFalse(state(old, feedLive = false).isLive(hockey, now))
    }

    @Test fun `no live reading survives the sport's display max duration`() {
        assertTrue(state().isLive(hockey, start + 3 * hour + 30 * minute), "a long overtime game is still live inside the cap")
        assertFalse(state().isLive(hockey, start + 4 * hour), "the feed alone cannot keep a hockey card live past ~3.75 h")
        val fresh = mapOf("nhl1" to FreshFixtureState(true, "3rd", start + 5 * hour - minute))
        assertFalse(state(fresh).isLive(hockey, start + 5 * hour), "a fresh LIVE cannot resurrect a game past the cap")
    }

    @Test fun `period sports show the period not the elapsed minute`() {
        val now = start + 2 * hour
        assertNull(LiveStatusPolicy.badgeProgress("Ice Hockey", "20", null, now), "TSDB's minute-elapsed does not fit hockey")
        assertNull(LiveStatusPolicy.badgeProgress("Basketball", "20", null, now))
        assertEquals("3rd", LiveStatusPolicy.badgeProgress("Ice Hockey", "20", FreshFixtureState(true, "3rd", now - minute), now))
        assertNull(LiveStatusPolicy.badgeProgress("Ice Hockey", "20", FreshFixtureState(true, "3rd", now - 10 * minute), now), "a stale period is not shown")
    }

    @Test fun `minute sports keep the feed minute`() {
        assertEquals("67", LiveStatusPolicy.badgeProgress("Soccer", "67", null, 0L))
        assertNull(LiveStatusPolicy.badgeProgress("Soccer", " ", null, 0L))
        assertEquals("2nd half", LiveStatusPolicy.badgeProgress("Soccer", "67", FreshFixtureState(true, "2nd half", 0L), 1L))
    }

    @Test fun `ui state exposes the badge text for a fixture`() {
        val now = start + 2 * hour
        val s = state(mapOf("nhl1" to FreshFixtureState(true, "3rd", now - minute)))
        assertEquals("3rd", s.liveProgress(hockey, now))
        assertNull(state().liveProgress(hockey, now))
    }
}
