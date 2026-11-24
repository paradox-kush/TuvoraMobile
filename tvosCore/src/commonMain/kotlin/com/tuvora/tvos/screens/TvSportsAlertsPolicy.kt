package com.tuvora.tvos.screens

import com.nuvio.app.features.radar.alerts.AlertBanner
import com.nuvio.app.features.radar.alerts.BannerAction
import com.nuvio.app.features.radar.alerts.SportsAlertController
import com.nuvio.app.features.radar.alerts.SportsAlertLabels
import com.nuvio.app.features.radar.alerts.SportsAlertSettings

/** One banner as the Apple TV overlay draws it: top-right, outside the focus system, never takes focus. */
data class TvAlertBanner(
    val id: String,
    val title: String,
    /** League · progress (single game); null on a summary. */
    val subtitle: String?,
    /** Up to 3 lines on a summary ("N sports updates"). */
    val lines: List<String>,
    /** `Hold Select to watch` / `Hold Select to open`. */
    val hint: String,
    val isSummary: Boolean,
)

/** One settings row for the SwiftUI detail page: a title, its current value, and whether it is a switch. */
data class TvAlertSettingRow(val id: String, val title: String, val value: String, val isSwitch: Boolean, val on: Boolean)

/**
 * Apple TV's presentation of the shared sports alerts (the decision itself is [com.nuvio.app.features.radar.alerts.SportsAlertPolicy],
 * identical on every platform). Pure, so Swift gets strings and flags, never a decision to make.
 */
object TvSportsAlertsPolicy {
    /** 10 s on TV and Apple TV (8 s on phone and desktop). */
    const val DISMISS_MS: Long = SportsAlertController.TV_DISMISS_MS

    fun hint(action: BannerAction): String = when (action) {
        is BannerAction.Watch -> "Hold Select to watch"
        is BannerAction.OpenGame, BannerAction.OpenSports -> "Hold Select to open"
    }

    fun banner(banner: AlertBanner): TvAlertBanner = TvAlertBanner(
        id = banner.id,
        title = banner.title,
        subtitle = banner.subtitle,
        lines = banner.lines,
        hint = hint(banner.action),
        isSummary = banner.isSummary,
    )

    /** The settings page, in order. Ids are what Swift passes back to [com.tuvora.tvos.screens.TvSportsAlerts.press]. */
    fun rows(s: SportsAlertSettings, signedIn: Boolean): List<TvAlertSettingRow> {
        if (!signedIn) return listOf(TvAlertSettingRow(ROW_ENABLED, "Sports alerts", SportsAlertLabels.SIGN_IN, isSwitch = false, on = false))
        val enabled = TvAlertSettingRow(ROW_ENABLED, "Sports alerts", if (s.enabled) "On" else "Off", isSwitch = true, on = s.enabled)
        if (!s.enabled) return listOf(enabled)
        return listOfNotNull(
            enabled,
            TvAlertSettingRow(ROW_TEAMS, "Teams you follow", SportsAlertLabels.level(s.teamLevel), isSwitch = false, on = false),
            TvAlertSettingRow(ROW_LEAGUES, "Leagues you follow", SportsAlertLabels.level(s.leagueLevel), isSwitch = false, on = false),
            TvAlertSettingRow(ROW_PLAYBACK, "While something plays", SportsAlertLabels.duringPlayback(s.duringPlayback), isSwitch = false, on = false),
            TvAlertSettingRow(ROW_PER_HOUR, "At most", SportsAlertLabels.perHour(s.maxPerHour), isSwitch = false, on = false),
            TvAlertSettingRow(ROW_BATCH, "Group updates", SportsAlertLabels.batch(s.batchSeconds), isSwitch = false, on = false),
            TvAlertSettingRow(ROW_QUIET, "Quiet hours", if (s.quietOn) "On" else "Off", isSwitch = true, on = s.quietOn),
            if (s.quietOn) TvAlertSettingRow(ROW_QUIET_FROM, "From", SportsAlertLabels.clock(s.quietStartMin), isSwitch = false, on = false) else null,
            if (s.quietOn) TvAlertSettingRow(ROW_QUIET_UNTIL, "Until", SportsAlertLabels.clock(s.quietEndMin), isSwitch = false, on = false) else null,
        )
    }

    const val ROW_ENABLED = "enabled"
    const val ROW_TEAMS = "teams"
    const val ROW_LEAGUES = "leagues"
    const val ROW_PLAYBACK = "playback"
    const val ROW_PER_HOUR = "per_hour"
    const val ROW_BATCH = "batch"
    const val ROW_QUIET = "quiet"
    const val ROW_QUIET_FROM = "quiet_from"
    const val ROW_QUIET_UNTIL = "quiet_until"
}
