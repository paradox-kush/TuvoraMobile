package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import com.nuvio.app.features.sportsdetail.api.WatchUi
import com.nuvio.app.features.sportsdetail.internal.WatchBlockPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchBlockPolicyTest {
    private val offer = WatchOffer("ch-1", "Sky Sports Main Event")
    private val playing = NowWatching("ch-9", "ESPN")
    private fun decide(now: NowWatching?, lookup: WatchLookup?, state: GameState = GameState.LIVE) = WatchBlockPolicy.decide(now, lookup, state)

    @Test fun `browsing with a matcher result offers Play`() {
        assertEquals(WatchUi.Browsing(offer), decide(null, WatchLookup.Found(offer)))
        assertEquals(WatchUi.Browsing(offer), decide(null, WatchLookup.Found(offer), GameState.UPCOMING))
    }

    @Test fun `watching a different channel offers Switch to the matched one`() {
        assertEquals(WatchUi.Watching(playing, switchTo = offer), decide(playing, WatchLookup.Found(offer)))
    }

    @Test fun `watching the matched channel needs no Switch`() {
        val same = NowWatching("ch-1", "Sky Sports Main Event")
        assertEquals(WatchUi.Watching(same, switchTo = null), decide(same, WatchLookup.Found(offer)))
    }

    @Test fun `watching with no or pending lookup still shows the current channel`() {
        assertEquals(WatchUi.Watching(playing, null), decide(playing, null))
        assertEquals(WatchUi.Watching(playing, null), decide(playing, WatchLookup.NoMatch))
        assertEquals(WatchUi.Watching(playing, null), decide(playing, WatchLookup.NoPlaylist))
    }

    @Test fun `not watching and the lookup states are distinct and honest`() {
        assertEquals(WatchUi.Checking, decide(null, null))
        assertEquals(WatchUi.NoPlaylist, decide(null, WatchLookup.NoPlaylist))
        assertEquals(WatchUi.NoMatch, decide(null, WatchLookup.NoMatch))
        assertEquals(WatchUi.LookupFailed, decide(null, WatchLookup.Failed))
    }

    @Test fun `finished or postponed games have no watch block`() {
        assertEquals(WatchUi.Hidden, decide(null, WatchLookup.Found(offer), GameState.FINAL))
        assertEquals(WatchUi.Hidden, decide(playing, null, GameState.POSTPONED))
    }
}
