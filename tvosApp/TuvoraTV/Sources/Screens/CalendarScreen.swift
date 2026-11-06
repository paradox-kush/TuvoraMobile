import SwiftUI
import TuvoraCore

// The Calendar page (design ported from the Arc fork, ysosrs123/NuvioTV-Fork, GPL-3.0): releases of the shows and
// films the viewer follows, as a List grouped by day or a Month grid. Data and every decision (what is followed,
// when to refresh, Loaded / Empty / Failed, the loading deadline) come from the shared Kotlin calendar through
// TvCalendar; this view only draws TvCalendarPresentation and handles focus.

private enum CalendarMetrics {
    static let gutter = dp(52)
    static let rowPoster = CGSize(width: dp(54), height: dp(81))
    static let dayCell = CGSize(width: dp(118), height: dp(92))
}

/// Day / time text from the presentation's ISO date (formatted in UTC: the day was already counted in the
/// Calendar's own zone by the shared code, so no second conversion may move it).
enum CalendarDates {
    private static let parser: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM-dd"; return f
    }()
    private static func formatter(_ template: String) -> DateFormatter {
        let f = DateFormatter(); f.timeZone = TimeZone(identifier: "UTC"); f.setLocalizedDateFormatFromTemplate(template); return f
    }
    private static let dayFormatter = formatter("EEEdMMMM")
    private static let weekdayFormatter = formatter("EEE")
    private static let monthFormatter = formatter("MMMMyyyy")

    static func day(_ iso: String?) -> String? { iso.flatMap(parser.date(from:)).map(dayFormatter.string(from:)) }
    static func weekday(_ iso: String?) -> String? { iso.flatMap(parser.date(from:)).map(weekdayFormatter.string(from:)) }
    static func month(year: Int32, month: Int32) -> String {
        parser.date(from: String(format: "%04d-%02d-01", year, month)).map(monthFormatter.string(from:)) ?? ""
    }
}

/// A presentation line that names a Localizable key (`calendar_movie_digital`) or is plain text ("S2 · E5 · Title").
func calendarLine(_ line: String?) -> String? {
    guard let line else { return nil }
    switch line {
    case "calendar_movie_cinema": return L("In cinemas")
    case "calendar_movie_digital": return L("Streaming / digital")
    default: return line
    }
}

func calendarLabel(_ key: String?) -> String? {
    switch key {
    case "calendar_label_new_show": return L("New show")
    case "calendar_label_season_premiere": return L("Season premiere")
    case "calendar_label_mid_season_premiere": return L("Mid-season premiere")
    case "calendar_label_mid_season_finale": return L("Mid-season finale")
    case "calendar_label_season_finale": return L("Season finale")
    case "calendar_label_series_finale": return L("Series finale")
    default: return nil
    }
}

