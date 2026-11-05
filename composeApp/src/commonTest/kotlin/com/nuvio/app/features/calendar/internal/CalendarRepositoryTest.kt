// Tuvora regression tests for the Calendar refresh (the Arc fork's CalendarRefresherTest covers its own wiring; these pin
// the four fixes over Arc and the "idle slate costs nothing" rule).
package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.calendar.api.CalendarAccounts
import com.nuvio.app.features.calendar.api.CalendarFollowItem
import com.nuvio.app.features.calendar.api.CalendarFollowOrigin
import com.nuvio.app.features.calendar.api.CalendarFollowType
import com.nuvio.app.features.calendar.api.CalendarFollowedRead
import com.nuvio.app.features.calendar.api.CalendarFollowedSources
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRepositoryTest {
    private val start = at("2026-10-05T23:00:00Z")

    private class MemoryStore : CalendarStore {
        val values = mutableMapOf<String, String>()
        override fun load(key: String): String? = values[key]
        override fun save(key: String, value: String) {
            values[key] = value
        }
    }

    private class Accounts(var signedIn: Boolean = false) : CalendarAccounts {
        override val activeProfileId: StateFlow<Int> = MutableStateFlow(1)
        override fun tmdbApiKey(): String? = "key"
        override fun traktSignedIn(): Boolean = signedIn
        override suspend fun traktAuthorizedHeaders(): Map<String, String>? = if (signedIn) emptyMap() else null
        override fun traktPublicHeaders(): Map<String, String> = emptyMap()
        override fun region(): String = "US"
    }

    /**
     * Runs everything due now, background scope included. advanceUntilIdle() stops once only backgroundScope work is
     * left, so it never started the repository's own refresh job (found by the first run of these tests).
     */
    private fun TestScope.settle() = repeat(10) { testScheduler.runCurrent() }

    private val store = MemoryStore()
    private val remote = FakeCalendarRemote()
    private val accounts = Accounts()
    private var reads = 0
    private var traktCalendarCalls = 0
    private var traktCalendarAnswer: List<TraktCalendarItem>? = emptyList()
    private var followed = listOf(
        CalendarFollowItem("tt1", CalendarFollowType.SERIES, CalendarFollowOrigin.LIBRARY, name = "One"),
        CalendarFollowItem("tt2", CalendarFollowType.SERIES, CalendarFollowOrigin.LIBRARY, name = "Two"),
    )

    @BeforeTest fun setUp() = BoundedLoad.resetReportsForTests()

    @AfterTest fun tearDown() {
        BoundedLoad.clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }

    private fun TestScope.repository(): CalendarRepository {
        val clock = { start + testScheduler.currentTime }
        BoundedLoad.clock = clock
        return CalendarRepository(
            store = store,
            sources = CalendarFollowedSources { reads++; CalendarFollowedRead(items = followed) },
            accounts = accounts,
            remote = remote,
            traktCalendar = { _: LocalDate -> traktCalendarCalls++; traktCalendarAnswer },
            clock = clock,
            deviceZone = { TimeZone.UTC },
            scope = backgroundScope,
        )
    }

    /** Both followed titles already stored, fetched an hour ago and not due again for 6 h. */
    private fun storeAFreshSlate(traktFetchedAtMs: Long = 0L) {
        val titles = listOf("tt1", "tt2").map { id ->
            CalendarTitleRecord(
                key = "series:imdb:$id", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = id), name = id,
                origins = setOf(CalendarOrigin.LIBRARY), tvmazeId = id.last().digitToInt(),
                sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 1, airInstantMs = start + DAY))),
                fetchedAtMs = start - HOUR, nextRefreshAtMs = start + 5 * HOUR,
            )
        }
        store.saveSnapshot(1, CalendarSnapshot(titles = titles, traktCalendarFetchedAtMs = traktFetchedAtMs))
    }

    @Test fun anIdleSlateMakesZeroNetworkRequests() = runTest {
        storeAFreshSlate()
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(emptyList(), remote.calls, "no title is due, so nothing is fetched")
        assertEquals(0, traktCalendarCalls, "signed out: the Trakt calendar is not asked")
        assertEquals(LoadStatus.Loaded, repository.state.value.status)
        assertEquals(2, repository.state.value.titles.size)
    }

    @Test fun anIdleSlateWithSportsPresentStillMakesZeroCalendarNetworkRequests() = runTest {
        storeAFreshSlate()
        val reads = mutableListOf<Long>()
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val game = com.nuvio.app.features.calendar.api.CalendarGameItem(id = "g1", home = "A", away = "B", kickoffMs = start + HOUR)
        val sports = object : com.nuvio.app.features.calendar.api.CalendarSportsSource {
            override fun games(nowMs: Long) = listOf(game).also { reads += nowMs }
            override val changes: kotlinx.coroutines.flow.Flow<Unit> = changes
        }
        val clock = { start + testScheduler.currentTime }
        BoundedLoad.clock = clock
        val repository = CalendarRepository(
            store = store, sources = CalendarFollowedSources { CalendarFollowedRead(items = followed) }, accounts = accounts,
            remote = remote, traktCalendar = { _: LocalDate -> traktCalendarCalls++; traktCalendarAnswer },
            clock = clock, deviceZone = { TimeZone.UTC }, scope = backgroundScope, sports = sports,
        )
        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(listOf(game), repository.state.value.games, "the games are read from the source")
        assertEquals(emptyList(), remote.calls, "the Calendar asked the network for nothing, sports or not")
        changes.emit(Unit)
        settle()
        assertTrue(reads.size >= 2, "a change in the sports state re-reads the games")
        assertEquals(emptyList(), remote.calls)
        assertEquals(0, traktCalendarCalls)
    }

    @Test fun anIdleSlateSignedInWithAFreshTraktCalendarMakesZeroNetworkRequests() = runTest {
        accounts.signedIn = true
        storeAFreshSlate(traktFetchedAtMs = start - HOUR)
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(emptyList(), remote.calls)
        assertEquals(0, traktCalendarCalls, "asked an hour ago; at most every 6 h")
    }

    /** Fix 1: Arc showed "all quiet" when every fetch failed. */
    @Test fun whenEveryFetchFailsTheCalendarShowsFailedNotEmpty() = runTest {
        remote.fail = true
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        settle()
        assertTrue(remote.calls.isNotEmpty(), "both titles were due and asked")
        assertEquals(LoadStatus.Failed(timedOut = false), repository.state.value.status)
        assertEquals(CalendarRunOutcome.FAILED, repository.state.value.lastOutcome)
    }

    @Test fun oneAnswerIsEnoughForAnEmptySlateToBeEmpty() = runTest {
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        settle()
        assertEquals(LoadStatus.Empty, repository.state.value.status, "the sources answered: nothing is coming")
    }

    /** Fix 2: Arc's loading state had no deadline. */
    @Test fun aHungFetchEndsTheWaitAtTheStallDeadlineAndTheLateAnswerStillLands() = runTest {
        val hang = CompletableDeferred<Unit>()
        remote.hang = hang
        remote.shows[1] = CalendarShowDates(entries = listOf(CalendarEntry(season = 1, episode = 1, airInstantMs = start + 3 * DAY)))
        remote.lookups["tt1"] = TvMazeLookup.Found(1, CalendarIds(imdb = "tt1"))
        followed = followed.take(1)
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        advanceTimeBy(1_000L)
        val loading = assertIs<LoadStatus.Loading>(repository.state.value.status)
        assertEquals(start + 30_000L, loading.deadlineAtMs, "a 30 s stall deadline")
        assertEquals(LoadStatus.Failed(timedOut = true), BoundedLoad.effectiveAt(loading, loading.deadlineAtMs), "the UI backstop")

        advanceTimeBy(31_000L)
        assertEquals(LoadStatus.Failed(timedOut = true), repository.state.value.status, "the screen stopped waiting")

        hang.complete(Unit)
        settle()
        assertEquals(LoadStatus.Loaded, repository.state.value.status, "the late answer replaced the timeout")
        assertEquals(1, repository.state.value.titles.single().entries.size)
    }

    /** Fix 3: Arc refreshed on every Calendar open. */
    @Test fun reopeningTheCalendarWithinFifteenMinutesDoesNotRefreshAgain() = runTest {
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        settle()
        val firstReads = reads
        val firstCalls = remote.calls.size
        advanceTimeBy(5 * MINUTE)
        repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        settle()
        assertEquals(firstReads, reads, "no re-read of the followed sources")
        assertEquals(firstCalls, remote.calls.size)

        advanceTimeBy(15 * MINUTE)
        repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        settle()
        assertEquals(firstReads + 1, reads, "15 min later an open refreshes again")

        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(firstReads + 2, reads, "Retry always runs")
    }

    /** Fix 4: the viewer's zone override is the zone everything is counted in. */
    @Test fun theZoneOverrideIsTheCalendarsZone() = runTest {
        val repository = repository()
        repository.updateSettings { it.copy(zoneOverride = "Asia/Tokyo") }
        settle()
        assertEquals(TimeZone.of("Asia/Tokyo"), repository.state.value.zone)
        repository.updateSettings { it.copy(zoneOverride = "Not/AZone") }
        assertEquals(TimeZone.UTC, repository.state.value.zone)
    }

    @Test fun aBellAddsTheTitleAndItsDueRemindersAreMarkedSeenForThirtyDays() = runTest {
        followed = emptyList()
        remote.lookups["tt7"] = TvMazeLookup.Found(7, CalendarIds(imdb = "tt7"))
        remote.shows[7] = CalendarShowDates(entries = listOf(CalendarEntry(season = 1, episode = 3, airInstantMs = start - 3 * HOUR)))
        val repository = repository()
        repository.setBell(CalendarTitleType.SERIES, CalendarIds(imdb = "tt7"), on = true)
        settle()
        assertTrue(repository.isBellOn(CalendarTitleType.SERIES, CalendarIds(imdb = "tt7")))
        val due = repository.dueReminders()
        assertEquals(listOf("series:imdb:tt7|S1E3"), due.items.map { it.episodeKey })
        repository.markRemindersSeen(due.items.map { it.episodeKey })
        assertTrue(repository.dueReminders().items.isEmpty())
        val saved = store.loadSnapshot(1).seenReminders
        assertEquals(setOf("series:imdb:tt7|S1E3"), saved.keys)
    }

    @Test fun signedInTheTraktCalendarIsAskedAtMostEverySixHours() = runTest {
        accounts.signedIn = true
        traktCalendarAnswer = null
        followed = emptyList()
        val repository = repository()
        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(1, traktCalendarCalls)
        assertEquals(LoadStatus.Failed(timedOut = false), repository.state.value.status, "the only call failed")
        traktCalendarAnswer = emptyList()
        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(2, traktCalendarCalls, "a failed answer is not kept as fresh")
        repository.requestRefresh(CalendarRefreshReason.RETRY)
        settle()
        assertEquals(2, traktCalendarCalls, "a good answer is fresh for 6 h")
    }
}
