import SwiftUI
import TuvoraCore

// Settings → Tracking and Settings → Integrations, translated from NuvioTV
// (ui/screens/settings/TrackingSettingsScreen.kt, TrackingProviderDialogs.kt, MdbListAccountDialog.kt,
// SettingsScreen.kt IntegrationSettingsContent, DebridSettingsScreen.kt, TmdbSettingsScreen.kt,
// MDBListSettingsScreen.kt, AnimeSkipSettingsScreen.kt). Every write goes through the shared
// repositories (tvosCore TvTracking for the device-code sign-ins).

/// What the Tracking and Integrations panes read.
@MainActor
final class IntegrationsModel: ObservableObject {
    @Published var trakt: TraktAuthUiState?
    @Published var simkl: SimklAuthUiState?
    @Published var mdbAuth: MdbListAuthState?
    @Published var mdbStatus: MdbListAccountStatus?
    @Published var tracking: TraktSettingsUiState?
    @Published var comments = true
    @Published var traktDevice = TvDeviceAuth(isLoading: false, userCode: nil, displayUrl: nil, qrUrl: nil, expiresAtEpochMs: 0, isPolling: false, credentialsConfigured: true, error: nil)
    @Published var simklDevice = TvDeviceAuth(isLoading: false, userCode: nil, displayUrl: nil, qrUrl: nil, expiresAtEpochMs: 0, isPolling: false, credentialsConfigured: true, error: nil)
    @Published var tmdb: TmdbSettings?
    @Published var debrid: DebridSettings?
    @Published var mdbSettings: MdbListSettings?

    var traktConnected: Bool { trakt?.mode == .connected }
    var simklConnected: Bool { simkl?.mode == .connected }
    var mdbConnected: Bool { mdbAuth?.isAuthenticated ?? false }

    func observe() async {
        TvTracking.shared.ensureLoaded()
        TmdbSettingsRepository.shared.ensureLoaded()
        DebridSettingsRepository.shared.ensureLoaded()
        MdbListSettingsRepository.shared.ensureLoaded()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in for await s in TvTracking.shared.trakt { self.trakt = s } }
            group.addTask { @MainActor in for await s in TvTracking.shared.simkl { self.simkl = s } }
            group.addTask { @MainActor in for await s in TvTracking.shared.mdbList { self.mdbAuth = s } }
            group.addTask { @MainActor in for await s in TvTracking.shared.mdbListStatus { self.mdbStatus = s } }
            group.addTask { @MainActor in for await s in TvTracking.shared.settings { self.tracking = s } }
            group.addTask { @MainActor in for await s in TvTracking.shared.commentsEnabled { self.comments = s.boolValue } }
            group.addTask { @MainActor in for await s in TvTracking.shared.traktDevice { self.traktDevice = s } }
            group.addTask { @MainActor in for await s in TvTracking.shared.simklDevice { self.simklDevice = s } }
            group.addTask { @MainActor in for await s in TmdbSettingsRepository.shared.uiState { self.tmdb = s } }
            group.addTask { @MainActor in for await s in DebridSettingsRepository.shared.uiState { self.debrid = s } }
            group.addTask { @MainActor in for await s in MdbListSettingsRepository.shared.uiState { self.mdbSettings = s } }
        }
    }
}

enum TrackingProvider: String { case trakt, simkl, mdblist
    var name: String {
        switch self { case .trakt: return "Trakt"; case .simkl: return "Simkl"; case .mdblist: return "MDBList" }
    }
}

// MARK: - Tracking

/// TrackingSettingsContent: Accounts, Sources, and each connected service's features.
struct TrackingSettingsDetail: View {
    @ObservedObject var model: IntegrationsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors

