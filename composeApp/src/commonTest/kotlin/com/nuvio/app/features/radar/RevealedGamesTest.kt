package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * "Show this game's score" reveals ONE game (its page and its hub / calendar card) for this session and profile; the
 * global Hide scores setting is untouched. kotlin.test → assertEquals(expected, actual, message).
 */
class RevealedGamesTest {
    private val kickoff = "2026-08-25T18:00:00"
    private val one = RadarFixture(id = "e1", leagueId = "4328", sport = "Soccer", home = "A", away = "B", ts = kickoff)
    private val two = RadarFixture(id = "e2", leagueId = "4328", sport = "Soccer", home = "C", away = "D", ts = kickoff)
    private val now = one.startEpochMs!! + 30 * 60_000L

    private fun hidden(prefs: RadarPrefs = RadarPrefs(scoresShown = false)) = RadarUiState(
        prefs = prefs,
        fixturesByLeague = mapOf("4328" to listOf(one, two)),
        liveEventIds = setOf("e1", "e2"),
        liveScores = mapOf(
            "e1" to RadarLiveScore("e1", homeScore = "2", awayScore = "0"),
            "e2" to RadarLiveScore("e2", homeScore = "1", awayScore = "1"),
        ),
        livescoreSports = setOf("soccer"),
    )

    @Test
    fun `revealing one game leaves the others hidden`() {
        val s = hidden().let { it.copy(revealedGames = it.revealedGames.reveal("e1")) }
        assertEquals(ScoreSlots("2", "0"), s.scoreSlots(one, now), "the revealed game shows its score")
        assertEquals(ScoreSlots.NONE, s.scoreSlots(two, now), "every other game stays hidden")
        assertEquals(false, s.prefs.scoresShown, "the global setting is untouched")
        assertEquals(true, s.scoreSpoilerFor("e1").shown, "the game page gets shown settings for the revealed game")
        assertEquals(false, s.scoreSpoilerFor("e2").shown, "and hidden ones for the rest")
    }

    @Test
    fun `hide again re-hides that game`() {
        val revealed = hidden().let { it.copy(revealedGames = it.revealedGames.reveal("e1")) }
        val again = revealed.copy(revealedGames = revealed.revealedGames.hide("e1"))
        assertEquals(ScoreSlots.NONE, again.scoreSlots(one, now), "hidden again")
        assertFalse("e1" in again.revealedGames, "no longer revealed")
    }

    @Test
    fun `a revealed game keeps the delay`() {
        val s = hidden(RadarPrefs(scoresShown = false, scoreDelaySeconds = 30)).let { it.copy(revealedGames = it.revealedGames.reveal("e1")) }
        s.recordObservedScores(now)
        assertEquals(ScoreSlots.PENDING, s.scoreSlots(one, now + 1_000L), "revealed means shown, under the same delay")
        assertEquals(now + 30_000L, s.nextScoreChangeAtMs(now + 1_000L), "and the hub still re-renders when it is due")
    }

    @Test
    fun `a fresh state forgets every reveal`() {
        // Profile switch and app restart both start from RadarUiState(): reveals are never stored.
        assertFalse("e1" in RadarUiState().revealedGames, "nothing revealed")
        val blob = RadarLocalState(prefs = RadarPrefs(scoresShown = false))
        assertEquals(RadarLocalState(prefs = RadarPrefs(scoresShown = false)), blob, "the persisted blob has no reveal field")
    }
}
