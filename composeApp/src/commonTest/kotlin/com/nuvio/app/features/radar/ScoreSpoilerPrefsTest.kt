package com.nuvio.app.features.radar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hide scores + score delay are per-profile prefs: persisted with the other radar prefs (old local blobs without the
 * keys read as today's behaviour) and pushed in sync_push_radar's p_prefs as scores_shown / score_delay_s.
 */
class ScoreSpoilerPrefsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `old local JSON without the keys decodes to defaults`() {
        val old = """{"follows":[],"prefs":{"featured_event_id":"wc","opt_in_state":"accepted","promo_dismissed":true},"teams":[]}"""
        val prefs = json.decodeFromString<RadarLocalState>(old).prefs
        assertEquals(true, prefs.scoresShown, "a blob from before the feature shows scores")
        assertEquals(0, prefs.scoreDelaySeconds, "and has no delay")
        assertEquals(ScoreSpoilerSettings(), prefs.scoreSpoiler, "settings are the defaults")
    }

    @Test
    fun `new keys round-trip through the local blob`() {
        val state = RadarLocalState(prefs = RadarPrefs(scoresShown = false, scoreDelaySeconds = 30))
        val text = json.encodeToString(RadarLocalState.serializer(), state)
        assertTrue("\"scores_shown\":false" in text, "persisted under scores_shown: $text")
        assertTrue("\"score_delay_s\":30" in text, "persisted under score_delay_s: $text")
        assertEquals(state, json.decodeFromString<RadarLocalState>(text), "round-trip")
    }

    @Test
    fun `a stored delay outside the allowed set reads as Off`() {
        assertEquals(0, RadarPrefs(scoreDelaySeconds = 45).scoreSpoiler.delaySeconds, "45 s is not a choice")
    }

    @Test
    fun `sync payload contains both keys`() {
        val payload = radarPrefsPayload(RadarPrefs(scoresShown = false, scoreDelaySeconds = 15))
        assertEquals(false, payload["scores_shown"]?.jsonPrimitive?.boolean, "scores_shown pushed")
        assertEquals(15, payload["score_delay_s"]?.jsonPrimitive?.int, "score_delay_s pushed")
        assertTrue(payload.containsKey("featured_event_id") && payload.containsKey("opt_in_state") && payload.containsKey("promo_dismissed"), "existing prefs still pushed")
    }

    @Test
    fun `sync payload sanitizes the delay`() {
        val payload = radarPrefsPayload(RadarPrefs(scoreDelaySeconds = 45))
        assertEquals(0, payload["score_delay_s"]?.jsonPrimitive?.int, "the backend check only accepts 0/15/30/60")
    }

    // --- the hub's view of one fixture -----------------------------------------------------------------------------

    private val kickoff = "2026-08-25T18:00:00"
    private val live = RadarFixture(id = "e1", leagueId = "4328", sport = "Soccer", home = "H", away = "A", ts = kickoff)
    private val start = live.startEpochMs!!

    private fun stateWith(prefs: RadarPrefs, scores: Map<String, RadarLiveScore>, fixtures: List<RadarFixture> = listOf(live)) = RadarUiState(
        prefs = prefs,
        fixturesByLeague = mapOf("4328" to fixtures),
        liveEventIds = setOf("e1"),
        liveScores = scores,
        livescoreSports = setOf("soccer"),
    )

    @Test
    fun `the hub delays a live score recorded where the fetch landed`() {
        val prefs = RadarPrefs(scoreDelaySeconds = 30)
        val t0 = start + 20 * 60_000L
        val first = stateWith(prefs, mapOf("e1" to RadarLiveScore("e1", homeScore = "0", awayScore = "0")))
        first.recordObservedScores(t0)
        val second = first.copy(liveScores = mapOf("e1" to RadarLiveScore("e1", homeScore = "1", awayScore = "0")))
        second.recordObservedScores(t0 + 40_000L)
        assertEquals(ScoreSlots("0", "0"), second.scoreSlots(live, t0 + 50_000L), "the 1-0 is only 10 s old: 0-0 shows")
        assertEquals(ScoreSlots("1", "0"), second.scoreSlots(live, t0 + 70_000L), "the 1-0 is due at +70 s")
        assertEquals(t0 + 70_000L, second.nextScoreChangeAtMs(t0 + 50_000L), "one local re-render when 1-0 is due")
    }

    @Test
    fun `hidden hides the fixture's own final score too`() {
        val finished = live.copy(status = "Match Finished", homeScore = "2", awayScore = "1")
        val state = stateWith(RadarPrefs(scoresShown = false), emptyMap(), listOf(finished))
        state.recordObservedScores(start + 5 * 60 * 60_000L)
        assertEquals(ScoreSlots(null, null), state.scoreSlots(finished, start + 5 * 60 * 60_000L), "a final result is hidden")
    }

    @Test
    fun `a game final at first sight shows at once under a delay`() {
        val finished = live.copy(status = "Match Finished", homeScore = "2", awayScore = "1")
        val state = stateWith(RadarPrefs(scoreDelaySeconds = 60), emptyMap(), listOf(finished))
        val now = start + 5 * 60 * 60_000L
        state.recordObservedScores(now)
        assertEquals(ScoreSlots("2", "1"), state.scoreSlots(finished, now), "old news is not delayed")
    }

    @Test
    fun `a live score never recorded yet is pending under a delay`() {
        val state = stateWith(RadarPrefs(scoreDelaySeconds = 15), mapOf("e1" to RadarLiveScore("e1", homeScore = "1", awayScore = "0")))
        val now = start + 20 * 60_000L
        state.recordObservedScores(now)
        assertEquals(ScoreSlots("–", "–", pending = true), state.scoreSlots(live, now + 5_000L), "nothing 15 s old yet")
    }
}
