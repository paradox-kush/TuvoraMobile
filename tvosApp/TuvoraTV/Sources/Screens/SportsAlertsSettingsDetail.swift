import SwiftUI
import TuvoraCore

/// Settings > Sports alerts: the phone's "Sports alerts" section (SportsAlertsSettingsSection.kt) in Apple TV's
/// CLASSIC settings rows. Which rows show and what they read is TvSportsAlertsPolicy.rows; a press goes back to
/// TvSportsAlerts.press (a switch toggles, a value row moves to its next value). Thin: no decisions here.
struct SportsAlertsSettingsDetail: View {
    @State private var rows: [TvAlertSettingRow] = TvSportsAlertsPolicy.shared.rows(s: TvSportsAlerts.shared.settings(), signedIn: TvSportsAlerts.shared.signedIn.value.boolValue)
    @State private var view: SportsAlertSettingsView?

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Sports alerts",
                                 subtitle: "Kick-offs, goals and full time for the teams and leagues you follow, while the app is open.")
            SettingsGroupCard {
                ForEach(rows, id: \.id) { row in rowView(row) }
            }
            if let view, view.signedIn, view.settings.enabled, !(view.teams.isEmpty && view.leagues.isEmpty) {
                SettingsGroupCard(title: "Teams and leagues", subtitle: "Switch one off to mute its alerts.") {
                    ForEach(Array(view.teams.enumerated()), id: \.offset) { _, pair in
                        let id = Self.text(pair.first)
                        let muted = view.settings.mutedTeams.contains(id)
                        SettingsToggleRow(title: Self.text(pair.second), subtitle: "Team", isOn: !muted) {
                            TvSportsAlerts.shared.setTeamMuted(teamId: id, muted: !muted)
                        }
                    }
                    ForEach(Array(view.leagues.enumerated()), id: \.offset) { _, pair in
                        let id = Self.text(pair.first)
                        let muted = view.settings.mutedLeagues.contains(id)
                        SettingsToggleRow(title: Self.text(pair.second), subtitle: "League", isOn: !muted) {
                            TvSportsAlerts.shared.setLeagueMuted(leagueId: id, muted: !muted)
                        }
                    }
                }
            }
        }
        // Settings, follows and sign-in in one flow (radar state + auth): every change redraws the rows.
        .task {
            for await next in SportsAlerts.shared.settingsView {
                view = next
                rows = TvSportsAlertsPolicy.shared.rows(s: next.settings, signedIn: next.signedIn)
            }
        }
    }

    @ViewBuilder
    private func rowView(_ row: TvAlertSettingRow) -> some View {
        switch row.id {
        case TvSportsAlertsPolicy.shared.ROW_ENABLED where !row.isSwitch:
            // Signed out: alerts need an account in v1. Said, not offered.
            SettingsActionRow(title: row.title, subtitle: row.value, showChevron: false, enabled: false) {}
        case TvSportsAlertsPolicy.shared.ROW_QUIET_FROM, TvSportsAlertsPolicy.shared.ROW_QUIET_UNTIL:
            QuietTimeRow(row: row)
        default:
            if row.isSwitch {
                SettingsToggleRow(title: row.title, subtitle: Self.subtitle(row.id), isOn: row.on) {
                    TvSportsAlerts.shared.press(rowId: row.id)
                }
                .accessibilityIdentifier("alerts.\(row.id)")
            } else {
                SettingsActionRow(title: row.title, subtitle: Self.subtitle(row.id), value: row.value, showChevron: false) {
                    TvSportsAlerts.shared.press(rowId: row.id)
                }
                .accessibilityIdentifier("alerts.\(row.id)")
            }
        }
    }

    /// The phone section's descriptions, by row.
    private static func subtitle(_ id: String) -> String? {
        let p = TvSportsAlertsPolicy.shared
        switch id {
        case p.ROW_TEAMS: return "What to show for a followed club's games"
        case p.ROW_LEAGUES: return "What to show for other games in a followed league"
        case p.ROW_PLAYBACK: return "Banners over the player"
        case p.ROW_PER_HOUR: return "Banners in any hour"
        case p.ROW_BATCH: return "Updates that land close together arrive as one banner"
        case p.ROW_QUIET: return "No sports banners in this window. Alerts are dropped, not saved for later."
        default: return nil
        }
    }

    /// A Kotlin Pair member (bridged as NSString) as a Swift string.
    private static func text(_ value: Any?) -> String { (value as? String) ?? "" }
}

/// Quiet hours From / Until: the time, then Earlier / Later in half-hour steps (the phone's TimeStepperRow). Two
/// plain buttons rather than LEFT / RIGHT on one row: LEFT from the detail pane must keep going back to the rail.
private struct QuietTimeRow: View {
    let row: TvAlertSettingRow
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(12)) {
            Text(ui: row.title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
            Spacer()
            Text(verbatim: row.value).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary).monospacedDigit()
            SettingsChoiceChip(label: "Earlier", selected: false) { TvSportsAlerts.shared.stepQuiet(rowId: row.id, steps: -1) }
                .accessibilityIdentifier("alerts.\(row.id).earlier")
            SettingsChoiceChip(label: "Later", selected: false) { TvSportsAlerts.shared.stepQuiet(rowId: row.id, steps: 1) }
                .accessibilityIdentifier("alerts.\(row.id).later")
        }
        .padding(.horizontal, dp(18)).padding(.vertical, dp(8))
        .frame(minHeight: dp(62))
        .background(Capsule().fill(colors.background))
        .focusSection()
    }
}
