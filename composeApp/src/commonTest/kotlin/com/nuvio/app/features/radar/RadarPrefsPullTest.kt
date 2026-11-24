package com.nuvio.app.features.radar

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hide scores / delay sync: a pulled row never resets what it lacks, and a local change no push has confirmed
 * ([RadarPrefs.spoilerDirty], persisted per profile) survives every pull until a push carries it.
 * kotlin.test → assertEquals(expected, actual, message).
 */
class RadarPrefsPullTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val localHidden30 = RadarPrefs(scoresShown = false, scoreDelaySeconds = 30)
    private val dirtyHidden30 = localHidden30.copy(spoilerDirty = true)
    private fun row(text: String) = json.decodeFromString<RadarPrefsRow>(text)

    // --- the flag itself -------------------------------------------------------------------------------------------

    @Test
    fun `spoiler_dirty survives the local blob round-trip`() {
        val text = json.encodeToString(RadarLocalState.serializer(), RadarLocalState(prefs = dirtyHidden30))
        assertTrue("\"spoiler_dirty\":true" in text, "persisted under spoiler_dirty: $text")
        assertEquals(true, json.decodeFromString<RadarLocalState>(text).prefs.spoilerDirty, "round-trip keeps it")
    }

    @Test
    fun `an old blob without spoiler_dirty decodes clean`() {
        val old = """{"follows":[],"prefs":{"scores_shown":false,"score_delay_s":30},"teams":[]}"""
        assertEquals(false, json.decodeFromString<RadarLocalState>(old).prefs.spoilerDirty, "nothing pending")
    }

    @Test
    fun `spoiler_dirty is never pushed`() {
        assertFalse(radarPrefsPayload(dirtyHidden30).containsKey("spoiler_dirty"), "device-local only")
    }

    // --- pull ------------------------------------------------------------------------------------------------------

    @Test
    fun `a pulled row without the spoiler columns keeps the local hidden and 30 s`() {
        val merged = RadarPrefsPull.merge(localHidden30, row("""{"featured_event_id":"wc","promo_dismissed":true}"""))
        assertEquals(false to 30, merged.scoresShown to merged.scoreDelaySeconds, "absent columns keep local")
        assertEquals("wc", merged.featuredEventId, "the columns the row has still apply")
    }

    @Test
    fun `a clean pull takes the remote spoiler values`() {
        val merged = RadarPrefsPull.merge(localHidden30, row("""{"scores_shown":true,"score_delay_s":15}"""))
        assertEquals(true to 15, merged.scoresShown to merged.scoreDelaySeconds, "remote applies")
        assertFalse(merged.spoilerDirty, "still clean")
    }

    @Test
    fun `a pull keeps the local spoiler values while dirty`() {
        val merged = RadarPrefsPull.merge(dirtyHidden30, row("""{"featured_event_id":"wc","scores_shown":true,"score_delay_s":0}"""))
        assertEquals(false to 30, merged.scoresShown to merged.scoreDelaySeconds, "the unpushed local change wins")
        assertTrue(merged.spoilerDirty, "still waiting for its push")
        assertEquals("wc", merged.featuredEventId, "the other prefs still apply")
    }

    @Test
    fun `a pull with a dirty change pushes after`() {
        assertTrue(RadarPrefsPull.pushAfterPull(RadarPrefsPull.merge(dirtyHidden30, row("""{"scores_shown":true}"""))), "dirty -> push")
        assertFalse(RadarPrefsPull.pushAfterPull(RadarPrefsPull.merge(localHidden30, row("""{"scores_shown":true}"""))), "clean -> no push")
    }

    @Test
    fun `profile B's pull ignores profile A's dirty change`() {
        val profileAState = dirtyHidden30                         // what is on screen: A's unpushed toggle
        val profileBStored = RadarPrefs(scoresShown = true)       // B's own blob: clean
        val base = RadarPrefsPull.localFor(pullProfileId = 2, currentProfileId = 1, current = profileAState) { profileBStored }
        val merged = RadarPrefsPull.merge(base, row("""{"scores_shown":true,"score_delay_s":15}"""))
        assertEquals(true to 15, merged.scoresShown to merged.scoreDelaySeconds, "B takes its remote row")
        assertFalse(merged.spoilerDirty, "A's flag never leaks into B")
        assertEquals(dirtyHidden30, RadarPrefsPull.localFor(1, 1, profileAState) { profileBStored }, "same profile: what is on screen")
    }

    // --- push ------------------------------------------------------------------------------------------------------

    @Test
    fun `a push clears dirty when the local values still match`() {
        assertFalse(RadarPrefsPull.afterPush(current = dirtyHidden30, pushed = dirtyHidden30).spoilerDirty, "confirmed")
    }

    @Test
    fun `a toggle made after the push read stays dirty`() {
        val toggledSince = dirtyHidden30.copy(scoresShown = true)
        assertTrue(RadarPrefsPull.afterPush(current = toggledSince, pushed = dirtyHidden30).spoilerDirty, "shown changed since the read")
        val delaySince = dirtyHidden30.copy(scoreDelaySeconds = 60)
        assertTrue(RadarPrefsPull.afterPush(current = delaySince, pushed = dirtyHidden30).spoilerDirty, "delay changed since the read")
    }

    @Test
    fun `a push never dirties a clean state`() {
        assertFalse(RadarPrefsPull.afterPush(current = localHidden30, pushed = dirtyHidden30.copy(scoresShown = true)).spoilerDirty, "clean stays clean")
    }
}
