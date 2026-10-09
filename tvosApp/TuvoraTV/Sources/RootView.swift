import SwiftUI
import TuvoraCore

/// Follows TvAppLifecycle's gate screen. The screens are placeholders until the Phase 3 UI; the
/// sign-in screen already runs the real device sign-in against the backend.
struct RootView: View {
    @State private var screen: TvGateScreen = .loading
    /// Simulator smoke hook: `-smokePlay <url> [-smokeLive]` opens the player straight away. With
    /// `-smokeAs <type>:<videoId>` (e.g. `series:tt0944947:1:1`) it waits for the signed-in main screen
    /// and plays the URL as that title, so its skip segments and add-on subtitles load;
    /// `-smokeStartMs <ms>` resumes from there.
    @State private var smokeSession: TvPlayerSession? = RootView.smokeSessionFromArguments()
    /// `-smokePlay <url> -smokeZapTo <url>`: opens the live player through PlaybackCoordinator (the real
    /// presented path) with a zapper to the second URL, so a UI test can zap with the remote.
    @StateObject private var smokeZapCoordinator = PlaybackCoordinator()

    var body: some View {
        Group {
            if let session = smokeSession {
                TvPlayerScreen(session: session) { smokeSession = nil }.environmentObject(PlaybackCoordinator())
            } else if AppArguments.list.contains("-smokeSignIn") {
                // Simulator smoke hook: shows the sign-in screen without signing the simulator out.
                SignInView()
            } else {
                gate
            }
        }
        // Simulator verification: `-smokeLocalSignIn <email> <password>` signs a TEST account in on a LOCAL
        // backend (TvLocalSmoke refuses anything but a debug build pointed at 127.0.0.1 / localhost).
        .task {
            #if DEBUG
            let args = AppArguments.list
            if let i = args.firstIndex(of: "-smokeLocalSignIn"), i + 2 < args.count {
                TvLocalSmoke.shared.signIn(email: args[i + 1], password: args[i + 2])
            }
            #endif
        }
        .task { await startSmokeZap() }
        .task { await startSmokePresent() }
        #if DEBUG
        .overlay(alignment: .topLeading) { EngineLedgerMarker() }
        #endif
        // Top Shelf items open here (tuvora://title?…); MainShell opens them once the gate reaches Main.
        .onOpenURL { url in if let link = DeepLink(url: url) { DeepLinkCenter.shared.pending = link } }
    }

    private static func smokeSessionFromArguments() -> TvPlayerSession? {
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count, !args.contains("-smokeAs"),
              !args.contains("-smokeZapTo"), !args.contains("-smokePresent") else { return nil }
        let launch = TvPlayerLaunches.shared.direct(url: args[i + 1], title: "Smoke test", isLive: args.contains("-smokeLive"), startPositionMs: 0)
        return TvPlayerSession(launch: launch, liveReresolve: nil, vodReresolve: nil)
    }

    private func startSmokeZap() async {
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count,
              let z = args.firstIndex(of: "-smokeZapTo"), z + 1 < args.count else { return }
        try? await Task.sleep(nanoseconds: 1_500_000_000)   // the window must be up before presenting
        let first = TvPlayerLaunches.shared.direct(url: args[i + 1], title: "Smoke test", isLive: true, startPositionMs: 0)
        let zapTo = args[z + 1]
        smokeZapCoordinator.play(TvPlayerSession(launch: first, liveReresolve: nil, vodReresolve: nil)) { offset in
            let next = TvPlayerLaunches.shared.direct(url: zapTo, title: "Smoke zap \(offset)", isLive: true, startPositionMs: 0)
            return TvPlayerSession(launch: next, liveReresolve: nil, vodReresolve: nil)
        }
    }

    private static var smokePresented = false

    /// `-smokePlay <url> -smokePresent`: opens the VOD player through PlaybackCoordinator (the real
    /// presented path every screen uses), so a UI test can leave it with the remote (B112).
    private func startSmokePresent() async {
        let args = AppArguments.list
        // Once per launch: `.task` runs again whenever the root reappears (after the player closes).
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count, args.contains("-smokePresent"), !Self.smokePresented else { return }
        Self.smokePresented = true
        try? await Task.sleep(nanoseconds: 1_500_000_000)   // the window must be up before presenting
        let launch = TvPlayerLaunches.shared.direct(url: args[i + 1], title: "Smoke test", isLive: args.contains("-smokeLive"), startPositionMs: 0)
        smokeZapCoordinator.play(TvPlayerSession(launch: launch, liveReresolve: nil, vodReresolve: nil))
    }

