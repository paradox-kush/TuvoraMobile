import SwiftUI
import TuvoraCore
import UIKit

/// Registers both playback engines with the shared session (PlaybackLanePolicy picks per stream).
enum PlayerEngines {
    static func register() {
        TvPlayerEngines.shared.register(lane: .libmpv, creator: Creator { MPVPlayerBridgeImpl() })
        TvPlayerEngines.shared.register(lane: .avPlayer, creator: Creator { AVPlayerBridgeImpl() })
    }

    private final class Creator: NSObject, NuvioPlayerBridgeCreator {
        let make: () -> NuvioPlayerBridge
        init(_ make: @escaping () -> NuvioPlayerBridge) { self.make = make }
        func createBridge() -> NuvioPlayerBridge {
            let bridge = make()
            EngineLedger.opened(bridge)
            return bridge
        }
    }
}

/// Full-screen playback over either engine: NuvioTV's player controls (PlayerScreen.kt:1736-2365) with
/// Apple TV remote behaviour (as AVPlayerViewController): click / Play-Pause toggles, left/right skip
/// 10 s, a swipe on the touch surface scrubs (click commits, Menu cancels), swipe down opens the
/// audio/subtitle panel, Menu hides the controls or leaves.
struct TvPlayerScreen: View {
    let session: TvPlayerSession
    /// The UIKit host's press relay (PlaybackCoordinator). Nil when the screen is embedded directly
    /// (simulator smoke path): SwiftUI's remote commands then feed the same policy.
    var relay: PlayerRemoteRelay? = nil
    let onClose: () -> Void

    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var state: TvPlayerState?
    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubMs: Int64?          // non-nil while scrubbing: the previewed position
    @State private var scrubOriginMs: Int64 = 0
    /// Whether playback was running when the scrub began (Back resumes it, AVPlayerViewController-style).
    @State private var resumeAfterScrub = false
    @State private var seekBarFocused = false
    @State private var panel: PlayerPanel?
    @State private var skipFlash: String?
    @State private var overlay: PlayerOverlay?
    /// The skip segment whose button auto-hid (10 s, NuvioTV); it shows again with the controls.
    @State private var skipHiddenFor: Int64?
    @FocusState private var rootFocused: Bool
    @FocusState private var skipFocused: Bool
    @FocusState private var startOverFocused: Bool
    /// This screen holds Live TV for display frame-rate matching (LiveDisplayCriteriaController).
    @State private var holdsLiveDisplay = false

    /// The Skip button is up: a segment is playing and it hasn't auto-hidden (or the controls are showing).
    private var skipVisible: Bool {
        guard let segment = state?.skipSegment, panel == nil, overlay == nil, state?.startOverAtMs == nil else { return false }
        return controlsVisible || skipHiddenFor != segment.startMs
    }
    private var startOverVisible: Bool { (state?.startOverAtMs != nil || state?.serverResumeOfferMs != nil) && panel == nil && overlay == nil }

    private var isLive: Bool { state?.isLive ?? false }

    /// Bare video: nothing over it that takes focus.
    private var bareVideoFocusable: Bool {
        panel == nil && overlay == nil && !controlsVisible && !skipVisible && !startOverVisible
    }

    /// UI-test marker: the session this screen drives and whether *that* session is playing.
    private var nowMarker: some View {
        Text("\(session.title)|\(session.state.value.isPlaying ? "playing" : "waiting")")
            .font(.system(size: 1))
            .opacity(0.01)
            .allowsHitTesting(false)
            .accessibilityIdentifier("player.now")
    }