    var body: some View {
        let t = model.traktConnected, s = model.simklConnected, m = model.mdbConnected
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Tracking",
                                 subtitle: "Connect tracking providers and choose which one powers your Library and Continue Watching.")
            SettingsGroupCard(title: "Accounts", subtitle: "Connect and manage tracking services") {
                providerRow(.trakt, presentation: traktPresentation)
                providerRow(.simkl, presentation: simklPresentation)
                providerRow(.mdblist, presentation: mdbPresentation)
            }
            SettingsGroupCard(title: "Sources",
                              subtitle: "Choose where Tuvora reads your library and watch progress. Playback scrobbles to every connected service.") {
                if let settings = model.tracking {
                    let library = TvTrackingPolicy.shared.effectiveLibrary(saved: settings.librarySourceMode, trakt: t, simkl: s, mdblist: m)
                    let progress = TvTrackingPolicy.shared.effectiveProgress(saved: settings.watchProgressSource, trakt: t, simkl: s, mdblist: m)
                    SettingsActionRow(title: "Library Source", subtitle: "Choose which source Tuvora reads for your Library",
                                      value: Self.label(library)) {
                        let modes = TvTrackingPolicy.shared.librarySources(trakt: t, simkl: s, mdblist: m)
                        dialogs.push(.picker(PickerSpec(title: "Library Source",
                            subtitle: "Choose the service Tuvora reads for your Library. Playback scrobbles to every connected service.",
                            options: modes.map { SettingsPickerOption(id: $0.name, title: Self.label($0)) },
                            selectedId: library.name, width: dp(620)) { id in
                                if let mode = modes.first(where: { $0.name == id }) { TvTracking.shared.setLibrarySource(mode: mode) }
                            }))
                    }
                    SettingsActionRow(title: "Watch Progress", subtitle: "Choose which progress source powers resume and continue watching",
                                      value: Self.label(progress)) {
                        let sources = TvTrackingPolicy.shared.watchProgressSources(trakt: t, simkl: s, mdblist: m)
                        dialogs.push(.picker(PickerSpec(title: "Watch Progress",
                            subtitle: "Choose the service Tuvora reads for resume and Continue Watching. Scrobbling remains active for every connected service.",
                            options: sources.map { SettingsPickerOption(id: $0.name, title: Self.label($0)) },
                            selectedId: progress.name, width: dp(660)) { id in
                                if let source = sources.first(where: { $0.name == id }) { TvTracking.shared.setWatchProgressSource(source: source) }
                            }))
                    }
                    if t || s {
                        let mlt = TvTrackingPolicy.shared.effectiveMoreLikeThis(saved: settings.moreLikeThisSource, trakt: t, simkl: s)
                        SettingsActionRow(title: "More Like This source",
                                          subtitle: "Choose where recommendations come from on detail and post-play screens",
                                          value: Self.label(mlt)) {
                            let sources = TvTrackingPolicy.shared.moreLikeThisSources(trakt: t, simkl: s)
                            dialogs.push(.picker(PickerSpec(title: "More Like This source",
                                subtitle: "Select the source for recommendations shown on detail and post-play screens.",
                                options: sources.map { SettingsPickerOption(id: $0.name, title: Self.label($0)) },
                                selectedId: mlt.name, width: dp(520)) { id in
                                    if let source = sources.first(where: { $0.name == id }) { TvTracking.shared.setMoreLikeThis(source: source) }
                                }))
                        }
                    }
                }
            }
            if t, let settings = model.tracking {
                let progressActive = TvTrackingPolicy.shared.effectiveProgress(saved: settings.watchProgressSource, trakt: t, simkl: s, mdblist: m) == .trakt
                SettingsGroupCard(title: "Trakt features", subtitle: "Trakt-specific history, reviews, and recommendations") {
                    SettingsActionRow(title: "Continue Watching Window",
                                      subtitle: progressActive ? "Trakt history considered for continue watching" : "Available when Trakt is the Watch Progress source",
                                      value: Self.windowLabel(Int(settings.continueWatchingDaysCap)), enabled: progressActive) {
                        dialogs.push(.picker(PickerSpec(title: "Continue Watching Window",
                            subtitle: "Choose how much Trakt activity should appear in continue watching.",
                            options: TvTrackingPolicy.shared.continueWatchingWindows.map {
                                SettingsPickerOption(id: "\($0.int32Value)", title: Self.windowLabel(Int($0.int32Value)))
                            },
                            selectedId: "\(settings.continueWatchingDaysCap)") { id in
                                TvTracking.shared.setContinueWatchingWindow(days: Int32(id) ?? 60)
                            }))
                    }
                    SettingsToggleRow(title: "Comments", subtitle: "Show Trakt reviews on metadata pages", isOn: model.comments) {
                        TvTracking.shared.setComments(enabled: !model.comments)
                    }
                }
            }
            if s, let settings = model.tracking {
                SettingsGroupCard(title: "Simkl features", subtitle: "Simkl-specific settings") {
                    SettingsActionRow(title: "Anime ID preference",
                                      subtitle: "Controls how anime series are identified. Choosing MAL or Kitsu gives each season its own entry instead of grouping under one IMDB ID.",
                                      value: Self.label(settings.simklAnimeIdPreference)) {
                        let prefs: [SimklAnimeIdPreference] = [.imdb, .mal, .kitsu, .tvdb]
                        dialogs.push(.picker(PickerSpec(title: "Anime ID preference",
                            subtitle: "Controls how anime series are identified. Choosing MAL or Kitsu gives each season its own entry instead of grouping under one IMDB ID.",
                            options: prefs.map { SettingsPickerOption(id: $0.name, title: Self.label($0)) },
                            selectedId: settings.simklAnimeIdPreference.name, width: dp(560)) { id in
                                if let p = prefs.first(where: { $0.name == id }) { TvTracking.shared.setAnimeId(preference: p) }
                            }))
                    }
                }
            }
        }
    }

    private func providerRow(_ provider: TrackingProvider, presentation: (String, String, Color)) -> some View {
        SettingsActionRow(title: provider.name, subtitle: presentation.0, value: presentation.1, valueColor: presentation.2) {
            dialogs.push(.custom(AnyView(TrackingAccountDialog(provider: provider, model: model, dialogs: dialogs))))
        }
    }

    /// traktConnectionPresentation / simklConnectionPresentation / mdbListConnectionPresentation.
    private var traktPresentation: (String, String, Color) {
        if model.traktDevice.isLoading && !model.traktConnected { return ("Starting Trakt connection…", "Connecting", colors.info) }
        if model.traktConnected { return ("Connected as \(model.trakt?.username ?? "Trakt user")", "Connected", colors.success) }
        if model.traktDevice.userCode != nil { return ("Finish connection approval", "Waiting for approval", colors.warning) }
        return ("Sync your watchlist, watch progress, continue watching, scrobbles, and personal lists with Trakt.", "Not connected", colors.textSecondary)
    }

    private var simklPresentation: (String, String, Color) {
        if model.simklDevice.isLoading && !model.simklConnected { return ("Starting Simkl connection…", "Connecting", colors.info) }
        if model.simklConnected { return ("Connected as \(model.simkl?.username ?? "Simkl user")", "Connected", colors.success) }
        if model.simklDevice.userCode != nil { return ("Finish connection approval", "Waiting for approval", colors.warning) }
        return ("Connect Simkl to sync lists, watched history, playback progress, and scrobbles.", "Not connected", colors.textSecondary)
    }

    private var mdbPresentation: (String, String, Color) {
        if model.mdbStatus?.isBusy == true && !model.mdbConnected { return ("Starting MDBList connection…", "Connecting", colors.info) }
        if model.mdbConnected { return ("Connected as \(model.mdbAuth?.user?.username ?? "MDBList user")", "Connected", colors.success) }
        if model.mdbAuth?.session != nil { return ("Finish connection approval", "Waiting for approval", colors.warning) }
        return ("Sync watched history and playback progress across devices", "Not connected", colors.textSecondary)
    }

    static func label(_ mode: LibrarySourceMode) -> String {
        switch mode { case .trakt: return "Trakt"; case .simkl: return "Simkl"; case .mdblist: return "MDBList"; default: return "Tuvora Library" }
    }
    static func label(_ source: WatchProgressSource) -> String {
        switch source { case .trakt: return "Trakt"; case .simkl: return "Simkl"; case .mdblist: return "MDBList"; default: return "Tuvora Sync" }
    }
    static func label(_ source: MoreLikeThisSourcePreference) -> String {
        switch source { case .trakt: return "Trakt"; case .simkl: return "Simkl"; default: return "TMDB" }
    }
    static func label(_ pref: SimklAnimeIdPreference) -> String {
        switch pref { case .mal: return "Prefer MyAnimeList"; case .kitsu: return "Prefer Kitsu"; case .tvdb: return "Prefer TVDB"; default: return "Prefer IMDB" }
    }
    static func windowLabel(_ days: Int) -> String { days == 0 ? "All history" : "\(days) days" }
}

