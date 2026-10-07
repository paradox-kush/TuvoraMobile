import SwiftUI
import TuvoraCore

// NuvioTV Sports Centre hub (ui/screens/radar/SportsHubScreen.kt): featured event banners, Live &
// Upcoming for followed leagues + teams, one rail per followed team and league, and Browse sports.
// OK on a match opens the channel sheet (MatchChannelsOverlay) and OK on a channel plays it. Data is
// the shared RadarRepository through TvSports; which rails appear is TvSportsHubPolicy.
//
// Not ported yet: the top search field (league / team search + follow), the league drill-in page with
// recent results, catch-up replays and recordings in the match sheet.

private enum SportsMetrics {
    static let gutter = dp(52)
    static let rowTitleBottom = dp(14)
    static let matchCardWidth = dp(300)
    static let teamsMinHeight = dp(64)
}

struct SportsScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @State private var radar: RadarUiState?
    @State private var now: Int64 = TvSports.shared.nowMs()
    @State private var page: FixturesPage?
    @State private var leaguePage: RadarLeague?
    /// A league picked in the browse dialog opens once the dialog has gone.
    @State private var pendingLeague: RadarLeague?
    @State private var browse: RadarCategory?
    @State private var sheet: MatchSheetTarget?
    @State private var pendingPlay: TvPlayerSession?
    @State private var smokeMatchOpened = false

    var body: some View {
        Group {
            if let radar {
                if let league = leaguePage {
                    LeaguePageView(league: league, radar: radar, now: now) { open($0) }
                        .onExitCommand { leaguePage = nil }
                } else if let page {
                    FixturesPageView(page: page, radar: radar, now: now) { open($0) }
                        .onExitCommand { self.page = nil }
                } else {
                    hub(radar, TvSports.shared.hub(state: radar, nowMs: now))
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task {
            TvSports.shared.ensureLoaded()
            for await next in TvSports.shared.state {
                radar = next
                now = TvSports.shared.nowMs()
                smokeLog("SMOKE sports follows=%d fixtures=%d loading=%d", next.follows.count, next.fixturesByLeague.count, next.loadingFixtures)
                // Simulator smoke hook: `-smokeSportsMatch` opens the first upcoming match's channel sheet.
                // `-smokeSportsLeague <leagueId>` opens a league page.
                let args = AppArguments.list
                if !smokeMatchOpened, let i = args.firstIndex(of: "-smokeSportsLeague"), i + 1 < args.count,
                   let league = next.leagueById(id: args[i + 1]) {
                    smokeMatchOpened = true
                    showLeague(league)
                }
                if !smokeMatchOpened, AppArguments.list.contains("-smokeSportsMatch"),
                   let first = TvSports.shared.hub(state: next, nowMs: now).upcoming.first {
                    smokeMatchOpened = true
                    open(first)
                }
            }
        }
        .task {
            // Live badges depend on the clock as well as the data; re-evaluate once a minute (no I/O).
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                now = TvSports.shared.nowMs()
            }
        }
        .task(id: scenePhase) {
            // Egress rule: the refresh loop lives only while Sports is on screen and the app is
            // active; leaving the tab or backgrounding cancels this task and with it the loop.
            guard scenePhase == .active else { return }
            try? await TvSports.shared.refreshWhileVisible()
        }
        .fullScreenCover(item: $browse, onDismiss: {
            if let league = pendingLeague { pendingLeague = nil; showLeague(league) }
        }) { category in
            CategoryDialog(category: category) { league in
                pendingLeague = league
                browse = nil
            }
            .environment(\.nuvio, colors)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black.opacity(0.6).ignoresSafeArea())
                .presentationBackground(.clear)
        }
        .fullScreenCover(item: $sheet, onDismiss: {
            if let session = pendingPlay { pendingPlay = nil; playback.play(session) }
        }) { target in
            MatchChannelsSheet(fixture: target.fixture, live: radar.map { TvSports.shared.isLive(state: $0, fixture: target.fixture, nowMs: now) } ?? false) { session in
                pendingPlay = session
                sheet = nil
            }
            .environment(\.nuvio, colors).environmentObject(playback)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black.opacity(0.6).ignoresSafeArea())
                .presentationBackground(.clear)
        }
    }

    private func open(_ fixture: RadarFixture) { sheet = MatchSheetTarget(fixture: fixture) }

    private func hub(_ radar: RadarUiState, _ hub: TvSportsHub) -> some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: dp(24)) {
                Text("Sports").font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary)
                    .padding(.leading, SportsMetrics.gutter)
                    .padding(.bottom, dp(12) - dp(24))

                if !hub.featured.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        SportsRowTitle(text: "Featured Events") {
                            let fixtures = hub.featured.flatMap { radar.upcoming(leagueIds: [$0.event.leagueId], nowMs: now, cap: 40) }
                            page = FixturesPage(title: "Featured Events", fixtures: Self.distinct(fixtures))
                        }
                        rail(hub.featured, id: \.event.id) { featured in
                            FeaturedBannerCard(featured: featured) {
                                if let league = radar.leagueById(id: featured.event.leagueId) { showLeague(league) }
                            }
                        }
                    }
                }

                if !hub.upcoming.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        SportsRowTitle(text: "Live & Upcoming") { page = FixturesPage(title: "Live & Upcoming", fixtures: hub.upcoming) }
                        matchRail(hub.upcoming, radar)
                    }
                } else if hub.upcomingLoading {
                    VStack(alignment: .leading, spacing: 0) {
                        SportsRowTitle(text: "Live & Upcoming", onSeeAll: nil)
                        HStack(spacing: dp(12)) {
                            ForEach(0..<4, id: \.self) { _ in
                                NuvioShimmer(cornerRadius: dp(12)).frame(width: SportsMetrics.matchCardWidth, height: dp(140))
                            }
                        }
                        .padding(.horizontal, SportsMetrics.gutter)
                    }
                }

                ForEach(hub.teamRows + hub.leagueRows, id: \.key) { row in
                    VStack(alignment: .leading, spacing: 0) {
                        SportsRowTitle(text: row.title, badge: row.badge) {
                            if let league = row.league {
                                showLeague(league)
                            } else {
                                page = FixturesPage(title: row.title, fixtures: row.fixtures)
                            }
                        }
                        matchRail(row.fixtures, radar)
                    }
                }

                VStack(alignment: .leading, spacing: 0) {
                    SportsRowTitle(text: hub.followPrompt ? "Follow your sports" : "Browse sports", onSeeAll: nil)
                    if hub.followPrompt {
                        Text("Pick leagues and events to follow — they'll appear here when they're coming up, and Tuvora finds which of your channels is showing them.")
                            .font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                            .padding(.horizontal, SportsMetrics.gutter).padding(.bottom, dp(8))
                    }
                    rail(hub.categories, id: \.category.name) { item in
                        CategoryTile(item: item) { browse = item.category }
                    }
                }
            }
            .padding(.top, dp(24)).padding(.bottom, dp(48))
        }
        .scrollClipDisabled()
    }

    /// Into a league (NuvioTV LeagueFixturesPage); browsing loads it without following it.
    private func showLeague(_ league: RadarLeague) {
        TvSports.shared.ensureLeagueLoaded(league: league)
        leaguePage = league
    }

    private func matchRail(_ fixtures: [RadarFixture], _ radar: RadarUiState) -> some View {
        rail(fixtures, id: { TvSportsHubPolicy.shared.fixtureKey(fixture: $0) }) { fixture in
            MatchCard(fixture: fixture, radar: radar, now: now) { open(fixture) }
                .frame(width: SportsMetrics.matchCardWidth)
        }
    }

    private func rail<Item, ID: Hashable, Card: View>(_ items: [Item], id: KeyPath<Item, ID>, @ViewBuilder card: @escaping (Item) -> Card) -> some View {
        rail(items, id: { $0[keyPath: id] }, card: card)
    }

    private func rail<Item, ID: Hashable, Card: View>(_ items: [Item], id: @escaping (Item) -> ID, @ViewBuilder card: @escaping (Item) -> Card) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: dp(12)) {
                ForEach(items.map { IdentifiedItem(id: id($0), item: $0) }) { entry in card(entry.item) }
            }
            .padding(.horizontal, SportsMetrics.gutter).padding(.vertical, dp(8))
        }
        .scrollClipDisabled()
        .focusSection()
    }

    static func distinct(_ fixtures: [RadarFixture]) -> [RadarFixture] {
        var seen = Set<String>()
        return fixtures.filter { seen.insert(TvSportsHubPolicy.shared.fixtureKey(fixture: $0)).inserted }
            .sorted { ($0.startEpochMs?.int64Value ?? .max) < ($1.startEpochMs?.int64Value ?? .max) }
    }
}