    /// UI-test marker: whole seconds played, and whether a scrub preview is up ("12|scrub").
    private var positionMarker: some View {
        Text("\((state?.positionMs ?? 0) / 1000)|\(scrubMs == nil ? "play" : "scrub")")
            .font(.system(size: 1))
            .opacity(0.01)
            .allowsHitTesting(false)
            .accessibilityIdentifier("player.pos")
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            EngineHost(session: session, generation: Int(state?.engineGeneration ?? 0)).ignoresSafeArea()
            if TvPlayerRemotePolicy.shared.coverFailedFrame(hasError: state?.errorMessage != nil) {
                Color.black.ignoresSafeArea()
            }
            // The focus target over bare video (Apple DTS: with nothing focused tvOS drops the arrows and
            // Back falls through to UIKit). A real Button, not a `.focusable()` container: the focus engine
            // never settled on the container once it had no tap gesture, so the clickpad edges did nothing.
            Button { handle(.select) } label: {
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
            }
            .buttonStyle(PlainNoChromeButtonStyle())
            .ignoresSafeArea()
            .focused($rootFocused)
            .disabled(!bareVideoFocusable)
            .accessibilityIdentifier("player.video")
            nowMarker
            positionMarker
            #if DEBUG
            RemoteTrailMarker()
            #endif

            if let state {
                PlayerChrome(session: session, state: state, scrubMs: scrubMs, visible: controlsVisible || scrubMs != nil,
                             skipFlash: skipFlash,
                             onShowPanel: { panel = $0 }, onShowOverlay: { overlay = $0 }, onActivity: bumpControls,
                             onReport: { report(trigger: state.errorMessage != nil ? "error" : "player") },
                             onSeekBarFocus: { seekBarFocused = $0 })
                    // Nothing under a dialog or panel may take focus from it.
                    .disabled(panel != nil || overlay != nil)
                if state.showNextEpisode, let next = state.nextEpisode, panel == nil, let meta = session.seriesMeta {
                    NextEpisodeCard(video: next) { playback.openSources(meta: meta, video: next) }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .padding(.trailing, dp(32)).padding(.bottom, controlsVisible ? dp(150) : dp(32))
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
                if skipVisible, let segment = state.skipSegment {
                    SkipSegmentButton(label: segment.label, countingDown: !controlsVisible && skipHiddenFor != segment.startMs) {
                        session.skipSegment(); bumpControls()
                    }
                    .focused($skipFocused)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .padding(.leading, dp(32)).padding(.bottom, controlsVisible ? dp(170) : dp(32))
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
                    .task(id: segment.startMs) {
                        // NuvioTV auto-hides the button after 10 s while the controls are hidden.
                        try? await Task.sleep(nanoseconds: 10_000_000_000)
                        if !Task.isCancelled, !controlsVisible { skipHiddenFor = segment.startMs }
                    }
                }
                if startOverVisible, state.startOverAtMs == nil, let at = state.serverResumeOfferMs {
                    // A media server's own position is newer than Tuvora's record (design D3): offered, never forced.
                    ServerResumeCard(at: SeekBar.clock(at.int64Value)) { session.acceptServerResume() }
                        .focused($startOverFocused)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                        .padding(.top, dp(160))
                }
                if startOverVisible, let at = state.startOverAtMs {
                    StartOverCard(resumeAt: SeekBar.clock(at.int64Value)) { session.startFromBeginning() }
                        .focused($startOverFocused)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                        .padding(.top, dp(160))
                }
                if let overlay {
                    switch overlay {
                    case .speed:
                        Color.black.opacity(0.35).ignoresSafeArea().transition(.opacity)
                        SpeedDialog(current: state.speed) { speed in
                            session.setSpeed(speed: speed); self.overlay = nil; bumpControls()
                        }
                        .transition(.opacity)
                    case .info:
                        StreamInfoOverlay(sections: session.streamInfoSections())
                            .transition(.opacity)
                    }
                }
                if let panel {
                    // HIG › Materials: glass over bright video needs ~35% dimming beneath it to stay legible.
                    Color.black.opacity(0.35).ignoresSafeArea().transition(.opacity)
                    if panel == .episodes || panel == .sources {
                        ContentPanel(session: session, kind: panel) { self.panel = nil; bumpControls() }
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    } else {
                        TrackPanel(session: session, kind: panel) { self.panel = nil; bumpControls() }
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            // A swipe scrubs only over bare video or the focused seek bar, past a dead zone (B112: a resting
            // thumb, or a swipe across the control buttons, paused playback into a scrub).
            RemoteTouchCatcher(
                isActive: { TvPlayerRemotePolicy.shared.scrubAllowed(ctx: remoteContext, seekBarFocused: seekBarFocused) },
                onBegan: { scrubOriginMs = scrubMs ?? state?.positionMs ?? 0 },
                onMoved: { dx, dy in scrub(dx: dx, dy: dy) },
                onEnded: { _, _ in }
            )
            .allowsHitTesting(false)
        }
        .onAppear {
            relay?.action = { remoteAction($0) }
            relay?.handler = { handle($0) }
            session.attach()
            bumpControls()
            if session.state.value.isLive && !holdsLiveDisplay {
                holdsLiveDisplay = true
                LiveDisplayCriteriaController.shared.enter()
            }
            // Simulator smoke hooks: `-smokePanel subtitles|audio|aspect` opens the track panel after 5 s;
            // `-smokeOverlay speed|info` a player dialog; `-smokeSkip <start>,<end>,<type>` adds a segment.
            let args = AppArguments.list
            if let i = args.firstIndex(of: "-smokePanel"), i + 1 < args.count {
                let kind: PlayerPanel = ["audio": .audio, "aspect": .aspect, "style": .style][args[i + 1]] ?? .subtitles
                Task { try? await Task.sleep(nanoseconds: 5_000_000_000); panel = kind }
            }
            if let i = args.firstIndex(of: "-smokeOverlay"), i + 1 < args.count {
                let kind: PlayerOverlay = args[i + 1] == "info" ? .info : .speed
                Task { try? await Task.sleep(nanoseconds: 6_000_000_000); overlay = kind }
            }
            // `-smokeAddonSub <url>` picks that file as an add-on subtitle after 6 s; `-smokeSubDelay <ms>` sets the delay.
            if let i = args.firstIndex(of: "-smokeAddonSub"), i + 1 < args.count {
                let sub = AddonSubtitle(id: "smoke", url: args[i + 1], language: "en", display: "Smoke (English)", addonName: "Smoke", isSelected: false)
                Task { try? await Task.sleep(nanoseconds: 6_000_000_000); session.selectAddonSubtitle(subtitle: sub) }
            }
            if let i = args.firstIndex(of: "-smokeSubDelay"), i + 1 < args.count, let ms = Int32(args[i + 1]) {
                Task { try? await Task.sleep(nanoseconds: 7_000_000_000); session.setSubtitleDelay(delayMs: ms) }
            }
            // `-smokeAudioDelay <ms>`; `-smokeStyleDemo` (yellow, boxed, larger, raised) / `-smokeStyleReset`
            // change the saved subtitle style; `-smokeReport` sends an issue report after 8 s.
            if let i = args.firstIndex(of: "-smokeAudioDelay"), i + 1 < args.count, let ms = Int32(args[i + 1]) {
                Task { try? await Task.sleep(nanoseconds: 6_000_000_000); session.setAudioDelay(delayMs: ms) }
            }
            if args.contains("-smokeStyleDemo") || args.contains("-smokeStyleReset") {
                let demo = args.contains("-smokeStyleDemo")
                Task {
                    try? await Task.sleep(nanoseconds: 6_000_000_000)
                    _ = session.resetSubtitleStyle()
                    if demo {
                        _ = session.setSubtitleTextColor(index: 2); _ = session.setSubtitleBackground(index: 1)
                        for _ in 0..<3 { _ = session.subtitleSizeStep(up: true) }
                        for _ in 0..<6 { _ = session.subtitleOffsetStep(up: true) }
                    }
                }
            }
            if args.contains("-smokeReport") {
                Task { try? await Task.sleep(nanoseconds: 8_000_000_000); report(trigger: "smoke") }
            }
            if let i = args.firstIndex(of: "-smokeSkip"), i + 1 < args.count {
                let parts = args[i + 1].split(separator: ",").map(String.init)
                if parts.count == 3, let a = Double(parts[0]), let b = Double(parts[1]) {
                    session.addSkipIntervalForTesting(startSeconds: a, endSeconds: b, type: parts[2])
                }
            }
        }
        .onDisappear {
            session.detach()
            if holdsLiveDisplay {
                holdsLiveDisplay = false
                LiveDisplayCriteriaController.shared.exit()
            }
        }
        // Live display frame-rate matching. Keyed by the session: a zap swaps the session in place.
        .task(id: ObjectIdentifier(session)) {
            for await next in session.state where next.isLive {
                LiveDisplayCriteriaController.shared.offer(next.displayCriteria)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: LiveDisplayCriteriaController.switchWillBegin)) { _ in
            session.displayModeSwitch(inProgress: true)
        }
        .onReceive(NotificationCenter.default.publisher(for: LiveDisplayCriteriaController.switchDidSettle)) { _ in
            session.displayModeSwitch(inProgress: false)
        }
        // Hosted by PlaybackCoordinator, the host's UIKit recognizers deliver the presses the policy acts on
        // (PlayerHostController); SwiftUI's commands below cover the embedded smoke path and the presses
        // left to the focus engine.
        .modifier(EmbeddedRemoteCommands(enabled: relay == nil, handle: handle))
        .onMoveCommand { direction in
            let input: TvRemoteInput
            switch direction {
            case .left: input = .left
            case .right: input = .right
            case .up: input = .up
            case .down: input = .down
            @unknown default: return
            }
            if relay != nil && remoteAction(input) != .passThrough { return }   // the host's recognizer took it
            handle(input)
        }
        .animation(NuvioTokens.Motion.overlay, value: controlsVisible)
        .animation(NuvioTokens.Motion.overlay, value: panel)
        .animation(NuvioTokens.Motion.overlay, value: overlay)
        .animation(NuvioTokens.Motion.fast, value: skipVisible)
        .onChange(of: skipVisible) { _, visible in
            // NuvioTV requests focus for the button when it appears over hidden controls.
            if visible && !controlsVisible { DispatchQueue.main.async { skipFocused = true } }
        }
        .onChange(of: startOverVisible) { _, visible in
            if visible { DispatchQueue.main.async { startOverFocused = true } }
        }
        .task {
            for await next in session.state {
                state = next
                smokeLog("SMOKE player lane=%@ gen=%d loading=%d playing=%d pos=%lld dur=%lld err=%@ skip=%@ startOver=%@ speed=%.2f subDelay=%d addonSub=%@ segments=%d audioDelay=%d/%d",
                      "\(next.lane)", next.engineGeneration, next.isLoading, next.isPlaying,
                      next.positionMs, next.durationMs, next.errorMessage ?? "-", next.skipSegment?.label ?? "-",
                      next.startOverAtMs.map { "\($0)" } ?? "-", next.speed, next.subtitleDelayMs, next.addonSubtitleId ?? "-", next.skipSegmentCount, next.audioDelayMs, next.audioDelaySupported ? 1 : 0)
            }
        }
    }

    /// NuvioTV "Report Issue": logged and sent as an analytics event (TvPlayerSession.reportIssue).
    private func report(trigger: String) {
        session.reportIssue(trigger: trigger)
        playback.notify(L("Report Sent"))
    }

    /// Live zapping: the next/previous channel of the list the viewer started from.
    private func zap(_ offset: Int) {
        guard let zapper = playback.zapper else { return }
        let from = session
        Task {
            if let next = await zapper(offset) {
                playback.continuePlaying(next, from: from, zapper: zapper)
            }
        }
    }

    /// The screen as TvPlayerRemotePolicy sees it.
    private var remoteContext: TvPlayerRemoteContext {
        TvPlayerRemoteContext(
            controlsVisible: controlsVisible || scrubMs != nil, panelOpen: panel != nil, overlayOpen: overlay != nil,
            isLive: isLive, isPlaying: state?.isPlaying ?? false, hasError: state?.errorMessage != nil,
            scrubbing: scrubMs != nil, canZap: playback.zapper != nil, actionButtonUp: skipVisible || startOverVisible
        )
    }

    private func remoteAction(_ input: TvRemoteInput) -> TvRemoteAction {
        TvPlayerRemotePolicy.shared.decide(input: input, ctx: remoteContext)
    }

    /// One remote press, as TvPlayerRemotePolicy decides it.
    private func handle(_ input: TvRemoteInput) {
        let action = remoteAction(input)
        smokeLog("SMOKE remote %@ -> %@", "\(input)", "\(action)")
        RemoteTrail.add("\(input)>\(action)")
        switch action {
        case .passThrough:
            // Focus moving between the controls keeps them up.
            if input != .back && panel == nil && overlay == nil { bumpControls() }
        case .togglePlayPause: session.togglePlayPause(); bumpControls()
        case .commitScrub: commitScrub()
        case .cancelScrub: cancelScrub()
        case .retry: session.retry(); bumpControls()
        case .skipBack: skip(-TvPlayerRemotePolicy.shared.SKIP_MS)
        case .skipForward: skip(TvPlayerRemotePolicy.shared.SKIP_MS)
        case .showControls: bumpControls()
        case .hideControls: hideControls()
        case .zapPrevious: zap(-1)
        case .zapNext: zap(1)
        case .openTracks: withAnimation(NuvioTokens.Motion.overlay) { panel = .subtitles }
        case .closePanel: withAnimation { panel = nil }; bumpControls()
        case .closeOverlay: withAnimation { overlay = nil }; bumpControls()
        case .leave:
            // B112: leaving always closes the session (engine destroyed) before the screen goes.
            session.close()
            onClose()
        }
    }

    private func commitScrub() {
        guard let target = scrubMs else { return }
        session.seekTo(positionMs: target)
        scrubMs = nil
        session.play()
        bumpControls()
    }

    /// Back during a scrub: back to where it was, playing again if it was playing.
    private func cancelScrub() {
        scrubMs = nil
        if resumeAfterScrub { session.play() }
        bumpControls()
    }

    private func hideControls() {
        hideTask?.cancel()
        withAnimation(NuvioTokens.Motion.overlay) { controlsVisible = false }
        DispatchQueue.main.async { if skipVisible { skipFocused = true } else if startOverVisible { startOverFocused = true } else { rootFocused = true } }
    }

    /// Touch-surface scrub (TvPlayerRemotePolicy.scrubTarget): past a dead zone, about 1/4 of the title
    /// per full swipe. Playback pauses only once the swipe really is a scrub.
    private func scrub(dx: CGFloat, dy: CGFloat) {
        guard let state,
              let target = TvPlayerRemotePolicy.shared.scrubTarget(
                originMs: scrubOriginMs, dx: Double(dx), dy: Double(dy), durationMs: state.durationMs, scrubbing: scrubMs != nil
              ) else { return }
        if scrubMs == nil {
            resumeAfterScrub = state.isPlaying
            session.pause()
        }
        scrubMs = target.int64Value
        bumpControls()
    }

    private func skip(_ deltaMs: Int64) {
        guard !isLive else { return }
        session.seekBy(offsetMs: deltaMs)
        skipFlash = deltaMs > 0 ? "+10s" : "−10s"
        Task { try? await Task.sleep(nanoseconds: 900_000_000); skipFlash = nil }
        bumpControls()
    }

    private func bumpControls() {
        controlsVisible = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled, state?.isPlaying == true, panel == nil, overlay == nil, scrubMs == nil {
                controlsVisible = false
                // The Skip / Start over controls take focus when the chrome hides (NuvioTV), else the video.
                DispatchQueue.main.async { if skipVisible { skipFocused = true } else if startOverVisible { startOverFocused = true } else { rootFocused = true } }
            }
        }
    }
}

/// SwiftUI's own remote commands, only for a screen embedded without PlaybackCoordinator's host. Under the
/// host they must not exist at all: a focused view's `onPlayPauseCommand` swallows the
/// press before the host's recognizer sees it (simulator: Play/Pause over bare video did nothing).
private struct EmbeddedRemoteCommands: ViewModifier {
    let enabled: Bool
    let handle: (TvRemoteInput) -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .onPlayPauseCommand { handle(.playPause) }
                .onExitCommand { handle(.back) }
        } else {
            content
        }
    }
}