struct CalendarScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @State private var calendar: CalendarState?
    @State private var now: Int64 = TvCalendar.shared.nowMs()
    /// Month view: the month shown (first day, ISO); nil = this month.
    @State private var anchor: String?
    @State private var details: PreviewBox?
    @State private var settingsOpen = false
    /// "Watch now" on a game: the fixture whose channels the existing Sports match sheet looks up.
    @State private var watching: WatchTarget?

    var body: some View {
        Group {
            if let calendar {
                let screen = TvCalendar.shared.screen(state: calendar, nowMs: now, anchorIso: anchor)
                content(screen)
                    // The UI's own deadline backstop: re-evaluate when a Loading reaches its deadline, even if
                    // the refresh never reports back. No I/O; the task ends with the screen.
                    .task(id: screen.nextChangeInMs?.int64Value ?? -1) {
                        guard let wait = screen.nextChangeInMs?.int64Value else { return }
                        try? await Task.sleep(nanoseconds: UInt64(max(wait, 0) + 50) * 1_000_000)
                        if !Task.isCancelled { now = TvCalendar.shared.nowMs() }
                    }
            } else {
                Color.clear
            }
        }
        .task {
            TvCalendar.shared.start()
            TvCalendar.shared.opened()   // throttled: refreshes only if due
            for await next in TvCalendar.shared.state {
                calendar = next
                now = TvCalendar.shared.nowMs()
                ContentFocusActivity.contentChanged()
            }
        }
        .task(id: scenePhase) {
            // Live / final flips with the clock, not only with new fixtures: re-read the games (a local read, no request)
            // once a minute, only while this page is on screen and the app is active.
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                TvCalendar.shared.reloadGames()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
        .calendarWatchSheet($watching)
    }

    @ViewBuilder
    private func content(_ screen: TvCalendarScreen) -> some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: dp(16)) {
                header(screen).focusSection()
                if screen.refreshing {
                    Text(ui: "Updating…").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        .padding(.leading, CalendarMetrics.gutter)
                }
                if screen.updateFailed {
                    HStack(spacing: dp(16)) {
                        Text(ui: "Couldn't update · showing what we had").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                        NuvioTextButton(title: "Retry") { TvCalendar.shared.retry() }
                    }
                    .padding(.leading, CalendarMetrics.gutter)
                    .focusSection()
                }
                switch screen.body {
                case .blank:
                    Color.clear.frame(height: dp(200))
                case .loading:
                    ForEach(0..<4, id: \.self) { _ in
                        NuvioShimmer().frame(height: CalendarMetrics.rowPoster.height + dp(24)).padding(.horizontal, CalendarMetrics.gutter)
                    }
                case .empty:
                    NuvioEmptyState(icon: "md_calendar_month", title: "Nothing on the horizon",
                                    subtitle: "Shows in your library and the ones you are watching land here when a new episode is dated. Press and hold + on a show to turn on its reminder.")
                case .failed, .failedTimedOut:
                    NuvioStateMessage(title: "Couldn't load the calendar",
                                      message: screen.body == .failedTimedOut
                                          ? "The TV listings are taking too long. We'll keep fetching in the background."
                                          : "The TV listings didn't answer. Your shows are safe; the listings are just being shy.",
                                      retry: { TvCalendar.shared.retry() })
                        .frame(height: dp(320))
                case .content:
                    if screen.monthView { month(screen) } else { list(screen) }
                default:
                    EmptyView()
                }
                if settingsOpen { settings(screen).focusSection() }
            }
            .padding(.vertical, dp(24))
        }
        .scrollClipDisabled()
    }

    private func header(_ screen: TvCalendarScreen) -> some View {
        HStack(spacing: dp(12)) {
            Text(ui: "Calendar").font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary)
            Spacer().frame(width: dp(24))
            HubChip(title: "List", selected: !screen.monthView) { TvCalendar.shared.setMonthView(on: false) }
            HubChip(title: "Month", selected: screen.monthView) { TvCalendar.shared.setMonthView(on: true) }
            Spacer().frame(width: dp(24))
            HubChip(title: "Shows", selected: screen.showShows) { TvCalendar.shared.setFilter(filter: "SHOWS", on: !screen.showShows) }
            HubChip(title: "Movies", selected: screen.showMovies) { TvCalendar.shared.setFilter(filter: "MOVIES", on: !screen.showMovies) }
            if screen.sportsChip {
                HubChip(title: "Sports", selected: screen.showSports) { TvCalendar.shared.setFilter(filter: "SPORTS", on: !screen.showSports) }
            }
            Spacer()
            HubChip(title: "Calendar settings", selected: settingsOpen) { settingsOpen.toggle() }
        }
        .padding(.horizontal, CalendarMetrics.gutter)
    }

    private func list(_ screen: TvCalendarScreen) -> some View {
        ForEach(screen.sections, id: \.id) { section in
            VStack(alignment: .leading, spacing: dp(10)) {
                Text(ui: sectionTitle(section)).font(NuvioType.titleMediumSemi)
                    .foregroundStyle(section.kind == "TODAY" ? colors.secondary : colors.textSecondary)
                ForEach(section.rows, id: \.id) { row in
                    if row.game != nil {
                        CalendarGameRow(row: row, showDate: section.kind == "JUST_AIRED" || section.kind == "LATER") { watch(row) }
                    } else {
                        CalendarRowButton(row: row, showDate: section.kind == "JUST_AIRED" || section.kind == "LATER") { open(row) }
                    }
                }
            }
            .padding(.horizontal, CalendarMetrics.gutter)
            .focusSection()
        }
    }

    private func sectionTitle(_ section: TvCalendarSection) -> String {
        switch section.kind {
        case "JUST_AIRED": return L("Just aired")
        case "TODAY": return L("Today")
        case "TOMORROW": return L("Tomorrow")
        case "LATER": return L("Later")
        case "NO_DATE": return L("No date yet")
        default: return CalendarDates.day(section.dateIso) ?? ""
        }
    }

    private func month(_ screen: TvCalendarScreen) -> some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            HStack(spacing: dp(16)) {
                NuvioTextButton(title: "Previous month") { anchor = TvCalendar.shared.shiftMonth(anchorIso: anchor, months: -1, nowMs: now) }
                Text(verbatim: CalendarDates.month(year: screen.monthYear, month: screen.monthNumber))
                    .font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary).frame(minWidth: dp(260))
                NuvioTextButton(title: "Next month") { anchor = TvCalendar.shared.shiftMonth(anchorIso: anchor, months: 1, nowMs: now) }
            }
            .focusSection()
            ForEach(Array(screen.weeks.enumerated()), id: \.offset) { _, week in
                HStack(spacing: dp(8)) {
                    ForEach(week, id: \.dateIso) { day in
                        CalendarDayCell(day: day) { if let first = day.rows.first { first.game == nil ? open(first) : watch(first) } }
                    }
                }
                .focusSection()
            }
        }
        .padding(.horizontal, CalendarMetrics.gutter)
    }

    private func settings(_ screen: TvCalendarScreen) -> some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            Text(ui: "Calendar settings").font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary)
            HStack(spacing: dp(12)) {
                HubChip(title: "Show “Coming up” on Home", selected: screen.comingUpRow) { TvCalendar.shared.setComingUpRow(on: !screen.comingUpRow) }
                HubChip(title: "Pop a note when a belled episode lands", selected: screen.cornerNote) { TvCalendar.shared.setCornerNote(on: !screen.cornerNote) }
            }
            HStack(spacing: dp(12)) {
                Text(ui: "Days ahead in the list").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                ForEach([14, 30, 60], id: \.self) { days in
                    HubChip(title: "\(days)", selected: screen.windowDays == Int32(days)) { TvCalendar.shared.setWindowDays(days: Int32(days)) }
                }
            }
        }
        .padding(.horizontal, CalendarMetrics.gutter)
    }

    private func watch(_ row: TvCalendarRow) {
        guard let game = row.game, game.canWatchNow, let fixture = TvCalendar.shared.fixtureFor(gameId: game.gameId) else { return }
        watching = WatchTarget(fixture: fixture, live: game.state == "LIVE")
    }

    private func open(_ row: TvCalendarRow) {
        details = PreviewBox(preview: MetaPreview(id: row.contentId, type: row.type, name: row.name, poster: row.poster, banner: nil, logo: nil,
                                                  posterShape: .poster, description: nil, releaseInfo: nil, rawReleaseDate: nil,
                                                  popularity: nil, voteCount: nil, imdbRating: nil, genres: [], pinned: false,
                                                  rawPosterUrl: nil, landscapePoster: nil, rawLandscapePosterUrl: nil,
                                                  runtime: nil, status: nil, ageRating: nil, language: nil, externalRatings: []))
    }
}