private struct IdentifiedItem<ID: Hashable, Item>: Identifiable {
    let id: ID
    let item: Item
}

struct FixturesPage {
    let title: String
    let fixtures: [RadarFixture]
}

private struct MatchSheetTarget: Identifiable {
    let id = UUID()
    let fixture: RadarFixture
}

extension RadarCategory: @retroactive Identifiable {
    public var id: String { name }
}

// MARK: - Rows and cards

/// RowTitle (SportsHubScreen.kt:1531-1577): optional 22dp badge, titleMedium SemiBold, "See all".
/// The crest hangs in the gutter to the left of the title, so every title starts on the gutter line
/// whether or not it has a crest (inline, crested and crest-less titles — or a crest still loading —
/// started at different x).
private struct SportsRowTitle: View {
    let text: String
    var badge: String? = nil
    let onSeeAll: (() -> Void)?
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(8)) {
            Text(ui: text).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
                .overlay(alignment: .leading) {
                    if let badge, !badge.isEmpty {
                        BadgeImage(url: badge, size: dp(22)).offset(x: -dp(30))
                    }
                }
            if let onSeeAll { SeeAllButton(action: onSeeAll).padding(.leading, dp(4)) }
            Spacer()
        }
        .padding(.horizontal, SportsMetrics.gutter)
        .padding(.bottom, SportsMetrics.rowTitleBottom)
    }
}