enum PlayerPanel: Hashable { case subtitles, audio, aspect, style, episodes, sources }
enum PlayerOverlay: Hashable { case speed, info }

/// Hosts the current engine's view controller; swaps it when the session escalates engines.
struct EngineHost: UIViewControllerRepresentable {
    let session: TvPlayerSession
    let generation: Int

    func makeUIViewController(context: Context) -> EngineContainer { EngineContainer() }

    func updateUIViewController(_ container: EngineContainer, context: Context) {
        container.show(generation: generation) { session.viewController() }
    }
}

final class EngineContainer: UIViewController {
    private var shownGeneration = -1
    private var child: UIViewController?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
    }

    func show(generation: Int, make: () -> UIViewController?) {
        guard generation != shownGeneration, let next = make() else { return }
        shownGeneration = generation
        if let child {
            child.willMove(toParent: nil)
            child.view.removeFromSuperview()
            child.removeFromParent()
        }
        addChild(next)
        next.view.frame = view.bounds
        next.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(next.view)
        next.didMove(toParent: self)
        child = next
    }
}

/// NuvioTV's player chrome: top gradient 150dp (black 70%→0), bottom 200dp (0→black 80%), bottom block
/// padding 32×24 — title (headlineMedium), episode line (titleMedium 90%), year · via (bodyMedium 68%) —
/// then the seek bar (8dp, 12 focused; track white 30/45%, buffered Secondary 35%, played Secondary)
/// and the 48dp circle buttons (focused: white fill, black icon), in glass on tvOS 26.
private struct PlayerChrome: View {
    let session: TvPlayerSession
    let state: TvPlayerState
    let scrubMs: Int64?
    let visible: Bool
    let skipFlash: String?
    let onShowPanel: (PlayerPanel) -> Void
    let onShowOverlay: (PlayerOverlay) -> Void
    let onActivity: () -> Void
    let onReport: () -> Void
    var onSeekBarFocus: (Bool) -> Void = { _ in }
    @State private var reported = false
    @Environment(\.nuvio) private var colors