/// One release: poster, title, episode line, when, label. A Button so the focus engine reaches it; focused = ring + lift.
struct CalendarRowButton: View {
    let row: TvCalendarRow
    var showDate = false
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(16)) {
                CachedPosterArtwork(urlString: row.poster, width: CalendarMetrics.rowPoster.width, height: CalendarMetrics.rowPoster.height) {
                    colors.backgroundCard
                }
                .frame(width: CalendarMetrics.rowPoster.width, height: CalendarMetrics.rowPoster.height)
                .clipShape(RoundedRectangle(cornerRadius: dp(8), style: .continuous))
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(verbatim: row.name).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if let line = calendarLine(row.episodeLine) {
                        Text(verbatim: line).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                    }
                    Text(verbatim: whenLine).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).lineLimit(1)
                    if let label = calendarLabel(row.labelKey) {
                        Text(verbatim: label).font(NuvioType.labelMedium).foregroundStyle(colors.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(dp(12))
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .contextMenu {
            Button(L("Hide from calendar")) { TvCalendar.shared.hide(titleKey: row.titleKey) }
        }
    }

    private var whenLine: String {
        var parts: [String] = []
        if showDate, let day = CalendarDates.day(row.dateIso) { parts.append(day) }
        if row.episodeLine != nil { parts.append(row.timeText ?? L("Time not announced")) }
        if let network = row.network { parts.append(network) }
        return parts.joined(separator: " · ")
    }
}

