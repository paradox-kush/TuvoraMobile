import SwiftUI
import TuvoraCore

// Live game-detail screen. Redesign 2026-11-14 (research/game-screen-redesign-2026-11/02-phase-a-frame.md A6; visual
// target mocks/tv-four-sports-animated.html). Two columns like Android TV: the left column is the phone's Overview
// (scoreboard with rank and colours, one-line last play, win-chance card, Play row); the right column is the section
// chips + one panel. EVERY decision (capping, formatting, colours, what changed since the last snapshot) is made in
// shared Kotlin (TvGameScreenModel); this file only lays out and animates. Nothing loops: each update animates the
// net change once, and reduce motion sets the values without animation.
//
// The screen fits one page on purpose: tvOS only scrolls to focusable views. Refresh runs only while this cover is up
// and the app is active (GameDetailRefreshPolicy decides the cadence in Kotlin); Menu/Back dismisses.
//
// Record is omitted: Apple TV has no recording surface.

/// Focus targets of the screen: the Play row (default) and the section chip row are two focus sections.
private enum GameFocus: Hashable {
    case play, more, reveal, chip(Int)
}

struct GameDetailScreen: View {
    let fixture: RadarFixture
    /// Plays the chosen channel (the Sports screen dismisses this cover first, then starts the player).
    let onPlay: (TvPlayerSession) -> Void
    /// No data for this game, or it has no event id: the existing match sheet takes over.
    let onNoData: () -> Void

    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var playback: PlaybackCoordinator
    @State private var model: TvGameDetailModel?
    @State private var state: TvGameDetailState = TvGameDetailStateLoading.shared
    /// What the Loaded state draws; recomputed by Kotlin on each snapshot.
    @State private var screen: TvGameScreenModel?
    /// The snapshot before this one, so Kotlin can say what really changed. Nil on open and after a resume: no animation then.
    @State private var previous: GameDetail?
    /// The chip the user picked, by SECTION (not slot): the chip list can gain entries before it (kickoff) without moving the view.
    @State private var selectedSection: TvSection?
    /// One entry per side whose score just changed; each clears itself after PULSE_MS (two moments in one update keep both).
    @State private var pulseSides: Set<String> = []
    /// One-shot emphasis on the strips (touchdown end zone, loss border, first-down glow, foul pop), each cleared after PULSE_MS.
    @State private var flashes: Set<StripFlash> = []
    /// The Plays row / last-play card that just arrived: only that one rises in; the rest of the list never moves.
    @State private var freshPlayKey: String?
    /// The ball slides only for a plain field move; a change of possession must not slide it across the field (spec 03 B1).
    @State private var fieldSlides = false
    /// Hide scores is on: the scoreboard numbers and spoiler sections are withheld by the model; the chip reveals.
    @State private var scoresHidden = false
    @State private var hideAgain = false
    @State private var hiddenCard = false
    @State private var matches: [RadarChannelMatcher.ChannelMatch] = []
    @State private var matching = true
    @State private var opening = false
    @State private var showSheet = false
    @FocusState private var focus: GameFocus?