    var body: some View {
        ZStack {
            if let error = state.errorMessage {
                VStack(spacing: dp(10)) {
                    Text("Playback failed").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                    Text(error).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).multilineTextAlignment(.center)
                    Text("Press the touch surface to try again").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                    ReportButton(reported: reported) { reported = true; onReport() }
                }
                .padding(dp(24))
                .navigationGlass(in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog))
            } else if state.isLoading && scrubMs == nil {
                ProgressView().scaleEffect(1.4)
            }
            if let skipFlash {
                Text(skipFlash).font(NuvioType.headlineMedium).foregroundStyle(.white)
                    .padding(.horizontal, dp(18)).padding(.vertical, dp(8))
                    .navigationGlass(in: Capsule())
                    .transition(.opacity)
            }
            if visible {
                VStack(spacing: 0) {
                    LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom).frame(height: dp(150))
                    Spacer()
                    ZStack(alignment: .bottom) {
                        LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).frame(height: dp(200))
                        controls.padding(.horizontal, dp(32)).padding(.bottom, dp(24))
                    }
                }
                .ignoresSafeArea()
                .transition(.opacity)
            }
        }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            Text(session.title).font(NuvioType.headlineMedium).foregroundStyle(.white).lineLimit(1)
            if let subtitle = session.subtitle {
                Text(ui: subtitle).font(NuvioType.titleMedium).foregroundStyle(.white.opacity(0.9)).lineLimit(1)
            }
            if state.isLive {
                HStack(spacing: dp(6)) {
                    Image("md_play_arrow").renderingMode(.template).resizable().frame(width: dp(24), height: dp(24))
                    Text("LIVE").font(NuvioType.inter(16, .bold))
                }
                .foregroundStyle(colors.primary)
            } else {
                SeekBar(positionMs: scrubMs ?? state.positionMs, bufferedMs: state.bufferedMs, durationMs: state.durationMs,
                        scrubbing: scrubMs != nil, onSeek: { session.seekBy(offsetMs: $0); onActivity() },
                        onFocusChange: onSeekBarFocus)
            }
            HStack(spacing: dp(4)) {
                PlayerButton(icon: state.isPlaying ? "ic_player_pause" : "ic_player_play") { session.togglePlayPause(); onActivity() }
                Spacer()
                if session.seriesMeta != nil { PlayerButton(icon: "ic_player_episodes") { onShowPanel(.episodes) } }
                if !state.isLive { PlayerButton(icon: "ic_player_source") { onShowPanel(.sources) } }
                PlayerButton(icon: "ic_player_subtitles") { onShowPanel(.subtitles) }
                PlayerButton(icon: "ic_player_audio_filled") { onShowPanel(.audio) }
                PlayerButton(icon: "ic_player_aspect_ratio") { onShowPanel(.aspect) }
                if !state.isLive { PlayerButton(icon: "md_speed") { onShowOverlay(.speed) } }
                PlayerButton(icon: "md_info") { onShowOverlay(.info) }
                PlayerButton(icon: reported ? "md_check_circle" : "md_flag") {
                    guard !reported else { return }
                    reported = true; onReport()
                }
            }
            .focusSection()
        }
    }
}