    /// `-smokeAs`: the smoke session as a catalog title, built once the profile is loaded.
    private static func smokeCatalogSession() -> TvPlayerSession? {
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokePlay"), i + 1 < args.count,
              let j = args.firstIndex(of: "-smokeAs"), j + 1 < args.count else { return nil }
        let spec = args[j + 1]
        guard let colon = spec.firstIndex(of: ":") else { return nil }
        let type = String(spec[..<colon]), videoId = String(spec[spec.index(after: colon)...])
        let start = args.firstIndex(of: "-smokeStartMs").flatMap { k in k + 1 < args.count ? Int64(args[k + 1]) : nil } ?? 0
        let launch = TvPlayerLaunches.shared.directAs(url: args[i + 1], title: "Smoke test", type: type, videoId: videoId, startPositionMs: start)
        return TvPlayerSession(launch: launch, liveReresolve: nil, vodReresolve: nil)
    }

    /// Simulator smoke hook: `-smokeAddM3uLater <seconds> <url> [-smokeAddM3uBackup <url>]` adds an M3U
    /// playlist that long after the main screen opens — a playlist arriving (as a sync pull would)
    /// while a screen such as the IPTV tab is already showing. Local test profiles only.
    private static var smokeAddDone = false
    private static func smokeAddPlaylistLater() {
        let args = AppArguments.list
        guard !smokeAddDone, let i = args.firstIndex(of: "-smokeAddM3uLater"), i + 2 < args.count,
              let delay = Double(args[i + 1]) else { return }
        smokeAddDone = true
        let backup = args.firstIndex(of: "-smokeAddM3uBackup").flatMap { $0 + 1 < args.count ? [args[$0 + 1]] : nil } ?? []
        let form = TvPlaylistForm(
            sourceType: "m3u_url", pasteLink: false, playlistUrl: "", server: "", username: "", password: "",
            name: "Smoke M3U", userAgent: "", m3uUrl: args[i + 2], portalUrl: "", macAddress: "", stalkerUsername: "",
            stalkerPassword: "", serialNumber: "", deviceId: "", sendDeviceId: true, deviceId2: "", signature: "",
            stbModel: "", hwVersion: "", epgUrl: "",
            autoRefreshHours: TvPlaylistFormPolicy.shared.DEFAULT_AUTO_REFRESH_HOURS, backupUrls: backup)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            #if DEBUG
            smokeLog("SMOKE adding playlist url=%@", args[i + 2])
            #endif
            TvPlaylists.shared.add(form: form) { ok in smokeLog("SMOKE add playlist ok=%d", ok.boolValue ? 1 : 0) }
        }
    }

    private var gate: some View {
        Group {
            switch screen {
            case .loading: SplashView(message: nil)
            case .signIn: SignInView()
            case .profilePicker: ProfilePickerView()
            case .switching: SplashView(message: nil)
            case .main: MainShell()
            }
        }
        .task {
            for await next in TvAppLifecycle.shared.screen {
                smokeLog("SMOKE gate screen=%@", "\(next)")
                if next == .main, smokeSession == nil, let session = Self.smokeCatalogSession() {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)   // let the add-ons load first
                    smokeSession = session
                }
                if next == .main { Self.smokeAddPlaylistLater() }
                screen = next
                TvAppGraph.shared.screenChanged(name: "tv_gate_\(next)")
            }
        }
    }
}



/// NuvioTV's startup splash (startup_splash_enabled): the Tuvora wordmark centred on the app background,
/// used while the session restores and while a profile opens.
struct SplashView: View {
    let message: String?
    @State private var pulse = false

    var body: some View {
        ZStack {
            NuvioPalette.marigold.background.ignoresSafeArea()
            VStack(spacing: dp(24)) {
                Image("app_logo_wordmark").resizable().scaledToFit().frame(height: dp(60))
                    .opacity(pulse ? 1 : 0.7)
                    .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: pulse)
                ProgressView().tint(NuvioPalette.marigold.secondary)
                if let message { Text(ui: message).font(NuvioType.bodyMedium).foregroundStyle(NuvioPalette.marigold.textSecondary) }
            }
        }
        .onAppear { pulse = true }
    }
}