    var body: some View {
        Group {
            switch onEnum(of: state) {
            case .loading:
                VStack(spacing: dp(16)) {
                    ProgressView()
                    Text("Loading game…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    // A focus target while loading: Menu still leaves, and the remote is never dead.
                    Button { dismiss() } label: {
                        Text("Back").font(NuvioType.labelLargeSemi).foregroundStyle(colors.textPrimary)
                            .padding(.horizontal, dp(24)).padding(.vertical, dp(10))
                            .background(Capsule().fill(colors.backgroundCard))
                    }
                    .buttonStyle(PlainNoChromeButtonStyle())
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
                if let screen {
                    content(screen, detail: loaded.detail)
                } else {
                    Color.clear
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.background.ignoresSafeArea())
        // New game, new identity (focus, selection, animation state all reset).
        .id(fixture.id ?? "game")
        .onExitCommand { dismiss() }
        // The model is made once, here; the state collector follows it (no polling-for-nil loop).
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            #if DEBUG
            // Simulator passes without a live game: `-gameDemo football|soccer` plays three canned snapshots (debug builds only).
            if let kind = Self.demoKind { await runDemo(kind); return }
            #endif
            if model == nil {
                guard let opened = TvGameDetail.shared.open(fixture: fixture) else { onNoData(); return }
                model = opened
            }
            // Egress rule: refresh lives only while this screen is up and the app is active; leaving or
            // backgrounding cancels this task and with it the loop. Cancellation is not swallowed.
            try? await model?.runWhileVisible()
        }
        // The first snapshot after a resume is a fresh start: nothing animates for it.
        .onChange(of: scenePhase) { _, _ in previous = nil }
        .task(id: model.map { ObjectIdentifier($0) }) {
            guard let model else { return }
            for await next in model.state {
                state = next
                if case .loaded(let l) = onEnum(of: next) { apply(l.detail, stale: l.stale) }
            }
        }
        .task(id: model.map { ObjectIdentifier($0) }) {
            // Hide scores / score delay: local redraw (settings change, due snapshot); lives with this screen.
            guard let model else { return }
            await withTaskGroup(of: Void.self) { group in
                group.addTask { try? await model.runScoreSpoilerWhileVisible() }
                group.addTask { @MainActor in
                    for await next in model.spoiler {
                        scoresHidden = next.hidden
                        hideAgain = next.hideAgain
                        hiddenCard = next.hiddenCard
                    }
                }
            }
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

    #if DEBUG
    private static var demoKind: String? {
        // The kind can also be switched without relaunching: write it to /tmp/tuvora-game-demo.txt (simulator reads host files).
        if let file = try? String(contentsOfFile: "/tmp/tuvora-game-demo.txt", encoding: .utf8) {
            let kind = file.trimmingCharacters(in: .whitespacesAndNewlines)
            if !kind.isEmpty { return kind }
        }
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-gameDemo"), args.indices.contains(i + 1) else { return nil }
        return args[i + 1]
    }

    /// Three snapshots, nine seconds apart, then stops: exercises each moment once.
    private func runDemo(_ kind: String) async {
        // `-gameDemoHidden`: the canned game as Hide scores shows it (the reveal chip's focus walk; no spoiler model here).
        if AppArguments.list.contains("-gameDemoHidden") { scoresHidden = true }
        for step in 0..<3 {
            TvGameScreenDemo.shared.enabled = true          // debug builds only (this whole block is #if DEBUG)
            guard let detail = TvGameScreenDemo.shared.detail(kind: kind, step: Int32(step)) else { return }
            state = TvGameDetailStateLoaded(detail: detail, stale: false)
            apply(detail, stale: false)
            try? await Task.sleep(nanoseconds: 9_000_000_000)
            if Task.isCancelled { return }
        }
    }
    #endif

    private var isLive: Bool {
        if case .loaded(let l) = onEnum(of: state) { return l.detail.state == .live }
        return false
    }

    // MARK: Snapshot -> screen

    /// One snapshot: Kotlin builds the view-model and the moments. The model is assigned WITHOUT a transaction, so nothing moves
    /// unless a view is tied to its own moment (bars, numbers, the new row, the strips); lists and cards never animate as a whole.
    private func apply(_ detail: GameDetail, stale: Bool) {
        let next = TvGameScreenModel.companion.from(
            detail: detail, nowMs: TvSports.shared.nowMs(), hasPreview: false, previous: previous, stale: stale)
        previous = detail
        let kinds = Set(next.moments.map { $0.kind })
        fieldSlides = !reduceMotion && kinds.contains(.fieldMove) && !kinds.contains(.possessionChange)
        freshPlayKey = reduceMotion ? nil : next.moments.first(where: { $0.kind == .newPlay })?.key
        if let fresh = freshPlayKey {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: GameMotion.pulseNanos)
                if freshPlayKey == fresh { freshPlayKey = nil }   // a later tab switch must not replay the entrance
            }
        }
        screen = next
        if !reduceMotion {
            for moment in next.moments {
                switch moment.kind {
                case .scoreChange: if let side = moment.side { pulse(side) }
                case .touchdown: flashOnce(.touchdown(moment.side ?? "home"))
                case .foulCount: flashOnce(.foul(moment.side ?? "home"))
                case .fieldMove where moment.loss: flashOnce(.loss)
                case .fieldMove where moment.firstDown: flashOnce(.firstDown)
                default: break
                }
            }
        }
        // Only a score change is announced, at most once per update.
        if let line = next.announcement { UIAccessibility.post(notification: .announcement, argument: line) }
        // The chip list may have gained or lost entries: keep the picked SECTION (or fall back to the first chip), and keep the
        // focus on that section's chip rather than on whatever now sits in the old slot.
        let index = Int(TvSectionSelection.shared.indexOf(chips: next.chips, selected: selectedSection))
        selectedSection = next.chips.isEmpty ? nil : next.chips[index].section
        if case .chip = focus { focus = next.chips.isEmpty ? .play : .chip(index) }
    }

    /// The score pops once (scale + accent) and settles: one shot, never repeating. Per side: two goals in one update both pulse.
    private func pulse(_ side: String) {
        withAnimation(GameMotion.pulse) { _ = pulseSides.insert(side) }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: GameMotion.pulseNanos)
            withAnimation(GameMotion.pulse) { _ = pulseSides.remove(side) }
        }
    }

    /// A strip flash lasts one pulse and is cleared: one shot, never repeating. Per kind: several can be on at once.
    private func flashOnce(_ value: StripFlash) {
        withAnimation(GameMotion.pulse) { _ = flashes.insert(value) }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: GameMotion.pulseNanos)
            withAnimation(GameMotion.pulse) { _ = flashes.remove(value) }
        }
    }

    // MARK: Loaded

    private func content(_ screen: TvGameScreenModel, detail: GameDetail) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            topBar(screen.scoreboard)
            HStack(alignment: .top, spacing: dp(18)) {
                leftColumn(screen)
                    .frame(width: dp(450), alignment: .topLeading)
                rightColumn(screen)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            if let chip = screen.updatedLabel {
                Text(chip).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            }
        }
        .padding(.horizontal, dp(40)).padding(.vertical, dp(22))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func topBar(_ sb: TvScoreboard) -> some View {
        HStack(spacing: dp(12)) {
            Text(sb.leagueLine).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
            if let venue = sb.venue {
                Text("· \(venue)").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: Left column

    private func leftColumn(_ screen: TvGameScreenModel) -> some View {
        VStack(alignment: .leading, spacing: dp(7)) {
            ScoreboardCard(board: screen.scoreboard, pulseSides: pulseSides, reduceMotion: reduceMotion, upcomingLabel: upcomingLabel, field: screen.field, possession: screen.basketball?.possession)
            if scoresHidden || hideAgain { revealScoresChip }
            if let field = screen.field { FieldStripCard(strip: field, flashes: flashes, slides: fieldSlides) }
            if let soccer = screen.soccer { SoccerStripCard(strip: soccer, board: screen.scoreboard, animated: !reduceMotion) }
            if let basketball = screen.basketball { BasketballStripCard(strip: basketball, board: screen.scoreboard, flashes: flashes, animated: !reduceMotion) }
            if let hockey = screen.hockey { HockeyStripCard(strip: hockey, board: screen.scoreboard, animated: !reduceMotion) }
            if screen.leftFit.showLastPlay, let play = screen.lastPlay { LastPlayCard(play: play, fresh: freshPlayKey == play.key) }
            if let win = screen.winCard { WinChanceCard(card: win, showChart: screen.leftFit.showChart, animated: !reduceMotion) }
            Spacer(minLength: 0)
            watchRow()
                .focusSection()
                // The chips sit top-right, out of the focus engine's geometric reach from the bottom-left Play row:
                // Up goes to the selected chip explicitly (Right also reaches them through the right section).
                // With scores hidden, Up reaches "Show this game's score" first (it sits above the row, left column).
                .onMoveCommand { direction in
                    guard direction == .up else { return }
                    if scoresHidden || hideAgain { focus = .reveal } else if !screenChips.isEmpty { focus = .chip(selectedIndex) }
                }
        }
    }

    /// "Scores hidden": flips the same global setting as the hub chip, so the user can reveal without leaving.
    /// Per game only ("Show this game's score" / "Hide again"); the hub's chip owns the global setting.
    private var revealScoresChip: some View {
        RevealScoresChip(hideAgain: hideAgain, gameKey: TvSportsHubPolicy.shared.fixtureKey(fixture: fixture), focus: $focus)
            // Right reaches the section chips; Down returns to the Play row.
            .onMoveCommand { direction in
                if direction == .right, !screenChips.isEmpty { focus = .chip(selectedIndex) }
                if direction == .down { focus = .play }
            }
    }

    private var screenChips: [TvChip] { screen?.chips ?? [] }
    private var selectedIndex: Int { Int(TvSectionSelection.shared.indexOf(chips: screenChips, selected: selectedSection)) }

    private var upcomingLabel: String { SportsWhen.label(fixture, now: TvSports.shared.nowMs()) }

    private func watchRow() -> some View {
        // Watching (a live channel is already open) switches; browsing plays the best matched channel.
        let watching = playback.session?.session.title
        let best = matches.first
        // One label policy for every sport: "Play / <channel>" when a channel matched, "Find channels" only when none did.
        let label = TvWatchActionPolicy.shared.label(watching: watching, bestChannel: best?.channel.name, matching: matching)
        return HStack(spacing: dp(10)) {
            if watching == nil, let best {
                GameActionButton(title: L(label.title), subtitle: label.subtitle, target: .play, focus: $focus) { play(best) }
                    .disabled(opening)
                if matches.count > 1 { GameActionButton(title: L("More channels"), subtitle: nil, target: .more, focus: $focus) { showSheet = true } }
            } else {
                GameActionButton(title: L(label.title), subtitle: label.subtitle, target: .play, focus: $focus) { showSheet = true }
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

    // MARK: Right column

    @ViewBuilder
    private func rightColumn(_ screen: TvGameScreenModel) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            if !screen.chips.isEmpty {
                chipRow(screen.chips)
                panel(screen)
                    .id(selectedSection)
                    .transition(.opacity)
                    .animation(reduceMotion ? nil : GameMotion.quick, value: selectedSection)
            } else {
                fallbackCards(screen)
            }
            Spacer(minLength: 0)
        }
        // The whole column is one focus section (full height), so Right from the Play row lands on the chips.
        .frame(maxHeight: .infinity, alignment: .topLeading)
        .focusSection()
    }

    private func chipRow(_ chips: [TvChip]) -> some View {
        HStack(spacing: dp(6)) {
            ForEach(Array(chips.enumerated()), id: \.offset) { index, chip in
                SectionChip(title: chip.title, selected: selectedIndex == index, target: .chip(index), focus: $focus)
            }
        }
        // Focusing a chip selects it (no OK press); OK does nothing.
        .onChange(of: focus) { _, now in
            if case .chip(let index) = now, chips.indices.contains(index), chips[index].section != selectedSection { selectedSection = chips[index].section }
        }
        // Down from any chip goes back to Play (nothing is below the chips in the right column).
        .onMoveCommand { direction in if direction == .down { focus = .play } }
    }

    @ViewBuilder
    private func panel(_ screen: TvGameScreenModel) -> some View {
        let section = TvSectionSelection.shared.resolve(chips: screen.chips, selected: selectedSection) ?? .plays
        switch section {
        case .plays: PlaysPanel(rows: screen.panels.plays, freshKey: freshPlayKey)
        case .stats: StatsPanel(rows: screen.panels.stats, byPeriod: screen.panels.byPeriod, board: screen.scoreboard, animated: !reduceMotion)
        case .leaders: LeadersPanel(rows: screen.panels.leaders, form: screen.panels.form)
        case .lineups:
            if let pitch = screen.pitch { PitchView(pitch: pitch) } else { LineupsPanel(sides: screen.panels.lineups) }
        case .standings: StandingsPanel(groups: screen.panels.standings)
        }
    }

    /// No sections at all (F1, UFC, tennis today): the right column keeps the situation line and the by-period table.
    @ViewBuilder
    private func fallbackCards(_ screen: TvGameScreenModel) -> some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            if hiddenCard && screen.situationLine == nil && screen.panels.byPeriod == nil {
                // Redaction left nothing to show: say why instead of an empty column (the reveal chip is on the left).
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text("Scores are hidden").font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary)
                    Text("Results, stats and plays for this game stay hidden until you choose to see them.")
                        .font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                }
            }
            if let line = screen.situationLine {
                Text(line).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(2)
            }
            if let table = screen.panels.byPeriod { ByPeriodTable(table: table) }
        }
    }
}