private struct SeekBar: View {
    let positionMs: Int64
    let bufferedMs: Int64
    let durationMs: Int64
    let scrubbing: Bool
    let onSeek: (Int64) -> Void
    var onFocusChange: (Bool) -> Void = { _ in }
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let total = Double(max(1, durationMs))
        let played = min(1, Double(positionMs) / total)
        let buffered = min(1, Double(bufferedMs) / total)
        let height = focused || scrubbing ? dp(12) : dp(8)
        VStack(alignment: .leading, spacing: dp(6)) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: dp(3)).fill(Color.white.opacity(focused || scrubbing ? 0.45 : 0.3))
                    RoundedRectangle(cornerRadius: dp(3)).fill(colors.secondary.opacity(0.35)).frame(width: geo.size.width * buffered)
                    RoundedRectangle(cornerRadius: dp(3)).fill(colors.secondary).frame(width: geo.size.width * played)
                    if scrubbing {
                        Text(Self.clock(positionMs)).font(NuvioType.labelLargeSemi).foregroundStyle(.white)
                            .padding(.horizontal, dp(8)).padding(.vertical, dp(4))
                            .navigationGlass(in: Capsule())
                            .offset(x: max(0, geo.size.width * played - dp(30)), y: -dp(28))
                    }
                }
                .frame(height: height)
                .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(height: dp(16))
            HStack {
                Text(Self.clock(positionMs))
                Spacer()
                Text("-" + Self.clock(max(0, durationMs - positionMs)))
            }
            .font(NuvioType.bodyMedium).foregroundStyle(.white.opacity(0.68)).monospacedDigit()
        }
        .focusable()
        .focused($focused)
        .onMoveCommand { direction in
            if direction == .left { onSeek(-10_000) } else if direction == .right { onSeek(10_000) }
        }
        .onChange(of: focused) { _, isFocused in onFocusChange(isFocused) }
        .animation(NuvioTokens.Motion.fast, value: focused)
    }

    static func clock(_ ms: Int64) -> String {
        let total = Int(max(0, ms) / 1000)
        let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60)
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

/// 48dp circle, 28dp custom icon (res/raw/ic_player_*); focused = white fill + black icon.
private struct PlayerButton: View {
    let icon: String
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Image(icon).renderingMode(.template).resizable().scaledToFit()
                .frame(width: dp(28), height: dp(28))
                .foregroundStyle(focused ? Color.black : Color.white)
                .frame(width: dp(48), height: dp(48))
                .background(Circle().fill(focused ? Color.white : Color.clear))
                .navigationGlass(in: Circle())
                .scaleEffect(focused ? 1.08 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}

/// NuvioTV's in-player side panel (520dp, BackgroundElevated, 16dp left corners), as glass on tvOS 26:
/// subtitle / audio track lists and the aspect options.
private struct TrackPanel: View {
    let session: TvPlayerSession
    let kind: PlayerPanel
    let onClose: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var tracks: [TvTrack] = []

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .leading, spacing: dp(12)) {
                HStack(spacing: dp(8)) {
                    PanelTab(title: "Subtitles", selected: current == .subtitles) { switchTo(.subtitles) }.focused($tabFocus, equals: .subtitles)
                    PanelTab(title: "Audio", selected: current == .audio) { switchTo(.audio) }.focused($tabFocus, equals: .audio)
                    PanelTab(title: "Aspect", selected: current == .aspect) { switchTo(.aspect) }.focused($tabFocus, equals: .aspect)
                    PanelTab(title: "Style", selected: current == .style) { switchTo(.style) }.focused($tabFocus, equals: .style)
                }
                .focusSection()
                ScrollView {
                    VStack(spacing: dp(6)) {
                        switch current {
                        case .subtitles:
                            let addonId = session.state.value.addonSubtitleId
                            SubtitleDelayRow(session: session)
                            PanelRow(title: "Off", checked: addonId == nil && !tracks.contains { $0.selected }) {
                                session.selectSubtitle(trackId: -1); reload()
                            }
                            ForEach(tracks, id: \.id) { t in
                                PanelRow(title: t.label.isEmpty ? t.language : t.label, detail: t.language, checked: addonId == nil && t.selected) {
                                    session.selectSubtitle(trackId: t.id); reload()
                                }
                            }
                            // SubtitleSelectionOverlay: the add-on subtitles found for this title.
                            if addonLoading || !addonSubtitles.isEmpty {
                                Text("Add-on subtitles").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(.top, dp(8))
                            }
                            ForEach(addonSubtitles, id: \.id) { sub in
                                PanelRow(title: sub.display.isEmpty ? sub.language : sub.display,
                                         detail: [sub.language, sub.addonName].compactMap { $0 }.joined(separator: " · "),
                                         checked: sub.id == addonId) {
                                    session.selectAddonSubtitle(subtitle: sub); reload()
                                }
                            }
                            if addonLoading { ProgressView().frame(maxWidth: .infinity).padding(dp(8)) }
                        case .audio:
                            AudioDelayRow(session: session)
                            ForEach(tracks, id: \.id) { t in
                                PanelRow(title: t.label.isEmpty ? t.language : t.label, detail: t.language, checked: t.selected) { session.selectAudio(trackId: t.id); reload() }
                            }
                            if tracks.isEmpty { Text("No other audio tracks").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
                        case .aspect:
                            ForEach([(0, "Fit"), (1, "Fill"), (2, "Zoom")], id: \.0) { mode, title in
                                PanelRow(title: title, checked: aspect == mode) { aspect = mode; session.setResizeMode(mode: Int32(mode)) }
                            }
                        case .style:
                            SubtitleStyleSection(session: session, supported: session.state.value.subtitleStyleSupported)
                        case .episodes, .sources:
                            EmptyView()
                        }
                    }
                }
                .focusSection()
            }
            .padding(dp(24))
            .frame(width: dp(520))
            .frame(maxHeight: .infinity)
            .navigationGlass(in: UnevenRoundedRectangle(topLeadingRadius: dp(16), bottomLeadingRadius: dp(16)))
        }
        .ignoresSafeArea()
        .onAppear {
            current = kind; reload()
            // Focus lands in the panel (on its tab), never left behind on the video.
            DispatchQueue.main.async { tabFocus = kind }
        }
        // Back is the player's (TvPlayerRemotePolicy: ClosePanel); a panel-level onExitCommand fired first
        // and the same press then also hid the controls.
        .task {
            for await next in session.addonSubtitles {
                addonSubtitles = next
                smokeLog("SMOKE addon subtitles=%d %@", next.count, next.prefix(3).map { "\($0.language)/\($0.addonName ?? "-")" }.joined(separator: ","))
            }
        }
        .task { for await next in session.addonSubtitlesLoading { addonLoading = next.boolValue } }
    }

    @State private var current: PlayerPanel = .subtitles
    @FocusState private var tabFocus: PlayerPanel?
    @AppStorage("tvos.player.aspect") private var aspect = 0
    @State private var addonSubtitles: [AddonSubtitle] = []
    @State private var addonLoading = false

    private func switchTo(_ panel: PlayerPanel) { current = panel; reload() }
    private func reload() {
        tracks = current == .audio ? session.audioTracks() : (current == .subtitles ? session.subtitleTracks() : [])
    }
}