/// TraktAccountDialog / SimklAccountDialog / MdbListAccountDialog: the device code (QR + code +
/// address + countdown) while connecting, the account and Disconnect once connected. The sign-in
/// starts when the dialog opens and stops polling when it closes.
struct TrackingAccountDialog: View {
    let provider: TrackingProvider
    @ObservedObject var model: IntegrationsModel
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var now = Date()

    var body: some View {
        NuvioDialog(title: provider.name, width: dp(720)) {
            VStack(spacing: dp(14)) {
                if connected {
                    connectedContent
                } else {
                    deviceContent
                }
            }
            .frame(maxWidth: .infinity)
        }
        .onAppear { if !connected { start() } }
        .onDisappear { TvTracking.shared.cancel() }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { now = $0 }
    }

    private var connected: Bool {
        switch provider { case .trakt: return model.traktConnected; case .simkl: return model.simklConnected; case .mdblist: return model.mdbConnected }
    }

    private func start() {
        switch provider {
        case .trakt: TvTracking.shared.startTrakt()
        case .simkl: TvTracking.shared.startSimkl()
        case .mdblist: if TvTracking.shared.mdbListConfigured() { TvTracking.shared.startMdbList() }
        }
    }

    /// The device flow's state, normalised across the three services.
    private var device: (loading: Bool, code: String?, url: String?, qr: String?, expiresAt: Int64, configured: Bool, error: String?) {
        switch provider {
        case .trakt:
            let d = model.traktDevice
            return (d.isLoading, d.userCode, d.displayUrl, d.qrUrl, d.expiresAtEpochMs, d.credentialsConfigured, d.error)
        case .simkl:
            let d = model.simklDevice
            return (d.isLoading, d.userCode, d.displayUrl, d.qrUrl, d.expiresAtEpochMs, d.credentialsConfigured, d.error)
        case .mdblist:
            let session = model.mdbAuth?.session
            let configured = TvTracking.shared.mdbListConfigured()
            let error: String? = configured && (model.mdbStatus?.error != nil || model.mdbStatus?.authError != nil) ? "Could not start sign-in." : nil
            return (model.mdbStatus?.isBusy ?? false, session?.userCode, session?.verificationUri, session?.verificationUriComplete,
                    session?.expiresAtEpochMs ?? 0, configured, error)
        }
    }

    private var instruction: String {
        switch provider {
        case .trakt: return "Go to trakt.tv/activate and enter this code:"
        case .simkl: return "Open the Simkl verification page and enter this code."
        case .mdblist: return "Scan the QR code, or enter this code at the address below."
        }
    }

    private var missingCredentials: String {
        switch provider {
        case .trakt: return "Missing TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in local.properties."
        case .simkl: return "Missing SIMKL_CLIENT_ID"
        case .mdblist: return "MDBList connection is unavailable in this build."
        }
    }