// MARK: - Left column pieces

private func teamColor(_ hex: String?, fallback: Color) -> Color {
    guard let hex, hex.count == 6, let v = UInt32(hex, radix: 16) else { return fallback }
    return Color(argb: 0xFF00_0000 | v)
}

private struct RevealScoresChip: View {
    let hideAgain: Bool
    let gameKey: String
    var focus: FocusState<GameFocus?>.Binding
    @Environment(\.nuvio) private var colors
    private var focused: Bool { focus.wrappedValue == .reveal }

    var body: some View {
        Button {
            if hideAgain { TvSports.shared.hideGame(id: gameKey) } else { TvSports.shared.revealGame(id: gameKey) }
        } label: {
            Label(hideAgain ? "Hide again" : "Show this game's score", systemImage: hideAgain ? "eye.slash" : "eye")
                .font(NuvioType.labelMedium)
                .foregroundStyle(focused ? colors.primary : colors.textSecondary)
                .padding(.horizontal, dp(14)).padding(.vertical, dp(6))
                .background(Capsule().fill(focused ? colors.focusBackground : colors.backgroundCard))
                .overlay(Capsule().stroke(focused ? colors.focusRing : Color.clear, lineWidth: dp(2)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused(focus, equals: .reveal)
        .reportsFocus(focused)
        .accessibilityIdentifier("game.revealScore")
    }
}

private struct ScoreboardCard: View {
    let board: TvScoreboard
    let pulseSides: Set<String>
    var reduceMotion = false
    let upcomingLabel: String
    var field: TvFieldStrip? = nil
    /// Basketball: a small dot beside the name of the team with the ball ("home" | "away").
    var possession: String? = nil
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(spacing: dp(4)) {
            if let home = board.home, let away = board.away {
                HStack(alignment: .center, spacing: dp(8)) {
                    sideView(home, side: "home", trailing: false)
                    Spacer(minLength: dp(6))
                    status
                    Spacer(minLength: dp(6))
                    sideView(away, side: "away", trailing: true)
                }
            } else {
                VStack(alignment: .leading, spacing: dp(4)) {
                    if let name = board.eventName { Text(name).font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary) }
                    if let s = board.eventSession { Text(s).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
                    status
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
        .background(RoundedRectangle(cornerRadius: dp(14)).fill(colors.backgroundCard))
    }

    private func sideView(_ s: TvScoreSide, side: String, trailing: Bool) -> some View {
        let tint = teamColor(s.colorHex, fallback: trailing ? colors.textTertiary : colors.secondary)
        let pulsing = pulseSides.contains(side)
        return HStack(spacing: dp(8)) {
            if trailing { scoreText(s, pulsing: pulsing) }
            VStack(alignment: trailing ? .trailing : .leading, spacing: dp(2)) {
                HStack(spacing: dp(4)) {
                    if trailing == false { badge(s, tint: tint) }
                    VStack(alignment: trailing ? .trailing : .leading, spacing: 0) {
                        HStack(spacing: dp(3)) {
                            if let rank = s.rank?.intValue {
                                Text("\(rank)").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                            }
                            if possession == side && !trailing { Circle().fill(colors.secondary).frame(width: dp(7), height: dp(7)) }
                            Text(s.shortName).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
                            if possession == side && trailing { Circle().fill(colors.secondary).frame(width: dp(7), height: dp(7)) }
                        }
                        if let record = s.record { Text(record).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1) }
                        if let dots = timeoutDots(side) { dots }
                        ForEach(Array(s.scorers.enumerated()), id: \.offset) { _, line in
                            Text(line).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1)
                        }
                    }
                    if trailing { badge(s, tint: tint) }
                }
            }
            if !trailing { scoreText(s, pulsing: pulsing) }
        }
    }

    /// Three dots per team under the name; filled = timeouts left (football).
    private func timeoutDots(_ side: String) -> AnyView? {
        guard let field, let left = (side == "home" ? field.homeTimeouts : field.awayTimeouts)?.intValue else { return nil }
        return AnyView(HStack(spacing: dp(3)) {
            ForEach(0..<3, id: \.self) { i in Circle().fill(i < left ? colors.secondary : colors.border).frame(width: dp(6), height: dp(6)) }
        })
    }

    private func badge(_ s: TvScoreSide, tint: Color) -> some View {
        ZStack {
            Circle().fill(tint)
            if let url = s.badgeUrl.flatMap(URL.init(string:)) {
                AsyncImage(url: url) { image in image.resizable().scaledToFit().padding(dp(4)) } placeholder: { Color.clear }
            } else {
                Text(String(s.shortName.prefix(3))).font(NuvioType.labelSmall).foregroundStyle(.white)
            }
        }
        .frame(width: dp(34), height: dp(34))
    }

    @ViewBuilder
    private func scoreText(_ s: TvScoreSide, pulsing: Bool) -> some View {
        if let score = s.score {
            Text(score)
                .font(NuvioType.inter(34, .bold))
                .foregroundStyle(pulsing ? colors.secondary : colors.textPrimary)
                .contentTransition(.numericText())
                .animation(reduceMotion ? nil : GameMotion.count, value: score)
                .scaleEffect(pulsing ? 1.25 : 1)
                .frame(minWidth: dp(34))
        }
    }

    @ViewBuilder
    private var status: some View {
        VStack(spacing: dp(2)) {
            if let main = board.statusMain {
                if board.isLive {
                    Text(L("LIVE")).font(NuvioType.labelSmall).foregroundStyle(colors.live)
                        .padding(.horizontal, dp(6)).padding(.vertical, dp(1))
                        .background(Capsule().fill(colors.live.opacity(0.14)))
                }
                Text(main).font(NuvioType.labelLargeSemi).foregroundStyle(board.isLive ? colors.textPrimary : colors.textSecondary).lineLimit(1)
                if let sub = board.statusSub { Text(sub).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1) }
            } else {
                Text(upcomingLabel).font(NuvioType.labelLargeSemi).foregroundStyle(colors.secondary)
            }
        }
    }
}

/// The newest play on one line. A new play fades and rises in once (the id changes with the play).
private struct LastPlayCard: View {
    let play: TvLastPlay
    var fresh = false
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(6)) {
            Text(play.time).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
            if let tag = play.tag { Text(tag).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary) }
            Text(play.text).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1).truncationMode(.tail)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(6))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
        .id(play.key)
        .modifier(FreshEntrance(fresh: fresh))
    }
}