private struct PanelTab: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    var body: some View { HubChip(title: title, selected: selected, action: action) }
}

private struct PanelRow: View {
    let title: String
    var detail: String? = nil
    let checked: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                    .foregroundStyle(checked ? colors.secondary : .clear)
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(focused ? Color.black : colors.textPrimary).lineLimit(1)
                    if let detail, !detail.isEmpty, detail != title {
                        Text(ui: detail).font(NuvioType.bodySmall).foregroundStyle(focused ? Color.black.opacity(0.7) : colors.textSecondary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(focused ? Color.white : Color.clear))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}


/// NuvioTV NextEpisodeOverlay: a card bottom-right near the end of an episode. Selecting it opens the
/// next episode's sources.
private struct NextEpisodeCard: View {
    let video: MetaVideo
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                CachedPosterArtwork(urlString: video.thumbnail, width: dp(128), height: dp(72), maximumWidth: dp(256)) { colors.backgroundCard }
                    .frame(width: dp(128), height: dp(72)).clipShape(RoundedRectangle(cornerRadius: dp(8)))
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text("Next Episode").font(NuvioType.labelMedium).foregroundStyle(focused ? Color.black.opacity(0.7) : colors.textSecondary)
                    Text("S\(video.season?.int32Value ?? 0) E\(video.episode?.int32Value ?? 0) · \(video.title)")
                        .font(NuvioType.titleMedium).foregroundStyle(focused ? Color.black : colors.textPrimary).lineLimit(1)
                }
                Image("md_play_arrow").renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                    .foregroundStyle(focused ? Color.black : Color.white)
            }
            .padding(dp(12))
            .frame(maxWidth: dp(460))
            .background(RoundedRectangle(cornerRadius: dp(16)).fill(focused ? Color.white : Color.clear))
            .navigationGlass(in: RoundedRectangle(cornerRadius: dp(16)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}

/// Episodes (this season) and Sources (this title's other sources) side panels, as NuvioTV's player panels.
private struct ContentPanel: View {
    let session: TvPlayerSession
    let kind: PlayerPanel
    let onClose: () -> Void
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var streams: StreamsUiState?
    @State private var switching = false

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .leading, spacing: dp(12)) {
                Text(kind == .episodes ? "Episodes" : "Sources").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                ScrollView {
                    VStack(spacing: dp(6)) {
                        if kind == .episodes, let meta = session.seriesMeta {
                            let season = session.currentSeason?.int32Value
                            ForEach(meta.videos.filter { $0.season?.int32Value == season }, id: \.id) { video in
                                PanelRow(title: "E\(video.episode?.int32Value ?? 0) · \(video.title)", detail: video.overview,
                                         checked: video.episode?.int32Value == session.currentEpisode?.int32Value) {
                                    playback.openSources(meta: meta, video: video)
                                }
                            }
                        } else {
                            let groups = (streams?.groups ?? []).filter { !$0.streams.isEmpty }
                            if groups.isEmpty {
                                Text("No other sources loaded for this title.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                            }
                            ForEach(groups, id: \.addonId) { group in
                                ForEach(Array(group.streams.enumerated()), id: \.offset) { _, stream in
                                    PanelRow(title: stream.streamLabel, detail: group.addonName, checked: false) { switchTo(stream) }
                                }
                            }
                        }
                    }
                }
                .focusSection()
            }
            .padding(dp(24))
            .frame(width: dp(520))
            .frame(maxHeight: .infinity)
            .navigationGlass(in: UnevenRoundedRectangle(topLeadingRadius: dp(16), bottomLeadingRadius: dp(16)))
        }
        .ignoresSafeArea()
        // Back is the player's (TvPlayerRemotePolicy: ClosePanel); a panel-level onExitCommand fired first
        // and the same press then also hid the controls.
        .task { for await next in TvTitle.shared.streams { streams = next } }
    }

    /// Switch source in place: the new source opens at the current position.
    private func switchTo(_ stream: StreamItem) {
        guard !switching, let meta = session.seriesMeta ?? TvTitle.shared.details.value.meta else { return }
        switching = true
        let video = meta.videos.first { $0.id == session.currentVideoId }
        let resume = session.state.value.positionMs
        Task {
            defer { switching = false }
            if let result = try? await TvTitle.shared.open(stream: stream, meta: meta, video: video, resumeMs: resume) {
                switch onEnum(of: result) {
                case .play(let play): playback.play(play.session)
                case .message(let message): playback.notify(message.text)
                }
            }
        }
    }
}

// MARK: - Skip / start over / speed / stream info (NuvioTV player extras)

/// SkipIntroButton.kt: #1E1E1E 85% card (glass here, it's a control), radius 12, 18×12 padding, a 20dp
/// skip-next icon and 14sp label; focused Secondary with OnSecondary content. A 4dp bar along the
/// bottom counts down the 10 s auto-hide while the controls are hidden.
private struct SkipSegmentButton: View {
    let label: String
    let countingDown: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool
    @State private var progress: CGFloat = 0

