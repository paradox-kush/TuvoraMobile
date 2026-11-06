// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): core/calendar/CalendarRefresher.kt.
// Tuvora changes: the refresh runs under BoundedLoad (deadline, fix 2), ends Failed when every fetch failed (fix 1),
// is throttled on open (fix 3), and counts days in the resolved zone (fix 4). No source-retry timers: a failed run is
// re-asked by the viewer (Retry) or an event (open after the throttle, a bell or settings change), never a loop.
package com.nuvio.app.features.calendar.internal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.calendar.api.CalendarAccounts
import com.nuvio.app.features.calendar.api.CalendarFollowItem
import com.nuvio.app.features.calendar.api.CalendarFollowOrigin
import com.nuvio.app.features.calendar.api.CalendarFollowType
import com.nuvio.app.features.calendar.api.CalendarFollowedRead
import com.nuvio.app.features.calendar.api.CalendarFollowedSources
import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.features.calendar.api.CalendarSportsSource
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadOutcome
import com.nuvio.app.features.iptv.LoadStatus
import com.nuvio.app.features.iptv.LoadSurface
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

data class CalendarState(
    val profileId: Int? = null,
    /** Followed titles with their releases, hidden titles left out. */
    val titles: List<CalendarTitleRecord> = emptyList(),
    val settings: CalendarSettings = CalendarSettings(),
    /** The profile's snapshot and settings are read. */
    val loaded: Boolean = false,
    /** The refresh, as the screen should show it: Idle, Loading (with its deadline), Loaded, Empty or Failed. */
    val status: LoadStatus = LoadStatus.Idle,
    val remaining: Int = 0,
    val lastOutcome: CalendarRunOutcome? = null,
    val seenReminders: Set<String> = emptySet(),
    val watched: CalendarWatchedIndex? = null,
    val zone: TimeZone = TimeZone.UTC,
    /** Games of the followed leagues and teams, read locally from the sports source (never fetched here). */
    val games: List<CalendarGameItem> = emptyList(),
)

/** A run in which every network fetch failed (fix 1): reported to BoundedLoad as a failure, never as "empty". */
class CalendarAllFetchesFailed : RuntimeException("every calendar fetch failed")

/**
 * Keeps the Calendar of the active profile. A refresh reads the followed titles from what the app already has (no
 * network), asks the Trakt calendar when signed in and due, then refreshes only the titles that are due, [concurrency]
 * at a time through the rate-limited clients. Every finished title is kept, so a refresh cut off by its deadline still
 * leaves what it fetched. A request while a refresh runs is kept and runs once after it.
 */
