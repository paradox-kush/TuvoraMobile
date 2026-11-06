package com.nuvio.app.core.contracts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.home.MetaPreview

/*
 * Firewall ports for the Calendar (features/calendar, fork-only). Slot-inversion, as HomeSportsSection: the shared
 * Home, details and navigation code host the slots and supply neutral callbacks; the Calendar feature renders them and
 * owns their state. No-op default: with nothing registered nothing renders and nothing is fetched.
 */

/** Home: the bell reminders card, the opt-in "Coming up" row, and the foreground reminder check. */
interface HomeCalendarSection {
    @Composable
    fun Render(onOpenTitle: (MetaPreview) -> Unit, onOpenCalendar: () -> Unit)
}

/**
 * Show / film details: the "Remind me of new episodes" switch. The details screen renders it as a row inside the
 * add-to-library list picker (the + control's menu); the Calendar owns the state and what toggling does.
 */
interface DetailsCalendarBell {
    /**
     * [type] is the app's content type ("series", "movie", …). Null when the Calendar cannot follow the title
     * (unsupported type or no usable id), in which case the host shows no row. Off → on adds the title to the
     * Calendar and turns its new-release reminders on.
     */
    @Composable
    fun reminder(contentId: String, type: String, name: String?, poster: String?): DetailsReminderControl?
}

/** A neutral value the host renders: [on] is the current state, [toggle] flips it. */
class DetailsReminderControl(val on: Boolean, val toggle: () -> Unit)

/** The Calendar page itself, hosted by the navigation as `CalendarRoute`. */
interface CalendarScreenContent {
    @Composable
    fun Render(
        onBack: () -> Unit,
        onOpenTitle: (MetaPreview) -> Unit,
        /** A sports game's "Watch now": the host opens the Sports channel-matching flow for that game id. */
        onWatchGame: (gameId: String) -> Unit,
        modifier: Modifier,
    )
}

object HomeCalendarSectionAccess {
    private var section: HomeCalendarSection? = null
    fun register(s: HomeCalendarSection) { section = s }
    fun current(): HomeCalendarSection? = section
    fun resetForTest() { section = null }
}

object DetailsCalendarBellAccess {
    private var bell: DetailsCalendarBell? = null
    fun register(b: DetailsCalendarBell) { bell = b }
    fun current(): DetailsCalendarBell? = bell
    fun resetForTest() { bell = null }
}

object CalendarScreenContentAccess {
    private var content: CalendarScreenContent? = null
    fun register(c: CalendarScreenContent) { content = c }
    fun current(): CalendarScreenContent? = content
    fun resetForTest() { content = null }
}