    /// TrackingDeviceAuthContent.
    @ViewBuilder
    private var deviceContent: some View {
        let d = device
        if d.loading {
            HStack(spacing: dp(12)) {
                ProgressView()
                Text("Starting \(provider.name) connection…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            }
        } else if let code = d.code, let qr = d.qr {
            Text(instruction).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).multilineTextAlignment(.center)
            if let image = QrHandOffDialog.qrImage(qr) {
                Image(uiImage: image).interpolation(.none).resizable()
                    .frame(width: dp(144), height: dp(144))
                    .padding(dp(8))
                    .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
            }
            VStack(spacing: dp(4)) {
                Text(code).font(NuvioType.inter(24, .bold)).foregroundStyle(colors.textPrimary)
                if let url = d.url { Text(url).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary) }
                if d.expiresAt > 0 {
                    let remaining = max(0, Int64(Double(d.expiresAt) - now.timeIntervalSince1970 * 1000))
                    Text("Code expires in \(TvTrackingPolicy.shared.formatDuration(valueMs: remaining))")
                        .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
            }
            HStack(spacing: dp(8)) {
                ProgressView().scaleEffect(0.6)
                Text("Waiting for approval...").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
            }
        } else {
            Text(d.error ?? (d.configured ? "Not connected" : missingCredentials))
                .font(NuvioType.bodyMedium)
                .foregroundStyle(d.error != nil || !d.configured ? colors.error : colors.textSecondary)
                .multilineTextAlignment(.center)
        }
        HStack(spacing: dp(8)) {
            SettingsDialogButton(title: "Cancel") { dialogs.pop() }
            if !d.loading && d.code == nil && d.configured {
                SettingsDialogButton(title: "Retry", primary: true) { start() }
            }
        }
    }

    /// ConnectedTrackingAccountContent.
    @ViewBuilder
    private var connectedContent: some View {
        let (label, description): (String, String) = {
            switch provider {
            case .trakt: return ("Connected as \(model.trakt?.username ?? "Trakt user")",
                                 "Sync your watchlist, watch progress, continue watching, scrobbles, and personal lists with Trakt.")
            case .simkl: return ("Connected as \(model.simkl?.username ?? "Simkl user")",
                                 "Connect Simkl to sync lists, watched history, playback progress, and scrobbles.")
            case .mdblist: return ("Connected as \(model.mdbAuth?.user?.username ?? "MDBList user")",
                                   "Sync watched history and playback progress across devices")
            }
        }()
        Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(40), height: dp(40)).foregroundStyle(colors.success)
        Text(ui: label).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
        Text(description).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).multilineTextAlignment(.center)
        if provider == .trakt, let expires = model.trakt?.tokenExpiresAtMillis?.int64Value {
            let remaining = max(0, Int64(Double(expires) - now.timeIntervalSince1970 * 1000))
            Text("Trakt access token refreshes in \(TvTrackingPolicy.shared.formatDuration(valueMs: remaining))")
                .font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
        }
        HStack(spacing: dp(8)) {
            SettingsDialogButton(title: "Close") { dialogs.pop() }
            SettingsDialogButton(title: "Disconnect", destructive: true) {
                dialogs.pop()
                dialogs.push(.custom(AnyView(DisconnectTrackingDialog(provider: provider, dialogs: dialogs))))
            }
        }
    }
}

/// The disconnect confirmation (TrackingSettingsScreen's NuvioDialog); Cancel takes focus first.
struct DisconnectTrackingDialog: View {
    let provider: TrackingProvider
    @ObservedObject var dialogs: SettingsDialogs
    @FocusState private var cancelFocused: Bool

    var body: some View {
        let (title, subtitle): (String, String) = {
            switch provider {
            case .trakt: return ("Disconnect Trakt?", "This will disconnect your Trakt account from Tuvora.")
            case .simkl: return ("Disconnect Simkl?", "This removes the Simkl account and cached Simkl data from this profile.")
            case .mdblist: return ("Disconnect MDBList?", "Stop sending watch updates from this profile. Your MDBList history is kept.")
            }
        }()
        NuvioDialog(title: title, subtitle: subtitle) {
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: "Cancel") { dialogs.pop() }.focused($cancelFocused)
                SettingsDialogButton(title: "Disconnect", destructive: true) {
                    switch provider {
                    case .trakt: TvTracking.shared.disconnectTrakt()
                    case .simkl: TvTracking.shared.disconnectSimkl()
                    case .mdblist: TvTracking.shared.disconnectMdbList()
                    }
                    dialogs.pop()
                }
            }
        }
        .onAppear { DispatchQueue.main.async { cancelFocused = true } }
    }
}

// MARK: - Integrations hub

enum IntegrationSection: String {
    case hub, debrid, tmdb, mdblist, animeskip, iptv, mediaservers

    /// Store builds compile debrid out (AppFeaturePolicy.debridEnabled; Apple TV compiles the App Store
    /// policy, guideline 5.2.3): Connected Services is not listed and cannot be opened, even by a hook.
    var isAvailable: Bool { self != .debrid || AppFeaturePolicy.shared.debridEnabled }
}

/// Which Integrations page is open. Menu on a page returns to the hub (NuvioTV's BackHandler).
@MainActor
final class IntegrationsNav: ObservableObject {
    @Published var section: IntegrationSection = {
        let args = AppArguments.list
        if let i = args.firstIndex(of: "-smokeIntegration"), i + 1 < args.count,
           let s = IntegrationSection(rawValue: args[i + 1]), s.isAvailable { return s }
        return .hub
    }()
}

/// IntegrationSettingsContent: the hub, then each integration's page.
struct IntegrationsSettingsDetail: View {
    @ObservedObject var nav: IntegrationsNav
    @ObservedObject var model: IntegrationsModel
    @ObservedObject var settings: SettingsModel

