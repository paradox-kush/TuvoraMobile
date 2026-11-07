package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SportsDetailService
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TvGameDetailModelTest {
    private val final = GameDetail(
        gameId = "tsdb:1", sport = "Soccer", leagueId = "1", leagueName = "L", startsAt = "2026-11-07T15:00:00Z",
        state = GameState.FINAL, header = TeamsHeader(Side("A"), Side("B")),
    )

    private class Counting(val load: DetailLoad) : SportsDetailService {
        var ticks = 0
        var syncs = 0
        override fun open(ref: GameRef) = object : GameDetailSource {
            override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad { ticks++; return load }
        }
        override suspend fun syncMapping() { syncs++ }
        override fun refreshConfig() = GameDetailRefreshPolicy.Config()
    }

    @Test fun `re-entering a FINAL game makes zero requests`() = runTest {
        val service = Counting(DetailLoad.Loaded(final))
        val ref = GameRef("tsdb:1", "1", "1", null, "A", "B", null, 0L)
        val model = TvGameDetailModel(ref, service)
        model.runWhileVisible()          // first foreground: one full tick, then idle
        assertEquals(1, service.ticks)
        model.runWhileVisible()          // scenePhase restart: nothing to fetch
        model.runWhileVisible()
        assertEquals(1, service.ticks)
        assertEquals(1, service.syncs)
    }
}
