import SwiftUI
import TuvoraCore

// Live game-detail screen (design approved 2026-11-07; research/mocks/sports-game-screen-design.html,
// contract research/sports-mapping/BLOCK_CONTRACT.md). Data is the shared core through TvGameDetail:
// header, live situation line + win probability, by-period linescore, latest plays and the watch row.
// The screen fits one page on purpose: tvOS only scrolls to focusable views, so a taller page of
// plain text would be unreachable. Refresh runs only while this cover is up and the app is active
// (GameDetailRefreshPolicy decides the cadence in Kotlin); Menu/Back dismisses.
//
// Record is omitted: Apple TV has no recording surface. Lineups/stats/standings blocks are not
// drawn yet (the TV layout of the mock keeps the "light" set).

struct GameDetailScreen: View {
    let fixture: RadarFixture
    /// Plays the chosen channel (the Sports screen dismisses this cover first, then starts the player).
    let onPlay: (TvPlayerSession) -> Void
    /// No data for this game, or it has no event id: the existing match sheet takes over.
    let onNoData: () -> Void

    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var playback: PlaybackCoordinator
    @State private var model: TvGameDetailModel?
    @State private var state: TvGameDetailState = TvGameDetailStateLoading.shared
    @State private var matches: [RadarChannelMatcher.ChannelMatch] = []
    @State private var matching = true
    @State private var opening = false
    @State private var showSheet = false

    var body: some View {
        Group {
            switch onEnum(of: state) {
            case .loading:
                VStack(spacing: dp(16)) {
                    ProgressView()
                    Text("Loading game…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let failed):
                NuvioStateMessage(
                    title: L("Couldn't load this game"),
                    message: failed.timedOut ? L("The score service took too long to answer.") : L("The score service didn't answer."),
                    retry: { model?.retry() }
                )
            case .empty:
                Color.clear.onAppear { onNoData() }
            case .loaded(let loaded):
                content(loaded.detail, stale: loaded.stale)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.background.ignoresSafeArea())
        .onExitCommand { dismiss() }
        // The model is made once, here; the state collector follows it (no polling-for-nil loop).
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            if model == nil {
                guard let opened = TvGameDetail.shared.open(fixture: fixture) else { onNoData(); return }
                model = opened
            }
            // Egress rule: refresh lives only while this screen is up and the app is active; leaving or
            // backgrounding cancels this task and with it the loop. Cancellation is not swallowed.
            try? await model?.runWhileVisible()
        }
        .task(id: model.map { ObjectIdentifier($0) }) {
            guard let model else { return }
            for await next in model.state { state = next }
        }
        .task {
            matches = (try? await TvSports.shared.matchChannels(fixture: fixture, onPartial: { matches = $0 })) ?? matches
            matching = false
        }
        .fullScreenCover(isPresented: $showSheet) {
            MatchChannelsSheet(fixture: fixture, live: isLive) { session in
                showSheet = false
                onPlay(session)
            }
            .environment(\.nuvio, colors).environmentObject(playback)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.opacity(0.6).ignoresSafeArea())
            .presentationBackground(.clear)
        }
    }

    private var isLive: Bool {
        if case .loaded(let l) = onEnum(of: state) { return l.detail.state == .live }
        return false
    }

    // MARK: Loaded

    private func content(_ detail: GameDetail, stale: Bool) -> some View {
        VStack(alignment: .leading, spacing: dp(20)) {
            headerView(detail)
            if let situation = situation(detail) {
                Text(situation).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(2)
            }
            if let home = winProbability(detail) { WinProbabilityBar(homePercent: home, home: homeName(detail), away: awayName(detail)) }
            if let ls = linescore(detail) { LinescoreTable(block: ls) }
            watchRow(detail)
            plays(detail)
            Spacer(minLength: 0)
            if stale {
                Text("Updating… showing the last score we got").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            }
        }
        .padding(.horizontal, dp(52)).padding(.vertical, dp(32))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .focusSection()
    }

    @ViewBuilder
    private func headerView(_ detail: GameDetail) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            Text([detail.leagueName, detail.round].compactMap { $0 }.joined(separator: " · "))
                .font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
            switch onEnum(of: detail.header) {
            case .teamsHeader(let h):
                HStack(alignment: .center, spacing: dp(24)) {
                    sideView(h.home, trailing: false)
                    Spacer(minLength: dp(16))
                    statusText(detail)
                    Spacer(minLength: dp(16))
                    sideView(h.away, trailing: true)
                }
            case .versusHeader(let h):
                HStack(alignment: .center, spacing: dp(24)) {
                    sideView(h.a, trailing: false)
                    Spacer(minLength: dp(16))
                    statusText(detail)
                    Spacer(minLength: dp(16))
                    sideView(h.b, trailing: true)
                }
            case .eventHeader(let h):
                Text(h.name).font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary)
                if let s = h.session { Text(s).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
                statusText(detail)
            }
            if let venue = detail.venue, !venue.isEmpty {
                Text(venue).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
            }
        }
    }

    private func sideView(_ side: Side, trailing: Bool) -> some View {
        HStack(spacing: dp(16)) {
            if trailing { score(side) }
            VStack(alignment: trailing ? .trailing : .leading, spacing: dp(2)) {
                Text(side.name).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary).lineLimit(2)
                if let record = side.record { Text(record).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary) }
            }
            if !trailing { score(side) }
        }
    }

    @ViewBuilder
    private func score(_ side: Side) -> some View {
        if let s = side.score { Text(s).font(NuvioType.inter(36, .bold)).foregroundStyle(colors.textPrimary) }
    }

    private func statusText(_ detail: GameDetail) -> some View {
        VStack(spacing: dp(4)) {
            switch detail.state {
            case .live:
                Text(detail.live?.clock ?? detail.live?.periodLabel ?? L("LIVE")).font(NuvioType.labelLargeSemi).foregroundStyle(colors.live)
                if let d = detail.live?.detail { Text(d).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary) }
            case .final: Text("Final").font(NuvioType.labelLargeSemi).foregroundStyle(colors.textSecondary)
            case .postponed: Text("Postponed").font(NuvioType.labelLargeSemi).foregroundStyle(colors.textSecondary)
            default: Text(SportsWhen.label(fixture, now: TvSports.shared.nowMs())).font(NuvioType.labelLargeSemi).foregroundStyle(colors.secondary)
            }
        }
    }

    // MARK: Watch row

    private func watchRow(_ detail: GameDetail) -> some View {
        // Watching (a live channel is already open) switches; browsing plays the best matched channel.
        let watching = playback.session?.session.title
        let best = matches.first
        return HStack(spacing: dp(16)) {
            if let watching {
                GameActionButton(title: L("Switch"), subtitle: L("Now on \(watching)"), autoFocus: true) { showSheet = true }
            } else if let best {
                GameActionButton(title: L("Play"), subtitle: best.channel.name, autoFocus: true) { play(best) }
                    .disabled(opening)
                if matches.count > 1 { GameActionButton(title: L("More channels"), subtitle: nil, autoFocus: false) { showSheet = true } }
            } else {
                GameActionButton(title: matching ? L("Finding channels…") : L("Find channels"), subtitle: nil, autoFocus: true) { showSheet = true }
            }
        }
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

    // MARK: Blocks

    private func situation(_ d: GameDetail) -> String? {
        for b in d.blocks { if case .situationBlock(let s) = onEnum(of: b) { return s.line } }
        return nil
    }

    private func winProbability(_ d: GameDetail) -> Int? {
        for b in d.blocks { if case .winProbabilityBlock(let w) = onEnum(of: b) { return Int(w.homePercent) } }
        return nil
    }

    private func linescore(_ d: GameDetail) -> LinescoreBlock? {
        for b in d.blocks { if case .linescoreBlock(let l) = onEnum(of: b) { return l } }
        return nil
    }

    private func homeName(_ d: GameDetail) -> String {
        switch onEnum(of: d.header) {
        case .teamsHeader(let h): return h.home.short ?? h.home.name
        case .versusHeader(let h): return h.a.short ?? h.a.name
        default: return ""
        }
    }

    private func awayName(_ d: GameDetail) -> String {
        switch onEnum(of: d.header) {
        case .teamsHeader(let h): return h.away.short ?? h.away.name
        case .versusHeader(let h): return h.b.short ?? h.b.name
        default: return ""
        }
    }

    @ViewBuilder
    private func plays(_ d: GameDetail) -> some View {
        let items: [TimelineItem] = d.blocks.compactMap { b in
            if case .timelineBlock(let t) = onEnum(of: b) { return t.items }
            return nil
        }.first ?? []
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: dp(6)) {
                Text("Latest plays").font(NuvioType.labelLargeSemi).foregroundStyle(colors.textSecondary)
                // Newest last in the contract's order; show the five most recent, newest first.
                ForEach(Array(items.suffix(5).reversed().enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: dp(12)) {
                        Text(item.time).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).frame(width: dp(64), alignment: .leading)
                        Text(item.text).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    }
                }
            }
        }
    }
}

