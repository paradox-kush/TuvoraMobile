import SwiftUI
import TuvoraCore

// Home's Calendar presence (design ported from the Arc fork, ysosrs123/NuvioTV-Fork, GPL-3.0): the bell reminders
// card ("New episodes are out") and the opt-in "Coming up" row, plus the in-app note when a belled episode lands.
// No background job: the reminder check runs only while Home is on screen and the app is active, reads local data
// only, and sleeps until the next release is due (1 min – 6 h, TvCalendar.reminderCheckDelayMs).
struct CalendarHomeSection: View {
    let onOpen: (MetaPreview) -> Void
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @State private var calendar: CalendarState?
    @State private var watching: WatchTarget?

    var body: some View {
        VStack(alignment: .leading, spacing: NuvioTokens.Layout.rowGap) {
            if calendar != nil {
                if let reminders = TvCalendar.shared.reminders() { reminderCard(reminders).focusSection() }
                let comingUp = TvCalendar.shared.comingUp(state: calendar!, nowMs: TvCalendar.shared.nowMs())
                if !comingUp.isEmpty { comingUpRow(comingUp).focusSection() }
            }
        }
        .calendarWatchSheet($watching)
        .task {
            TvCalendar.shared.start()
            for await next in TvCalendar.shared.state { calendar = next }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            // Home settles first; then a throttled, due-only refresh (zero requests on an idle slate).
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            guard !Task.isCancelled else { return }
            TvCalendar.shared.homeSettled()
            while !Task.isCancelled {
                let names = TvCalendar.shared.nextReminderNote()
                if names.count == 1 {
                    playback.notify(String(format: L("New episode of %@ is out"), names[0]))
                } else if names.count > 1 {
                    playback.notify(String(format: L("%d new episodes are out"), Int32(names.count)))
                }
                let wait = TvCalendar.shared.reminderCheckDelayMs(nowMs: TvCalendar.shared.nowMs())
                try? await Task.sleep(nanoseconds: UInt64(max(wait, 60_000)) * 1_000_000)
            }
        }
    }

    private func reminderCard(_ reminders: TvCalendarReminders) -> some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            HStack(spacing: dp(10)) {
                Image("md_notifications_active").renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                    .foregroundStyle(colors.secondary)
                Text(ui: "New episodes are out").font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary)
            }
            ForEach(reminders.rows, id: \.id) { row in
                CalendarRowButton(row: row) { onOpen(preview(row)) }
            }
            HStack(spacing: dp(12)) {
                if reminders.more > 0 {
                    Text(verbatim: String(format: L("+%d more"), reminders.more)).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
                }
                NuvioTextButton(title: "Got it") { TvCalendar.shared.dismissReminders(episodeKeys: reminders.episodeKeys) }
            }
        }
        .padding(dp(16))
        .frame(maxWidth: dp(720), alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(16), style: .continuous).fill(colors.backgroundElevated))
        .padding(.leading, NuvioTokens.Layout.gutter)
    }

    private func comingUpRow(_ items: [TvCalendarComingUp]) -> some View {
        let width = dp(NuvioCardSize.posterWidthDp)
        return VStack(alignment: .leading, spacing: 0) {
            NuvioShelfHeader(title: "Coming up") { EmptyView() }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: NuvioTokens.Layout.itemGap) {
                    ForEach(items, id: \.row.titleKey) { item in
                        if let game = item.row.game {
                            GameCard(row: item.row, game: game, whenText: whenText(item), width: width * 1.6) {
                                if game.canWatchNow, let fixture = TvCalendar.shared.fixtureFor(gameId: game.gameId) {
                                    watching = WatchTarget(fixture: fixture, live: game.state == "LIVE")
                                }
                            }
                        } else {
                            NuvioPosterCard(title: item.row.name, subtitle: whenText(item), imageURL: item.row.poster,
                                            width: width, height: width * 1.5, showLabel: true) { onOpen(preview(item.row)) }
                        }
                    }
                }
                .padding(.horizontal, NuvioTokens.Layout.gutter)
            }
            .scrollClipDisabled()
        }
    }

    private func whenText(_ item: TvCalendarComingUp) -> String {
        switch item.whenKind {
        case "NEW": return L("New")
        case "TODAY": return item.timeText ?? L("Today")
        case "TONIGHT": return [L("Tonight"), item.timeText].compactMap { $0 }.joined(separator: " ")
        case "TOMORROW": return L("Tomorrow")
        case "WEEKDAY": return CalendarDates.weekday(item.dateIso) ?? ""
        default: return CalendarDates.day(item.dateIso) ?? ""
        }
    }

    private func preview(_ row: TvCalendarRow) -> MetaPreview {
        MetaPreview(id: row.contentId, type: row.type, name: row.name, poster: row.poster, banner: nil, logo: nil,
                    posterShape: .poster, description: nil, releaseInfo: nil, rawReleaseDate: nil,
                    popularity: nil, voteCount: nil, imdbRating: nil, genres: [], pinned: false,
                    rawPosterUrl: nil, landscapePoster: nil, rawLandscapePosterUrl: nil,
                    runtime: nil, status: nil, ageRating: nil, language: nil, externalRatings: [])
    }
}

/// A game card in the "Coming up" row: kick-off or live score on top, both teams under. Focusable (ring + lift) when
/// "Watch now" applies, which opens the Sports match sheet; otherwise informational.
private struct GameCard: View {
    let row: TvCalendarRow
    let game: TvCalendarGame
    let whenText: String
    let width: CGFloat
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        if game.canWatchNow {
            Button(action: action) { card }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focused)
                .reportsFocus(focused)
        } else {
            card
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            Text(verbatim: game.state == "LIVE" ? [L("LIVE"), game.progress].compactMap { $0 }.joined(separator: " ") : whenText)
                .font(NuvioType.labelMedium).foregroundStyle(game.state == "LIVE" ? colors.secondary : colors.textSecondary)
            Text(verbatim: row.name).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
            if let league = game.league {
                Text(verbatim: league).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
        }
        .padding(dp(12))
        .frame(width: width, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(focused ? colors.focusBackground : colors.backgroundCard))
        .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
        .animation(NuvioTokens.Motion.fast, value: focused)
    }
}
