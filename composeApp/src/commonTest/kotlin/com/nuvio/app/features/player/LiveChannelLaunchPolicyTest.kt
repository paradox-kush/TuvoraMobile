package com.nuvio.app.features.player

import com.nuvio.app.features.player.LiveChannelLaunchPolicy.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveChannelLaunchPolicyTest {

    // Regression: playLiveXtreamChannel's async branch did `liveStreamUrlForAsync(id) ?: return@launch`
    // — a failed resolve returned silently after the picker had already been dismissed, so a channel
    // tap could look like a frozen app. These assert the resolve outcome is never a silent no-op.

    @Test
    fun `a failed resolve surfaces feedback instead of a silent no-op`() {
        assertEquals(Outcome.Feedback, LiveChannelLaunchPolicy.resolutionOutcome(null))
    }

    @Test
    fun `a blank url is treated as a failed resolve rather than a playable source`() {
        assertEquals(Outcome.Feedback, LiveChannelLaunchPolicy.resolutionOutcome(""))
        assertEquals(Outcome.Feedback, LiveChannelLaunchPolicy.resolutionOutcome("   "))
    }

    @Test
    fun `a resolved url launches that url`() {
        assertEquals(
            Outcome.Launch("http://host/live/1.ts"),
            LiveChannelLaunchPolicy.resolutionOutcome("http://host/live/1.ts"),
        )
    }
}