// MARK: - Pieces

private struct WinProbabilityBar: View {
    let homePercent: Int
    let home: String
    let away: String
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            HStack {
                Text("\(home) \(homePercent)%").font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary)
                Spacer()
                Text("\(100 - homePercent)% \(away)").font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(colors.backgroundCard)
                    Capsule().fill(colors.secondary).frame(width: geo.size.width * CGFloat(max(0, min(100, homePercent))) / 100)
                }
            }
            .frame(height: dp(8))
        }
        .accessibilityElement(children: .combine)
    }
}

private struct LinescoreTable: View {
    let block: LinescoreBlock
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            row(label: "", cells: block.columns, header: true)
            ForEach(Array(block.rows.enumerated()), id: \.offset) { index, cells in
                row(label: index < block.rowLabels.count ? block.rowLabels[index] : "", cells: cells, header: false)
            }
        }
    }

    private func row(label: String, cells: [String], header: Bool) -> some View {
        HStack(spacing: 0) {
            Text(label).frame(width: dp(140), alignment: .leading).lineLimit(1)
            ForEach(Array(cells.enumerated()), id: \.offset) { _, c in Text(c).frame(minWidth: dp(44)) }
            Spacer(minLength: 0)
        }
        .font(header ? NuvioType.labelMedium : NuvioType.bodyMedium)
        .foregroundStyle(header ? colors.textTertiary : colors.textPrimary)
    }
}

/// Primary / secondary action of the watch row: FocusBackground fill + FocusRing when focused.
private struct GameActionButton: View {
    let title: String
    let subtitle: String?
    let autoFocus: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(2)) {
                Text(title).font(NuvioType.titleMediumSemi).foregroundStyle(focused ? colors.primary : colors.textPrimary)
                if let subtitle { Text(subtitle).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1) }
            }
            .padding(.horizontal, dp(20)).padding(.vertical, dp(12))
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(focused ? colors.focusBackground : colors.backgroundElevated))
            .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? dp(2) : dp(1)))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onAppear { if autoFocus { DispatchQueue.main.async { focused = true } } }
    }
}