/// A month-grid day. Focusable even when empty so D-pad movement through the grid is regular; focused = ring.
private struct CalendarDayCell: View {
    let day: TvCalendarDay
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(4)) {
                HStack {
                    Text(verbatim: "\(day.day)").font(day.isToday ? NuvioType.titleMediumSemi : NuvioType.titleSmall)
                        .foregroundStyle(day.isToday ? colors.secondary : (day.inMonth ? colors.textPrimary : colors.textDisabled))
                    Spacer(minLength: 0)
                    if day.isToday, let weekday = CalendarDates.weekday(day.dateIso) {
                        Text(verbatim: weekday).font(NuvioType.labelSmall).foregroundStyle(colors.secondary)
                    }
                }
                ForEach(Array(day.rows.prefix(2).enumerated()), id: \.offset) { _, row in
                    Text(verbatim: row.name).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
                }
                if day.rows.count > 2 {
                    Text(verbatim: "+\(day.rows.count - 2)").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                }
                Spacer(minLength: 0)
            }
            .padding(dp(8))
            .frame(width: CalendarMetrics.dayCell.width, height: CalendarMetrics.dayCell.height, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: dp(10), style: .continuous)
                .fill(focused ? colors.focusBackground : (day.inMonth ? colors.backgroundCard : colors.background)))
            .overlay(RoundedRectangle(cornerRadius: dp(10), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// A fixture to find channels for, from a game's "Watch now".
struct WatchTarget: Identifiable {
    let fixture: RadarFixture
    let live: Bool
    var id: String { fixture.id ?? fixture.displayTitle }
}

private struct CalendarWatchSheet: ViewModifier {
    @Binding var target: WatchTarget?
    @Environment(\.nuvio) private var colors
    @EnvironmentObject private var playback: PlaybackCoordinator
    @State private var pendingPlay: TvPlayerSession?

    func body(content: Content) -> some View {
        content.fullScreenCover(item: $target, onDismiss: {
            if let session = pendingPlay { pendingPlay = nil; playback.play(session) }
        }) { target in
            // The same channel-matching sheet the Sports tab opens: which of my channels shows this game.
            MatchChannelsSheet(fixture: target.fixture, live: target.live) { session in
                pendingPlay = session
                self.target = nil
            }
            .environment(\.nuvio, colors).environmentObject(playback)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.opacity(0.6).ignoresSafeArea())
            .presentationBackground(.clear)
        }
    }
}

extension View {
    func calendarWatchSheet(_ target: Binding<WatchTarget?>) -> some View { modifier(CalendarWatchSheet(target: target)) }
}

/// A sports game: both teams, league and kick-off; score + minute when live, score + FT when final. When "Watch now"
/// applies (live or within 15 minutes of kick-off) the whole row is the button (focused = ring + lift); otherwise it is
/// plain information with nothing to focus.
struct CalendarGameRow: View {
    let row: TvCalendarRow
    var showDate = false
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        if let game = row.game, game.canWatchNow {
            Button(action: action) { card(game) }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focused)
                .reportsFocus(focused)
        } else if let game = row.game {
            card(game)
        }
    }

    private func card(_ game: TvCalendarGame) -> some View {
        HStack(spacing: dp(16)) {
            HStack(spacing: dp(6)) {
                badge(game.homeBadge)
                if game.home != nil && game.away != nil { badge(game.awayBadge) }
            }
            VStack(alignment: .leading, spacing: dp(4)) {
                Text(verbatim: row.name.replacingOccurrences(of: " v ", with: "  v  ")).font(NuvioType.titleMediumSemi)
                    .foregroundStyle(colors.textPrimary).lineLimit(1)
                Text(verbatim: whenLine(game)).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).lineLimit(1)
                if let status = status(game) {
                    Text(verbatim: status).font(NuvioType.labelMedium)
                        .foregroundStyle(game.state == "LIVE" ? colors.secondary : colors.textSecondary)
                }
            }
            Spacer(minLength: 0)
            if game.canWatchNow {
                Text(ui: "Watch now").font(NuvioType.labelLarge).foregroundStyle(focused ? colors.textPrimary : colors.secondary)
            }
        }
        .padding(dp(12))
        .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(focused ? colors.focusBackground : colors.backgroundCard))
        .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
        .animation(NuvioTokens.Motion.fast, value: focused)
    }

    private func badge(_ url: String?) -> some View {
        CachedPosterArtwork(urlString: url, width: dp(40), height: dp(40), maximumWidth: dp(40)) { colors.backgroundCard }
            .environment(\.artworkContentMode, .fit)
            .frame(width: dp(40), height: dp(40))
            .clipShape(RoundedRectangle(cornerRadius: dp(6), style: .continuous))
    }

    private func whenLine(_ game: TvCalendarGame) -> String {
        var parts: [String] = []
        if showDate, let day = CalendarDates.day(row.dateIso) { parts.append(day) }
        if let time = row.timeText { parts.append(time) }
        if let league = game.league, !league.isEmpty { parts.append(league) }
        return parts.joined(separator: " · ")
    }

    private func status(_ game: TvCalendarGame) -> String? {
        let score = [game.homeScore, game.awayScore].compactMap { $0 }.joined(separator: " – ")
        switch game.state {
        case "LIVE": return [L("LIVE"), game.progress, score.isEmpty ? nil : score].compactMap { $0 }.joined(separator: " · ")
        case "FINAL": return [score.isEmpty ? nil : score, L("FT")].compactMap { $0 }.joined(separator: " · ")
        case "POSTPONED": return L("Postponed")
        default: return nil
        }
    }
}
