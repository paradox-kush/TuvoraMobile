package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadSurface
import kotlin.test.Test
import kotlin.test.assertEquals

class LivePauseStateTest {
    @Test
    fun `preparing carries the bounded live pause deadline`() {
        val loading = BoundedLoad.begin(LoadSurface.LIVE_PAUSE, nowMs = 5_000L)
        val state = LivePauseState.Preparing(loading)
        assertEquals(5_000L + LivePausePolicy.BUFFER_START_DEADLINE_MS, state.status.deadlineAtMs)
    }

    @Test
    fun `states are values`() {
        val a = LivePauseState.Shifted(paused = true, behindLiveMs = 30_000, windowStartMs = 0, windowEndMs = 90_000, positionMs = 60_000, bufferFull = false)
        assertEquals(a, a.copy())
        assertEquals(LivePauseState.Failed(LivePauseFailure.NO_SPACE), LivePauseState.Failed(LivePauseFailure.NO_SPACE))
    }
}
