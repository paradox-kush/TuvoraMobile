package com.nuvio.app.features.calendar.api

import com.nuvio.app.features.calendar.internal.CalendarRateLimiter
import com.nuvio.app.features.calendar.internal.CalendarRepository
import com.nuvio.app.features.calendar.internal.DefaultCalendarRemoteSources
import com.nuvio.app.features.calendar.internal.PlatformCalendarHttp
import com.nuvio.app.features.calendar.internal.PlatformCalendarStore
import com.nuvio.app.features.calendar.internal.TmdbCalendarClient
import com.nuvio.app.features.calendar.internal.TraktCalendarClient
import com.nuvio.app.features.calendar.internal.TvMazeCalendarClient
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.TimeZone

/**
 * Api-level provider (rules doc Rule 1 point 1): the composition root registers the ports once per process
 * ([register], from FeatureContributions.kt) and obtains the one repository through [repository]. Built lazily,
 * so nothing touches storage or the network before the first Calendar surface asks for it — and no background
 * job exists: work runs only when a surface (Calendar page, Home, the reminder check) asks.
 */
object CalendarFeature {
    private var sources: CalendarFollowedSources? = null
    private var accounts: CalendarAccounts? = null
    private var sports: CalendarSportsSource? = null

    fun register(sources: CalendarFollowedSources, accounts: CalendarAccounts, sports: CalendarSportsSource? = null) {
        this.sources = sources
        this.accounts = accounts
        this.sports = sports
    }

    val isRegistered: Boolean get() = sources != null && accounts != null

    @OptIn(ExperimentalTime::class)
    val repository: CalendarRepository by lazy {
        val sources = checkNotNull(sources) { "CalendarFeature.register() was not called at app init" }
        val accounts = checkNotNull(accounts) { "CalendarFeature.register() was not called at app init" }
        val clock = { Clock.System.now().toEpochMilliseconds() }
        val limiter = CalendarRateLimiter(nowMs = clock)
        val trakt = TraktCalendarClient(PlatformCalendarHttp, accounts::traktAuthorizedHeaders, accounts::traktPublicHeaders)
        CalendarRepository(
            store = PlatformCalendarStore,
            sources = sources,
            accounts = accounts,
            remote = DefaultCalendarRemoteSources(
                tvMaze = TvMazeCalendarClient(PlatformCalendarHttp, limiter),
                trakt = trakt,
                tmdb = TmdbCalendarClient(PlatformCalendarHttp, accounts::tmdbApiKey),
            ),
            traktCalendar = trakt::myShows,
            sports = sports,
            clock = clock,
            deviceZone = { TimeZone.currentSystemDefault() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}

