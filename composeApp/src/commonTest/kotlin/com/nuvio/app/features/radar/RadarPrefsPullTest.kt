package com.nuvio.app.features.radar

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pulled radar_prefs row must never reset Hide scores / score delay it says nothing about (a row from before the
 * columns existed), and must never overwrite a newer local toggle still waiting for its debounced push.
 * kotlin.test → assertEquals(expected, actual, message).
 */
class RadarPrefsPullTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val localHidden30 = RadarPrefs(scoresShown = false, scoreDelaySeconds = 30)

    @Test
    fun `a pulled row without the spoiler columns keeps the local hidden and 30 s`() {
        val row = json.decodeFromString<RadarPrefsRow>("""{"featured_event_id":"wc","opt_in_state":"accepted","promo_dismissed":true}""")
        val merged = RadarPrefsPull.merge(localHidden30, row, localSpoilerWins = false)
        assertEquals(false, merged.scoresShown, "absent scores_shown keeps the local hidden")
        assertEquals(30, merged.scoreDelaySeconds, "absent score_delay_s keeps the local 30 s")
        assertEquals("wc", merged.featuredEventId, "the columns the row has still apply")
        assertEquals(true, merged.promoDismissed, "promo_dismissed applies")
    }

    @Test
    fun `a pulled row with the spoiler columns applies them`() {
        val row = json.decodeFromString<RadarPrefsRow>("""{"scores_shown":true,"score_delay_s":15}""")
        val merged = RadarPrefsPull.merge(localHidden30, row, localSpoilerWins = false)
        assertEquals(true, merged.scoresShown, "remote shown applies")
        assertEquals(15, merged.scoreDelaySeconds, "remote 15 s applies")
    }

    @Test
    fun `a pulled delay outside the allowed set reads as Off`() {
        val row = json.decodeFromString<RadarPrefsRow>("""{"scores_shown":true,"score_delay_s":45}""")
        assertEquals(0, RadarPrefsPull.merge(localHidden30, row, localSpoilerWins = false).scoreDelaySeconds, "45 s is not a choice")
    }

    @Test
    fun `a local toggle still waiting for its push survives an older pulled row`() {
        val olderRemote = json.decodeFromString<RadarPrefsRow>("""{"scores_shown":true,"score_delay_s":0}""")
        val merged = RadarPrefsPull.merge(localHidden30, olderRemote, localSpoilerWins = true)
        assertEquals(false, merged.scoresShown, "the newer local hidden is kept")
        assertEquals(30, merged.scoreDelaySeconds, "the newer local 30 s is kept")
    }

    @Test
    fun `local spoiler wins while a change is unpushed`() {
        assertTrue(RadarPrefsPull.localSpoilerWins(changeSeq = 2, pushedSeq = 1, changeSeqAtPullStart = 2), "change 2 not pushed yet")
    }

    @Test
    fun `local spoiler wins when it changed after the pull started even if already pushed`() {
        // The pull's select may have read the row before that push landed.
        assertTrue(RadarPrefsPull.localSpoilerWins(changeSeq = 3, pushedSeq = 3, changeSeqAtPullStart = 2), "changed mid-pull")
    }

    @Test
    fun `remote wins when every local change was pushed before the pull started`() {
        assertFalse(RadarPrefsPull.localSpoilerWins(changeSeq = 2, pushedSeq = 2, changeSeqAtPullStart = 2), "nothing local pending")
        assertFalse(RadarPrefsPull.localSpoilerWins(changeSeq = 0, pushedSeq = 0, changeSeqAtPullStart = 0), "never toggled")
    }

    @Test
    fun `an unpushed local change is pushed after the pull`() {
        assertTrue(RadarPrefsPull.pushAfterPull(changeSeq = 2, pushedSeq = 1), "the dropped debounced push is re-sent")
        assertFalse(RadarPrefsPull.pushAfterPull(changeSeq = 2, pushedSeq = 2), "nothing to push")
    }
}