/// MatchCard (SportsHubScreen.kt:937-1043): league line + status pill, one row per team with crest and
/// score, kick-off · venue below. Focus = FocusBackground fill + 2dp FocusRing.
private struct MatchCard: View {
    let fixture: RadarFixture
    let radar: RadarUiState
    let now: Int64
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let sports = TvSports.shared
        let status = sports.status(state: radar, fixture: fixture, nowMs: now)
        let shape = RoundedRectangle(cornerRadius: dp(12), style: .continuous)
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(8)) {
                HStack(spacing: dp(6)) {
                    if let badge = fixture.leagueBadge, !badge.isEmpty { BadgeImage(url: badge, size: dp(16)) }
                    Text(fixture.roundLabel ?? fixture.league ?? "").font(NuvioType.labelSmall)
                        .foregroundStyle(colors.textSecondary).lineLimit(1)
                    Spacer(minLength: dp(8))
                    MatchStatusPill(status: status)
                }
                VStack(alignment: .leading, spacing: 0) {
                    if let home = fixture.home, !home.isEmpty, let away = fixture.away, !away.isEmpty {
                        let homeScore = sports.homeScore(state: radar, fixture: fixture)
                        let awayScore = sports.awayScore(state: radar, fixture: fixture)
                        TeamRow(name: home, badge: fixture.homeBadge, score: homeScore,
                                dim: TvSportsHubPolicy.shared.scoreTrails(own: homeScore, other: awayScore))
                        TeamRow(name: away, badge: fixture.awayBadge, score: awayScore,
                                dim: TvSportsHubPolicy.shared.scoreTrails(own: awayScore, other: homeScore))
                    } else {
                        Text(fixture.displayTitle).font(NuvioType.inter(14, .semibold)).foregroundStyle(colors.textPrimary).lineLimit(2)
                    }
                }
                .frame(minHeight: SportsMetrics.teamsMinHeight, alignment: .top)
                HStack(spacing: 0) {
                    let whenLabel = sports.showsWhen(state: radar, fixture: fixture, nowMs: now)
                        ? SportsWhen.label(fixture, now: now) : nil
                    if let whenLabel {
                        Text(whenLabel).font(NuvioType.labelMedium).lineLimit(1)
                            .foregroundStyle(sports.isSoon(state: radar, fixture: fixture, nowMs: now) ? colors.secondary : colors.textSecondary)
                    }
                    if let venue = fixture.venue, !venue.isEmpty {
                        if whenLabel != nil { Text(" · ").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary) }
                        Text(venue).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: dp(18))
            }
            .padding(dp(12))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(focused ? colors.focusBackground : colors.backgroundElevated))
            .overlay(shape.stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? dp(2) : dp(1)))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// TeamRow: 28dp crest (monogram disc fallback), titleSmall name, titleMedium SemiBold score that