/// Win chance as a market-implied probability, always credited (Polymarket's display condition): caption and the one extra
/// line come from shared Kotlin. Bar and chart animate to the new value once per update. No odds, prices or links.
private struct WinChanceCard: View {
    let card: TvWinCard
    var showChart: Bool = true
    var animated = true
    @Environment(\.nuvio) private var colors

    var body: some View {
        let chance = card.chance
        let home = Int(chance.homePercent), away = Int(chance.awayPercent), draw = chance.drawPercent?.intValue
        let homeTint = teamColor(card.homeColorHex, fallback: colors.secondary)
        let awayTint = teamColor(card.awayColorHex, fallback: colors.textTertiary)
        VStack(alignment: .leading, spacing: dp(5)) {
            HStack {
                Text("WIN CHANCE").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                Spacer(minLength: dp(8))
                if let caption = chance.caption { Text(verbatim: caption).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1) }
            }
            HStack(alignment: .firstTextBaseline) {
                pct(card.homeName, home, leader: home >= away, tint: homeTint)
                Spacer(minLength: 0)
                if let draw {
                    Text("Draw \(draw)%").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                    Spacer(minLength: 0)
                }
                pct(card.awayName, away, leader: away > home, tint: awayTint, trailing: true)
            }
            bar(home: home, draw: draw ?? 0, away: away, homeTint: homeTint, awayTint: awayTint)
                .animation(animated ? GameMotion.move : nil, value: home)
            if showChart, card.chart.count >= 2 {
                WinChart(points: card.chart.map { $0.floatValue }, tint: card.leaderSide == "home" ? homeTint : awayTint, reference: CGFloat(card.reference),
                         markers: card.markers, dividers: card.dividers, homeTint: homeTint, awayTint: awayTint, animated: animated)
            }
            if let line = chance.line {
                Text(verbatim: line).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
            }
            if let stale = chance.staleLabel {
                Text(verbatim: stale).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
        .background(RoundedRectangle(cornerRadius: dp(14)).fill(colors.backgroundCard))
        .accessibilityElement(children: .combine)
    }

    private func pct(_ name: String, _ value: Int, leader: Bool, tint: Color, trailing: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: dp(4)) {
            if trailing { Text("\(value)%").font(leader ? NuvioType.inter(20, .bold) : NuvioType.labelLargeSemi).foregroundStyle(leader ? tint : colors.textSecondary).contentTransition(.numericText()).animation(animated ? GameMotion.count : nil, value: value) }
            Text(name).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
            if !trailing { Text("\(value)%").font(leader ? NuvioType.inter(20, .bold) : NuvioType.labelLargeSemi).foregroundStyle(leader ? tint : colors.textSecondary).contentTransition(.numericText()).animation(animated ? GameMotion.count : nil, value: value) }
        }
    }

    private func bar(home: Int, draw: Int, away: Int, homeTint: Color, awayTint: Color) -> some View {
        GeometryReader { geo in
            let total = max(1, CGFloat(home + draw + away))
            let gap = dp(2)
            HStack(spacing: gap) {
                Capsule().fill(homeTint).frame(width: max(0, (geo.size.width - gap * 2) * CGFloat(home) / total))
                if draw > 0 { Capsule().fill(colors.border).frame(width: max(0, (geo.size.width - gap * 2) * CGFloat(draw) / total)) }
                Capsule().fill(awayTint).frame(maxWidth: .infinity)
            }
        }
        .frame(height: dp(6))
    }
}

/// Home's win chance over time. The newest segment draws itself in (animatable point count); scoring moments are hollow dots
/// ON the line (placed by core from the feed's wall clock, never estimated) with a label above or below; period dividers are
/// faint lines with labels under the chart. A new marker scales in once.
private struct WinChart: View {
    let points: [Float]
    let tint: Color
    /// Dashed reference line in 0..1 plot space (50 % two-way, an even third three-way), from core.
    var reference: CGFloat = 0.5
    var markers: [TvChartMarker] = []
    var dividers: [TvChartDivider] = []
    var homeTint: Color = .white
    var awayTint: Color = .white
    var animated = true
    @Environment(\.nuvio) private var colors

    private let inset = dp(10)

