package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TvGameDetailReducerTest {
    private val detail = GameDetail(
        gameId = "tsdb:1", sport = "Soccer", leagueId = "4328", leagueName = "EPL", startsAt = "2026-11-07T15:00:00Z",
        state = GameState.LIVE, header = TeamsHeader(Side("A"), Side("B")),
    )

    @Test fun `a failed first load is Failed with Retry and never Empty`() {
        val s = TvGameDetailReducer.reduce(TvGameDetailState.Loading, DetailLoad.Failed(timedOut = true))
        assertEquals(TvGameDetailState.Failed(timedOut = true), s)
    }

    @Test fun `an empty first load is Empty so the match sheet can take over`() {
        assertEquals(TvGameDetailState.Empty, TvGameDetailReducer.reduce(TvGameDetailState.Loading, DetailLoad.Empty))
    }

    @Test fun `a failed refresh keeps the last good game and marks it stale`() {
        val loaded = TvGameDetailReducer.reduce(TvGameDetailState.Loading, DetailLoad.Loaded(detail))
        val after = TvGameDetailReducer.reduce(loaded, DetailLoad.Failed(false))
        assertIs<TvGameDetailState.Loaded>(after)
        assertTrue(after.stale)
        assertEquals(detail, after.detail)
        assertEquals(false, (TvGameDetailReducer.reduce(after, DetailLoad.Loaded(detail)) as TvGameDetailState.Loaded).stale)
    }

    @Test fun `failure count resets on success and grows otherwise`() {
        assertEquals(0, TvGameDetailReducer.failures(3, DetailLoad.Loaded(detail)))
        assertEquals(4, TvGameDetailReducer.failures(3, DetailLoad.Failed(false)))
        assertEquals(1, TvGameDetailReducer.failures(0, DetailLoad.Empty))
    }
}