/// dims to TextTertiary when that side trails.
private struct TeamRow: View {
    let name: String
    let badge: String?
    let score: String?
    let dim: Bool
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(10)) {
            TeamBadge(name: name, badge: badge)
            Text(name).font(NuvioType.titleSmall).foregroundStyle(colors.textPrimary).lineLimit(1)
            Spacer(minLength: 0)
            if let score, !score.isEmpty {
                Text(score).font(NuvioType.titleMediumSemi).foregroundStyle(dim ? colors.textTertiary : colors.textPrimary).lineLimit(1)
                    .padding(.leading, dp(8))
            }
        }
        .padding(.vertical, dp(2))
    }
}

private struct TeamBadge: View {
    let name: String
    let badge: String?
    @Environment(\.nuvio) private var colors

    var body: some View {
        let monogram = ZStack {
            Circle().fill(colors.backgroundCard)
            Circle().stroke(colors.border, lineWidth: dp(1))
            Text(TvSportsHubPolicy.shared.teamMonogram(name: name)).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
        }
        .frame(width: dp(28), height: dp(28))
        if let badge, !badge.isEmpty {
            BadgeImage(url: badge, size: dp(28)) { monogram }
        } else {
            monogram
        }
    }
}

/// BadgeImage: a crest decoded at display size, fitted, never cropped.
private struct BadgeImage<Fallback: View>: View {
    let url: String
    let size: CGFloat
    @ViewBuilder var fallback: Fallback

    var body: some View {
        CachedPosterArtwork(urlString: url, width: size, height: size, maximumWidth: size) { fallback }
            .environment(\.artworkContentMode, .fit)
            .frame(width: size, height: size)
    }
}

extension BadgeImage where Fallback == Color {
    init(url: String, size: CGFloat) { self.init(url: url, size: size) { Color.clear } }
}

/// MatchStatusPill / LiveBadge (SportsHubScreen.kt:1150-1206): LIVE = marigold-live 16% fill, 50%
/// hairline, 6dp dot; other pills tint 12% / 35%. labelSmall Bold.
private struct MatchStatusPill: View {
    let status: TvMatchStatus
    @Environment(\.nuvio) private var colors

    var body: some View {
        switch status.tone {
        case .live: pill(colors.live, fill: 0.16, stroke: 0.5, dot: true)
        case .muted: pill(colors.textTertiary, fill: 0.12, stroke: 0.35, dot: false)
        case .accent: pill(colors.secondary, fill: 0.12, stroke: 0.35, dot: false)
        default: EmptyView()
        }
    }

    /// Day pills are decided language-neutrally in Kotlin (TvMatchDay) and worded here, localized.
    private var text: String {
        switch status.day {
        case .today: return L("Today").uppercased()
        case .tomorrow: return L("Tomorrow").uppercased()
        default: return L(status.text)
        }
    }