    var body: some View {
        VStack(spacing: dp(1)) {
            GeometryReader { geo in
                let w = geo.size.width, h = geo.size.height
                ZStack(alignment: .topLeading) {
                    Path { p in
                        let y = inset + (h - inset * 2) * (1 - reference)
                        p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: w, y: y))
                    }
                    .stroke(colors.border, style: StrokeStyle(lineWidth: 1, dash: [dp(3), dp(3)]))
                    .opacity(0.8)
                    ForEach(Array(dividers.enumerated()), id: \.offset) { _, d in
                        Rectangle().fill(colors.border.opacity(0.6)).frame(width: dp(1), height: h)
                            .position(x: w * CGFloat(d.x), y: h / 2)
                    }
                    ChartLine(points: points, shown: Double(points.count))
                        .stroke(tint, style: StrokeStyle(lineWidth: dp(2.5), lineCap: .round, lineJoin: .round))
                        .padding(.vertical, inset)
                        .animation(animated ? GameMotion.count : nil, value: points.count)
                    ForEach(markers, id: \.key) { m in
                        let color = teamColor(m.colorHex, fallback: tint)
                        let y = inset + (h - inset * 2) * (1 - CGFloat(m.y))
                        ZStack {
                            Circle().fill(colors.backgroundCard).frame(width: dp(8), height: dp(8))
                                .overlay(Circle().stroke(color, lineWidth: dp(2)))
                                .position(x: w * CGFloat(m.x), y: y)
                            Text(m.label).font(NuvioType.labelSmall).foregroundStyle(color).fixedSize()
                                .position(x: w * CGFloat(m.x), y: m.labelAbove ? y - dp(12) : y + dp(12))
                        }
                        .transition(.scale.combined(with: .opacity))
                    }
                }
                .animation(animated ? GameMotion.quick : nil, value: markers.map(\.key))
            }
            .frame(height: dp(48))
            if !dividers.isEmpty {
                GeometryReader { geo in
                    ForEach(Array(dividers.enumerated()), id: \.offset) { _, d in
                        Text(d.label).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).fixedSize()
                            .position(x: geo.size.width * CGFloat(d.x), y: dp(6))
                    }
                }
                .frame(height: dp(12))
            }
        }
    }
}

private struct ChartLine: Shape {
    let points: [Float]
    /// How many points are drawn; a fractional value draws part of the newest segment.
    var shown: Double
    var animatableData: Double {
        get { shown }
        set { shown = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard points.count >= 2 else { return path }
        func pt(_ i: Int) -> CGPoint {
            CGPoint(x: rect.minX + rect.width * CGFloat(i) / CGFloat(points.count - 1), y: rect.maxY - rect.height * CGFloat(points[i]))
        }
        path.move(to: pt(0))
        let whole = min(points.count - 1, Int(shown.rounded(.down)) - 1)
        if whole >= 1 { for i in 1...whole { path.addLine(to: pt(i)) } }
        let frac = shown - Double(whole + 1)
        if whole + 1 < points.count, frac > 0 {
            let a = pt(max(0, whole)), b = pt(whole + 1)
            path.addLine(to: CGPoint(x: a.x + (b.x - a.x) * CGFloat(frac), y: a.y + (b.y - a.y) * CGFloat(frac)))
        }
        return path
    }
}

// MARK: - Right column pieces

private struct SectionChip: View {
    let title: String
    let selected: Bool
    let target: GameFocus
    var focus: FocusState<GameFocus?>.Binding
    @Environment(\.nuvio) private var colors

    var body: some View {
        let focused = focus.wrappedValue == target
        Button {} label: {
            Text(title).font(NuvioType.labelLargeSemi).lineLimit(1).fixedSize()
                .foregroundStyle(focused ? colors.primary : (selected ? colors.secondary : colors.textSecondary))
                .padding(.horizontal, dp(11)).padding(.vertical, dp(6))
                .background(Capsule().fill(focused ? colors.focusBackground : (selected ? colors.secondary.opacity(0.16) : colors.backgroundElevated)))
                .overlay(Capsule().stroke(focused ? colors.focusRing : Color.clear, lineWidth: dp(2)))
                .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused(focus, equals: target)
        .reportsFocus(focused)
    }
}

private struct PlaysPanel: View {
    let rows: [TvPlayRow]
    var freshKey: String?
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.element.key) { index, row in
                if let period = row.period, index == 0 || rows[index - 1].period != period {
                    Text(period.uppercased()).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                        .padding(.top, index == 0 ? 0 : dp(8)).padding(.bottom, dp(2))
                }
                HStack(alignment: .firstTextBaseline, spacing: dp(8)) {
                    Text(row.time).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).frame(width: dp(48), alignment: .leading)
                    if let tag = row.tag { Text(tag).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary) }
                    if row.ppg {
                        Text("PPG").font(NuvioType.labelSmall).foregroundStyle(colors.onSecondary)
                            .padding(.horizontal, dp(5)).background(Capsule().fill(colors.secondary))
                    }
                    Text(row.text).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                    Spacer(minLength: 0)
                    if let score = row.score { Text(score).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary) }
                }
                .padding(.vertical, dp(3))
                .modifier(FreshEntrance(fresh: row.key == freshKey))
            }
        }
    }
}

private struct StatsPanel: View {
    let rows: [TvStatRowView]
    let byPeriod: TvByPeriod?
    let board: TvScoreboard
    var animated = true
    @Environment(\.nuvio) private var colors

    var body: some View {
        let homeTint = teamColor(board.home?.colorHex, fallback: colors.secondary)
        let awayTint = teamColor(board.away?.colorHex, fallback: colors.textTertiary)
        VStack(alignment: .leading, spacing: dp(8)) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                VStack(spacing: dp(2)) {
                    HStack {
                        pill(row.home, leading: row.leader == "home", tint: homeTint)
                        Spacer(minLength: 0)
                        Text(row.label).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        Spacer(minLength: 0)
                        pill(row.away, leading: row.leader == "away", tint: awayTint)
                    }
                    if let fraction = row.homeFraction?.floatValue {
                        GeometryReader { geo in
                            HStack(spacing: dp(2)) {
                                Capsule().fill(homeTint).frame(width: max(0, (geo.size.width - dp(2)) * CGFloat(fraction)))
                                Capsule().fill(awayTint).frame(maxWidth: .infinity)
                            }
                        }
                        .frame(height: dp(3))
                        .animation(animated ? GameMotion.move : nil, value: fraction)
                    }
                }
            }
            if let table = byPeriod { ByPeriodTable(table: table).padding(.top, dp(4)) }
        }
    }

    private func pill(_ value: String, leading: Bool, tint: Color) -> some View {
        Text(value).font(NuvioType.labelLargeSemi).foregroundStyle(leading ? Color.white : colors.textPrimary)
            .padding(.horizontal, dp(8))
            .background(Capsule().fill(leading ? tint : Color.clear).animation(animated ? GameMotion.quick : nil, value: leading))
            .frame(minWidth: dp(40))
    }
}

