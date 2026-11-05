// New for the Sports games scope (game model shape after homelessbrian/NuvioPlusIPTV livetv/calendar, GPL-3.0).
package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarGameState
import com.nuvio.app.features.iptv.LoadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class CalendarGamesTest {
    private val sydney = TimeZone.of("Australia/Sydney")
    private val newYork = TimeZone.of("America/New_York")
    /** Tue 6 Oct 2026, 10:00 in Sydney. */
    private val now = at("2026-10-05T23:00:00Z")

    private fun game(
        id: String,
        kickoff: String,
        state: CalendarGameState = CalendarGameState.SCHEDULED,
        home: String? = "Home $id",
        away: String? = "Away $id",
        homeScore: String? = null,
        awayScore: String? = null,
        progress: String? = null,
    ) = CalendarGameItem(
        id = id, league = "League", home = home, away = away, kickoffMs = at(kickoff), state = state,
        homeScore = homeScore, awayScore = awayScore, progress = progress,
    )

    private val all = CalendarFilter.entries.toSet()

    @Test fun aFollowedTeamsFixtureAppearsOnItsKickoffDayInTheOverrideZone() {
        // Mon 5 Oct 21:00 EDT = Tue 6 Oct 01:00 UTC = Tue 6 Oct 12:00 AEDT.
        val g = game("g1", "2026-10-06T01:00:00Z")
        val sydneyToday = buildCalendarSections(emptyList(), now, sydney, games = listOf(g)).single()
        assertEquals(CalendarSectionKind.TODAY, sydneyToday.kind)
        assertEquals(LocalDate(2026, 10, 6), sydneyToday.date)
        assertEquals("g1", sydneyToday.items.single().game?.id)
        assertEquals(kotlinx.datetime.LocalTime(12, 0), sydneyToday.items.single().time)

        val newYorkDay = buildCalendarSections(emptyList(), now, newYork, games = listOf(g)).single()
        assertEquals(LocalDate(2026, 10, 5), newYorkDay.date, "the same kick-off counts on the previous day in New York")
    }

    @Test fun gamesSortWithEpisodesByTimeOfDay() {
        val episode = CalendarEntry(season = 1, episode = 1, airInstantMs = at("2026-10-06T03:00:00Z"))
        val early = game("early", "2026-10-06T01:00:00Z")
        val late = game("late", "2026-10-06T06:00:00Z")
        val today = buildCalendarSections(listOf(showRecord("series:imdb:tt1", episode)), now, sydney, games = listOf(late, early)).single()
        assertEquals(listOf("game:early", "series:imdb:tt1", "game:late"), today.items.map { it.title.key })
    }

    @Test fun finalsOfTheLastSevenDaysAreJustAiredAndOlderOnesDropOut() {
        val recent = game("recent", "2026-10-03T10:00:00Z", CalendarGameState.FINAL, homeScore = "2", awayScore = "1")
        val old = game("old", "2026-09-20T10:00:00Z", CalendarGameState.FINAL, homeScore = "0", awayScore = "0")
        val sections = buildCalendarSections(emptyList(), now, sydney, games = listOf(recent, old))
        val justAired = sections.single { it.kind == CalendarSectionKind.JUST_AIRED }
        assertEquals(listOf("recent"), justAired.items.map { it.game?.id })
    }

    @Test fun gamesBeyondTheWindowNeverFloodLater() {
        val far = game("far", "2026-12-20T10:00:00Z")
        assertEquals(emptyList(), buildCalendarSections(emptyList(), now, sydney, windowDays = 30, games = listOf(far)))
    }

    @Test fun theSameGameFromLeagueAndTeamLanesCountsOnce() {
        val a = game("dup", "2026-10-07T10:00:00Z")
        val b = a.copy(homeScore = null)
        assertEquals(1, listOf(a, b).filteredBy(all, now).size)
    }

    @Test fun aScheduledGameLongPastItsKickoffIsStaleNotUpcoming() {
        val stale = game("stale", "2026-10-04T10:00:00Z")
        assertEquals(emptyList(), listOf(stale).filteredBy(all, now))
        assertEquals(1, listOf(stale.copy(state = CalendarGameState.FINAL)).filteredBy(all, now).size)
    }

    @Test fun theFilterKeepsShowsFilmsAndSportsApart() {
        val show = showRecord("series:imdb:tt1", CalendarEntry(season = 1, episode = 1, airInstantMs = now + DAY))
        val film = show.copy(key = "movie:imdb:tt2", type = CalendarTitleType.MOVIE, ids = CalendarIds(imdb = "tt2"))
        val titles = listOf(show, film)
        assertEquals(listOf(show), titles.filteredBy(setOf(CalendarFilter.SHOWS)))
        assertEquals(listOf(film), titles.filteredBy(setOf(CalendarFilter.MOVIES, CalendarFilter.SPORTS)))
        assertEquals(titles, titles.filteredBy(all))
        val g = game("g", "2026-10-07T10:00:00Z")
        assertEquals(emptyList(), listOf(g).filteredBy(setOf(CalendarFilter.SHOWS, CalendarFilter.MOVIES), now))
        assertEquals(listOf(g), listOf(g).filteredBy(setOf(CalendarFilter.SPORTS), now))
    }

    @Test fun theScreenAppliesTheFiltersToSectionsAndMonth() {
        val show = showRecord("series:imdb:tt1", CalendarEntry(season = 1, episode = 1, airInstantMs = now + DAY))
        val g = game("g", "2026-10-07T10:00:00Z")
        val state = CalendarState(titles = listOf(show), games = listOf(g), status = LoadStatus.Loaded, zone = sydney)
        fun keys(filters: Set<CalendarFilter>) = calendarScreenUi(
            state.copy(settings = CalendarSettings(filters = filters)), now, CalendarViewMode.LIST, null,
        ).sections.flatMap { it.items }.map { it.title.key }
        assertEquals(listOf("series:imdb:tt1", "game:g"), keys(all))
        assertEquals(listOf("series:imdb:tt1"), keys(setOf(CalendarFilter.SHOWS)))
        assertEquals(listOf("game:g"), keys(setOf(CalendarFilter.SPORTS)))

        val month = calendarScreenUi(state.copy(settings = CalendarSettings(filters = setOf(CalendarFilter.SPORTS))), now, CalendarViewMode.MONTH, LocalDate(2026, 10, 1)).month!!
        assertEquals(listOf("game:g"), month.weeks.flatten().flatMap { it.items }.map { it.title.key })
    }

    @Test fun aGameAloneMakesTheCalendarContentNotEmpty() {
        val state = CalendarState(games = listOf(game("g", "2026-10-07T10:00:00Z")), status = LoadStatus.Loaded, zone = sydney)
        assertEquals(CalendarBody.Content, calendarScreenUi(state, now, CalendarViewMode.LIST, null).body)
    }

    @Test fun watchNowIsOfferedForLiveGamesAndFromFifteenMinutesBeforeKickoff() {
        val kickoff = at("2026-10-06T12:00:00Z")
        val g = game("g", "2026-10-06T12:00:00Z")
        assertFalse(g.canWatchNow(kickoff - 16 * MINUTE))
        assertTrue(g.canWatchNow(kickoff - 15 * MINUTE))
        assertTrue(g.canWatchNow(kickoff + MINUTE))
        assertTrue(g.copy(state = CalendarGameState.LIVE).canWatchNow(kickoff + 2 * HOUR))
        assertFalse(g.copy(state = CalendarGameState.FINAL).canWatchNow(kickoff + MINUTE))
        assertFalse(g.copy(state = CalendarGameState.POSTPONED).canWatchNow(kickoff))
        assertFalse(g.canWatchNow(kickoff + 13 * HOUR), "a scheduled game that never went live is stale")
    }

    @Test fun comingUpListsLiveGamesFirstThenTodaysAndTheNextDaysRespectingTheSportsFilter() {
        val live = game("live", "2026-10-05T22:00:00Z", CalendarGameState.LIVE, homeScore = "1", awayScore = "0", progress = "67'")
        val tonight = game("tonight", "2026-10-06T08:00:00Z")
        val nextWeek = game("nextWeek", "2026-10-14T08:00:00Z")
        val final = game("final", "2026-10-05T10:00:00Z", CalendarGameState.FINAL)
        val games = listOf(nextWeek, tonight, final, live)
        val row = buildCalendarComingUpGames(games, all, now, sydney)
        assertEquals(listOf("live", "tonight"), row.map { it.game.id })
        assertEquals(CalendarComingUpWhen.NEW, row.first().whenKind)
        assertEquals(CalendarComingUpWhen.TODAY, row.last().whenKind)
        assertEquals(emptyList(), buildCalendarComingUpGames(games, setOf(CalendarFilter.SHOWS), now, sydney))
    }

    @Test fun theTitleIsBothSidesElseTheEventNameElseTheLeague() {
        assertEquals("A v B", game("g", "2026-10-07T10:00:00Z", home = "A", away = "B").title)
        assertEquals("Monaco GP", game("g", "2026-10-07T10:00:00Z", home = null, away = null).copy(event = "Monaco GP").title)
        assertEquals("League", game("g", "2026-10-07T10:00:00Z", home = null, away = null).title)
        assertNull(game("g", "2026-10-07T10:00:00Z").progress)
    }

    @Test fun settingsDefaultToEveryFilterOnAndOldSettingsStillLoad() {
        assertEquals(CalendarFilter.entries.toSet(), CalendarSettings().filters)
        val store = object : CalendarStore {
            val values = mutableMapOf("settings.1" to """{"windowDays":14}""")
            override fun load(key: String) = values[key]
            override fun save(key: String, value: String) { values[key] = value }
        }
        assertEquals(CalendarFilter.entries.toSet(), store.loadSettings(1).filters)
        store.saveSettings(1, CalendarSettings(filters = setOf(CalendarFilter.SPORTS)))
        assertEquals(setOf(CalendarFilter.SPORTS), store.loadSettings(1).filters)
    }
}