    var body: some View {
        // An unavailable page (debrid in a store build) falls back to the hub rather than rendering.
        switch nav.section.isAvailable ? nav.section : .hub {
        case .hub:
            VStack(alignment: .leading, spacing: dp(14)) {
                SettingsDetailHeader(title: "Integrations", subtitle: "Manage available integrations")
                SettingsGroupCard {
                    if IntegrationSection.debrid.isAvailable {
                        SettingsActionRow(title: "Connected Services", subtitle: "Experimental cloud account sources") { nav.section = .debrid }
                    }
                    SettingsActionRow(title: "TMDB", subtitle: "Metadata enrichment controls") { nav.section = .tmdb }
                    SettingsActionRow(title: "MDBList Ratings", subtitle: "External ratings providers") { nav.section = .mdblist }
                    SettingsActionRow(title: "Anime-Skip", subtitle: "Anime intro/outro skip timestamps") { nav.section = .animeskip }
                    SettingsActionRow(title: "IPTV (Xtream Codes)", subtitle: "Add a live TV / VOD provider by URL") { nav.section = .iptv }
                    SettingsActionRow(title: MS("compose_settings_page_media_servers", "Media servers"),
                                      subtitle: MS("settings_content_discovery_media_servers_description", "Connect Jellyfin or Emby to browse and play your own library.")) { nav.section = .mediaservers }
                        .accessibilityIdentifier("integrations.mediaServers")
                }
            }
        case .debrid: DebridSettingsDetail(model: model)
        case .tmdb: TmdbSettingsDetail(model: model)
        case .mdblist: MdbListSettingsDetail(model: model)
        case .animeskip: AnimeSkipSettingsDetail(settings: settings)
        case .iptv: IptvSettingsDetail(model: settings)
        case .mediaservers: MediaServersSettingsDetail()
        }
    }
}

/// The API-key / Client-ID entry dialog NuvioTV opens from a key row, with the native keyboard.
struct KeyEntryDialog: View {
    let title: String
    let subtitle: String
    let placeholder: String
    let initial: String
    @ObservedObject var dialogs: SettingsDialogs
    let onSave: (String) -> Void
    @State private var text = ""

    var body: some View {
        NuvioDialog(title: title, subtitle: subtitle, width: dp(560)) {
            SettingsTextField(label: title, hint: placeholder, text: $text) { save() }
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: "Cancel") { dialogs.pop() }
                if !initial.isEmpty {
                    SettingsDialogButton(title: "Clear") { onSave(""); dialogs.pop() }
                }
                SettingsDialogButton(title: "Save", primary: true) { save() }
            }
            .padding(.top, dp(8))
        }
        .onAppear { text = initial }
    }

    private func save() {
        onSave(text.trimmingCharacters(in: .whitespacesAndNewlines))
        dialogs.pop()
    }
}

/// A saved secret, shown as NuvioTV does: "Not set", or masked to its last four characters.
func maskedKey(_ key: String) -> String {
    let trimmed = key.trimmingCharacters(in: .whitespaces)
    guard !trimmed.isEmpty else { return "Not set" }
    return trimmed.count <= 4 ? "••••" : "••••" + trimmed.suffix(4)
}

// MARK: - Debrid (Connected Services)