private struct ByPeriodTable: View {
    let table: TvByPeriod
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(3)) {
            row(label: "", cells: table.columns, header: true)
            ForEach(Array(table.rows.enumerated()), id: \.offset) { index, cells in
                row(label: index < table.rowLabels.count ? table.rowLabels[index] : "", cells: cells, header: false)
            }
        }
    }

    private func row(label: String, cells: [String], header: Bool) -> some View {
        HStack(spacing: 0) {
            Text(label).frame(width: dp(60), alignment: .leading).lineLimit(1)
            ForEach(Array(cells.enumerated()), id: \.offset) { _, c in Text(c).frame(minWidth: dp(32)) }
            Spacer(minLength: 0)
        }
        .font(header ? NuvioType.labelMedium : NuvioType.labelLargeSemi)
        .foregroundStyle(header ? colors.textTertiary : colors.textPrimary)
    }
}

private struct LeadersPanel: View {
    let rows: [TvLeaderRowView]
    let form: TvFormView?
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: dp(8)) {
                    Text(row.tag ?? "").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).frame(width: dp(40), alignment: .leading)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(row.athlete).font(NuvioType.labelLargeSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
                        Text(row.category).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Text(row.value).font(NuvioType.labelLargeSemi).foregroundStyle(colors.textPrimary)
                }
                .padding(.vertical, dp(2))
            }
            if let form {
                HStack(spacing: dp(12)) {
                    formLine(form.homeName, form.home)
                    formLine(form.awayName, form.away)
                }
                .padding(.top, dp(6))
            }
        }
    }

    private func formLine(_ name: String, _ results: [String]) -> some View {
        HStack(spacing: dp(4)) {
            Text(name).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
            ForEach(Array(results.enumerated()), id: \.offset) { _, r in
                Text(r).font(NuvioType.labelMedium).foregroundStyle(r == "W" ? colors.secondary : colors.textTertiary)
            }
        }
    }
}

private struct LineupsPanel: View {
    let sides: [TvLineupSideView]
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(alignment: .top, spacing: dp(16)) {
            ForEach(Array(sides.enumerated()), id: \.offset) { _, side in
                VStack(alignment: .leading, spacing: dp(3)) {
                    Text([side.name, side.formation].compactMap { $0 }.joined(separator: " · "))
                        .font(NuvioType.labelLargeSemi).foregroundStyle(colors.textPrimary)
                    ForEach(Array(side.players.enumerated()), id: \.offset) { _, player in
                        Text(player).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct StandingsPanel: View {
    let groups: [TvStandingsGroupView]
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                VStack(alignment: .leading, spacing: dp(2)) {
                    HStack(spacing: 0) {
                        Text(group.name).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
                        Spacer(minLength: 0)
                        ForEach(Array(group.columns.enumerated()), id: \.offset) { _, c in
                            Text(c).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).frame(width: dp(38))
                        }
                    }
                    ForEach(Array(group.rows.enumerated()), id: \.offset) { _, row in
                        HStack(spacing: 0) {
                            Text(row.name).font(row.isGameTeam ? NuvioType.labelLargeSemi : NuvioType.labelMedium)
                                .foregroundStyle(row.isGameTeam ? colors.textPrimary : colors.textSecondary).lineLimit(1)
                            Spacer(minLength: 0)
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { _, c in
                                Text(c).font(NuvioType.labelMedium).foregroundStyle(row.isGameTeam ? colors.textPrimary : colors.textSecondary).frame(width: dp(38))
                            }
                        }
                        .padding(.vertical, dp(2))
                        .background(row.isGameTeam ? colors.secondary.opacity(0.14) : Color.clear)
                    }
                }
            }
        }
    }
}

// MARK: - Buttons

/// Primary / secondary action of the watch row: FocusBackground fill + FocusRing when focused.
private struct GameActionButton: View {
    let title: String
    let subtitle: String?
    let target: GameFocus
    var focus: FocusState<GameFocus?>.Binding
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let focused = focus.wrappedValue == target
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(2)) {
                Text(title).font(NuvioType.titleMediumSemi).foregroundStyle(focused ? colors.primary : colors.textPrimary)
                if let subtitle { Text(subtitle).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1).frame(maxWidth: dp(200), alignment: .leading) }
            }
            .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(focused ? colors.focusBackground : colors.backgroundElevated))
            .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? dp(2) : dp(1)))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused(focus, equals: target)
        .reportsFocus(focused)
        // The primary action takes focus when it appears (Play is the first action on open).
        // (Never from a chip: a channel list that lands late must not pull focus away from the user.)
        .onAppear {
            if target == .play {
                DispatchQueue.main.async { if focus.wrappedValue == nil || focus.wrappedValue == .play { focus.wrappedValue = .play } }
            }
        }
    }
}

// MARK: - Sport strips (Phase B)

/// One-shot emphasis moments on the field strip.
/// The spec's motion tokens (01-shared-contract §5, material3 durations) as Animations. Reduce Motion never reaches these (callers pass nil).
private enum GameMotion {
    static let quickMs = 200.0, moveMs = 500.0, countMs = 600.0, pulseMs = 700.0
    static var quick: Animation { .timingCurve(0.2, 0, 0, 1, duration: quickMs / 1000) }
    static var move: Animation { .timingCurve(0.2, 0, 0, 1, duration: moveMs / 1000) }
    static var count: Animation { .timingCurve(0.2, 0, 0, 1, duration: countMs / 1000) }
    static var pulse: Animation { .timingCurve(0.2, 0, 0, 1, duration: pulseMs / 1000) }
    /// emphasizedDecelerate: things entering (the new play, a pop-in chip).
    static var enter: Animation { .timingCurve(0.05, 0.7, 0.1, 1, duration: quickMs / 1000) }
    static var pulseNanos: UInt64 { UInt64(pulseMs * 1_000_000) }
}

/// A one-shot entrance (fade + 8 dp rise) for a view that has just appeared because it is new. Others never move.
private struct FreshEntrance: ViewModifier {
    let fresh: Bool
    @State private var shown = false
    func body(content: Content) -> some View {
        content
            .opacity(fresh && !shown ? 0 : 1)
            .offset(y: fresh && !shown ? dp(8) : 0)
            .onAppear { if fresh { withAnimation(GameMotion.enter) { shown = true } } }
    }
}

private enum StripFlash: Hashable {
    case loss, firstDown
    case touchdown(String)
    case foul(String)
}

private func hexColor(_ hex: String?, _ fallback: Color) -> Color { teamColor(hex, fallback: fallback) }

/// Football field strip: home end zone left, away right, 10-yard lines, drive span, first-down line, ball. Positions come
/// from Kotlin as 0..1 (0 = home goal line); this only maps them to pixels. Changes slide once (the screen assignment is animated).
private struct FieldStripCard: View {
    let strip: TvFieldStrip
    let flashes: Set<StripFlash>
    /// true only for a plain field move (not a change of possession): the ball, first-down line and drive span then slide.
    var slides = false
    @Environment(\.nuvio) private var colors

