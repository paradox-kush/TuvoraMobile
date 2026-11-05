package com.tuvora.tvos.screens

import com.nuvio.app.features.calendar.api.CalendarFeature
import com.nuvio.app.features.calendar.internal.CalendarIds
import com.nuvio.app.features.calendar.internal.CalendarRefreshReason
import com.nuvio.app.features.calendar.internal.CalendarReminderBatcher
import com.nuvio.app.features.calendar.internal.CalendarRepository
import com.nuvio.app.features.calendar.internal.CalendarState
import com.nuvio.app.features.calendar.internal.CalendarTitleType
import com.nuvio.app.features.calendar.internal.CalendarViewMode
import com.nuvio.app.features.calendar.internal.CalendarFilter
import com.nuvio.app.features.calendar.internal.buildCalendarComingUp
import com.nuvio.app.features.calendar.internal.buildCalendarComingUpGames
import com.nuvio.app.features.calendar.internal.filteredBy
import com.nuvio.app.features.calendar.internal.calendarKeyMatches
import com.nuvio.app.features.calendar.internal.calendarNextReminderAtMs
import com.nuvio.app.features.calendar.internal.calendarReminderCard
import com.nuvio.app.features.calendar.internal.calendarReminderCheckDelayMs
import com.nuvio.app.features.calendar.internal.calendarShiftMonth
import com.nuvio.app.features.radar.fixtureByKey
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Apple TV's Calendar over the shared CalendarRepository (the phone's, desktop's and — in its own codebase — Android
 * TV's): followed titles, their releases, the bell, reminders. What the page shows is [TvCalendarPresentation]; this
 * object only reaches what Swift can't (suspend settings writes, the internal repository). No timers of its own: the
 * SwiftUI views call [opened] / [homeSettled] on appearance and run the reminder check only while visible.
 */
object TvCalendar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val batcher = CalendarReminderBatcher()
    private val repository: CalendarRepository get() = CalendarFeature.repository

    val state: StateFlow<CalendarState> get() = repository.state

    @OptIn(ExperimentalTime::class)
    fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun start() = repository.start()

    /** The page appeared: refreshes only if due (open throttle). */
    fun opened() = repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)

    fun homeSettled() = repository.requestRefresh(CalendarRefreshReason.HOME_SETTLED)

    fun retry() = repository.requestRefresh(CalendarRefreshReason.RETRY)

    fun screen(state: CalendarState, nowMs: Long, anchorIso: String?): TvCalendarScreen =
        TvCalendarPresentation.screen(state, nowMs, anchorIso)

    fun shiftMonth(anchorIso: String?, months: Int, nowMs: Long): String {
        val zone = state.value.zone
        val anchor = anchorIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: com.nuvio.app.features.calendar.internal.calendarDateAt(nowMs, zone)
        return calendarShiftMonth(anchor, months).toString()
    }

    fun setMonthView(on: Boolean) = settings { it.copy(viewMode = if (on) CalendarViewMode.MONTH else CalendarViewMode.LIST) }
    fun setComingUpRow(on: Boolean) = settings { it.copy(comingUpRow = on) }
    fun setCornerNote(on: Boolean) = settings { it.copy(cornerMessage = on) }
    fun setWindowDays(days: Int) = settings { it.copy(windowDays = days) }

    /** [filter] is a [CalendarFilter] name ("SHOWS", "MOVIES", "SPORTS"); the last chip left on stays on. */
    fun setFilter(filter: String, on: Boolean) {
        val chip = CalendarFilter.entries.firstOrNull { it.name == filter } ?: return
        settings { current ->
            val next = if (on) current.filters + chip else current.filters - chip
            if (next.isEmpty()) current else current.copy(filters = next)
        }
    }

    /** Re-reads the games from the sports state the app already holds (a local read, no request): call while visible. */
    fun reloadGames() {
        scope.launch { repository.reloadGames() }
    }

    /** The sports fixture a game id names, for the existing channel-matching sheet ("Watch now"); null if no longer held. */
    fun fixtureFor(gameId: String): com.nuvio.app.features.radar.RadarFixture? =
        com.nuvio.app.features.radar.RadarRepository.uiState.value.fixtureByKey(gameId)

    fun hide(titleKey: String) {
        val title = state.value.titles.firstOrNull { it.key == titleKey } ?: return
        scope.launch { repository.setHidden(title, hidden = true) }
    }

    fun isBellOn(state: CalendarState, contentId: String, type: String): Boolean {
        val titleType = typeOf(type) ?: return false
        val ids = CalendarIds.fromContentId(contentId)
        return !ids.isEmpty && state.settings.bellOn.any { calendarKeyMatches(it, titleType, ids) }
    }

    /** Whether a bell is offered at all for this title (a series or film with an id the Calendar can read). */
    fun canBell(contentId: String, type: String): Boolean = typeOf(type) != null && !CalendarIds.fromContentId(contentId).isEmpty

    fun toggleBell(contentId: String, type: String) {
        val titleType = typeOf(type) ?: return
        val ids = CalendarIds.fromContentId(contentId)
        if (ids.isEmpty) return
        val on = isBellOn(state.value, contentId, type)
        scope.launch { repository.setBell(titleType, ids, !on) }
    }

    fun comingUp(state: CalendarState, nowMs: Long): List<TvCalendarComingUp> =
        if (!state.settings.comingUpRow) emptyList()
        else TvCalendarPresentation.comingUpGames(
            buildCalendarComingUpGames(state.games, state.settings.filters, nowMs, state.zone), state.zone, nowMs,
        ) + TvCalendarPresentation.comingUp(
            buildCalendarComingUp(state.titles.filteredBy(state.settings.filters), state.watched, nowMs, state.zone, state.settings.comingUpHiddenKeys),
        )

    fun reminders(): TvCalendarReminders? = TvCalendarPresentation.reminders(calendarReminderCard(repository.dueReminders()))

    fun dismissReminders(episodeKeys: List<String>) {
        scope.launch { repository.markRemindersSeen(episodeKeys) }
    }

    /** Names of the shows that became due since the last check (each announced once per process); empty when none. */
    fun nextReminderNote(): List<String> {
        if (!state.value.settings.cornerMessage) return emptyList()
        return batcher.nextBatch(repository.dueReminders()).mapNotNull { it.title.name }.distinct()
    }

    /** How long the foreground reminder check sleeps before looking again (1 min – 6 h). */
    fun reminderCheckDelayMs(nowMs: Long): Long {
        val current = state.value
        return calendarReminderCheckDelayMs(
            calendarNextReminderAtMs(current.titles, current.settings.bellOn, current.seenReminders, nowMs),
            nowMs,
        )
    }

    private fun settings(transform: (com.nuvio.app.features.calendar.internal.CalendarSettings) -> com.nuvio.app.features.calendar.internal.CalendarSettings) {
        scope.launch { repository.updateSettings(transform) }
    }

    private fun typeOf(type: String): CalendarTitleType? = when (type.lowercase()) {
        "series", "tv", "show", "anime" -> CalendarTitleType.SERIES
        "movie" -> CalendarTitleType.MOVIE
        else -> null
    }
}