/// DebridSettingsContent: cloud library, link resolving, accounts, link preparation, filters. Formatter
/// templates, per-resolution/quality limits and size range are not ported.
private struct DebridSettingsDetail: View {
    @ObservedObject var model: IntegrationsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    private var repo: DebridSettingsRepository { DebridSettingsRepository.shared }

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Connected Services", subtitle: "Connect accounts for links and library access")
            if let d = model.debrid {
                SettingsGroupCard {
                    Text("These integrations are experimental and may be kept, changed, or removed later.")
                        .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    SettingsToggleRow(title: "Cloud library", subtitle: "Browse and play files already in your connected accounts.",
                                      isOn: d.cloudLibraryEnabled, enabled: d.hasCloudLibraryProvider) {
                        repo.setCloudLibraryEnabled(value: !d.cloudLibraryEnabled)
                    }
                    SettingsToggleRow(title: "Resolve playable links",
                                      subtitle: "Ask a connected service for playable links when a result needs it. This may add the item to that service.",
                                      isOn: d.linkResolvingEnabled, enabled: d.hasResolverProvider) {
                        repo.setLinkResolvingEnabled(value: !d.linkResolvingEnabled)
                    }
                    if d.canResolvePlayableLinks && d.resolverServices.count > 1, let active = d.activeResolverProviderId {
                        SettingsActionRow(title: "Resolve with", subtitle: "Choose which connected account handles playable links.",
                                          value: DebridProviders.shared.displayName(id: active)) {
                            dialogs.push(.picker(PickerSpec(title: "Resolve with",
                                options: d.resolverServices.map { SettingsPickerOption(id: $0.provider.id, title: $0.provider.displayName) },
                                selectedId: active) { repo.setPreferredResolverProviderId(providerId: $0) }))
                        }
                    }
                    if !d.hasResolverProvider {
                        Text("Connect an account first.").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    }
                    sectionLabel("Accounts")
                    ForEach(DebridProviders.shared.visible(), id: \.id) { provider in
                        let key = d.providerApiKeys[provider.id] ?? ""
                        SettingsActionRow(title: provider.displayName, subtitle: "Connect your \(provider.displayName) account.",
                                          value: key.isEmpty ? "Not set" : "Connected",
                                          valueColor: key.isEmpty ? nil : colors.success) {
                            dialogs.push(.custom(AnyView(KeyEntryDialog(
                                title: "\(provider.displayName) API Key", subtitle: "Enter your \(provider.displayName) API key.",
                                placeholder: "Enter \(provider.displayName) API key", initial: key, dialogs: dialogs) { value in
                                    repo.setProviderApiKey(providerId: provider.id, value: value)
                                })))
                        }
                    }
                }
                if d.canResolvePlayableLinks || Self.smokeFilters {
                    SettingsGroupCard {
                        sectionLabel("Link Preparation")
                        SettingsToggleRow(title: "Prepare links", subtitle: "Resolve playable links before playback starts.",
                                          isOn: d.instantPlaybackPreparationLimit > 0) {
                            repo.setInstantPlaybackPreparationLimit(value: d.instantPlaybackPreparationLimit > 0 ? 0 : 1)
                        }
                        if d.instantPlaybackPreparationLimit > 0 {
                            SettingsActionRow(title: "Links to prepare", value: prepareLabel(Int(d.instantPlaybackPreparationLimit))) {
                                dialogs.push(.picker(PickerSpec(title: "Links to prepare",
                                    options: [1, 2, 3, 5].map { SettingsPickerOption(id: "\($0)", title: prepareLabel($0)) },
                                    selectedId: "\(d.instantPlaybackPreparationLimit)") { repo.setInstantPlaybackPreparationLimit(value: Int32($0) ?? 1) }))
                            }
                        }
                    }
                    SettingsGroupCard {
                        sectionLabel("Filters & Sorting")
                        SettingsActionRow(title: "Max results", subtitle: "Limit how many Direct Debrid sources appear.",
                                          value: maxLabel(Int(d.streamMaxResults))) {
                            dialogs.push(.picker(PickerSpec(title: "Max results",
                                options: [0, 5, 10, 20, 50].map { SettingsPickerOption(id: "\($0)", title: maxLabel($0)) },
                                selectedId: "\(d.streamMaxResults)") { repo.setStreamMaxResults(value: Int32($0) ?? 0) }))
                        }
                        let prefs = d.streamPreferences
                        limitRow("Per resolution limit", "Cap repeated 2160p, 1080p, 720p results after sorting.",
                                 Int(prefs.maxPerResolution)) { TvDebrid.shared.setMaxPerResolution(value: $0) }
                        limitRow("Per quality limit", "Cap repeated BluRay, WEB-DL, REMUX results after sorting.",
                                 Int(prefs.maxPerQuality)) { TvDebrid.shared.setMaxPerQuality(value: $0) }
                        SettingsActionRow(title: "Size range", subtitle: "Filter streams by file size.",
                                          value: sizeLabel(Int(prefs.sizeMinGb), Int(prefs.sizeMaxGb))) {
                            let ranges = TvDebrid.shared.sizeRangeOptions
                            dialogs.push(.picker(PickerSpec(title: "Size range",
                                options: ranges.map { SettingsPickerOption(id: "\($0.minGb)-\($0.maxGb)", title: sizeLabel(Int($0.minGb), Int($0.maxGb))) },
                                selectedId: "\(prefs.sizeMinGb)-\(prefs.sizeMaxGb)") { id in
                                    if let r = ranges.first(where: { "\($0.minGb)-\($0.maxGb)" == id }) {
                                        TvDebrid.shared.setSizeRange(minGb: r.minGb, maxGb: r.maxGb)
                                    }
                                }))
                        }
                        let sorts: [(DebridStreamSortMode, String)] = [(.`default`, "Original order"), (.qualityDesc, "Best quality first"),
                                                                       (.sizeDesc, "Largest first"), (.sizeAsc, "Smallest first")]
                        enumRow("Sort results", "Choose how results are ordered.", sorts, d.streamSortMode) { repo.setStreamSortMode(value: $0) }
                        let qualities: [(DebridStreamMinimumQuality, String)] = [(.any, "Any quality"), (.p720, "720p and above"),
                                                                                 (.p1080, "1080p and above"), (.p2160, "4K only")]
                        enumRow("Minimum quality", "Hide sources below the selected resolution.", qualities, d.streamMinimumQuality) { repo.setStreamMinimumQuality(value: $0) }
                        let features: [(DebridStreamFeatureFilter, String)] = [(.any, "Any"), (.exclude, "Hide"), (.only, "Only")]
                        enumRow("Dolby Vision", "Show, hide, or require Dolby Vision sources.", features, d.streamDolbyVisionFilter) { repo.setStreamDolbyVisionFilter(value: $0) }
                        enumRow("HDR", "Show, hide, or require HDR sources.", features, d.streamHdrFilter) { repo.setStreamHdrFilter(value: $0) }
                        let codecs: [(DebridStreamCodecFilter, String)] = [(.any, "Any codec"), (.h264, "H.264 / AVC"), (.hevc, "HEVC / H.265"), (.av1, "AV1")]
                        enumRow("Codec", "Filter sources by video codec.", codecs, d.streamCodecFilter) { repo.setStreamCodecFilter(value: $0) }
                    }
                }
                SettingsGroupCard {
                    sectionLabel("Formatting")
                    templateRow("Name template", "Controls how result names appear. Leave blank to use the original result name.",
                                d.streamNameTemplate, isDefault: TvDebrid.shared.isDefaultNameTemplate(template: d.streamNameTemplate)) {
                        TvDebrid.shared.setNameTemplate(value: $0)
                    }
                    templateRow("Description template", "Controls the metadata shown under each result. Leave blank to use the original result details.",
                                d.streamDescriptionTemplate,
                                isDefault: TvDebrid.shared.isDefaultDescriptionTemplate(template: d.streamDescriptionTemplate)) {
                        TvDebrid.shared.setDescriptionTemplate(value: $0)
                    }
                    SettingsActionRow(title: "Reset formatting", subtitle: "Restore default source formatting.", value: "Reset to Default") {
                        TvDebrid.shared.resetTemplates()
                    }
                }
            }
        }
    }

    /// Simulator smoke hook: `-smokeDebridFilters` shows the link and filter sections without an account.
    private static let smokeFilters = AppArguments.list.contains("-smokeDebridFilters")

    private func sectionLabel(_ text: String) -> some View {
        Text(ui: text).font(NuvioType.labelLarge).foregroundStyle(colors.textPrimary).padding(.top, dp(4))
    }

    private func prepareLabel(_ n: Int) -> String { n == 1 ? "1 link" : "\(n) links" }
    /// debrid_stream_max_results_all / _count, in the viewer's language.
    private func maxLabel(_ n: Int) -> String {
        n <= 0 ? L("All streams") : String(format: LK("debrid_stream_max_results_count", "%1$ld streams"), n)
    }
    /// sizeRangeLabel (TvDebrid.sizeRangeLabel is the tested English form).
    private func sizeLabel(_ minGb: Int, _ maxGb: Int) -> String {
        if minGb <= 0 && maxGb <= 0 { return L("Any") }
        if minGb <= 0 { return String(format: LK("debrid_size_range_up_to", "Up to %1$ldGB"), maxGb) }
        if maxGb <= 0 { return String(format: LK("debrid_size_range_min_plus", "%1$ldGB+"), minGb) }
        return String(format: LK("debrid_size_range_min_max", "%1$ld-%2$ldGB"), minGb, maxGb)
    }

    /// NuvioTV edits templates in a phone web editor it serves over the LAN; Apple TV edits them in
    /// place with the native keyboard (the template shows as the row subtitle).
    private func templateRow(_ title: String, _ description: String, _ template: String, isDefault: Bool,
                             save: @escaping (String) -> Void) -> some View {
        SettingsActionRow(title: title, subtitle: template.isEmpty ? description : template,
                          value: template.isEmpty ? "Original format" : (isDefault ? "Default format" : nil)) {
            dialogs.push(.custom(AnyView(KeyEntryDialog(title: title, subtitle: description,
                placeholder: title, initial: template, dialogs: dialogs, onSave: save))))
        }
    }

    private func limitRow(_ title: String, _ subtitle: String, _ current: Int, set: @escaping (Int32) -> Void) -> some View {
        SettingsActionRow(title: title, subtitle: subtitle, value: maxLabel(current)) {
            dialogs.push(.picker(PickerSpec(title: title,
                options: TvDebrid.shared.limitOptions.map { SettingsPickerOption(id: "\($0.int32Value)", title: maxLabel(Int($0.int32Value))) },
                selectedId: "\(current)") { set(Int32($0) ?? 0) }))
        }
    }

    private func enumRow<E: Equatable>(_ title: String, _ subtitle: String, _ options: [(E, String)], _ current: E,
                                       set: @escaping (E) -> Void) -> some View {
        let index = options.firstIndex { $0.0 == current } ?? 0
        return SettingsActionRow(title: title, subtitle: subtitle, value: options[index].1) {
            dialogs.push(.picker(PickerSpec(title: title, subtitle: subtitle,
                options: options.enumerated().map { SettingsPickerOption(id: "\($0.offset)", title: $0.element.1) },
                selectedId: "\(index)") { id in
                    if let i = Int(id), options.indices.contains(i) { set(options[i].0) }
                }))
        }
    }
}