    var body: some View {
        Button(action: action) {
            VStack(spacing: 0) {
                HStack(spacing: dp(8)) {
                    Image("md_skip_next").renderingMode(.template).resizable().frame(width: dp(20), height: dp(20))
                    Text(ui: label).font(NuvioType.inter(14, .medium))
                }
                .foregroundStyle(focused ? colors.onSecondary : Color.white)
                .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
                GeometryReader { geo in
                    Rectangle().fill(Color.white.opacity(countingDown ? 0.15 : 0))
                        .overlay(alignment: .leading) {
                            Rectangle().fill(focused ? colors.onSecondary.opacity(0.5) : Color.white.opacity(0.6))
                                .frame(width: geo.size.width * progress)
                                .opacity(countingDown ? 1 : 0)
                        }
                }
                .frame(height: dp(4))
            }
            .fixedSize()
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(focused ? colors.secondary : Color(argb: 0xD91E1E1E)))
            .clipShape(RoundedRectangle(cornerRadius: dp(12), style: .continuous))
            .navigationGlass(in: RoundedRectangle(cornerRadius: dp(12), style: .continuous))
            .scaleEffect(focused ? 1.05 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .onAppear { withAnimation(.linear(duration: 10)) { progress = 1 } }
    }
}

/// StartOverAction: black 72% card (radius 16), "Resuming at 13:42 — the provider is slow to jump there"
/// (bodyMedium white 86%) over a primary "Start from beginning" button; shown after 15 s of a resume
/// that hasn't drawn a frame (ResumeLoadPolicy).
private struct StartOverCard: View {
    let resumeAt: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: dp(8)) {
            Text("Resuming at \(resumeAt) — the provider is slow to jump there")
                .font(NuvioType.bodyMedium).foregroundStyle(.white.opacity(0.86)).multilineTextAlignment(.center)
            Button(action: action) {
                Text("Start from beginning").font(NuvioType.labelLargeSemi)
                    .foregroundStyle(focused ? Color.black : Color.white)
                    .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                    .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.12)))
                    .scaleEffect(focused ? 1.04 : 1)
                    .animation(NuvioTokens.Motion.fast, value: focused)
            }
            .buttonStyle(PlainNoChromeButtonStyle())
            .focused($focused)
        }
        .padding(.horizontal, dp(16)).padding(.vertical, dp(12))
        .frame(maxWidth: dp(460))
        .background(RoundedRectangle(cornerRadius: dp(16), style: .continuous).fill(Color.black.opacity(0.72)))
    }
}

/// "You watched further on your server. Continue at 41:07?" - the same card as StartOverCard.
private struct ServerResumeCard: View {
    let at: String
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: dp(8)) {
            Text(verbatim: MS("ms_resume_from_server_message", "You watched further on your server. Continue at %1$s?", at))
                .font(NuvioType.bodyMedium).foregroundStyle(.white.opacity(0.86)).multilineTextAlignment(.center)
            Button(action: action) {
                Text(verbatim: MS("ms_resume_from_server_action", "Continue")).font(NuvioType.labelLargeSemi)
                    .foregroundStyle(focused ? Color.black : Color.white)
                    .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                    .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.12)))
                    .scaleEffect(focused ? 1.04 : 1)
                    .animation(NuvioTokens.Motion.fast, value: focused)
            }
            .buttonStyle(PlainNoChromeButtonStyle())
            .focused($focused)
            .accessibilityIdentifier("player.serverResume")
        }
        .padding(.horizontal, dp(16)).padding(.vertical, dp(12))
        .frame(maxWidth: dp(460))
        .background(RoundedRectangle(cornerRadius: dp(16), style: .continuous).fill(Color.black.opacity(0.72)))
    }
}

/// SpeedSelectionDialog: 300dp, "Playback Speed" headlineSmall, one row per NuvioTV speed ("Normal" for 1x)
/// with a check on the current one. A glass dialog here (control layer).
private struct SpeedDialog: View {
    let current: Float
    let onSelect: (Float) -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focusedSpeed: Float?

    var body: some View {
        let speeds = TvPlaybackSpeeds.shared.values.map { $0.floatValue }
        let selected = TvPlaybackSpeeds.shared.nearest(current: current)
        VStack(alignment: .leading, spacing: dp(6)) {
            Text("Playback Speed").font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary).padding(.bottom, dp(10))
            ForEach(speeds, id: \.self) { speed in
                SpeedRow(title: TvPlaybackSpeeds.shared.label(speed: speed), checked: speed == selected,
                         focused: focusedSpeed == speed) { onSelect(speed) }
                    .focused($focusedSpeed, equals: speed)
            }
        }
        .padding(dp(24))
        .frame(width: dp(300))
        .navigationGlass(in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog, style: .continuous))
        .focusSection()
        .defaultFocus($focusedSpeed, selected)
        .onAppear { DispatchQueue.main.async { focusedSpeed = selected } }
    }
}

/// One speed: PanelRow's look, but its focus comes from the dialog's single focus binding (a row with
/// its own @FocusState plus the dialog's binding never lit up).
private struct SpeedRow: View {
    let title: String
    let checked: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                    .foregroundStyle(checked ? (focused ? Color.black : colors.secondary) : .clear)
                Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(focused ? Color.black : colors.textPrimary)
                Spacer()
            }
            .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(focused ? Color.white : Color.clear))
            .scaleEffect(focused ? 1.03 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
    }
}

/// StreamInfoOverlay.kt: over a bottom scrim, bottom-left at 48dp × 36dp: section labels (SOURCE /
/// VIDEO / AUDIO / SUBTITLE) with their facts in a row, 36dp apart — a small TextTertiary label over a
/// white value. Menu closes it.
private struct StreamInfoOverlay: View {
    let sections: [TvInfoSection]
    @Environment(\.nuvio) private var colors

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black.opacity(0.55), location: 0.35),
                                   .init(color: .black.opacity(0.9), location: 1)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(alignment: .leading, spacing: dp(16)) {
                if sections.isEmpty {
                    Text("No stream information yet").font(NuvioType.bodyLarge).foregroundStyle(colors.textSecondary)
                }
                ForEach(sections, id: \.title) { section in
                    VStack(alignment: .leading, spacing: dp(4)) {
                        Text(ui: section.title).font(NuvioType.labelMedium).kerning(dp(1)).foregroundStyle(colors.secondary)
                        HStack(alignment: .top, spacing: dp(36)) {
                            ForEach(section.rows, id: \.label) { row in
                                VStack(alignment: .leading, spacing: dp(2)) {
                                    Text(ui: row.label).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                                    Text(row.value).font(NuvioType.bodyLarge).foregroundStyle(.white).lineLimit(1)
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, dp(48)).padding(.vertical, dp(36))
        }
    }
}

/// NuvioTV's Subtitles Delay control (SubtitleDelayConfig): − / value / + in 100 ms steps, ±60 s;
/// selecting the value resets it to 0.
private struct SubtitleDelayRow: View {
    let session: TvPlayerSession
    @Environment(\.nuvio) private var colors
    @State private var delay: Int32 = 0

    var body: some View {
        HStack(spacing: dp(8)) {
            Text("Subtitles Delay").font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
            Spacer()
            DelayButton(icon: "md_remove") { change(up: false) }
            DelayButton(title: TvSubtitleDelay.shared.label(ms: delay)) { session.setSubtitleDelay(delayMs: 0); delay = 0 }
            DelayButton(icon: "md_add") { change(up: true) }
        }
        .padding(.horizontal, dp(16)).padding(.vertical, dp(4))
        .focusSection()
        .task { for await next in session.state { delay = next.subtitleDelayMs } }
    }

    private func change(up: Bool) {
        let next = TvSubtitleDelay.shared.step(currentMs: delay, up: up)
        delay = next
        session.setSubtitleDelay(delayMs: next)
    }
}

private struct DelayButton: View {
    var icon: String? = nil
    var title: String? = nil
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Group {
                if let icon {
                    Image(icon).renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                } else if let title {
                    Text(ui: title).font(NuvioType.labelLargeSemi).monospacedDigit().frame(minWidth: dp(52))
                }
            }
            .foregroundStyle(focused ? Color.black : Color.white)
            .padding(.horizontal, dp(10)).padding(.vertical, dp(8))
            .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.1)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}

/// The "Report Issue" button NuvioTV shows on a playback error; "Report Sent" once pressed.
private struct ReportButton: View {
    let reported: Bool
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(reported ? "Report Sent" : "Report Issue").font(NuvioType.labelLargeSemi)
                .foregroundStyle(focused ? Color.black : Color.white)
                .padding(.horizontal, dp(18)).padding(.vertical, dp(8))
                .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.12)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .disabled(reported)
    }
}

