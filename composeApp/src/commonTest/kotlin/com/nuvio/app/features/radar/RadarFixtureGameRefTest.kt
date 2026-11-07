package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the Sports hub can hand to the game screen: only fixtures with an id, a league and a kickoff. */
class RadarFixtureGameRefTest {
    private val fx = RadarFixture(id = "2601", leagueId = "4380", home = "Washington Capitals", away = "Florida Panthers", ts = "2026-11-07T18:00:00")

    @Test fun `a team fixture becomes a tsdb game reference`() {
        val r = assertNotNull(fx.toGameRef())
        assertEquals("tsdb:2601", r.gameId)
        assertEquals("4380", r.leagueId)
        assertEquals("2601", r.tsdbEventId)
        assertEquals("Washington Capitals", r.homeName)
        assertEquals("Florida Panthers", r.awayName)
        assertEquals(fx.startEpochMs, r.startsAtMs)
    }

    @Test fun `a single-entity event keeps its name and has no away side`() {
        val r = assertNotNull(RadarFixture(id = "9", leagueId = "4370", event = "Sao Paulo Grand Prix", ts = "2026-11-08T17:00:00").toGameRef())
        assertEquals("Sao Paulo Grand Prix", r.homeName)
        assertNull(r.awayName)
    }

    @Test fun `a fixture missing an id a league or a kickoff is not openable`() {
        assertNull(fx.copy(id = null).toGameRef())
        assertNull(fx.copy(leagueId = null).toGameRef())
        assertNull(fx.copy(ts = null).toGameRef())
        assertEquals(false, fx.copy(ts = null).opensGameScreen())
        assertEquals(true, fx.opensGameScreen())
    }
}