// MARK: - TMDB

/// TmdbSettingsContent. "Modern home" and "Enrich Continue Watching" are NuvioTV-only settings the
/// shared TMDB settings don't have.
private struct TmdbSettingsDetail: View {
    @ObservedObject var model: IntegrationsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    private var repo: TmdbSettingsRepository { TmdbSettingsRepository.shared }

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "TMDB Enrichment", subtitle: "Choose which metadata fields should come from TMDB")
            if let t = model.tmdb {
                SettingsGroupCard {
                    SettingsToggleRow(title: "Enable TMDB Enrichment", subtitle: StoreCopy.tmdbEnrichmentSubtitle, isOn: t.enabled) {
                        repo.setEnabled(value: !t.enabled)
                    }
                    SettingsActionRow(title: "Language", subtitle: "TMDB metadata language for title, logo, and enabled fields",
                                      value: PlaybackLanguageLabel.name(t.language), enabled: t.enabled) {
                        let codes = TvSettings.shared.languageCodes() + ["en-AU", "en-CA", "en-GB"]
                        let options = codes.map { SettingsPickerOption(id: $0, title: PlaybackLanguageLabel.name($0)) }
                            .sorted { $0.title.localizedCompare($1.title) == .orderedAscending }
                        dialogs.push(.picker(PickerSpec(title: "TMDB Language", options: options, selectedId: t.language) { repo.setLanguage(value: $0) }))
                    }
                    toggle("Artwork", "Logo and backdrop images from TMDB", t.useArtwork, t.enabled) { repo.setUseArtwork(value: $0) }
                    toggle("Basic Info", "Description, genres, and rating from TMDB", t.useBasicInfo, t.enabled) { repo.setUseBasicInfo(value: $0) }
                    toggle("Details", "Runtime, status, country, and language from TMDB", t.useDetails, t.enabled) { repo.setUseDetails(value: $0) }
                    toggle("Credits", "Cast with photos, director, and writer from TMDB", t.useCredits, t.enabled) { repo.setUseCredits(value: $0) }
                    toggle("Productions", "Production companies from TMDB", t.useProductions, t.enabled) { repo.setUseProductions(value: $0) }
                    toggle("Networks", "Networks with logos from TMDB", t.useNetworks, t.enabled) { repo.setUseNetworks(value: $0) }
                    toggle("Episodes", "Episode titles, overviews, thumbnails, and runtime from TMDB", t.useEpisodes, t.enabled) { repo.setUseEpisodes(value: $0) }
                    toggle("Trailers", "Trailer candidates from TMDB videos for the detail trailer section", t.useTrailers, t.enabled) { repo.setUseTrailers(value: $0) }
                    toggle("More Like This", "TMDB recommendation backdrops on detail and post-play screens", t.useMoreLikeThis, t.enabled) { repo.setUseMoreLikeThis(value: $0) }
                    toggle("Collections", "TMDB movie collections in release order", t.useCollections, t.enabled) { repo.setUseCollections(value: $0) }
                }
            }
        }
    }

    private func toggle(_ title: String, _ subtitle: String, _ on: Bool, _ enabled: Bool, set: @escaping (Bool) -> Void) -> some View {
        SettingsToggleRow(title: title, subtitle: subtitle, isOn: on, enabled: enabled) { set(!on) }
    }
}

