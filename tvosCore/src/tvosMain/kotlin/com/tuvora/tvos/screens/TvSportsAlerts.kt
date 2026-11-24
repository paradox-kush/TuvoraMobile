package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.core.contracts.PlaybackContextAccess
import com.nuvio.app.features.common.lifecycle.Disposable
import com.nuvio.app.features.livetv.LiveNowWatching
import com.nuvio.app.features.radar.RadarGameWatch
import com.nuvio.app.features.radar.RadarRepository
import com.nuvio.app.features.radar.alerts.AlertChip
import com.nuvio.app.features.radar.alerts.BannerAction
import com.nuvio.app.features.radar.alerts.SportsAlertLabels
import com.nuvio.app.features.radar.alerts.SportsAlertSettings
import com.nuvio.app.features.radar.alerts.SportsAlerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Apple TV's door to the shared sports alerts: the banner to draw, its one action, and the settings, for Swift. The
 * decision is the phone's ([com.nuvio.app.features.radar.alerts.SportsAlertPolicy]); the session is started and stopped
 * by TvAppLifecycle on Foreground / Background. The SwiftUI banner (MainShell + PlayerHost overlay, outside the focus
 * system, long-press Select enabled only while a banner is up) and settings page are the tvosApp lane's.
 */
object TvSportsAlerts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** TvAppGraph.start(): the Watch lookup the game screen uses, and the TV dismiss time. Once per process. */
    internal fun install() {
        SportsAlerts.installWatch(RadarGameWatch(LiveNowWatching.channel), dismissAfter = TvSportsAlertsPolicy.DISMISS_MS)
    }

    /** The banner to draw, or null. */
    val banner: StateFlow<TvAlertBanner?> by lazy {
        SportsAlerts.banner.map { it?.let(TvSportsAlertsPolicy::banner) }
            .stateIn(scope, SharingStarted.Eagerly, SportsAlerts.banner.value?.let(TvSportsAlertsPolicy::banner))
    }

    /** Long press on Select while a banner is up. Swift does what the result says. */
    fun act(): TvAlertAction? = when (val action = SportsAlerts.act()) {
        is BannerAction.OpenGame -> TvAlertAction(kind = TvAlertAction.OPEN_GAME, eventId = action.eventId, channelId = null)
        is BannerAction.Watch -> TvAlertAction(kind = TvAlertAction.WATCH, eventId = action.eventId, channelId = action.channelId)
        BannerAction.OpenSports -> TvAlertAction(kind = TvAlertAction.OPEN_SPORTS, eventId = null, channelId = null)
        null -> null
    }

    fun dismiss() = SportsAlerts.dismiss()

    // --- what is on screen (gate 3 and "during playback") -------------------------------------------------------

    /** The player is showing: dispose the handle when it leaves. [live] = Live TV / a live channel. */
    fun enterPlayback(live: Boolean): Disposable =
        PlaybackContextAccess.current().enter(if (live) PlaybackContext.LiveTv else PlaybackContext.Vod)

    /** The live channel now playing (so an alert for the game it carries is not shown over it); clear with [stoppedChannel]. */
    fun playingChannel(contentId: String, name: String) = LiveNowWatching.set(contentId, name)

    fun stoppedChannel(contentId: String) = LiveNowWatching.clear(contentId)

    /** The game screen is showing [eventId] ([TvSportsHubPolicy.fixtureKey]); call [closedGame] when it leaves. */
    fun openedGame(eventId: String) = SportsAlerts.openedGame(eventId)

    fun closedGame(eventId: String) = SportsAlerts.closedGame(eventId)

    // --- settings -------------------------------------------------------------------------------------------------

    val signedIn: StateFlow<Boolean> get() = SportsAlerts.signedIn

    fun settings(): SportsAlertSettings = SportsAlerts.settings()

    fun rows(): List<TvAlertSettingRow> = TvSportsAlertsPolicy.rows(SportsAlerts.settings(), SportsAlerts.signedIn.value)

    /** A row was pressed ([TvAlertSettingRow.id]): a switch toggles, a value row moves to its next value. */
    fun press(rowId: String) {
        val s = SportsAlerts.settings()
        when (rowId) {
            TvSportsAlertsPolicy.ROW_ENABLED -> SportsAlerts.setEnabled(!s.enabled)
            TvSportsAlertsPolicy.ROW_TEAMS -> SportsAlerts.cycleTeamLevel()
            TvSportsAlertsPolicy.ROW_LEAGUES -> SportsAlerts.cycleLeagueLevel()
            TvSportsAlertsPolicy.ROW_PLAYBACK -> SportsAlerts.cycleDuringPlayback()
            TvSportsAlertsPolicy.ROW_PER_HOUR -> SportsAlerts.cyclePerHour()
            TvSportsAlertsPolicy.ROW_BATCH -> SportsAlerts.cycleBatch()
            TvSportsAlertsPolicy.ROW_QUIET -> SportsAlerts.setQuietOn(!s.quietOn)
            TvSportsAlertsPolicy.ROW_QUIET_FROM -> SportsAlerts.setQuietRange(SportsAlertLabels.stepClock(s.quietStartMin, 1), s.quietEndMin)
            TvSportsAlertsPolicy.ROW_QUIET_UNTIL -> SportsAlerts.setQuietRange(s.quietStartMin, SportsAlertLabels.stepClock(s.quietEndMin, 1))
        }
    }

    /** Left / right on a time row: half-hour steps either way. */
    fun stepQuiet(rowId: String, steps: Int) {
        val s = SportsAlerts.settings()
        when (rowId) {
            TvSportsAlertsPolicy.ROW_QUIET_FROM -> SportsAlerts.setQuietRange(SportsAlertLabels.stepClock(s.quietStartMin, steps), s.quietEndMin)
            TvSportsAlertsPolicy.ROW_QUIET_UNTIL -> SportsAlerts.setQuietRange(s.quietStartMin, SportsAlertLabels.stepClock(s.quietEndMin, steps))
        }
    }

    /** The hub header's bell chip (`Alerts on` / `Alerts off`; `Sign in to get alerts`, disabled, when signed out). */
    fun hubChip(): AlertChip = SportsAlertLabels.hubChip(SportsAlerts.settings(), SportsAlerts.signedIn.value)

    fun toggleEnabled() = SportsAlerts.toggleEnabled()

    /** The game screen's `Alerts: Default / All / Off` chip, or null for a game that is not followed. */
    fun gameChip(eventId: String, leagueId: String?, homeId: String?, awayId: String?): AlertChip? =
        SportsAlerts.gameChip(RadarRepository.uiState.value, eventId, leagueId, homeId, awayId, SportsAlerts.signedIn.value)

    fun cycleGame(eventId: String, kickoffMs: Long) = SportsAlerts.cycleGame(eventId, kickoffMs)

    fun setTeamMuted(teamId: String, muted: Boolean) = SportsAlerts.setTeamMuted(teamId, muted)

    fun setLeagueMuted(leagueId: String, muted: Boolean) = SportsAlerts.setLeagueMuted(leagueId, muted)
}

/** What Swift must do after a banner action: [kind] is one of the constants. */
data class TvAlertAction(val kind: String, val eventId: String?, val channelId: String?) {
    companion object {
        const val OPEN_GAME = "open_game"
        const val WATCH = "watch"
        const val OPEN_SPORTS = "open_sports"
    }
}