    private func pill(_ tint: Color, fill: Double, stroke: Double, dot: Bool) -> some View {
        HStack(spacing: dp(4)) {
            if dot { Circle().fill(tint).frame(width: dp(6), height: dp(6)) }
            Text(verbatim: text).font(NuvioType.labelSmall.weight(.bold)).foregroundStyle(tint).lineLimit(1)
        }
        .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
        .background(Capsule().fill(tint.opacity(fill)))
        .overlay(Capsule().stroke(tint.opacity(stroke), lineWidth: dp(1)))
        .fixedSize()
    }
}

/// FeaturedBannerCard (SportsHubScreen.kt:1357-1419): 360×140 artwork, bottom scrim, 3dp focus ring.
private struct FeaturedBannerCard: View {
    let featured: TvSportsFeatured
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let event = featured.event
        let shape = RoundedRectangle(cornerRadius: dp(12), style: .continuous)
        Button(action: action) {
            ZStack(alignment: .bottomLeading) {
                colors.backgroundCard
                CachedPosterArtwork(urlString: event.banner ?? event.badge, width: dp(360), height: dp(140), maximumWidth: dp(360)) { Color.clear }
                LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                if focused { Color.white.opacity(0.12) }
                VStack(alignment: .leading, spacing: 0) {
                    Text(event.title).font(NuvioType.inter(16, .bold)).foregroundStyle(.white).lineLimit(1)
                    Text(featured.matchCount > 0 ? "\(featured.matchCount) upcoming" : "\(event.from) – \(event.to)")
                        .font(NuvioType.labelMedium).foregroundStyle(.white.opacity(0.85))
                }
                .padding(dp(12))
            }
            .frame(width: dp(360), height: dp(140))
            .clipShape(shape)
            .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: dp(3)))
            .hoverEffect(.highlight)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// CategoryTile (SportsHubScreen.kt:1464-1510): 200dp, radius 10, flagship league badge 40dp.
private struct CategoryTile: View {
    let item: TvSportsCategory
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(10), style: .continuous)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                Group {
                    if let badge = item.category.leagues.first?.badge, !badge.isEmpty {
                        BadgeImage(url: badge, size: dp(40))
                    } else {
                        Color.clear
                    }
                }
                .frame(width: dp(40), height: dp(40))
                Spacer().frame(height: dp(8))
                Text(item.category.name).font(NuvioType.titleSmall).foregroundStyle(colors.textPrimary).lineLimit(1)
                Text(item.subtitle).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
            }
            .padding(dp(12))
            .frame(width: dp(200), alignment: .leading)
            .background(shape.fill(focused ? colors.focusBackground : colors.backgroundElevated))
            .overlay(shape.stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? dp(2) : dp(1)))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// SportsFixturesPage: a titled grid of match cards; Menu goes back to the hub.
private struct FixturesPageView: View {
    let page: FixturesPage
    let radar: RadarUiState
    let now: Int64
    let onMatch: (RadarFixture) -> Void
    @Environment(\.nuvio) private var colors

    private var fixtures: [RadarFixture] { page.fixtures }

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(16)) {
                Text(ui: page.title).font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary)
                if fixtures.isEmpty {
                    Text("No upcoming matches.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: SportsMetrics.matchCardWidth), spacing: dp(12))], alignment: .leading, spacing: dp(12)) {
                        ForEach(fixtures, id: \.self) { fixture in
                            MatchCard(fixture: fixture, radar: radar, now: now) { onMatch(fixture) }
                        }
                    }
                    .focusSection()
                }
            }
            .padding(.horizontal, SportsMetrics.gutter).padding(.vertical, dp(24))
        }
        .scrollClipDisabled()
    }
}

/// LeagueFixturesPage (SportsHubScreen.kt:1255-1354): the league's crest (56dp), name and
/// "sport · N upcoming", a Follow toggle, then Live & Upcoming and Recent results. Loads the league on
/// demand, so browsing never requires following.
private struct LeaguePageView: View {
    let league: RadarLeague
    let radar: RadarUiState
    let now: Int64
    let onMatch: (RadarFixture) -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var followFocused: Bool

