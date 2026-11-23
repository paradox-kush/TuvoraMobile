package com.tuvora.tvos.screens

import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarGameState
import com.nuvio.app.features.calendar.internal.CalendarSettings
import com.nuvio.app.features.calendar.internal.CalendarState
import com.nuvio.app.features.calendar.internal.parseCalendarInstantMs
import com.nuvio.app.features.radar.RadarFixture
import com.nuvio.app.features.radar.RadarLiveScore
import com.nuvio.app.features.radar.RadarPrefs
import com.nuvio.app.features.radar.RadarUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * Apple TV applies the same Hide scores / score delay policy as the phone: the hub's score slots and the calendar's
 * game rows come out of shared Kotlin already redacted. kotlin.test → assertEquals(expected, actual, message).
 */
class TvScoreSpoilerTest {
    private val live = RadarFixture(id = "e1", leagueId = "4328", sport = "Soccer", home = "A", away = "B", ts = "2026-09-28T12:00:00")
    private val now = live.startEpochMs!! + 30 * 60_000L

    private fun state(prefs: RadarPrefs) = RadarUiState(
        prefs = prefs,
        fixturesByLeague = mapOf("4328" to listOf(live)),
        liveEventIds = setOf("e1"),
        liveScores = mapOf("e1" to RadarLiveScore("e1", homeScore = "2", awayScore = "0")),
        livescoreSports = setOf("soccer"),
    )

    @Test
    fun `the hub shows the score by default`() {
        val s = state(RadarPrefs())
        assertEquals("2", TvSportsHubPolicy.homeScore(s, live, now), "home score")
        assertEquals("0", TvSportsHubPolicy.awayScore(s, live, now), "away score")
    }

    @Test
    fun `hidden scores give the hub no numbers and no dimming`() {
        val s = state(RadarPrefs(scoresShown = false))
        val home = TvSportsHubPolicy.homeScore(s, live, now)
        val away = TvSportsHubPolicy.awayScore(s, live, now)
        assertEquals(null, home, "home hidden")
        assertEquals(null, away, "away hidden")
        assertFalse(TvSportsHubPolicy.scoreTrails(away, home), "no winner emphasis")
    }

    @Test
    fun `a delay with nothing due shows dashes`() {
        val s = state(RadarPrefs(scoreDelaySeconds = 30))
        s.recordObservedScores(now)
        assertEquals("–", TvSportsHubPolicy.homeScore(s, live, now + 1_000L), "pending")
        assertEquals("2", TvSportsHubPolicy.homeScore(s, live, now + 31_000L), "due after 30 s")
        assertEquals(now + 30_000L, s.nextScoreChangeAtMs(now + 1_000L), "one redraw when due")
    }

    @Test
    fun `a pending calendar game reaches Swift as pending`() {
        val calNow = parseCalendarInstantMs("2026-10-05T23:00:00Z")!!
        val game = CalendarGameItem(
            id = "g1", home = "Arsenal", away = "Chelsea", kickoffMs = parseCalendarInstantMs("2026-10-06T01:00:00Z")!!,
            state = CalendarGameState.LIVE, homeScore = "–", awayScore = "–", scorePending = true,
        )
        val screen = TvCalendarPresentation.screen(
            CalendarState(games = listOf(game), zone = TimeZone.of("Australia/Sydney"), settings = CalendarSettings()), calNow, null,
        )
        assertTrue(screen.sections.single().rows.single().game!!.scorePending, "pending flag carried")
    }
}