    var body: some View {
        let border: Color? = strip.borderHex.map { hexColor($0, colors.secondary) }
        VStack(alignment: .leading, spacing: dp(3)) {
            HStack {
                if let line = strip.line { Text(line).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary).lineLimit(1) }
                Spacer(minLength: dp(6))
                if let label = strip.ballLabel {
                    Text(strip.attackingRight == true ? "\(label) ▶▶" : "◀◀ \(label)")
                        .font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                }
            }
            field
            if let summary = strip.driveSummary {
                Text(summary).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(6))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
        .overlay(
            RoundedRectangle(cornerRadius: dp(12))
                .stroke(flashes.contains(.loss) ? colors.live : (border ?? Color.clear), lineWidth: dp(2))
        )
    }

    private var field: some View {
        let home = hexColor(strip.homeEndZoneHex, colors.secondary)
        let away = hexColor(strip.awayEndZoneHex, colors.textTertiary)
        let tint = hexColor(strip.driveTintHex, colors.secondary)
        return GeometryReader { geo in
            let w = geo.size.width, ez = w * 0.08, fw = w - ez * 2
            ZStack(alignment: .topLeading) {
                Color(argb: 0xFF14241A).clipShape(RoundedRectangle(cornerRadius: dp(3)))
                // End zones.
                RoundedRectangle(cornerRadius: dp(4)).fill(home).frame(width: ez)
                    .scaleEffect(flashes.contains(.touchdown("home")) ? 1.3 : 1)
                    .brightness(flashes.contains(.touchdown("home")) ? 0.5 : 0)
                RoundedRectangle(cornerRadius: dp(4)).fill(away).frame(width: ez).offset(x: w - ez)
                    .scaleEffect(flashes.contains(.touchdown("away")) ? 1.3 : 1)
                    .brightness(flashes.contains(.touchdown("away")) ? 0.5 : 0)
                // 10-yard lines (midfield stronger).
                ForEach(1..<10, id: \.self) { i in
                    Rectangle().fill(Color(argb: i == 5 ? 0xFF4A6A52 : 0xFF2C4232)).frame(width: i == 5 ? dp(2) : dp(1))
                        .offset(x: ez + fw * CGFloat(i) / 10)
                }
                // Drive span.
                if let from = strip.driveFrom?.floatValue, let to = strip.driveTo?.floatValue {
                    Rectangle().fill(tint.opacity(0.38)).frame(width: max(0, fw * CGFloat(to - from))).offset(x: ez + fw * CGFloat(from))
                        .animation(slides ? GameMotion.move : nil, value: to)
                }
                // First-down line.
                if let fd = strip.firstDownX?.floatValue {
                    Rectangle().fill(colors.secondary).frame(width: dp(3)).offset(x: ez + fw * CGFloat(fd) - dp(1.5))
                        .shadow(color: flashes.contains(.firstDown) ? colors.secondary : .clear, radius: dp(6))
                        .animation(slides ? GameMotion.move : nil, value: fd)
                }
                // Ball.
                Ellipse().fill(Color.white).overlay(Ellipse().stroke(Color(argb: 0xFF17130A), lineWidth: dp(1)))
                    .frame(width: dp(15), height: dp(10))
                    .position(x: ez + fw * CGFloat(strip.ballX), y: geo.size.height / 2)
                    .animation(slides ? GameMotion.move : nil, value: strip.ballX)
            }
        }
        .frame(height: dp(32))
    }
}

/// Soccer strip: last real event chip, possession bar, corners and cards. All numbers are the feed's.
private struct SoccerStripCard: View {
    let strip: TvSoccerStrip
    let board: TvScoreboard
    var animated = true
    @Environment(\.nuvio) private var colors

    var body: some View {
        let homeTint = teamColor(board.home?.colorHex, fallback: colors.secondary)
        let awayTint = teamColor(board.away?.colorHex, fallback: colors.textTertiary)
        VStack(alignment: .leading, spacing: dp(4)) {
            HStack {
                ZStack {
                    if let chip = strip.chip { eventChip(chip).id("\(chip.label)|\(chip.minute ?? "")|\(chip.text)").transition(.scale.combined(with: .opacity)) }
                }
                .animation(animated ? GameMotion.enter : nil, value: strip.chip?.text)
                Spacer(minLength: dp(6))
                if let home = strip.homePossession?.intValue, let away = strip.awayPossession?.intValue {
                    Text("Possession \(home)% – \(away)%").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        .contentTransition(.numericText())
                        .animation(animated ? GameMotion.count : nil, value: home)
                }
            }
            if let home = strip.homePossession?.intValue {
                GeometryReader { geo in
                    HStack(spacing: dp(2)) {
                        Capsule().fill(homeTint).frame(width: max(0, (geo.size.width - dp(2)) * CGFloat(home) / 100))
                        Capsule().fill(awayTint).frame(maxWidth: .infinity)
                    }
                }
                .frame(height: dp(5))
                .animation(animated ? GameMotion.move : nil, value: home)
            }
            HStack {
                if let hc = strip.homeCorners?.intValue, let ac = strip.awayCorners?.intValue {
                    Text("Corners \(hc) – \(ac)").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        .contentTransition(.numericText()).animation(animated ? GameMotion.count : nil, value: hc + ac)
                }
                Spacer(minLength: dp(6))
                Text(cards).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
            }
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(7))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
    }

    /// "Cards ■1 – ■2" in yellow squares; a red count shows only when > 0.
    private var cards: AttributedString {
        func part(_ yellow: Int32, _ red: Int32) -> AttributedString {
            var s = AttributedString("■\(yellow)")
            s.foregroundColor = Color(argb: 0xFFE8C400)
            if red > 0 {
                var r = AttributedString(" ■\(red)")
                r.foregroundColor = colors.live
                s.append(r)
            }
            return s
        }
        var out = AttributedString("Cards ")
        out.append(part(strip.homeYellow, strip.homeRed))
        out.append(AttributedString(" – "))
        out.append(part(strip.awayYellow, strip.awayRed))
        return out
    }

    private func eventChip(_ chip: TvEventChip) -> some View {
        let (bg, fg): (Color, Color) = {
            switch chip.style {
            case .goal: return (colors.secondary, colors.onSecondary)
            case .yellow: return (Color(argb: 0xFFE8C400), Color(argb: 0xFF17130A))
            case .red: return (colors.live.opacity(0.25), colors.live)
            case .neutral: return (colors.backgroundElevated, colors.textSecondary)
            }
        }()
        return HStack(spacing: dp(4)) {
            Text(chip.label).font(NuvioType.labelSmall)
            if let team = chip.team { Text(team).font(NuvioType.labelSmall) }
            if let minute = chip.minute { Text(minute).font(NuvioType.labelSmall) }
        }
        .foregroundStyle(fg)
        .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
        .background(Capsule().fill(bg))
    }
}

/// Both lineups on one pitch: home on the left half, away on the right; shirt number in the dot, formations in the header.
private struct PitchView: View {
    let pitch: TvPitch
    @Environment(\.nuvio) private var colors