class CalendarRepository(
    private val store: CalendarStore,
    private val sources: CalendarFollowedSources,
    private val accounts: CalendarAccounts,
    private val remote: CalendarRemoteSources,
    private val traktCalendar: suspend (todayUtc: LocalDate) -> List<TraktCalendarItem>?,
    private val clock: () -> Long,
    private val deviceZone: () -> TimeZone,
    private val scope: CoroutineScope,
    private val concurrency: Int = 2,
    /** Null when the app has no sports feature: the Calendar then holds shows and films only. */
    private val sports: CalendarSportsSource? = null,
) {
    private val log = Logger.withTag("Calendar")
    private val dataMutex = Mutex()
    private val jobLock = SynchronizedObject()
    private var started = false
    private var job: Job? = null
    private var workInFlight = false
    private var runAgain: CalendarRefreshReason? = null
    private var lastFinishedAtMs: Long? = null

    private var owner: Int? = null
    private var data = CalendarSnapshot()
    private var settings = CalendarSettings()
    private var watched: CalendarWatchedIndex? = null
    private val progress = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private var games: List<CalendarGameItem> = emptyList()

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    /** Reads the cached Calendar without asking for anything new. */
    fun start() {
        val first = synchronized(jobLock) { if (started) false else true.also { started = true } }
        if (!first) return
        scope.launch {
            var previous: Int? = null
            accounts.activeProfileId.collect { profileId ->
                // Only a real switch cancels: the first emission must not cancel a refresh asked for at start.
                if (previous != null) cancelRefresh()
                previous = profileId
                ensureLoaded(profileId)
            }
        }
        sports?.let { source ->
            // Local reads only: the sports feature refreshes its fixtures under its own policy; the Calendar never asks.
            scope.launch {
                reloadGames()
                source.changes.collect { reloadGames() }
            }
        }
    }

    /** Re-reads the games from the sports source (no network). Cheap; callers may tick it while visible. */
    suspend fun reloadGames() {
        val source = sports ?: return
        val read = runCatching { source.games(clock()) }.getOrElse {
            if (it is CancellationException) throw it
            log.w { "games unreadable: ${it::class.simpleName}" }
            return
        }
        dataMutex.withLock { games = read }
        publish()
    }

    fun requestRefresh(reason: CalendarRefreshReason) {
        start()
        synchronized(jobLock) {
            if (job?.isActive == true || workInFlight) {
                if (runAgain == null || reason.forced) runAgain = reason
                return
            }
            if (!calendarRefreshDue(reason, lastFinishedAtMs, _state.value.lastOutcome, clock())) return
            job = scope.launch {
                var next: CalendarRefreshReason? = reason
                while (next != null) {
                    runBounded(next)
                    next = synchronized(jobLock) {
                        val again = runAgain
                        runAgain = null
                        again?.takeIf { calendarRefreshDue(it, lastFinishedAtMs, _state.value.lastOutcome, clock()) }
                    }
                }
            }
        }
    }

    fun cancelRefresh() {
        synchronized(jobLock) {
            runAgain = null
            job?.cancel()
        }
    }

    private suspend fun runBounded(reason: CalendarRefreshReason) {
        publish(status = BoundedLoad.begin(LoadSurface.CALENDAR), remaining = 0)
        val outcome = BoundedLoad.run(
            surface = LoadSurface.CALENDAR,
            progress = progress,
            onProgress = { loading -> scope.launch { publish(status = loading) } },
            isEmpty = { it == CalendarRunOutcome.EMPTY },
            // The screen stops waiting at the deadline; the titles still being fetched keep landing in the background.
            cancelOnTimeout = false,
        ) { runRefresh(reason) }
        val status = when (outcome) {
            is LoadOutcome.Loaded -> LoadStatus.Loaded
            is LoadOutcome.Empty -> LoadStatus.Empty
            is LoadOutcome.Failed -> LoadStatus.Failed(outcome.timedOut)
        }
        if (outcome is LoadOutcome.Failed && outcome.timedOut) log.w { "refresh passed its deadline; still fetching in the background" }
        publish(status = status, remaining = if (status is LoadStatus.Failed && status.timedOut) _state.value.remaining else 0)
    }

    /** One refresh. Throws [CalendarAllFetchesFailed] when it asked the network and nothing answered. */
    private suspend fun runRefresh(reason: CalendarRefreshReason): CalendarRunOutcome {
        synchronized(jobLock) { workInFlight = true }
        val profileId = accounts.activeProfileId.value
        try {
            ensureLoaded(profileId)
            val settings = dataMutex.withLock { settings }
            val zone = resolveCalendarZone(settings.zoneOverride, deviceZone())
            val nowMs = clock()
            val read = runCatching { sources.read() }.getOrElse {
                if (it is CancellationException) throw it
                log.w { "followed sources unreadable: ${it::class.simpleName}" }
                CalendarFollowedRead(unreadOrigins = CalendarFollowOrigin.entries.toSet())
            }
            val index = watchedIndexOf(read)
            val signedIn = accounts.traktSignedIn()
            val snapshot = dataMutex.withLock { data }
            val traktAsked = signedIn && nowMs - snapshot.traktCalendarFetchedAtMs >= CALENDAR_TRAKT_REFRESH_MS
            val traktItems = if (traktAsked) {
                runCatching { traktCalendar(calendarDateAt(nowMs, TimeZone.UTC)) }.getOrElse {
                    if (it is CancellationException) throw it
                    null
                }
            } else {
                null
            }
            val traktCandidates = when {
                !signedIn -> emptyList()
                traktItems != null -> traktCalendarCandidates(traktItems)
                else -> snapshot.titles.filter { CalendarOrigin.TRAKT_CALENDAR in it.origins }.map {
                    CalendarTitleCandidate(it.ids, it.type, CalendarOrigin.TRAKT_CALENDAR, name = it.name)
                }
            }
            val rules = CalendarFollowRules(
                hiddenTitleKeys = settings.hiddenTitleKeys,
                bellOn = settings.bellOn,
                removedFromContinueWatching = read.removedFromContinueWatching,
            )
            val unread = read.unreadOrigins.mapTo(mutableSetOf()) { it.toOrigin() }
            val candidates = read.items.mapNotNull { it.toCandidate() } + traktCandidates +
                carriedCalendarCandidates(snapshot.titles, unread)
            val followed = followCalendarTitles(candidates, rules, nowMs)

            val due = withOwner(profileId) {
                var records = reconcileCalendarRecords(followed.titles, data.titles)
                records = when {
                    !signedIn -> records.map { it.dueWithoutTraktCalendar() }
                    traktItems != null -> applyTraktCalendar(records, traktItems, nowMs, zone)
                    else -> records
                }
                data = data.copy(
                    titles = records,
                    traktCalendarFetchedAtMs = when {
                        traktItems != null -> nowMs
                        signedIn -> data.traktCalendarFetchedAtMs
                        else -> 0L
                    },
                    seenReminders = data.seenReminders.prunedSeen(nowMs),
                    updatedAtMs = nowMs,
                )
                watched = index
                records.filter { calendarDueForRun(it, nowMs, reason) }
                    .sortedWith(compareByDescending<CalendarTitleRecord> { CalendarOrigin.BELL in it.origins }.thenBy { it.nextRefreshAtMs })
            } ?: return CalendarRunOutcome.EMPTY
            publish(remaining = due.size)
            save(profileId)
            log.i { "refresh titles=${followed.titles.size} due=${due.size} trakt=${traktItems?.size ?: "-"} left-out=${followed.leftOut.size}" }

            var failed = 0
            if (due.isNotEmpty()) {
                val fetcher = CalendarTitleFetcher(remote, accounts.region(), zone)
                val queue = ArrayDeque(due.map { it.key })
                val queueLock = SynchronizedObject()
                supervisorScope {
                    repeat(concurrency.coerceAtLeast(1)) {
                        launch {
                            while (true) {
                                val key = synchronized(queueLock) { queue.removeFirstOrNull() } ?: break
                                if (refreshOne(profileId, key, fetcher)) synchronized(queueLock) { failed++ }
                                progress.tryEmit(Unit)
                            }
                        }
                    }
                }
                save(profileId)
            }
            val (hasEntries, carriedFailure) = dataMutex.withLock {
                data.titles.visible(settings).any { it.entries.isNotEmpty() } to
                    calendarCarriesFailure(_state.value.lastOutcome, data.titles)
            }
            val outcome = calendarRunOutcome(
                titlesAsked = due.size,
                titlesFailed = failed,
                traktAsked = traktAsked,
                traktFailed = traktAsked && traktItems == null,
                hasEntries = hasEntries,
                carriedFailure = carriedFailure,
            )
            finish(profileId, outcome)
            if (outcome == CalendarRunOutcome.FAILED) throw CalendarAllFetchesFailed()
            return outcome
        } finally {
            // A run cut off by its deadline outlives its job: a request kept meanwhile runs now, not never.
            val again = synchronized(jobLock) {
                workInFlight = false
                if (job?.isActive == true) null else runAgain.also { runAgain = null }
            }
            again?.let(::requestRefresh)
        }
    }

    /** True when the title's fetch failed. */
    private suspend fun refreshOne(profileId: Int, key: String, fetcher: CalendarTitleFetcher): Boolean {
        val (record, tvMazeIds, traktFetchedAt) = withOwner(profileId) {
            Triple(data.titles.firstOrNull { it.key == key }, data.tvmazeIds, data.traktCalendarFetchedAtMs)
        } ?: return false
        if (record == null) return false
        val nowMs = clock()
        val signedIn = accounts.traktSignedIn()
        val outcome = try {
            fetcher.refresh(record, tvMazeIds, signedIn && record.isCoveredByTrakt(traktFetchedAt, nowMs), nowMs)
                .withoutTraktCalendarIfSignedOut(signedIn)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CalendarFetchOutcome(fetcher.failed(record, nowMs))
        }
        withOwner(profileId) {
            data = data.copy(
                titles = data.titles.map { if (it.key == key) outcome.record else it },
                tvmazeIds = data.tvmazeIds + outcome.tvMazeIds,
            )
        }
        publish(remaining = (_state.value.remaining - 1).coerceAtLeast(0))
        return outcome.failed
    }

    /** Records how the run ended; when the screen gave up waiting, the landed result replaces its "timed out". */
    private suspend fun finish(profileId: Int, outcome: CalendarRunOutcome) {
        synchronized(jobLock) { lastFinishedAtMs = clock() }
        // Kept with the snapshot: after a restart a title still in failure back-off must show Failed, not all quiet.
        withOwner(profileId) { data = data.copy(lastOutcome = outcome) } ?: return
        val current = _state.value.status
        val landed = if (current is LoadStatus.Failed && current.timedOut) {
            when (outcome) {
                CalendarRunOutcome.LOADED -> LoadStatus.Loaded
                CalendarRunOutcome.EMPTY -> LoadStatus.Empty
                CalendarRunOutcome.FAILED -> LoadStatus.Failed(timedOut = false)
            }
        } else {
            current
        }
        publish(status = landed, lastOutcome = outcome)
        save(profileId)
    }

    // ---- viewer actions ----

    suspend fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) {
        start()
        val profileId = accounts.activeProfileId.value
        ensureLoaded(profileId)
        withOwner(profileId) {
            settings = transform(settings).normalized()
            store.saveSettings(profileId, settings)
        } ?: return
        publish()
    }

    /** Turns a title's bell on or off; on adds it to the Calendar at once even if nothing else follows it. */
    suspend fun setBell(type: CalendarTitleType, ids: CalendarIds, on: Boolean) {
        val key = calendarTitleKey(type, ids) ?: return
        updateSettings { current ->
            val without = current.bellOn.filterNot { calendarKeyMatches(it, type, ids) }.toSet()
            current.copy(bellOn = if (on) without + key else without)
        }
        requestRefresh(CalendarRefreshReason.BELL_CHANGED)
    }

    fun isBellOn(type: CalendarTitleType, ids: CalendarIds): Boolean =
        _state.value.settings.bellOn.any { calendarKeyMatches(it, type, ids) }

    suspend fun setHidden(title: CalendarTitleRecord, hidden: Boolean) {
        updateSettings { current ->
            val without = current.hiddenTitleKeys.filterNot { calendarKeyMatches(it, title.type, title.ids) }.toSet()
            val names = current.hiddenTitleNames.filterKeys { it in without }
            if (hidden) {
                current.copy(hiddenTitleKeys = without + title.key, hiddenTitleNames = names + (title.key to (title.name ?: title.key)))
            } else {
                current.copy(hiddenTitleKeys = without, hiddenTitleNames = names)
            }
        }
        requestRefresh(CalendarRefreshReason.SETTINGS_CHANGED)
    }

    fun dueReminders(): CalendarDueReminders {
        val current = _state.value
        val index = current.watched ?: CalendarWatchedIndex()
        return dueReminders(current.titles, current.settings.bellOn, current.seenReminders, index::isWatched, clock())
    }

    suspend fun markRemindersSeen(episodeKeys: Collection<String>) {
        if (episodeKeys.isEmpty()) return
        val profileId = accounts.activeProfileId.value
        withOwner(profileId) { data = data.copy(seenReminders = data.seenReminders.withSeen(episodeKeys, clock())) } ?: return
        publish()
        save(profileId)
    }

    // ---- state ----

    private suspend fun ensureLoaded(profileId: Int) {
        dataMutex.withLock {
            if (owner == profileId) return
            data = store.loadSnapshot(profileId)
            settings = store.loadSettings(profileId)
            owner = profileId
            watched = null
        }
        synchronized(jobLock) { lastFinishedAtMs = null }
        // The profile's stored last outcome comes back with it (null for a profile that never ran).
        publish(status = LoadStatus.Idle, remaining = 0, resetOutcome = true)
    }

    /** Runs [block] under the lock only while [profileId] still owns the data; null otherwise. */
    private suspend inline fun <T> withOwner(profileId: Int, block: () -> T): T? = dataMutex.withLock {
        if (owner != profileId) null else block()
    }

    private suspend fun save(profileId: Int) {
        val snapshot = dataMutex.withLock { if (owner == profileId) data else null } ?: return
        runCatching { store.saveSnapshot(profileId, snapshot) }.onFailure { log.w { "snapshot not saved: ${it::class.simpleName}" } }
    }

    private suspend fun publish(
        status: LoadStatus? = null,
        remaining: Int? = null,
        lastOutcome: CalendarRunOutcome? = null,
        resetOutcome: Boolean = false,
    ) {
        dataMutex.withLock {
            val previous = _state.value
            _state.value = CalendarState(
                profileId = owner,
                titles = data.titles.visible(settings),
                settings = settings,
                loaded = owner != null,
                status = status ?: previous.status,
                remaining = remaining ?: previous.remaining,
                lastOutcome = if (resetOutcome) data.lastOutcome else lastOutcome ?: previous.lastOutcome,
                seenReminders = data.seenReminders.keys,
                watched = watched,
                zone = resolveCalendarZone(settings.zoneOverride, deviceZone()),
                games = games,
            )
        }
    }
}

