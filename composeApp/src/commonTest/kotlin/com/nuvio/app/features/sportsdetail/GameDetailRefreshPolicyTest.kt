package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy.Config
import com.nuvio.app.features.sportsdetail.api.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameDetailRefreshPolicyTest {
    private val now = 10_000_000L
    private val cfg = Config()

    private fun plan(
        state: GameState,
        startsAt: Long = now - 3_600_000L,
        lastEvent: Long? = null,
        lastSummary: Long? = null,
        failures: Int = 0,
        config: Config = cfg,
    ) = GameDetailRefreshPolicy.plan(state, startsAt, now, lastEvent, lastSummary, failures, config)

    @Test fun `defaults are 15 s live and 60 s summary`() {
        assertEquals(15_000L, cfg.liveMs)
        assertEquals(60_000L, cfg.summaryMs)
    }

    @Test fun `final and postponed games are idle - zero requests and no next check`() {
        for (s in listOf(GameState.FINAL, GameState.POSTPONED)) {
            val p = plan(s, lastEvent = null, lastSummary = null)
            assertTrue(p.isIdle, "$s")
            assertFalse(p.fetchEvent)
            assertFalse(p.fetchSummary)
            assertNull(p.nextCheckInMs)
        }
    }

    @Test fun `upcoming far from kickoff is idle`() {
        val p = plan(GameState.UPCOMING, startsAt = now + 2 * 3_600_000L)
        assertTrue(p.isIdle)
        assertNull(p.nextCheckInMs)
    }

    @Test fun `upcoming inside the pre-kickoff lead ticks the event only so the card flips to live`() {
        val p = plan(GameState.UPCOMING, startsAt = now + 10 * 60_000L, lastEvent = now - 20_000L, lastSummary = null)
        assertTrue(p.fetchEvent)
        assertFalse(p.fetchSummary)
        val waiting = plan(GameState.UPCOMING, startsAt = now + 10 * 60_000L, lastEvent = now - 5_000L)
        assertFalse(waiting.fetchEvent)
        assertEquals(10_000L, waiting.nextCheckInMs)
    }

    @Test fun `live with nothing fetched yet fetches both now`() {
        val p = plan(GameState.LIVE)
        assertTrue(p.fetchEvent && p.fetchSummary)
        assertEquals(0L, p.nextCheckInMs)
    }

    @Test fun `live ticks at 15 s and summary at 60 s`() {
        val fresh = plan(GameState.LIVE, lastEvent = now - 10_000L, lastSummary = now - 10_000L)
        assertFalse(fresh.fetchEvent || fresh.fetchSummary)
        assertEquals(5_000L, fresh.nextCheckInMs)
        val eventDue = plan(GameState.LIVE, lastEvent = now - 16_000L, lastSummary = now - 16_000L)
        assertTrue(eventDue.fetchEvent)
        assertFalse(eventDue.fetchSummary)
        val bothDue = plan(GameState.LIVE, lastEvent = now - 16_000L, lastSummary = now - 61_000L)
        assertTrue(bothDue.fetchEvent && bothDue.fetchSummary)
    }

    @Test fun `remote config cannot push the cadence below its floor`() {
        val aggressive = Config(liveMs = 1_000L, summaryMs = 2_000L, minMs = 6_000L)
        val p = plan(GameState.LIVE, lastEvent = now - 3_000L, lastSummary = now - 3_000L, config = aggressive)
        assertFalse(p.fetchEvent)
        assertEquals(3_000L, p.nextCheckInMs)
    }

    @Test fun `failures back off exponentially up to a cap`() {
        val one = plan(GameState.LIVE, lastEvent = now - 20_000L, lastSummary = now - 20_000L, failures = 1)
        assertFalse(one.fetchEvent) // 15 s * 2 = 30 s
        assertEquals(10_000L, one.nextCheckInMs)
        val many = plan(GameState.LIVE, lastEvent = now - 20_000L, lastSummary = now - 20_000L, failures = 50)
        assertEquals(15_000L * 8 - 20_000L, many.nextCheckInMs) // capped at 8x
    }

    @Test fun `config is read from the mapping`() {
        val c = GameDetailRefreshPolicy.configFrom(bundledMapping().espn.liveMs, bundledMapping().espn.summaryMs, bundledMapping().espn.minMs)
        assertEquals(15_000L, c.liveMs)
        assertEquals(60_000L, c.summaryMs)
        assertEquals(6_000L, c.minMs)
    }
}