/// Language names in the viewer's locale ("en-GB" → "English (United Kingdom)").
enum PlaybackLanguageLabel {
    static func name(_ code: String) -> String {
        Locale.current.localizedString(forIdentifier: code)?.capitalized
            ?? Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? code
    }
}

// MARK: - MDBList

/// MDBListSettingsContent: enable, API key, and the rating sources in NuvioTV's order.
private struct MdbListSettingsDetail: View {
    @ObservedObject var model: IntegrationsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    private var repo: MdbListSettingsRepository { MdbListSettingsRepository.shared }

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "MDBList Ratings", subtitle: "Configure external ratings shown in the detail hero")
            if let m = model.mdbSettings {
                SettingsGroupCard {
                    SettingsToggleRow(title: "Enable MDBList Ratings", subtitle: m.hasCredentials && !m.enabled
                                      ? "Ratings are off. Turn on Enable MDBList Ratings to show them."
                                      : "Fetch ratings from external providers in metadata detail screen", isOn: m.enabled) {
                        repo.setEnabled(value: !m.enabled)
                    }
                    SettingsActionRow(title: "API Key",
                                      subtitle: "Use your MDBList account connected in Tracking, or enter a separate API key for ratings.",
                                      value: maskedKey(m.apiKey)) {
                        dialogs.push(.custom(AnyView(KeyEntryDialog(title: "API Key", subtitle: "Required to fetch ratings from MDBList",
                            placeholder: "Enter MDBList API key", initial: m.apiKey, dialogs: dialogs) { repo.setApiKey(value: $0) })))
                    }
                    let providers: [(String, String, String, Bool)] = [
                        ("trakt", "Trakt", "Show Trakt score", m.useTrakt),
                        ("imdb", "IMDb", "Show IMDb score (and hide default IMDb line when available)", m.useImdb),
                        ("tmdb", "TMDB", "Show TMDB score", m.useTmdb),
                        ("letterboxd", "Letterboxd", "Show Letterboxd score", m.useLetterboxd),
                        ("tomatoes", "Rotten Tomatoes", "Show critics score", m.useTomatoes),
                        ("audience", "Audience Score", "Show audience score", m.useAudience),
                        ("metacritic", "Metacritic", "Show Metacritic score", m.useMetacritic),
                        ("mal", "MyAnimeList", "Show MyAnimeList rating", m.useMal),
                    ]
                    ForEach(providers, id: \.0) { id, title, subtitle, on in
                        SettingsToggleRow(title: title, subtitle: subtitle, isOn: on, enabled: m.enabled) {
                            repo.setProviderEnabled(providerId: id, value: !on)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Anime-Skip

/// AnimeSkipSettingsContent: enable and the Client ID.
private struct AnimeSkipSettingsDetail: View {
    @ObservedObject var settings: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs

    var body: some View {
        let enabled = settings.player?.animeSkipEnabled ?? false
        let clientId = settings.player?.animeSkipClientId ?? ""
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Anime Skip", subtitle: "Configure your Anime Skip Client ID for anime intro/outro skipping")
            SettingsGroupCard {
                SettingsToggleRow(title: "Enable Anime Skip", subtitle: "Fetch skip timestamps from anime-skip.com", isOn: enabled) {
                    PlayerSettingsRepository.shared.setAnimeSkipEnabled(enabled: !enabled)
                }
                SettingsActionRow(title: "Client ID", subtitle: "Required to fetch skip timestamps from anime-skip.com",
                                  value: clientId.isEmpty ? "Not set" : maskedKey(clientId)) {
                    dialogs.push(.custom(AnyView(KeyEntryDialog(title: "Anime Skip Client ID",
                        subtitle: "Create your own Client ID at anime-skip.com/account/settings",
                        placeholder: "Enter Client ID", initial: clientId, dialogs: dialogs) {
                            PlayerSettingsRepository.shared.setAnimeSkipClientId(clientId: $0)
                        })))
                }
            }
        }
    }
}