    var body: some View {
        let page = TvSports.shared.leaguePage(state: radar, league: league, nowMs: now)
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(12)) {
                HStack(spacing: dp(12)) {
                    if let badge = league.badge, !badge.isEmpty { BadgeImage(url: badge, size: dp(56)) }
                    VStack(alignment: .leading, spacing: dp(2)) {
                        Text(league.name).font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary).lineLimit(1)
                        Text(ui: page.subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    }
                    Spacer()
                    Button { TvSports.shared.toggleFollow(league: league) } label: {
                        Text(page.followed ? "★ Following" : "Follow").font(NuvioType.labelLarge)
                            .foregroundStyle(page.followed ? (followFocused ? colors.textPrimary : colors.secondary) : colors.textPrimary)
                            .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
                            .background(RoundedRectangle(cornerRadius: dp(8)).fill(followFocused ? colors.primary : .clear))
                    }
                    .buttonStyle(PlainNoChromeButtonStyle())
                    .focused($followFocused)
                    .reportsFocus(followFocused)
                }
                .focusSection()
                if !page.upcoming.isEmpty { section("Live & Upcoming", page.upcoming) }
                if !page.recent.isEmpty { section("Recent results", page.recent) }
                if page.empty {
                    Text("No scheduled matches right now.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                        .padding(.vertical, dp(12))
                }
                if !page.loaded {
                    HStack(spacing: dp(12)) {
                        ForEach(0..<3, id: \.self) { _ in
                            NuvioShimmer(cornerRadius: dp(12)).frame(width: SportsMetrics.matchCardWidth, height: dp(140))
                        }
                    }
                }
            }
            .padding(.horizontal, SportsMetrics.gutter).padding(.vertical, dp(24))
        }
        .scrollClipDisabled()
        .onAppear { DispatchQueue.main.async { followFocused = true } }
    }

    private func section(_ title: String, _ fixtures: [RadarFixture]) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            Text(ui: title).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).padding(.vertical, dp(8))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: SportsMetrics.matchCardWidth), spacing: dp(12))], alignment: .leading, spacing: dp(12)) {
                ForEach(fixtures, id: \.self) { fixture in
                    MatchCard(fixture: fixture, radar: radar, now: now) { onMatch(fixture) }
                }
            }
            .focusSection()
        }
    }
}

// MARK: - Dialogs

/// FocusableRow (SportsHubScreen.kt:1513-1528): full-width row, radius 8, Primary fill on focus.
private struct FocusableRow<Content: View>: View {
    /// Take focus when it appears (rows that load after the dialog opened would otherwise leave
    /// tvOS with nothing focused).
    var autoFocus = false
    let action: () -> Void
    @ViewBuilder var content: Content
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) { content }
                .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.primary : .clear))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onAppear { if autoFocus { DispatchQueue.main.async { focused = true } } }
    }
}

/// Browse: a category's leagues; OK goes into the league (discovery first — following happens on its
/// page), as NuvioTV's browse dialog does.
private struct CategoryDialog: View {
    let category: RadarCategory
    let onOpen: (RadarLeague) -> Void
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var followed: Set<String> = []

    var body: some View {
        NuvioDialog(title: category.name, subtitle: "Select a league to see its matches") {
            ForEach(category.leagues, id: \.id) { league in
                FocusableRow(action: { onOpen(league) }) {
                    Group {
                        if let badge = league.badge, !badge.isEmpty { BadgeImage(url: badge, size: dp(32)) } else { Color.clear }
                    }
                    .frame(width: dp(32), height: dp(32))
                    Text(league.name).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                    Text(followed.contains(league.id) ? "★ Following" : "›").font(NuvioType.labelLarge)
                        .foregroundStyle(followed.contains(league.id) ? colors.secondary : colors.textSecondary)
                }
            }
        }
        .frame(maxHeight: dp(460))
        .task { for await state in TvSports.shared.state { followed = state.followedLeagueIds } }
        .onExitCommand { dismiss() }
    }
}

/// MatchChannelsOverlay (SportsHubScreen.kt:638-845): the viewer's channels showing this match, in
/// evidence tiers; OK plays the channel.
private struct MatchChannelsSheet: View {
    let fixture: RadarFixture
    let live: Bool
    let onPlay: (TvPlayerSession) -> Void
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var playback: PlaybackCoordinator
    @State private var hasPlaylists = true
    @State private var matching = true
    @State private var failed = false
    @State private var groups: [TvMatchGroup] = []
    @State private var opening = false