private val CalendarRefreshReason.forced: Boolean
    get() = this == CalendarRefreshReason.RETRY || this == CalendarRefreshReason.SETTINGS_CHANGED ||
        this == CalendarRefreshReason.BELL_CHANGED

internal fun List<CalendarTitleRecord>.visible(settings: CalendarSettings): List<CalendarTitleRecord> {
    val hidden = settings.hiddenTitleKeys
    return if (hidden.isEmpty()) this else filterNot { title -> hidden.any(title::matchesKey) }
}

internal fun CalendarFollowOrigin.toOrigin(): CalendarOrigin = when (this) {
    CalendarFollowOrigin.LIBRARY -> CalendarOrigin.LIBRARY
    CalendarFollowOrigin.PROGRESS -> CalendarOrigin.PROGRESS
    CalendarFollowOrigin.WATCHED -> CalendarOrigin.WATCHED
    CalendarFollowOrigin.DROPPED -> CalendarOrigin.DROPPED
}

internal fun CalendarFollowItem.toCandidate(): CalendarTitleCandidate? {
    val ids = CalendarIds.fromContentId(contentId).merged(
        CalendarIds(imdb = imdbId?.takeIf { it.startsWith("tt") }, tmdb = tmdbId, trakt = traktId),
    )
    if (ids.isEmpty) return null
    val type = if (type == CalendarFollowType.MOVIE) CalendarTitleType.MOVIE else CalendarTitleType.SERIES
    return CalendarTitleCandidate(ids, type, origin.toOrigin(), lastActivityMs, name, contentId, poster)
}

internal fun watchedIndexOf(read: CalendarFollowedRead): CalendarWatchedIndex = CalendarWatchedIndex(
    buildSet {
        read.watchedEpisodes.forEach { addAll(CalendarWatchedIndex.episodeKey(it.contentId, it.season, it.episode)) }
        read.watchedMovies.forEach { addAll(CalendarWatchedIndex.movieKey(it)) }
    },
)

/**
 * Signed out of Trakt: the times its calendar gave stay on show until TVmaze or Trakt's public next-episode answer
 * replaces them, so the title is asked again now instead of losing its times for hours.
 */
internal fun CalendarTitleRecord.dueWithoutTraktCalendar(): CalendarTitleRecord =
    if (sources[CalendarSource.TRAKT].isNullOrEmpty() || nextRefreshAtMs == 0L) this else copy(nextRefreshAtMs = 0L)

/** After a good fetch while signed out, the Trakt calendar's own times give way to what was just fetched. */
internal fun CalendarFetchOutcome.withoutTraktCalendarIfSignedOut(signedIn: Boolean): CalendarFetchOutcome =
    if (signedIn || record.failures > 0) this else copy(record = record.withoutSources(CalendarSource.TRAKT))