// MARK: - Audio delay / subtitle style

/// AudioSelectionOverlay's Audio Delay row: − / "+0.125s" / + in 25 ms steps within ±3 s, with the
/// range under it. On AVPlayer it says it's unavailable instead (no engine control).
private struct AudioDelayRow: View {
    let session: TvPlayerSession
    @Environment(\.nuvio) private var colors
    @State private var delay: Int32 = 0
    @State private var supported = true

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            HStack(spacing: dp(8)) {
                Text("Audio Delay").font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
                Spacer()
                if supported {
                    DelayButton(icon: "md_remove") { change(up: false) }
                        .disabled(!TvAudioDelay.shared.canStep(currentMs: delay, up: false))
                    DelayButton(title: TvAudioDelay.shared.label(ms: delay)) { session.setAudioDelay(delayMs: 0) }
                    DelayButton(icon: "md_add") { change(up: true) }
                        .disabled(!TvAudioDelay.shared.canStep(currentMs: delay, up: true))
                }
            }
            Text(supported ? TvAudioDelay.shared.rangeLabel() : "Audio delay isn't available with the AVPlayer engine. Choose libmpv in Settings → Playback to use it.")
                .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, dp(16)).padding(.vertical, dp(4))
        .focusSection()
        .task {
            for await next in session.state {
                delay = next.audioDelayMs
                supported = next.audioDelaySupported
            }
        }
    }

    private func change(up: Bool) { session.setAudioDelay(delayMs: TvAudioDelay.shared.step(currentMs: delay, up: up)) }
}

/// SubtitleStyleSidePanel, as a tab of the track panel: Font Size and Bottom Offset steppers, Text Color
/// swatches (NuvioTV's palette), a background box, Bold and Outline, and Reset. Changes save to the
/// shared subtitle settings and apply to the picture at once. AVPlayer draws the system's own style.
private struct SubtitleStyleSection: View {
    let session: TvPlayerSession
    let supported: Bool
    @Environment(\.nuvio) private var colors
    @State private var style: TvSubtitleStyleView?

    var body: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            if !supported {
                Text("Subtitle style applies to the libmpv engine. AVPlayer uses the system subtitle style.")
                    .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let style {
                stepper("Font Size", value: "\(style.fontSizeSp)") { apply(session.subtitleSizeStep(up: $0)) }
                stepper("Bottom Offset", value: "\(style.bottomOffset)") { apply(session.subtitleOffsetStep(up: $0)) }
                swatches("Text Color", colors: style.textColors.map { $0.int64Value }, selected: Int(style.textColorIndex)) {
                    apply(session.setSubtitleTextColor(index: Int32($0)))
                }
                swatches("Background", colors: style.backgrounds.map { $0.int64Value }, selected: Int(style.backgroundIndex)) {
                    apply(session.setSubtitleBackground(index: Int32($0)))
                }
                HStack(spacing: dp(8)) {
                    Text("Bold").font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
                    Spacer()
                    DelayButton(title: L(style.bold ? "On" : "Off")) { apply(session.toggleSubtitleBold()) }
                }
                .focusSection()
                HStack(spacing: dp(8)) {
                    Text("Outline").font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
                    Spacer()
                    DelayButton(title: L(style.outline ? "On" : "Off")) { apply(session.toggleSubtitleOutline()) }
                }
                .focusSection()
                PanelRow(title: "Reset", checked: false) { apply(session.resetSubtitleStyle()) }
            }
        }
        .padding(.horizontal, dp(16))
        .task {
            // Follows the shared subtitle settings, so a change made anywhere shows here.
            for await _ in PlayerSettingsRepository.shared.uiState { style = session.subtitleStyleView() }
        }
    }

    private func apply(_ next: TvSubtitleStyleView) { style = next }

    private func stepper(_ title: String, value: String, change: @escaping (Bool) -> Void) -> some View {
        HStack(spacing: dp(8)) {
            Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
            Spacer()
            DelayButton(icon: "md_remove") { change(false) }
            DelayButton(title: value) {}
            DelayButton(icon: "md_add") { change(true) }
        }
        .focusSection()
    }

    private func swatches(_ title: String, colors palette: [Int64], selected: Int, pick: @escaping (Int) -> Void) -> some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
            HStack(spacing: dp(10)) {
                ForEach(Array(palette.enumerated()), id: \.offset) { index, argb in
                    Swatch(argb: UInt32(truncatingIfNeeded: argb), selected: index == selected) { pick(index) }
                }
            }
        }
        .focusSection()
    }
}

/// A colour circle: 2dp FocusRing when focused (as NuvioTV's), a check when it's the current colour.
private struct Swatch: View {
    let argb: UInt32
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            ZStack {
                // A checkerboard hint under translucent colours so "no background" reads as empty.
                Circle().fill(Color.white.opacity(0.15))
                Circle().fill(Color(argb: argb))
                if selected {
                    Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(15), height: dp(15))
                        .foregroundStyle(Color(argb: argb) == Color(argb: 0xFFFFFFFF) ? Color.black : Color.white)
                }
            }
            .frame(width: dp(30), height: dp(30))
            .overlay(Circle().stroke(focused ? colors.focusRing : Color.white.opacity(0.3), lineWidth: focused ? NuvioTokens.Stroke.focus : 1))
            .scaleEffect(focused ? 1.12 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}