    var body: some View {
        NuvioDialog(title: fixture.displayTitle + (live ? "   🔴 LIVE" : ""), subtitle: TvSports.shared.sheetSubtitle(fixture: fixture, whenText: fixture.startEpochMs == nil ? nil : SportsWhen.label(fixture, now: TvSports.shared.nowMs())), width: dp(620)) {
            if !hasPlaylists {
                message("Add an IPTV playlist to find and watch this match on your channels.")
            } else if groups.isEmpty && matching {
                message("Finding channels…")
            } else if groups.isEmpty && failed {
                message("Couldn't load channels from your providers. Please try again.")
            } else if groups.isEmpty {
                message("None of your channels list this match. Matching depends on your playlist's EPG and channel names.")
            } else {
                ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                    if let label = group.label {
                        Text(ui: label).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, dp(4))
                    }
                    ForEach(group.matches, id: \.channel.contentId) { match in
                        FocusableRow(autoFocus: match === groups.first?.matches.first, action: { play(match) }) {
                            CachedPosterArtwork(urlString: match.channel.logo, width: dp(36), height: dp(36), maximumWidth: dp(36)) { colors.backgroundCard }
                                .environment(\.artworkContentMode, .fit)
                                .frame(width: dp(36), height: dp(36))
                                .clipShape(RoundedRectangle(cornerRadius: dp(6)))
                            VStack(alignment: .leading, spacing: dp(2)) {
                                Text(match.channel.name).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                                Text(TvSports.shared.matchDetail(match: match)).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
                            }
                            Spacer(minLength: 0)
                            Text("▶").foregroundStyle(colors.textPrimary)
                        }
                        .disabled(opening)
                    }
                }
                Text("Matched from your channels' EPG, channel names, and broadcaster listings.")
                    .font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.top, dp(12))
            }
        }
        .frame(maxHeight: dp(480))
        .onExitCommand { dismiss() }
        .task {
            hasPlaylists = TvSports.shared.hasPlaylists()
            guard hasPlaylists else { matching = false; return }
            do {
                let matches = try await TvSports.shared.matchChannels(fixture: fixture, onPartial: { partial in
                    groups = TvSports.shared.groupMatches(fixture: fixture, matches: partial)
                })
                groups = TvSports.shared.groupMatches(fixture: fixture, matches: matches)
                smokeLog("SMOKE sports match channels=%d", matches.count)
            } catch {
                failed = true
            }
            matching = false
        }
    }

    private func message(_ text: String) -> some View {
        Text(ui: text).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func play(_ match: RadarChannelMatcher.ChannelMatch) {
        opening = true
        Task {
            if let session = try? await TvSports.shared.playMatch(match: match) {
                onPlay(session)
            } else {
                opening = false
                playback.notify("\(match.channel.name) isn't available right now.")
            }
        }
    }
}


/// A kick-off's when-line ("Today 3:30 AM", "Sat, Oct 3 8:30 AM"), worded in the viewer's language:
/// Kotlin decides the calendar day (TvMatchDay); the words and the date/time format are localized here.
enum SportsWhen {
    static func label(_ fixture: RadarFixture, now: Int64) -> String {
        guard let start = fixture.startEpochMs?.int64Value else { return L("Time TBC") }
        let date = Date(timeIntervalSince1970: TimeInterval(start) / 1000)
        let day: String
        switch TvSports.shared.day(fixture: fixture, nowMs: now) {
        case .today: day = L("Today")
        case .tomorrow: day = L("Tomorrow")
        default: day = format(date, template: "EEEMMMd")
        }
        return "\(day) \(format(date, template: "jmm"))"
    }

    private static func format(_ date: Date, template: String) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = DateFormatter.dateFormat(fromTemplate: template, options: 0, locale: .current)
        return f.string(from: date)
    }
}
