package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NoGameWatch
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.SportsDetailAccess
import com.nuvio.app.features.sportsdetail.api.SportsDetailService
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * iOS never wraps its UI in `installFeatures` (only Android's MainActivity does), so the screen reads the composition
 * locals with nothing provided. Their defaults must be the service/port registered at process start — a null default made
 * the game screen fall straight back to the old match sheet on iPhone.
 */
class SportsDetailAccessTest {
    private val service = object : SportsDetailService {
        override fun open(ref: GameRef): GameDetailSource = error("unused")
        override suspend fun syncMapping() {}
        override fun refreshConfig() = GameDetailRefreshPolicy.Config()
    }
    private val watch = object : GameWatchPort {
        override val nowWatching: StateFlow<NowWatching?> = MutableStateFlow(null)
        override suspend fun lookup(ref: GameRef): WatchLookup = WatchLookup.NoMatch
    }

    @AfterTest fun reset() = SportsDetailAccess.resetForTest()

    @Test fun `nothing registered means no service and the no-op watch port`() {
        SportsDetailAccess.resetForTest()
        assertNull(SportsDetailAccess.service())
        assertSame(NoGameWatch, SportsDetailAccess.watch())
    }

    @Test fun `what the platform registered at process start is what an unwrapped composition reads`() {
        SportsDetailAccess.register(service, watch)
        assertSame(service, SportsDetailAccess.service())
        assertSame(watch, SportsDetailAccess.watch())
        assertNotNull(SportsDetailAccess.service())
    }
}