    var body: some View {
        let home = teamColor(pitch.homeHex, fallback: colors.secondary)
        let away = teamColor(pitch.awayHex, fallback: colors.textTertiary)
        VStack(alignment: .leading, spacing: dp(6)) {
            HStack {
                Text([pitch.homeName, pitch.homeFormation].compactMap { $0 }.joined(separator: " · ")).font(NuvioType.labelLargeSemi).foregroundStyle(colors.textPrimary)
                Spacer(minLength: 0)
                Text([pitch.awayFormation, pitch.awayName].compactMap { $0 }.joined(separator: " · ")).font(NuvioType.labelLargeSemi).foregroundStyle(colors.textPrimary)
            }
            GeometryReader { geo in
                ZStack(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: dp(8)).fill(Color(argb: 0xFF14241A))
                    Rectangle().fill(Color(argb: 0xFF2C4232)).frame(width: dp(1)).offset(x: geo.size.width / 2)
                    Circle().stroke(Color(argb: 0xFF2C4232), lineWidth: dp(1)).frame(width: dp(60), height: dp(60))
                        .position(x: geo.size.width / 2, y: geo.size.height / 2)
                    ForEach(Array(pitch.spots.enumerated()), id: \.offset) { _, spot in
                        Text(spot.number).font(NuvioType.labelSmall).foregroundStyle(.white)
                            .frame(width: dp(22), height: dp(22))
                            .background(Circle().fill(spot.isHome ? home : away))
                            .overlay(Circle().stroke(Color.white.opacity(0.5), lineWidth: dp(1)))
                            .position(x: geo.size.width * (0.03 + 0.94 * CGFloat(spot.x)), y: geo.size.height * (0.08 + 0.84 * CGFloat(spot.y)))
                    }
                }
            }
            .frame(height: dp(250))
        }
    }
}

// MARK: - Basketball and hockey strips (Phase C)

/// Filled / empty dots (`total` capped); a number follows when the count passes the dots.
private struct CountDots: View {
    let filled: Int
    let total: Int
    var showNumber = false
    var popLast = false
    @Environment(\.nuvio) private var colors
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: dp(3)) {
            ForEach(0..<total, id: \.self) { i in
                Circle().fill(i < filled ? colors.secondary : colors.border)
                    .frame(width: dp(8), height: dp(8))
                    .scaleEffect(popLast && i == filled - 1 ? 1.5 : 1)
                    .animation(reduceMotion ? nil : GameMotion.quick, value: i < filled)
            }
            if showNumber && filled > total {
                Text("\(filled)").font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary)
            }
        }
    }
}

/// Basketball: run chip, bonus chip, team fouls (5 dots) and timeouts (7 dots). Fouls, bonus and timeouts are the feed's own.
private struct BasketballStripCard: View {
    let strip: TvBasketballStrip
    let board: TvScoreboard
    let flashes: Set<StripFlash>
    var animated = true
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            if strip.runLabel != nil || strip.bonusLabel != nil {
                HStack {
                    if let run = strip.runLabel { chip(run, filled: true).id(run).transition(.scale.combined(with: .opacity)) }
                    Spacer(minLength: dp(6))
                    if let bonus = strip.bonusLabel { chip(bonus, filled: false).id(bonus).transition(.scale.combined(with: .opacity)) }
                }
                .animation(animated ? GameMotion.enter : nil, value: [strip.runLabel ?? "", strip.bonusLabel ?? ""])
            }
            if strip.homeFouls != nil || strip.awayFouls != nil {
                pair("Team fouls") { side in
                    let n = (side == "home" ? strip.homeFouls : strip.awayFouls)?.intValue
                    return n.map { AnyView(CountDots(filled: $0, total: 5, showNumber: true, popLast: flashes.contains(.foul(side)))) }
                }
            }
            if strip.homeTimeouts != nil || strip.awayTimeouts != nil {
                pair("Timeouts") { side in
                    let n = (side == "home" ? strip.homeTimeouts : strip.awayTimeouts)?.intValue
                    return n.map { AnyView(CountDots(filled: $0, total: 7)) }
                }
            }
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(7))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
    }

    private func pair(_ label: String, _ dots: (String) -> AnyView?) -> some View {
        HStack(spacing: dp(6)) {
            Text(board.home?.shortName ?? "").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            if let d = dots("home") { d }
            Spacer(minLength: dp(4))
            Text(label).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
            Spacer(minLength: dp(4))
            if let d = dots("away") { d }
            Text(board.away?.shortName ?? "").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
        }
    }

    private func chip(_ text: String, filled: Bool) -> some View {
        Text(text).font(NuvioType.labelSmall)
            .foregroundStyle(filled ? colors.onSecondary : colors.secondary)
            .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
            .background(Capsule().fill(filled ? colors.secondary : colors.secondary.opacity(0.16)))
    }
}

/// Hockey: "Power play · TEAM" chip (+ time and a bar only when the derived clock exists), shots, power plays, faceoffs.
private struct HockeyStripCard: View {
    let strip: TvHockeyStrip
    let board: TvScoreboard
    var animated = true
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            HStack(spacing: dp(6)) {
                Text(strip.chipLabel).font(NuvioType.labelSmall)
                    .foregroundStyle(strip.onPowerPlay ? colors.onSecondary : colors.textSecondary)
                    .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
                    .background(Capsule().fill(strip.onPowerPlay ? colors.secondary : colors.backgroundElevated))
                    .id(strip.chipLabel).transition(.scale.combined(with: .opacity))
                    .animation(animated ? GameMotion.enter : nil, value: strip.chipLabel)
                if let left = strip.timeLeftText {
                    Text(left).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary).contentTransition(.numericText())
                        .animation(animated ? GameMotion.count : nil, value: left)
                }
                Spacer(minLength: dp(6))
                if let hs = strip.homeShots?.intValue, let a = strip.awayShots?.intValue {
                    Text("Shots \(hs) – \(a)").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).contentTransition(.numericText())
                        .animation(animated ? GameMotion.count : nil, value: hs + a)
                }
            }
            if let fraction = strip.fractionLeft?.floatValue {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(colors.border)
                        Capsule().fill(colors.secondary).frame(width: geo.size.width * CGFloat(fraction))
                    }
                }
                .frame(height: dp(5))
                .animation(animated ? GameMotion.move : nil, value: fraction)
            }
            HStack {
                if let pp = strip.powerPlayText {
                    Text("Power plays \(pp)").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        .contentTransition(.numericText()).animation(animated ? GameMotion.count : nil, value: pp)
                }
                Spacer(minLength: dp(6))
                if let f = strip.homeFaceoffs?.intValue, let g = strip.awayFaceoffs?.intValue {
                    Text("Faceoffs \(f) – \(g)").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        .contentTransition(.numericText()).animation(animated ? GameMotion.count : nil, value: f + g)
                }
            }
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(7))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
    }
}
