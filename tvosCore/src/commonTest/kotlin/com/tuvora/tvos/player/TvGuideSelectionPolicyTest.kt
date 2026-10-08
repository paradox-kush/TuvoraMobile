package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TvGuideSelectionPolicyTest {
    @Test fun secondOkWhileResolvingKeepsTheRequest() {
        assertEquals(TvGuideSelectionAction.AwaitFullscreen, TvGuideSelectionPolicy.decide(true, false))
    }
    @Test fun resolvedPreviewPromotesAndAnotherChannelPreviews() {
        assertEquals(TvGuideSelectionAction.Fullscreen, TvGuideSelectionPolicy.decide(true, true))
        assertEquals(TvGuideSelectionAction.Preview, TvGuideSelectionPolicy.decide(false, true))
        assertEquals(TvGuideSelectionAction.Preview, TvGuideSelectionPolicy.decide(false, false))
    }
}
