import SwiftUI
import TuvoraCore

/// NuvioTV's Settings, CLASSIC style (ui/screens/settings/SettingsScreen.kt + SettingsDesignSystem.kt):
/// a BackgroundElevated workspace (radius 28, 1dp Border, padding 20) holding a 220dp category rail and
/// the detail pane (≤ 880dp). On Apple TV a rail item selects when focus rests on it (NuvioTV Horizon's
/// 140 ms focus-select), so the detail follows the remote without an extra click.
///
/// Categories follow NuvioTV's order. Left out: Advanced and Debug.
struct SettingsScreen: View {
    enum Category: String, CaseIterable, Identifiable {
        case account, profiles, appearance, layout, discovery, integrations, playback, tracking, party, about
        var id: String { rawValue }
        /// The rail's categories. Content & Discovery manages add-ons and plugins, which the App Store
        /// build compiles out (the phone's store build drops the same row).
        static var visible: [Category] {
            allCases.filter { $0 != .discovery || StoreCopy.showsContentDiscovery }
                // NuvioTV hides Watch party while the server has it off (watch_party_enabled / capability).
                .filter { $0 != .party || TvParty.shared.state.value.available }
        }
        var title: String {
            switch self {
            case .account: return "Account"
            case .profiles: return "Profiles"
            case .appearance: return "Appearance"
            case .layout: return "Layout"
            case .discovery: return "Content & Discovery"
            case .integrations: return "Integrations"
            case .playback: return "Playback"
            case .tracking: return "Tracking"
            case .party: return "Watch party"
            case .about: return "About"
            }
        }
        var icon: String {
            switch self {
            case .account: return "md_person"
            case .profiles: return "md_people"
            case .appearance: return "md_palette"
            case .layout: return "md_grid_view"
            case .discovery: return "md_explore"
            case .integrations: return "md_link"
            case .playback: return "md_play_arrow"
            case .tracking: return "md_sync"
            case .party: return "md_people"
            case .about: return "md_info"
            }
        }
    }

    @Environment(\.nuvio) private var colors
    @StateObject private var model = SettingsModel()
    @StateObject private var dialogs = SettingsDialogs()
    @StateObject private var integrations = IntegrationsModel()
    @StateObject private var integrationsNav = IntegrationsNav()
    @StateObject private var party = PartyModel()
    /// Simulator smoke hook: `-smokeSettings <category>` opens a category, `-smokeSettingsDialog <addPlaylist|signOut|engine|playlistDetails|setupCode>` a dialog.
    @State private var selected: Category = {
        let args = AppArguments.list
        if let i = args.firstIndex(of: "-smokeSettings"), i + 1 < args.count, let c = Category(rawValue: args[i + 1]),
           Category.visible.contains(c) { return c }
        return .account
    }()
    @FocusState private var railFocus: Category?
    /// Dialogs are a focus scope: every push/pop re-picks focus inside the top dialog. Disabling the
    /// workspace alone left focus on the (now disabled) row behind the dialog, so the dialog could
    /// not be used and Menu fell through to the shell (walkthrough finding).
    @Namespace private var dialogScope
    @StateObject private var rowMemory = RowFocusMemory()
    /// Entry point that pulls focus into a freshly pushed dialog, then steps aside so tvOS re-picks
    /// focus - and with the workspace disabled, the dialog is the only place it can go.
    @FocusState private var dialogEntryFocused: Bool
    @State private var dialogEntryActive = false

    var body: some View {
        ZStack {
            workspace
                .disabled(!dialogs.stack.isEmpty)
                .environment(\.rowFocusMemory, rowMemory)
            if !dialogs.stack.isEmpty {
                Color.black.opacity(0.6).ignoresSafeArea()
                ZStack {
                    if dialogEntryActive {
                        Color.white.opacity(0.001).frame(width: 2, height: 2)
                            .focusable()
                            .focused($dialogEntryFocused)
                            .onChange(of: dialogEntryFocused) { _, now in if now { dialogEntryActive = false } }
                    }
                    ForEach(dialogs.stack) { entry in
                        let top = entry.id == dialogs.stack.last?.id
                        let fullScreen = entry.kind.isFullScreen
                        // A dialog opened over a full-screen page dims it; a full-screen page stays drawn under it.
                        ZStack {
                            if !fullScreen && top && dialogs.stack.dropLast().contains(where: { $0.kind.isFullScreen }) {
                                Color.black.opacity(0.6).ignoresSafeArea()
                            }
                            SettingsDialogView(entry: entry, model: model, dialogs: dialogs)
                        }
                        .padding(.vertical, fullScreen ? -60 : 0)
                        .opacity(top || fullScreen ? 1 : 0)
                        .disabled(!top)
                    }
                }
                .padding(.vertical, 60)
                .focusSection()
                .focusScope(dialogScope)
                .onExitCommand { dialogs.pop() }
            }
        }
        .onChange(of: dialogs.stack.isEmpty) { _, empty in if !empty { ContentFocusActivity.shared.leftEdgeOwned = true } }
        .onChange(of: dialogs.stack.last?.id) { _, top in
            // After the last dialog closes, focus returns to the row that opened it (re-enabled rows
            // don't get it back on their own); the rail item if no row is known.
            if top == nil {
                // Put focus back before the edge catcher re-arms, or tvOS can pick the catcher first.
                DispatchQueue.main.async {
                    if rowMemory.last != nil { rowMemory.restore() } else { railFocus = selected }
                    DispatchQueue.main.async { ContentFocusActivity.shared.leftEdgeOwned = false }
                }
            }
            else {
                dialogEntryActive = true
                DispatchQueue.main.async { dialogEntryFocused = true }
            }
        }
        .environmentObject(dialogs)
        .task { await model.observe() }
        .task { await integrations.observe() }
        .task { await party.observe() }
        .onReceive(DeepLinkCenter.shared.$partyCode) { code in if code != nil && party.view.available { selected = .party; railFocus = .party } }
        // The server turned watch parties off (or has none): leave the category that just disappeared.
        .onChange(of: party.view.available) { _, available in if !available && selected == .party { selected = .account; railFocus = .account } }
        .task {
            // Land on the selected category's rail item (defaultFocus alone loses to the first item
            // when the screen appears under the shell).
            railFocus = selected
            openRequestedSetupCode()
            smokeDialog()
        }
    }

    private var workspace: some View {
        HStack(alignment: .top, spacing: dp(16)) {
            // NuvioTV's rail is a LazyColumn: it scrolls when the categories outgrow the workspace.
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: dp(10)) {
                    ForEach(Category.visible) { category in
                        SettingsRailButton(title: category.title, icon: category.icon, selected: selected == category,
                                           focused: railFocus == category) { select(category) }
                            .focused($railFocus, equals: category)
                            .accessibilityIdentifier("settings.rail.\(category.rawValue)")
                    }
                }
                .padding(.vertical, dp(4)).padding(.horizontal, dp(3))
            }
            .frame(width: dp(220))
            .frame(maxHeight: .infinity)
            .focusSection()
            .defaultFocus($railFocus, selected)

            ScrollView(.vertical, showsIndicators: false) {
                detail
                    .id(selected)
                    .transition(.asymmetric(insertion: .opacity.combined(with: .offset(x: dp(40))), removal: .opacity))
                    .frame(maxWidth: dp(880), alignment: .topLeading)
                    .padding(.vertical, dp(4))
                    .padding(.horizontal, dp(6))
            }
            // Simulator smoke hook: `-smokeScrollBottom` opens the pane scrolled to its end.
            .defaultScrollAnchor(AppArguments.list.contains("-smokeScrollBottom") ? .bottom : .top)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .focusSection()
            // Menu in the detail goes back to its rail item (the detail's parent), as NuvioTV's Back.
            .onExitCommand {
                // Menu on an integration's page returns to the Integrations hub first (NuvioTV BackHandler).
                if selected == .integrations && integrationsNav.section != .hub {
                    integrationsNav.section = .hub
                } else {
                    railFocus = selected
                }
            }
        }
        .padding(dp(20))
        .background(RoundedRectangle(cornerRadius: dp(28), style: .continuous).fill(colors.backgroundElevated))
        .overlay(RoundedRectangle(cornerRadius: dp(28), style: .continuous).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
        .padding(.top, 60).padding(.bottom, 60).padding(.leading, dp(16)).padding(.trailing, 80)
        .onChange(of: railFocus) { _, focused in
            guard let focused else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.14) {
                if railFocus == focused { select(focused) }
            }
        }
    }

    private func select(_ category: Category) {
        guard selected != category else { return }
        if category == .integrations { integrationsNav.section = .hub }
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.2)) { selected = category }
    }

    @ViewBuilder
    private var detail: some View {
        switch selected {
        case .account: AccountSettingsDetail(model: model)
        case .profiles: ProfilesSettingsDetail()
        case .appearance: AppearanceSettingsDetail(model: model)
        case .layout: LayoutSettingsDetail()
        case .discovery: if StoreCopy.showsContentDiscovery { AddonsSettingsDetail(model: model) }
        case .integrations: IntegrationsSettingsDetail(nav: integrationsNav, model: integrations, settings: model)
        case .playback: PlaybackSettingsDetail(model: model)
        case .tracking: TrackingSettingsDetail(model: integrations)
        case .party: WatchPartySettingsDetail(party: party)
        case .about: AboutSettingsDetail()
        }
    }

    /// The IPTV hub's empty state ("I have a setup code") lands here: the IPTV page, with the code screen open.
    private func openRequestedSetupCode() {
        guard DeepLinkCenter.shared.openSetupCode else { return }
        DeepLinkCenter.shared.openSetupCode = false
        selected = .integrations
        integrationsNav.section = .iptv
        dialogs.push(.setupCode)
    }

    private func smokeDialog() {
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokeSettingsDialog"), i + 1 < args.count else { return }
        switch args[i + 1] {
        case "addPlaylist":
            // `-smokeFormSource <type>` picks the source; `-smokeFormM3u <url>` and
            // `-smokeBackupRows <a,b,…>` pre-fill the M3U URL and the backup rows (UITests).
            let form = PlaylistFormModel(editing: nil)
            func arg(_ name: String) -> String? { args.firstIndex(of: name).flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } }
            if let type = arg("-smokeFormSource") { form.sourceType = type }
            if let url = arg("-smokeFormM3u") { form.m3uUrl = url }
            if let rows = arg("-smokeBackupRows") { form.backupUrls = rows.split(separator: ",").map(String.init) }
            dialogs.push(.playlistForm(form))
        case "stalker":
            let form = PlaylistFormModel(editing: nil); form.sourceType = "stalker"; dialogs.push(.playlistForm(form))
        case "signOut": dialogs.push(.signOut)
        case "mediaServerAdd": dialogs.push(.mediaServerAdd(nil))
        case "mediaServerDetails": if let key = TvMediaServers.shared.firstKey() { dialogs.push(.mediaServerDetails(key)) }
        case "traktConnect": dialogs.push(.custom(AnyView(TrackingAccountDialog(provider: .trakt, model: integrations, dialogs: dialogs))))
        case "simklConnect": dialogs.push(.custom(AnyView(TrackingAccountDialog(provider: .simkl, model: integrations, dialogs: dialogs))))
        case "mdblistConnect": dialogs.push(.custom(AnyView(TrackingAccountDialog(provider: .mdblist, model: integrations, dialogs: dialogs))))
        case "disconnectTrakt": dialogs.push(.custom(AnyView(DisconnectTrackingDialog(provider: .trakt, dialogs: dialogs))))
        case "watchProgress":
            let sources = TvTrackingPolicy.shared.watchProgressSources(trakt: integrations.traktConnected, simkl: integrations.simklConnected, mdblist: integrations.mdbConnected)
            dialogs.push(.picker(PickerSpec(title: "Watch Progress",
                subtitle: "Choose the service Tuvora reads for resume and Continue Watching. Scrobbling remains active for every connected service.",
                options: sources.map { SettingsPickerOption(id: $0.name, title: TrackingSettingsDetail.label($0)) },
                selectedId: sources.first?.name ?? "", width: dp(660)) { _ in }))
        case "debridTemplate" where IntegrationSection.debrid.isAvailable:
            dialogs.push(.custom(AnyView(KeyEntryDialog(title: "Name template",
                subtitle: "Controls how result names appear. Leave blank to use the original result name.",
                placeholder: "Name template", initial: TvDebrid.shared.defaultNameTemplate, dialogs: dialogs) { _ in })))
        case "debridKey" where IntegrationSection.debrid.isAvailable:
            dialogs.push(.custom(AnyView(KeyEntryDialog(title: "Torbox API Key", subtitle: "Enter your Torbox API key.",
                                                        placeholder: "Enter Torbox API key", initial: "", dialogs: dialogs) { _ in })))
        case "engine":
            dialogs.push(.picker(PlaybackSettingsDetail.enginePicker(current: UserDefaults.standard.string(forKey: "tvos.player.engine") ?? "auto")))
        case "guideRegions": dialogs.push(.guideRegions)
        case "iptvPairing": dialogs.push(.iptvPairing)
        case "setupCode": dialogs.push(.setupCode)
        case "contentTypes", "categories", "catchUpCorrection", "guideOffset", "playlistActions", "playlistDetails":
            // `-smokeSettingsDialog <kind> [-smokeAccount <name>]`: the playlist's Content & Categories,
            // its Live TV category checklist, or its offset pickers.
            let kind = args[i + 1]
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                let wanted = args.firstIndex(of: "-smokeAccount").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
                let accounts = model.xtream?.accounts ?? []
                guard let account = accounts.first(where: { $0.name == wanted }) ?? accounts.first else { return }
                switch kind {
                case "contentTypes": dialogs.push(.contentTypes(account.id))
                case "categories": dialogs.push(.categoryChecklist(account.id, "live"))
                case "catchUpCorrection": dialogs.push(.picker(IptvOffsetPickers.catchUp(account)))
                case "guideOffset": dialogs.push(.picker(IptvOffsetPickers.guide(account)))
                default: dialogs.push(.playlistDetails(account.id, banner: args.firstIndex(of: "-smokeBanner").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }))
                }
            }
        case "hiddenItems":
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                // `-smokeAccount <name>` picks the playlist; else the first.
                let args = AppArguments.list
                let wanted = args.firstIndex(of: "-smokeAccount").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
                let accounts = model.xtream?.accounts ?? []
                if let account = accounts.first(where: { $0.name == wanted }) ?? accounts.first { dialogs.push(.hiddenItems(account)) }
            }
        default: break
        }
    }
}

// MARK: - State

/// Everything the settings detail panes read, collected from the shared repositories.
@MainActor
final class SettingsModel: ObservableObject {
    @Published var auth: AuthState?
    @Published var xtream: XtreamUiState?
    /// Step 0.3: playlist id -> the backup server answering now (1-based); absent = on its main server.
    @Published var activeServers: [String: Int] = [:]
    @Published var addons: [ManagedAddon] = []
    @Published var player: PlayerSettingsUiState?
    @Published var theme: AppTheme = .marigold
    @Published var amoled = false

    func observe() async {
        TvPlaylists.shared.ensureLoaded()
        PlayerSettingsRepository.shared.ensureLoaded()
        ThemeSettingsRepository.shared.ensureLoaded()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in for await s in AuthRepository.shared.state { self.auth = s } }
            group.addTask { @MainActor in for await s in TvPlaylists.shared.state { self.xtream = s } }
            group.addTask { @MainActor in
                for await m in TvPlaylists.shared.activeServers { self.activeServers = m.mapValues { $0.intValue } }
            }
            group.addTask { @MainActor in for await s in AddonRepository.shared.uiState { self.addons = s.addons } }
            group.addTask { @MainActor in for await s in PlayerSettingsRepository.shared.uiState { self.player = s } }
            group.addTask { @MainActor in for await t in ThemeSettingsRepository.shared.selectedTheme { self.theme = t } }
            group.addTask { @MainActor in for await on in ThemeSettingsRepository.shared.amoledEnabled { self.amoled = on.boolValue } }
        }
    }
}

enum SettingsDialogKind {
    case signOut
    case picker(PickerSpec)
    /// Step 2: the full-screen two-pane page for a playlist (replaces the old actions dialog). [banner] is a
    /// persistent line such as "Starshare added your playlist".
    case playlistDetails(String, banner: String?)
    /// Step 2: "Enter setup code", full screen.
    case setupCode
    /// Media servers: add / sign in (a dialog), and one server's page (full screen).
    case mediaServerAdd(String?)
    case mediaServerDetails(String)
    /// Detach / Remove: Cancel first, the destructive button needs OK held for two seconds.
    case holdConfirm(HoldConfirmSpec)
    case hiddenItems(XtreamAccount)
    case guideRegions
    case iptvPairing
    case contentTypes(String)                 // account id
    case categoryChecklist(String, String)    // account id, content type
    case playlistForm(PlaylistFormModel)
    /// Step 0.3: one backup row of the playlist form (address, move, remove).
    case backupServer(PlaylistFormModel, index: Int)
    case addonActions(ManagedAddon)
    case removeAddon(ManagedAddon)
    case qr(url: String, instruction: String)
    case licences
    /// A dialog that owns its own state (tracking sign-in, key entry).
    case custom(AnyView)
}

struct PickerSpec {
    let title: String
    var subtitle: String? = nil
    let options: [SettingsPickerOption]
    let selectedId: String
    var width: CGFloat = dp(420)
    let onSelect: (String) -> Void
}

extension SettingsDialogKind {
    /// Full-screen pages fill the content area (they are not centred cards) and stay visible behind a dialog opened over them.
    var isFullScreen: Bool {
        switch self {
        case .playlistDetails, .setupCode, .mediaServerDetails: return true
        default: return false
        }
    }
}

struct SettingsDialogEntry: Identifiable {
    let id = UUID()
    let kind: SettingsDialogKind
}

/// Dialogs stack (a picker can open over the playlist form, as NuvioTV's auto-refresh picker does).
@MainActor
final class SettingsDialogs: ObservableObject {
    @Published private(set) var stack: [SettingsDialogEntry] = []
    func push(_ kind: SettingsDialogKind) { stack.append(SettingsDialogEntry(kind: kind)) }
    func pop() { _ = stack.popLast() }
}

private struct SettingsDialogView: View {
    let entry: SettingsDialogEntry
    @ObservedObject var model: SettingsModel
    @ObservedObject var dialogs: SettingsDialogs

    var body: some View {
        switch entry.kind {
        case .signOut:
            // AccountSignOutConfirmationDialog
            NuvioDialog(title: "Sign out?",
                        subtitle: StoreCopy.signOutSubtitle) {
                HStack(spacing: dp(8)) {
                    Spacer()
                    SettingsDialogButton(title: "Cancel") { dialogs.pop() }
                    SettingsDialogButton(title: "Sign Out", destructive: true) {
                        dialogs.pop()
                        Task { try? await AuthRepository.shared.signOut() }
                    }
                }
            }
        case .picker(let spec):
            SettingsSingleChoiceDialog(title: spec.title, subtitle: spec.subtitle, options: spec.options,
                                       selectedId: spec.selectedId, width: spec.width) { id in
                dialogs.pop()
                spec.onSelect(id)
            }
        case .playlistDetails(let accountId, let banner):
            PlaylistDetailsPage(accountId: accountId, banner: banner, model: model, dialogs: dialogs)
        case .setupCode:
            SetupCodeScreen(dialogs: dialogs)
        case .mediaServerAdd(let existingKey):
            MediaServerAddDialog(existingKey: existingKey, dialogs: dialogs)
        case .mediaServerDetails(let key):
            MediaServerDetailsPage(key: key, dialogs: dialogs)
        case .holdConfirm(let spec):
            HoldConfirmDialog(spec: spec, dialogs: dialogs)
        case .hiddenItems(let account):
            HiddenItemsDialog(account: account, dialogs: dialogs)
        case .guideRegions:
            GuideRegionsDialog(dialogs: dialogs)
        case .iptvPairing:
            IptvPairingDialog(dialogs: dialogs)
        case .contentTypes(let accountId):
            ContentTypesDialog(accountId: accountId, model: model, dialogs: dialogs)
        case .categoryChecklist(let accountId, let type):
            CategoryChecklistDialog(accountId: accountId, type: type, model: model, dialogs: dialogs)
        case .playlistForm(let form):
            PlaylistFormDialog(form: form, xtream: model.xtream, dialogs: dialogs)
        case .backupServer(let form, let index):
            BackupServerEditorDialog(form: form, index: index, dialogs: dialogs)
        case .addonActions(let addon):
            NuvioDialog(title: addon.displayTitle, subtitle: addon.manifestUrl) {
                SettingsActionRow(title: addon.enabled ? "Disable" : "Enable", showChevron: false) {
                    AddonRepository.shared.setAddonEnabled(manifestUrl: addon.manifestUrl, enabled: !addon.enabled)
                    dialogs.pop()
                }
                SettingsActionRow(title: "Remove", showChevron: false) {
                    dialogs.pop()
                    dialogs.push(.removeAddon(addon))
                }
            }
        case .removeAddon(let addon):
            NuvioDialog(title: "Delete addon?", subtitle: "Are you sure you want to delete it?") {
                HStack(spacing: dp(8)) {
                    Spacer()
                    SettingsDialogButton(title: "No") { dialogs.pop() }
                    SettingsDialogButton(title: "Yes", destructive: true) {
                        AddonRepository.shared.removeAddon(manifestUrl: addon.manifestUrl)
                        dialogs.pop()
                    }
                }
            }
        case .qr(let url, let instruction):
            QrHandOffDialog(url: url, instruction: instruction) { dialogs.pop() }
        case .licences:
            LicencesDialog { dialogs.pop() }
        case .custom(let view):
            view
        }
    }
}

// MARK: - Account

/// AccountSettingsInline + account/AccountSettingsContent.kt.
private struct AccountSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var syncing = false

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "Account", subtitle: "Account and sync status")
            SettingsGroupCard {
                VStack(alignment: .leading, spacing: dp(6)) {
                    switch model.auth.map({ onEnum(of: $0) }) {
                    case .authenticated(let signedIn) where !signedIn.isAnonymous:
                        statusCard(email: signedIn.email ?? "")
                        AccountActionButton(icon: "md_sync", title: "Sync now",
                                            subtitle: "Get the latest from your other devices and send anything that hasn't synced yet") {
                            TvSettings.shared.syncNow()
                        }
                        signOutButton.padding(.top, dp(8))
                    case .authenticated, .unauthenticated:
                        Text(ui: StoreCopy.accountSyncDescription)
                            .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    case .loading, .none:
                        Text("Loading…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    }
                }
            }
        }
    }

    /// StatusCard: Secondary 10% on radius 8, check icon, "Signed in" label + email.
    private func statusCard(email: String) -> some View {
        HStack(spacing: dp(8)) {
            Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(16), height: dp(16)).foregroundStyle(colors.secondary)
            Text("Signed in").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            Text(email).font(NuvioType.inter(12, .medium)).foregroundStyle(colors.textPrimary).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
        .background(RoundedRectangle(cornerRadius: dp(8)).fill(colors.secondary.opacity(0.1)))
    }

    private var signOutButton: some View {
        SignOutButton { dialogs.push(.signOut) }
    }
}

/// SettingsActionButton (AccountSettingsContent.kt): BackgroundCard, FocusBackground + 2dp FocusRing
/// focused, radius 8, 22dp icon (Primary focused), bodyMedium Medium title, 11sp subtitle.
private struct AccountActionButton: View {
    let icon: String
    let title: String
    let subtitle: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                Image(icon).renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                    .foregroundStyle(focused ? colors.primary : colors.textSecondary)
                VStack(alignment: .leading, spacing: 0) {
                    Text(ui: title).font(NuvioType.inter(14, .medium)).foregroundStyle(colors.textPrimary)
                    Text(ui: subtitle).font(NuvioType.inter(11, .regular)).foregroundStyle(colors.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, dp(14)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.02 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// SignOutSettingsButton: #C62828 at 12% (25% focused), #F44336 icon/text, 50% ring when focused.
private struct SignOutButton: View {
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        let red = Color(argb: 0xFFF44336)
        Button(action: action) {
            HStack(spacing: dp(10)) {
                Image("md_logout").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                Text("Sign Out").font(NuvioType.inter(14, .medium))
                Spacer(minLength: 0)
            }
            .foregroundStyle(red)
            .padding(.horizontal, dp(14)).padding(.vertical, dp(8))
            .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color(argb: 0xFFC62828).opacity(focused ? 0.25 : 0.12)))
            .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused ? red.opacity(0.5) : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.02 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Profiles

/// ProfileSettingsContent.kt. Manage Profiles opens the picker in manage mode (edit, PIN, delete, add).
private struct ProfilesSettingsDetail: View {
    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "Profiles", subtitle: "Manage user profiles for this account")
            SettingsGroupCard {
                SettingsActionRow(title: "Manage Profiles") {
                    ProfilePickerLaunch.manageRequested = true
                    TvAppLifecycle.shared.openProfilePicker()
                }
            }
        }
    }
}

// MARK: - Appearance

/// ThemeSettingsContent (ThemeSettingsScreen.kt): colour-theme swatches + AMOLED. The synced
/// ThemeSettingsRepository drives the whole app live. Settings style, launcher artwork, font and app
/// language have no Apple TV equivalent here; "Pure Black Surfaces" is not in the shared theme settings.
private struct AppearanceSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    /// The free themes (supporter themes are the inert membership subsystem and resolve to Marigold).
    private let themes: [(AppTheme, String)] = [(.marigold, "Marigold"), (.crimson, "Crimson"), (.ocean, "Ocean"),
                                                 (.violet, "Violet"), (.emerald, "Emerald"), (.amber, "Amber"),
                                                 (.rose, "Rose"), (.white, "White")]

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Appearance", subtitle: "Choose your color theme, font and language")
            SettingsGroupCard(title: "Color Theme", subtitle: "Pick the accent color used across the app") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: dp(10)) {
                        ForEach(themes, id: \.1) { theme, name in
                            ThemeSwatchChip(name: name, palette: NuvioPalette.of(theme), selected: model.theme == theme) {
                                ThemeSettingsRepository.shared.setTheme(theme: theme)
                            }
                        }
                    }
                    .padding(dp(4))
                }
                .scrollClipDisabled()
                .focusSection()
                SettingsToggleRow(title: "AMOLED Mode", subtitle: "Use pure black for app backgrounds", isOn: model.amoled) {
                    ThemeSettingsRepository.shared.setAmoled(enabled: !model.amoled)
                }
            }
        }
    }
}

/// ThemeSwatchChip: 96dp card, radius 18, 40dp accent circle with a check when selected, labelMedium name.
private struct ThemeSwatchChip: View {
    let name: String
    let palette: NuvioPalette
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(spacing: dp(8)) {
                Circle().fill(palette.secondary).frame(width: dp(40), height: dp(40))
                    .overlay {
                        if selected {
                            Image("md_check").renderingMode(.template).resizable().frame(width: dp(20), height: dp(20))
                                .foregroundStyle(palette.onSecondary)
                        }
                    }
                Text(name).font(NuvioType.labelMedium).lineLimit(1)
                    .foregroundStyle(focused || selected ? colors.textPrimary : colors.textSecondary)
            }
            .padding(.horizontal, dp(8)).padding(.vertical, dp(12))
            .frame(width: dp(96))
            .background(RoundedRectangle(cornerRadius: dp(18), style: .continuous).fill(colors.background))
            .overlay(RoundedRectangle(cornerRadius: dp(18), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Layout

/// LayoutSettingsContent (LayoutSettingsScreen.kt), the options Apple TV screens honour. Device-local,
/// like NuvioTV's LayoutPreferenceDataStore.
private struct LayoutSettingsDetail: View {
    @AppStorage(NuvioLayoutPrefs.collapseSidebarKey) private var collapseSidebar = false
    @AppStorage(NuvioLayoutPrefs.showHeroKey) private var showHero = true
    @AppStorage(NuvioLayoutPrefs.posterLabelsKey) private var posterLabels = true
    @AppStorage(NuvioLayoutPrefs.posterWidthKey) private var posterWidth: Double = 126
    @AppStorage(NuvioLayoutPrefs.cwEnabledKey) private var cwEnabled = true
    @AppStorage(NuvioLayoutPrefs.cwStyleKey) private var cwStyle = "card"
    @AppStorage(NuvioLayoutPrefs.homeLayoutKey) private var homeLayout = "modern"
    @Environment(\.nuvio) private var colors

    private let widths: [(String, Double)] = [("Compact", 104), ("Dense", 112), ("Standard", 120),
                                              ("Balanced", 126), ("Comfort", 134), ("Large", 140)]

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Layout Settings", subtitle: "Adjust home layout, content visibility, and poster behavior")
            SettingsGroupCard(title: "Home Layout", subtitle: "Choose structure and hero source.") {
                HStack(spacing: dp(12)) {
                    ForEach([("modern", "Modern View"), ("grid", "Grid View"), ("classic", "Classic View")], id: \.0) { key, label in
                        HomeLayoutCard(layout: key, title: label, selected: homeLayout == key) { homeLayout = key }
                    }
                }
                .focusSection()
            }
            SettingsGroupCard(title: "Home Content", subtitle: "Control what appears on home and search.") {
                SettingsToggleRow(title: "Collapse Sidebar", subtitle: "Hide sidebar by default; show when focused.", isOn: collapseSidebar) { collapseSidebar.toggle() }
                SettingsToggleRow(title: "Show Hero Section", subtitle: "Display hero carousel at top of home.", isOn: showHero) { showHero.toggle() }
                SettingsToggleRow(title: "Show Poster Labels", subtitle: "Show titles under posters in rows, grid, and see-all.", isOn: posterLabels) { posterLabels.toggle() }
            }
            SettingsGroupCard(title: "Poster Card", subtitle: "Tune card width and corner radius.") {
                optionRow(title: "Width", selected: widths.first { $0.1 == posterWidth }?.0 ?? "Custom") {
                    ForEach(widths, id: \.1) { label, value in
                        SettingsChoiceChip(label: label, selected: posterWidth == value) { posterWidth = value }
                    }
                }
                SettingsDialogButton(title: "Reset to Default") { posterWidth = 126 }
            }
            SettingsGroupCard(title: "Continue Watching", subtitle: "Settings for the Continue Watching section.") {
                SettingsToggleRow(title: "Show Continue Watching", subtitle: "Show the Continue Watching row on the home screen.", isOn: cwEnabled) { cwEnabled.toggle() }
                if cwEnabled {
                    HStack(spacing: dp(8)) {
                        ForEach([("card", "Card"), ("wide", "Wide"), ("poster", "Poster")], id: \.0) { key, label in
                            SettingsChoiceChip(label: label, selected: cwStyle == key) { cwStyle = key }
                        }
                    }
                    .focusSection()
                }
            }
        }
    }

    private func optionRow<Chips: View>(title: String, selected: String, @ViewBuilder chips: () -> Chips) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            HStack {
                Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
                Spacer()
                Text(selected).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: dp(8)) { chips() }.padding(dp(4))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }
}

/// LayoutSettingsScreen.kt LayoutCard: a Background card (radius 12) with a 112dp sketch of the layout
/// and its name (labelLarge) under it, a check when selected; selected 1dp FocusRing, focused 2dp. The
/// sketches are static (NuvioTV animates them).
private struct HomeLayoutCard: View {
    let layout: String
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(spacing: dp(6)) {
                sketch.frame(maxWidth: .infinity).frame(height: dp(112))
                HStack(spacing: dp(6)) {
                    if selected {
                        Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(14), height: dp(14)).foregroundStyle(colors.focusRing)
                    }
                    Text(ui: title).font(NuvioType.labelLarge).foregroundStyle(selected || focused ? colors.textPrimary : colors.textSecondary)
                }
            }
            .padding(dp(10))
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(colors.background))
            .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous)
                .stroke(focused || selected ? colors.focusRing : .clear, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }

    private var block: Color { colors.textTertiary.opacity(0.45) }

    @ViewBuilder
    private var sketch: some View {
        switch layout {
        case "classic":
            VStack(alignment: .leading, spacing: dp(5)) {
                RoundedRectangle(cornerRadius: dp(4)).fill(block.opacity(0.6)).frame(height: dp(44))
                ForEach(0..<2, id: \.self) { _ in
                    HStack(spacing: dp(4)) { ForEach(0..<6, id: \.self) { _ in RoundedRectangle(cornerRadius: dp(3)).fill(block) } }
                        .frame(height: dp(26))
                }
            }
        case "grid":
            VStack(alignment: .leading, spacing: dp(4)) {
                RoundedRectangle(cornerRadius: dp(4)).fill(block.opacity(0.6)).frame(height: dp(30))
                ForEach(0..<3, id: \.self) { _ in
                    HStack(spacing: dp(4)) { ForEach(0..<7, id: \.self) { _ in RoundedRectangle(cornerRadius: dp(3)).fill(block) } }
                        .frame(height: dp(22))
                }
            }
        default:
            VStack(alignment: .leading, spacing: dp(5)) {
                HStack(spacing: dp(6)) {
                    VStack(alignment: .leading, spacing: dp(4)) {
                        RoundedRectangle(cornerRadius: dp(2)).fill(block).frame(width: dp(50), height: dp(8))
                        RoundedRectangle(cornerRadius: dp(2)).fill(block.opacity(0.7)).frame(width: dp(70), height: dp(5))
                        RoundedRectangle(cornerRadius: dp(2)).fill(block.opacity(0.7)).frame(width: dp(60), height: dp(5))
                    }
                    RoundedRectangle(cornerRadius: dp(4)).fill(block.opacity(0.6))
                }
                .frame(height: dp(56))
                HStack(spacing: dp(4)) { ForEach(0..<6, id: \.self) { _ in RoundedRectangle(cornerRadius: dp(3)).fill(block) } }
                    .frame(height: dp(40))
            }
        }
    }
}

// MARK: - Content & Discovery (add-ons)

/// ContentDiscoverySettingsContent + the Addons screen (ui/screens/addon), inline: install by URL,
/// the installed list, enable/disable/remove, refresh.
private struct AddonsSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var url = ""
    @State private var installing = false
    @State private var status: (text: String, error: Bool)?

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "Content & Discovery", subtitle: "Add-ons, plugins, catalogs, and discovery sources")
            SettingsGroupCard(title: "Install addon") {
                SettingsTextField(label: "Addon URL", hint: "https://example.com", text: $url) { install() }
                HStack(spacing: dp(12)) {
                    SettingsDialogButton(title: installing ? "Installing" : "Install", primary: true) { install() }
                    if let status {
                        Text(ui: status.text).font(NuvioType.bodySmall).foregroundStyle(status.error ? colors.error : colors.textSecondary)
                    }
                }
            }
            SettingsGroupCard(title: "Installed") {
                if model.addons.isEmpty {
                    Text("No addons installed. Add one to get started.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                }
                ForEach(model.addons, id: \.manifestUrl) { addon in
                    SettingsActionRow(title: addon.displayTitle,
                                      subtitle: addon.errorMessage ?? addon.manifest?.description_,
                                      value: addon.enabled ? "On" : "Off") {
                        dialogs.push(.addonActions(addon))
                    }
                }
                SettingsActionRow(title: "Refresh Addons", subtitle: "Pull latest addon changes for current profile") {
                    AddonRepository.shared.refreshAll()
                }
            }
        }
    }

    private func install() {
        let raw = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !installing else { return }
        guard !raw.isEmpty else { status = ("Enter a valid addon URL", true); return }
        if raw.contains("://"), !(raw.lowercased().hasPrefix("http://") || raw.lowercased().hasPrefix("https://")) {
            status = ("Addon URL must start with http or https", true); return
        }
        installing = true
        status = nil
        Task { @MainActor in
            defer { installing = false }
            do {
                let result = try await AddonRepository.shared.addAddon(rawUrl: raw)
                switch onEnum(of: result) {
                case .success(let ok): status = ("Installed \(ok.manifest.name)", false); url = ""
                case .error(let failure): status = (failure.message, true)
                }
            } catch {
                status = (error.localizedDescription, true)
            }
        }
    }
}

// MARK: - Integrations (IPTV)

/// XtreamSettingsContent (XtreamSettingsScreen.kt), reached from the Integrations hub as on NuvioTV.
/// Not ported: Content & Categories and the catch-up / guide-offset pickers.
struct IptvSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @State private var regionSummary = "—"
    @State private var managedLines: [String: String] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: "IPTV (Xtream Codes)", subtitle: "Paste your IPTV portal or M3U URL to add a provider.")
            SettingsGroupCard {
                SettingsActionRow(title: "Add IPTV account", subtitle: "Paste a portal / M3U URL", leadingIcon: "md_add") {
                    TvPlaylists.shared.clearError()
                    dialogs.push(.playlistForm(PlaylistFormModel(editing: nil)))
                }
                // Pair from a phone: typing on a TV remote is painful, so a QR + code lets the viewer enter
                // the playlist on their phone (NuvioTV P5).
                SettingsActionRow(title: "Add from phone", subtitle: "Scan a QR on your phone and enter a playlist — no typing on the TV",
                                  leadingIcon: "md_phone_android") {
                    dialogs.push(.iptvPairing)
                }
                SettingsActionRow(title: "Enter setup code", subtitle: "A code from your provider adds everything they set up for you",
                                  leadingIcon: "md_vpn_key") {
                    dialogs.push(.setupCode)
                }
                .accessibilityIdentifier("iptv.setupCode")
                SettingsActionRow(title: "Guide regions", subtitle: "Choose which countries' EPG this device keeps",
                                  value: regionSummary, leadingIcon: "md_explore") {
                    dialogs.push(.guideRegions)
                }
                ForEach(model.xtream?.accounts ?? [], id: \.id) { account in
                    // Step 0.3: name the backup that is answering when it isn't the main server (NuvioTV); then
                    // Step 2's "Managed by X \u{00B7} N days left" for a playlist a provider installed; else the address.
                    let source = model.activeServers[account.id].map(BackupServerCopy.using)
                        ?? managedLines[account.id]
                        ?? (account.fileName ?? TvIptvSettingsPolicy.shared.maskedAddress(url: account.baseUrl)) // P4: never the login
                    SettingsActionRow(title: TvIptvSettingsPolicy.shared.playlistName(name: account.name),
                                      subtitle: [source, model.xtream?.saveWarnings[account.id]]
                                          .compactMap { $0 }.joined(separator: "\n"),
                                      value: account.enabled ? "On" : "Off",
                                      leadingIcon: managedLines[account.id] != nil ? "md_link" : nil) {
                        dialogs.push(.playlistDetails(account.id, banner: nil))
                    }
                    .accessibilityIdentifier("playlist.row.\(account.name)")
                }
            }
        }
        .task { for await lines in TvPlaylistRows.shared.managedLines { managedLines = lines } }
        .task(id: model.xtream?.accounts.map(\.id)) { TvPlaylistRows.shared.refresh() }
        // Re-read when the region picker closes (the dialog stack changes).
        .task(id: dialogs.stack.count) {
            let regions = (try? await TvIptvPersonalize.shared.regions()) ?? []
            let selected = (try? await TvIptvPersonalize.shared.selectedRegions()) ?? []
            regionSummary = TvIptvSettingsPolicy.shared.regionSummary(selected: selected, available: regions)
        }
    }
}

/// "Hidden in <playlist>" (NuvioTV hiddenFor dialog): hides made on any device or tuvora.co, undone here.
private struct HiddenItemsDialog: View {
    let account: XtreamAccount
    @ObservedObject var dialogs: SettingsDialogs
    @State private var items: [TvHiddenItem]?

    var body: some View {
        NuvioDialog(title: "Hidden in \(TvIptvSettingsPolicy.shared.playlistName(name: account.name))",
                    subtitle: TvIptvSettingsPolicy.shared.hiddenSubtitle(loading: items == nil, count: Int32(items?.count ?? 0)),
                    width: dp(520)) {
            ForEach(items ?? [], id: \.id) { item in
                SettingsActionRow(title: item.name, subtitle: item.kindLabel, value: "Unhide", showChevron: false) {
                    TvIptvPersonalize.shared.unhide(id: item.id)
                    smokeLog("SMOKE settings unhid=%@", item.name)
                    items?.removeAll { $0.id == item.id }
                }
            }
            SettingsDialogButton(title: "Done", fullWidth: true, initialFocus: (items ?? []).isEmpty) { dialogs.pop() }
        }
        .task {
            items = (try? await TvIptvPersonalize.shared.hiddenItems(accountId: account.id)) ?? []
            smokeLog("SMOKE settings hidden count=%d", items?.count ?? -1)
            // `-smokeUnhide <name>` (with `-smokeSettingsDialog hiddenItems`) reverts one verification hide,
            // by exact name — never anything else the account has hidden.
            let args = AppArguments.list
            if let i = args.firstIndex(of: "-smokeUnhide"), i + 1 < args.count {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                for item in (items ?? []) where item.name == args[i + 1] {
                    TvIptvPersonalize.shared.unhide(id: item.id)
                    smokeLog("SMOKE settings unhid=%@", item.name)
                    items?.removeAll { $0.id == item.id }
                }
            }
        }
    }
}

/// EpgRegionPickerDialog: "Done" / "Use all", then a check row per region (flag, name, channel count);
/// checked rows FocusBackground. Empty selection = every region (the opt-in default).
private struct GuideRegionsDialog: View {
    @ObservedObject var dialogs: SettingsDialogs
    @State private var regions: [TvEpgRegion]?
    @State private var selected: Set<String> = []

    var body: some View {
        NuvioDialog(title: "Guide regions",
                    subtitle: TvIptvSettingsPolicy.shared.regionDialogSubtitle(selectedCount: Int32(selected.count), total: Int32(regions?.count ?? 0))) {
            if let regions, regions.isEmpty {
                SettingsHelperText(text: "Available after the first guide sync.")
                SettingsDialogButton(title: "Done", primary: true, fullWidth: true, initialFocus: true) { dialogs.pop() }
            } else if let regions {
                HStack(spacing: dp(8)) {
                    SettingsDialogButton(title: "Done", primary: true) {
                        TvIptvPersonalize.shared.setRegions(regions: selected)
                        smokeLog("SMOKE settings regions=%@", selected.sorted().joined(separator: ","))
                        dialogs.pop()
                    }
                    SettingsDialogButton(title: "Use all") { selected = [] }
                    Spacer()
                }
                ForEach(regions, id: \.name) { region in
                    // B119: under "All" (empty) every row is checked and OK removes just that one.
                    RegionCheckRow(region: region,
                                   checked: TvIptvSettingsPolicy.shared.regionChecked(selected: selected, name: region.name),
                                   initialFocus: region.name == regions.first?.name) {
                        selected = TvIptvSettingsPolicy.shared.toggleRegion(selected: selected, available: regions, name: region.name)
                    }
                }
            } else {
                ProgressView()
            }
        }
        .task {
            regions = (try? await TvIptvPersonalize.shared.regions()) ?? []
            selected = (try? await TvIptvPersonalize.shared.selectedRegions()) ?? []
        }
    }
}

/// EpgRegionCheckRow: card radius 10, BackgroundCard / FocusBackground when checked or focused, 16dp
/// padding; flag, name, "N channels", check.
private struct RegionCheckRow: View {
    let region: TvEpgRegion
    let checked: Bool
    let initialFocus: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(10), style: .continuous)
        Button(action: action) {
            HStack(spacing: dp(12)) {
                if !region.flag.isEmpty { Text(region.flag).font(NuvioType.titleMedium) }
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(region.name).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    Text("\(region.channelCount) channels").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
                Spacer()
                if checked {
                    Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(20), height: dp(20))
                        .foregroundStyle(colors.secondary)
                }
            }
            .padding(dp(16))
            .background(shape.fill(checked || focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: dp(2)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onAppear { if initialFocus { DispatchQueue.main.async { focused = true } } }
    }
}

/// The "Add Playlist" form's state, kept outside the view so a picker can open over it.
@MainActor
final class PlaylistFormModel: ObservableObject {
    let editing: XtreamAccount?
    @Published var sourceType = "xtream"
    @Published var pasteLink = false
    @Published var playlistUrl = ""
    @Published var server = ""
    @Published var username = ""
    @Published var password = ""
    @Published var name = ""
    @Published var userAgent = ""
    @Published var m3uUrl = ""
    @Published var portalUrl = ""
    @Published var macAddress = ""
    @Published var stalkerUsername = ""
    @Published var stalkerPassword = ""
    @Published var serialNumber = ""
    @Published var deviceId = ""
    @Published var sendDeviceId = true
    // F46: optional STB identity overrides; blank = the values derived from the MAC.
    @Published var deviceId2 = ""
    @Published var signature = ""
    @Published var stbModel = ""
    @Published var hwVersion = ""
    @Published var epgUrl = ""
    @Published var autoRefreshHours: Int32 = TvPlaylistFormPolicy.shared.DEFAULT_AUTO_REFRESH_HOURS
    /// Step 0.3: backup server rows as typed, priority order (validated by the shared rules on save).
    @Published var backupUrls: [String] = []

    init(editing: XtreamAccount?) {
        self.editing = editing
        guard let editing else { return }
        let f = TvPlaylistFormPolicy.shared.fromAccount(account: editing)
        sourceType = f.sourceType; server = f.server; username = f.username; password = f.password
        name = f.name; userAgent = f.userAgent; m3uUrl = f.m3uUrl; portalUrl = f.portalUrl
        macAddress = f.macAddress; stalkerUsername = f.stalkerUsername; stalkerPassword = f.stalkerPassword
        serialNumber = f.serialNumber; deviceId = f.deviceId; sendDeviceId = f.sendDeviceId
        deviceId2 = f.deviceId2; signature = f.signature; stbModel = f.stbModel; hwVersion = f.hwVersion
        epgUrl = f.epgUrl; autoRefreshHours = f.autoRefreshHours; backupUrls = f.backupUrls
    }

    var kotlin: TvPlaylistForm {
        TvPlaylistForm(sourceType: sourceType, pasteLink: pasteLink, playlistUrl: playlistUrl, server: server,
                       username: username, password: password, name: name, userAgent: userAgent, m3uUrl: m3uUrl,
                       portalUrl: portalUrl, macAddress: macAddress, stalkerUsername: stalkerUsername,
                       stalkerPassword: stalkerPassword, serialNumber: serialNumber, deviceId: deviceId,
                       sendDeviceId: sendDeviceId, deviceId2: deviceId2, signature: signature,
                       stbModel: stbModel, hwVersion: hwVersion, epgUrl: epgUrl, autoRefreshHours: autoRefreshHours,
                       backupUrls: backupUrls)
    }
}

/// XtreamAddDialog: 760dp NuvioDialog, source-type tiles, the source's fields, EPG URL, auto-refresh,
/// the no-content disclaimer, the Add button and the verify status. DNS provider is left out: Apple TV
/// (like iOS) can't resolve one playlist through its own DNS.
private struct PlaylistFormDialog: View {
    @ObservedObject var form: PlaylistFormModel
    let xtream: XtreamUiState?
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @FocusState private var sourceFocus: String?
    /// The backup row (or "Add backup server") that opened the row editor: focus goes back to it when the
    /// editor closes, instead of to the top of this long form.
    @FocusState private var backupFocus: BackupFocus?
    @State private var returnFocus: BackupFocus?
    @State private var depth = 0

    private let sources = [("m3u_url", "URL"), ("m3u_file", "File"), ("xtream", "Xtream"), ("stalker", "Stalker")]

    var body: some View {
        let validating = xtream?.isValidating ?? false
        NuvioDialog(title: form.editing != nil ? "Edit Playlist" : "Add Playlist",
                    subtitle: form.editing != nil ? "Update this playlist's source and options" : "Add an IPTV source and choose its options",
                    width: dp(760)) {
            VStack(alignment: .leading, spacing: dp(16)) {
                label("Source Type")
                HStack(spacing: dp(8)) {
                    ForEach(sources, id: \.0) { id, title in
                        SettingsChoiceChip(label: title, selected: form.sourceType == id) { form.sourceType = id }
                            .focused($sourceFocus, equals: id)
                    }
                }
                .focusSection()

                sourceFields

                if TvBackupServers.shared.supports(sourceType: form.sourceType) { backupServers }

                label("EPG URL (optional)")
                SettingsTextField(label: "EPG URL", hint: "http://host:port/xmltv.php?username=…&password=…", text: $form.epgUrl)

                label("Auto-Refresh")
                SettingsActionRow(title: "Auto-Refresh", value: TvPlaylistFormPolicy.shared.autoRefreshLabel(hours: form.autoRefreshHours)) {
                    dialogs.push(.picker(PickerSpec(
                        title: "Auto-Refresh",
                        options: TvPlaylistFormPolicy.shared.autoRefreshOptions.map {
                            SettingsPickerOption(id: "\($0.int32Value)", title: TvPlaylistFormPolicy.shared.autoRefreshLabel(hours: $0.int32Value))
                        },
                        selectedId: "\(form.autoRefreshHours)") { id in form.autoRefreshHours = Int32(id) ?? 24 }))
                }
                SettingsHelperText(text: "Periodically check this playlist for new movies and series.")
                SettingsHelperText(text: "Tuvora does not provide any channels, movies, or series. All content comes from the playlist you add here, and you must have a valid subscription with that provider.")

                AddPlaylistButton(label: validating ? "Verifying…" : (form.editing != nil ? "Save changes" : "Add Playlist"),
                                  enabled: TvPlaylistFormPolicy.shared.canSubmit(form: form.kotlin)) { submit() }
                    .accessibilityIdentifier("playlist.submit")

                if validating {
                    Text("Verifying…").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                } else if let error = xtream?.error {
                    Text(error).font(NuvioType.bodySmall).foregroundStyle(colors.error)
                }
            }
        }
        .defaultFocus($sourceFocus, form.sourceType)
        .onAppear {
            depth = dialogs.stack.count
            let type = form.sourceType; DispatchQueue.main.async { sourceFocus = type }
        }
        .onChange(of: dialogs.stack.count) { _, count in
            // Back on top after the row editor closed: after the dialog host re-picks focus, put it on the
            // row that opened the editor (the row index may have moved; clamp to what is left).
            guard count == depth, let target = returnFocus else { return }
            returnFocus = nil
            let wanted = target.clamped(to: form.backupUrls.count)
            // The host first parks focus on its entry point and lets tvOS re-pick; claim it once that
            // settles (a few tries, so a slow hand-off can't leave focus at the top of the form).
            for delay in [0.2, 0.45, 0.8, 1.3] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if backupFocus != wanted { backupFocus = wanted } }
            }
        }
    }

    /// Step 0.3 "Backup servers" (NuvioTV BackupServersSection): one row per server, priority order; OK
    /// opens the row editor, so moving through the form never pops the keyboard. A row's problem shows as
    /// its value in the error colour; Add is offered until the shared maximum.
    @ViewBuilder
    private var backupServers: some View {
        let problems = Dictionary(TvBackupServers.shared.problems(form: form.kotlin).map { (Int($0.index), $0.message) },
                                  uniquingKeysWith: { first, _ in first })
        label("Backup servers")
        SettingsHelperText(text: "Used automatically if the main server doesn't respond.")
        ForEach(Array(form.backupUrls.enumerated()), id: \.offset) { index, value in
            SettingsActionRow(title: BackupServerCopy.row(index + 1),
                              // P4: a read-only row — the login is masked; the editor it opens keeps the raw value.
                              subtitle: value.isEmpty ? L("Not set — press OK to enter an address")
                                  : TvIptvSettingsPolicy.shared.maskedAddress(url: value),
                              value: problems[index].map(L), valueColor: colors.error) {
                openEditor(index: index)
            }
            .focused($backupFocus, equals: .row(index))
            .accessibilityIdentifier("backup.row.\(index)")
        }
        if TvBackupServers.shared.canAdd(rows: form.backupUrls) {
            SettingsActionRow(title: "Add backup server", subtitle: BackupServerCopy.addSubtitle(Int(TvBackupServers.shared.MAX_BACKUPS)),
                              leadingIcon: "md_add") {
                form.backupUrls = TvBackupServers.shared.add(rows: form.backupUrls)
                openEditor(index: form.backupUrls.count - 1)
            }
            .focused($backupFocus, equals: .add)
            .accessibilityIdentifier("backup.add")
        }
    }

    private func openEditor(index: Int) {
        returnFocus = .row(index)
        dialogs.push(.backupServer(form, index: index))
    }

    @ViewBuilder
    private var sourceFields: some View {
        switch form.sourceType {
        case "m3u_url":
            SettingsTextField(label: "M3U URL", hint: "(http://host/get.php?…&type=m3u_plus  or  …/playlist.m3u)", text: $form.m3uUrl, onSubmit: submit)
            SettingsTextField(label: "User-Agent (optional)", text: $form.userAgent, onSubmit: submit)
            SettingsTextField(label: "Name (optional)", text: $form.name, onSubmit: submit)
            SettingsHelperText(text: "Paste the playlist URL. The whole list is downloaded and indexed once so it browses fast; large lists take a moment after saving.")
        case "m3u_file":
            SettingsHelperText(text: "No file picker on this device — add the file by URL or from your phone (it syncs).")
        case "stalker":
            SettingsTextField(label: "Portal URL", hint: "(http://host:port)", text: $form.portalUrl, onSubmit: submit)
            SettingsTextField(label: "MAC Address", hint: "(00:1A:79:xx:xx:xx)", text: $form.macAddress, onSubmit: submit)
            SettingsActionRow(title: "Randomize MAC", subtitle: "Generate a virtual STB MAC (Infomir range)") {
                form.macAddress = TvPlaylistFormPolicy.shared.randomStbMac()
            }
            SettingsTextField(label: "Username (optional)", text: $form.stalkerUsername, onSubmit: submit)
            SettingsTextField(label: "Password (optional)", text: $form.stalkerPassword, secure: true, onSubmit: submit)
            SettingsTextField(label: "Serial Number (optional)", text: $form.serialNumber, onSubmit: submit)
            SettingsTextField(label: "Device ID (optional)", text: $form.deviceId, onSubmit: submit)
            SettingsTextField(label: "Device ID 2 (optional)", text: $form.deviceId2, onSubmit: submit)
            SettingsTextField(label: "Signature (optional)", text: $form.signature, onSubmit: submit)
            SettingsTextField(label: "STB Model (optional, e.g. MAG254)", text: $form.stbModel, onSubmit: submit)
            SettingsTextField(label: "Hardware Version (optional, e.g. 2.6-IB-00)", text: $form.hwVersion, onSubmit: submit)
            SettingsActionRow(title: "Send Device ID", subtitle: "Include device identifier in portal requests",
                              value: form.sendDeviceId ? "On" : "Off") { form.sendDeviceId.toggle() }
            SettingsHelperText(text: "Enter your portal URL and the MAC registered with the provider. The serial, device IDs and signature are derived from the MAC and Tuvora presents itself as a MAG250 — only fill in the optional fields if your provider (or your old box) gave you specific values. Long values are easier to enter from your phone: use \"Add from phone\".")
        default:
            HStack(spacing: dp(8)) {
                SettingsChoiceChip(label: "Enter details", selected: !form.pasteLink) { form.pasteLink = false }
                SettingsChoiceChip(label: "Paste link", selected: form.pasteLink) { form.pasteLink = true }
            }
            .focusSection()
            .padding(.top, dp(4))
            if form.pasteLink {
                SettingsTextField(label: "Playlist URL", hint: "http://host:port/get.php?username=…&password=…", text: $form.playlistUrl, onSubmit: submit)
            } else {
                SettingsTextField(label: "Server URL", hint: "(portal, e.g. http://host:port)", text: $form.server, onSubmit: submit)
                SettingsTextField(label: "Username", text: $form.username, onSubmit: submit)
                SettingsTextField(label: "Password", text: $form.password, secure: true, onSubmit: submit)
                SettingsTextField(label: "Name (optional)", text: $form.name, onSubmit: submit)
                SettingsTextField(label: "User-Agent (optional)", text: $form.userAgent, onSubmit: submit)
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(ui: text).font(NuvioType.labelLarge).foregroundStyle(colors.textPrimary)
    }

    private func submit() {
        let kotlinForm = form.kotlin
        guard TvPlaylistFormPolicy.shared.canSubmit(form: kotlinForm), !(xtream?.isValidating ?? false) else { return }
        smokeLog("SMOKE playlist submit source=%@ backups=%ld", form.sourceType, form.backupUrls.count)
        let done: (KotlinBoolean) -> Void = { ok in
            if ok.boolValue { DispatchQueue.main.async { dialogs.pop() } }
        }
        if let editing = form.editing {
            TvPlaylists.shared.edit(accountId: editing.id, form: kotlinForm, onResult: done)
        } else {
            TvPlaylists.shared.add(form: kotlinForm, onResult: done)
        }
    }
}

/// XtreamAddButton: full width, radius 10, BackgroundElevated + 1dp Border; Primary fill when focused.
private struct AddPlaylistButton: View {
    let label: String
    let enabled: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(ui: label).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary.opacity(enabled ? 1 : 0.4))
                .frame(maxWidth: .infinity).padding(.vertical, dp(12))
                .background(RoundedRectangle(cornerRadius: dp(10)).fill(focused ? colors.primary : colors.backgroundElevated))
                .overlay(RoundedRectangle(cornerRadius: dp(10)).stroke(focused ? colors.primary : colors.border, lineWidth: NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .padding(.top, dp(12))
    }
}

// MARK: - Backup servers (Step 0.3)

/// Which backup control of the playlist form had focus (restored when the row editor closes).
enum BackupFocus: Hashable {
    case row(Int), add
    func clamped(to count: Int) -> BackupFocus {
        guard case .row(let i) = self else { return self }
        return count == 0 ? .add : .row(min(i, count - 1))
    }
}

/// NuvioTV's backup-server copy (iptv_backup_server_* / iptv_using_backup_server*), looked up by key so
/// translations flow in through Scripts/gen-localizable.py.
enum BackupServerCopy {
    static func row(_ n: Int) -> String { String(format: LK("iptv_backup_server_row", "Backup server %1$ld"), n) }
    static func addSubtitle(_ max: Int) -> String {
        String(format: LK("iptv_backup_server_add_subtitle", "Another address for the same playlist (up to %1$ld)"), max)
    }
    static func using(_ n: Int) -> String { String(format: LK("iptv_using_backup_server", "Using backup server %1$ld"), n) }
    static func usingAddress(_ n: Int, _ address: String) -> String {
        String(format: LK("iptv_using_backup_server_host", "Using backup server %1$ld (%2$@)"), n,
               TvIptvSettingsPolicy.shared.maskedAddress(url: address))
    }
    static func removeTitle(_ n: Int) -> String {
        String(format: LK("iptv_backup_server_remove_confirm_title", "Remove backup server %1$ld?"), n)
    }
    static func removeSubtitle(_ address: String) -> String {
        String(format: LK("iptv_backup_server_remove_confirm_subtitle", "%1$@ won't be tried if the main server stops responding."),
               TvIptvSettingsPolicy.shared.maskedAddress(url: address))
    }
}

/// NuvioTV BackupServerEditorDialog: one backup row — its address (the native keyboard opens only when
/// the field is clicked), the moves that can act (UX100: a dead move is left out, not disabled), Remove
/// (confirmed) and Done. The editor follows a moved server; a row left blank is dropped on close.
private struct BackupServerEditorDialog: View {
    @ObservedObject var form: PlaylistFormModel
    @State var index: Int
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @FocusState private var focus: EditorFocus?
    @State private var depth = 0
    @State private var returnFocus: EditorFocus?
    @State private var removed = false

    enum EditorFocus: Hashable { case moveUp, moveDown, remove, done }

    var body: some View {
        let count = form.backupUrls.count
        NuvioDialog(title: BackupServerCopy.row(index + 1), subtitle: "Used automatically if the main server doesn't respond.",
                    width: dp(560)) {
            if index < count {
                // First focusable of the dialog, so the host's focus hand-off lands here on open.
                SettingsTextField(label: "Server address", hint: TvBackupServers.shared.hint(sourceType: form.sourceType),
                                  text: address, id: "backup.address")
                if let problem = TvBackupServers.shared.problemAt(form: form.kotlin, index: Int32(index)) {
                    Text(ui: problem).font(NuvioType.bodySmall).foregroundStyle(colors.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                ForEach(TvBackupServers.shared.rowActions(index: Int32(index), count: Int32(count)), id: \.self) { action in
                    actionRow(action, count: count)
                }
            }
            SettingsDialogButton(title: "Done", primary: true, fullWidth: true) { close() }
                .focused($focus, equals: .done)
                .accessibilityIdentifier("backup.done")
        }
        .onAppear { depth = dialogs.stack.count }
        .onChange(of: dialogs.stack.count) { _, now in
            // Back from "Remove backup server N?" (Cancel): focus returns to Remove, as on NuvioTV.
            guard now == depth, let target = returnFocus else { return }
            returnFocus = nil
            for delay in [0.2, 0.45, 0.8, 1.3] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } }
            }
        }
        .onDisappear {
            // Menu or Done on a row nobody typed into drops it (NuvioTV), never after a confirmed remove.
            if !removed, index < form.backupUrls.count, form.backupUrls[index].trimmingCharacters(in: .whitespaces).isEmpty {
                form.backupUrls = TvBackupServers.shared.remove(rows: form.backupUrls, index: Int32(index))
            }
        }
    }

    private var address: Binding<String> {
        Binding(get: { index < form.backupUrls.count ? form.backupUrls[index] : "" },
                set: { form.backupUrls = TvBackupServers.shared.update(rows: form.backupUrls, index: Int32(index), value: $0) })
    }

    @ViewBuilder
    private func actionRow(_ action: TvBackupRowAction, count: Int) -> some View {
        switch action {
        case .moveUp:
            SettingsActionRow(title: "Move up", subtitle: "Try this server earlier", showChevron: false) {
                form.backupUrls = TvBackupServers.shared.moveUp(rows: form.backupUrls, index: Int32(index))
                index -= 1
                focus = index > 0 ? .moveUp : .moveDown
            }
            .focused($focus, equals: .moveUp)
            .accessibilityIdentifier("backup.moveUp")
        case .moveDown:
            SettingsActionRow(title: "Move down", subtitle: "Try this server later", showChevron: false) {
                form.backupUrls = TvBackupServers.shared.moveDown(rows: form.backupUrls, index: Int32(index))
                index += 1
                focus = index < count - 1 ? .moveDown : .moveUp
            }
            .focused($focus, equals: .moveDown)
            .accessibilityIdentifier("backup.moveDown")
        case .remove:
            SettingsActionRow(title: "Remove backup server", showChevron: false) {
                let value = form.backupUrls[index]
                guard action.needsConfirmation else { removeRow(); return }
                returnFocus = .remove
                dialogs.push(.custom(AnyView(RemoveBackupServerDialog(index: index, address: value, dialogs: dialogs) { removeRow() })))
            }
            .focused($focus, equals: .remove)
            .accessibilityIdentifier("backup.remove")
        }
    }

    private func removeRow() {
        removed = true
        form.backupUrls = TvBackupServers.shared.remove(rows: form.backupUrls, index: Int32(index))
        dialogs.pop()
    }

    private func close() { dialogs.pop() }
}

/// UX100 — "Remove backup server N?" in the Remove-playlist shape: a danger-tinted Remove over Cancel,
/// focus on Cancel so a stray click can't delete; Cancel or Menu goes back to the row editor.
private struct RemoveBackupServerDialog: View {
    let index: Int
    let address: String
    @ObservedObject var dialogs: SettingsDialogs
    let onConfirm: () -> Void
    @FocusState private var cancelFocused: Bool

    var body: some View {
        NuvioDialog(title: BackupServerCopy.removeTitle(index + 1),
                    subtitle: address.isEmpty ? nil : BackupServerCopy.removeSubtitle(address),
                    width: dp(460)) {
            SettingsDialogButton(title: "Remove backup server", destructive: true, fullWidth: true) {
                dialogs.pop()
                onConfirm()
            }
            .accessibilityIdentifier("backup.remove.confirm")
            SettingsDialogButton(title: "Cancel", fullWidth: true) { dialogs.pop() }
                .focused($cancelFocused)
                .accessibilityIdentifier("backup.remove.cancel")
        }
        .defaultFocus($cancelFocused, true)
        .onAppear { DispatchQueue.main.async { cancelFocused = true } }
    }
}

// MARK: - Playback

/// PlaybackSettingsContent (PlaybackSettingsSections.kt + PlaybackAudio/Subtitle/AutoPlaySettings.kt),
/// the options with meaning on Apple TV. Shared settings (synced) go through PlayerSettingsRepository;
/// the engine choice is Apple-TV-only and read by TvPlayerSession at open.
private struct PlaybackSettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @AppStorage("tvos.player.engine") private var engine = "auto"

    var body: some View {
        let player = model.player
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Playback Settings", subtitle: "Configure video playback and subtitle options")
            SettingsGroupCard(title: "General", subtitle: "Core playback behavior.") {
                SettingsToggleRow(title: "Skip Intro", subtitle: "Use introdb.app to detect intros and recaps.",
                                  isOn: player?.skipIntroEnabled ?? true) {
                    PlayerSettingsRepository.shared.setSkipIntroEnabled(enabled: !(player?.skipIntroEnabled ?? true))
                }
            }
            SettingsGroupCard(title: "Player & Stream Selection", subtitle: "Player preference, auto-play, and source filtering.") {
                SettingsActionRow(title: "Internal Engine", value: Self.engineLabel(engine)) {
                    dialogs.push(.picker(Self.enginePicker(current: engine) { engine = $0 }))
                }
                SettingsActionRow(title: "Auto Stream Selection", value: autoPlayLabel(player?.streamAutoPlayMode)) {
                    dialogs.push(.picker(PickerSpec(
                        title: "Auto Stream Selection",
                        options: [SettingsPickerOption(id: "manual", title: "Manual (choose stream)", description: "Always show source list and let me choose."),
                                  SettingsPickerOption(id: "first", title: "Auto-play first source", description: "Play the first available source automatically.")],
                        selectedId: player?.streamAutoPlayMode == .firstStream ? "first" : "manual", width: dp(520)) { id in
                            PlayerSettingsRepository.shared.setStreamAutoPlayMode(mode: id == "first" ? .firstStream : .manual)
                        }))
                }
                SettingsToggleRow(title: "Auto-play Next Episode", subtitle: "Start next episode automatically when prompt appears.",
                                  isOn: player?.streamAutoPlayNextEpisodeEnabled ?? true) {
                    PlayerSettingsRepository.shared.setStreamAutoPlayNextEpisodeEnabled(enabled: !(player?.streamAutoPlayNextEpisodeEnabled ?? true))
                }
            }
            SettingsGroupCard(title: "Audio & Video", subtitle: "Audio controls and video compatibility.") {
                SettingsActionRow(title: "Preferred Audio Language", value: Self.languageLabel(player?.preferredAudioLanguage ?? "device")) {
                    dialogs.push(.picker(PickerSpec(title: "Preferred Audio Language", options: Self.audioOptions(),
                                                    selectedId: player?.preferredAudioLanguage ?? "device") {
                        PlayerSettingsRepository.shared.setPreferredAudioLanguage(language: $0)
                    }))
                }
            }
            SettingsGroupCard(title: "Subtitles", subtitle: "Language, style, and render mode.") {
                SettingsActionRow(title: "Preferred Language", value: Self.languageLabel(player?.preferredSubtitleLanguage ?? "none")) {
                    dialogs.push(.picker(PickerSpec(title: "Preferred Language", options: Self.subtitleOptions(),
                                                    selectedId: player?.preferredSubtitleLanguage ?? "none") {
                        PlayerSettingsRepository.shared.setPreferredSubtitleLanguage(language: $0)
                    }))
                }
                let size = player?.subtitleStyle.fontSizeSp ?? 18
                SettingsActionRow(title: "Size", value: "\(size)") {
                    dialogs.push(.picker(PickerSpec(title: "Size", options: stride(from: 12, through: 40, by: 2).map { SettingsPickerOption(id: "\($0)", title: "\($0)") },
                                                    selectedId: "\(size)") { TvSettings.shared.setSubtitleFontSize(sp: Int32($0) ?? 18) }))
                }
                SettingsToggleRow(title: "Bold", subtitle: "Use bold font weight for subtitles", isOn: player?.subtitleStyle.bold ?? false) {
                    TvSettings.shared.setSubtitleBold(bold: !(player?.subtitleStyle.bold ?? false))
                }
                SettingsToggleRow(title: "Outline", subtitle: "Add outline around subtitle text for better visibility", isOn: player?.subtitleStyle.outlineEnabled ?? true) {
                    TvSettings.shared.setSubtitleOutline(enabled: !(player?.subtitleStyle.outlineEnabled ?? true))
                }
            }
        }
    }

    static func engineLabel(_ key: String) -> String {
        switch key { case "libmpv": return "libmpv"; case "avplayer": return "AVPlayer"; default: return "Auto (Best for Content)" }
    }

    static func enginePicker(current: String, onSelect: @escaping (String) -> Void = { UserDefaults.standard.set($0, forKey: "tvos.player.engine") }) -> PickerSpec {
        PickerSpec(title: "Internal Engine",
                   options: [SettingsPickerOption(id: "auto", title: engineLabel("auto")),
                             SettingsPickerOption(id: "libmpv", title: engineLabel("libmpv")),
                             SettingsPickerOption(id: "avplayer", title: engineLabel("avplayer"))],
                   selectedId: current, onSelect: onSelect)
    }

    private func autoPlayLabel(_ mode: StreamAutoPlayMode?) -> String {
        switch mode {
        case .firstStream: return "Auto-play first source"
        case .regexMatch: return "Auto-play regex match"
        default: return "Manual (choose stream)"
        }
    }

    static func languageLabel(_ code: String) -> String {
        switch code.lowercased() {
        case "device": return "Device language"
        case "default": return "Default (media file)"
        case "original": return "Original language"
        case "none", "": return "None"
        default: return Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? code
        }
    }

    private static func languages() -> [SettingsPickerOption] {
        TvSettings.shared.languageCodes()
            .map { SettingsPickerOption(id: $0, title: languageLabel($0)) }
            .sorted { $0.title.localizedCompare($1.title) == .orderedAscending }
    }

    static func audioOptions() -> [SettingsPickerOption] {
        [SettingsPickerOption(id: "device", title: "Device language"),
         SettingsPickerOption(id: "default", title: "Default (media file)"),
         SettingsPickerOption(id: "original", title: "Original language", description: "Requires TMDB enrichment to be enabled")] + languages()
    }

    static func subtitleOptions() -> [SettingsPickerOption] {
        [SettingsPickerOption(id: "none", title: "None"), SettingsPickerOption(id: "device", title: "Device language")] + languages()
    }
}

// MARK: - About

/// AboutSettingsContent (AboutScreen.kt): wordmark, version, Discord and privacy links (QR hand-off —
/// Apple TV has no browser), licences. In-app updates are the App Store's job on Apple TV.
private struct AboutSettingsDetail: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–" }

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "About", subtitle: "App information, updates, and legal links")
            SettingsGroupCard {
                VStack(spacing: dp(10)) {
                    Image("app_logo_wordmark").resizable().scaledToFit().frame(width: dp(180), height: dp(40))
                    Text("Version \(version)").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, dp(4))
                SettingsActionRow(title: "Join the Discord", subtitle: "Setup help, feature ideas and release news", trailingIcon: "md_open_in_new") {
                    dialogs.push(.qr(url: "https://discord.gg/wFu9T2nS8X", instruction: "Scan with your phone to join the Tuvora Discord"))
                }
                SettingsActionRow(title: "Privacy Policy", subtitle: "View our privacy policy", trailingIcon: "md_open_in_new") {
                    dialogs.push(.qr(url: "https://tuvora.co/privacy", instruction: "No web browser on this TV. Scan with your phone to open this page"))
                }
                SettingsActionRow(title: "Licenses & Attribution", subtitle: "Data sources, acknowledgements, and licenses") {
                    dialogs.push(.licences)
                }
            }
        }
    }
}

/// LicensesAttributionsScreen, as a dialog: the app licence and the platform-neutral data credits.
private struct LicencesDialog: View {
    let close: () -> Void
    @Environment(\.nuvio) private var colors

    private let entries: [(String, String)] = [
        ("Tuvora", "Source code and license terms are available in the project repository. Licensed under the GNU General Public License v3.0."),
        ("The Movie Database (TMDB)", "Tuvora uses the TMDB API for movie and TV metadata, artwork, trailers, cast, production details, collections, and recommendations. This product uses the TMDB API but is not endorsed or certified by TMDB."),
        ("IMDb Non-Commercial Datasets", "Tuvora uses IMDb Non-Commercial Datasets, including title.ratings.tsv.gz, for IMDb ratings and vote counts. Information courtesy of IMDb (https://www.imdb.com). Used with permission. IMDb data is for personal and non-commercial use under IMDb terms."),
        ("libmpv / MPVKit", "Used as a playback engine. mpv/libmpv and bundled native components retain their upstream license terms."),
    ]

    var body: some View {
        NuvioDialog(title: "Licenses & Attribution", width: dp(640)) {
            ForEach(entries, id: \.0) { title, text in
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(ui: title).font(NuvioType.titleSmall).foregroundStyle(colors.textPrimary)
                    Text(ui: text).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, dp(6))
            }
            SettingsDialogButton(title: "Close", primary: true, action: close)
        }
        .frame(maxHeight: dp(460))
    }
}


/// IptvPairingScreen (NuvioTV P5): "Add IPTV from your phone" — QR (220dp on white) beside the code
/// (Primary-outlined, 4sp tracking), the URL, "Waiting for a playlist from your phone…" and the expiry;
/// then success / expired / error with Try again. The poll lives in this dialog's task only.
private struct IptvPairingDialog: View {
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var state = TvPairingState(status: "loading", code: nil, webUrl: nil, expiresAtMs: nil, message: nil)
    @State private var attempt = 0
    @State private var now = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            switch state.status {
            case "success":
                Text("Playlist added").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                Text(state.message ?? "Your playlist is now on your TV.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                SettingsDialogButton(title: "Done", primary: true, initialFocus: true) { dialogs.pop() }
            case "expired", "error":
                Text(state.status == "expired" ? "Pairing code expired" : "Couldn't pair")
                    .font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                Text(state.status == "expired" || state.message == nil
                     ? "The code timed out before a playlist was received. Try again to get a new code."
                     : state.message!)
                    .font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                HStack(spacing: dp(8)) {
                    SettingsDialogButton(title: "Try again", primary: true, initialFocus: true) { attempt += 1 }
                    SettingsDialogButton(title: "Back") { dialogs.pop() }
                }
            default:
                Text("Add IPTV from your phone").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                Text("Scan the QR code with your phone, or open the link below and enter the code. Then type your playlist on the phone — it will appear here.")
                    .font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).frame(maxWidth: dp(520), alignment: .leading)
                HStack(alignment: .top, spacing: dp(32)) {
                    qr
                    codeColumn
                }
                .padding(.top, dp(12))
                SettingsDialogButton(title: "Back", initialFocus: true) { dialogs.pop() }
                    .padding(.top, dp(12))
            }
        }
        .padding(dp(32))
        .frame(maxWidth: dp(720), alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(20)).fill(colors.backgroundElevated))
        .task(id: attempt) {
            try? await TvIptvPairing.shared.run { next in
                // The session reports from Kotlin's dispatcher threads; state belongs to the main actor.
                DispatchQueue.main.async { state = next }
                #if DEBUG
                smokeLog("SMOKE pairing status=%@ code=%@", next.status, next.code ?? "-")
                #endif
            }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                now = Date()
            }
        }
    }

    @ViewBuilder
    private var qr: some View {
        if let url = state.webUrl, let image = QrCode.image(for: url) {
            Image(decorative: image, scale: 1).interpolation(.none).resizable()
                .padding(dp(10)).frame(width: dp(220), height: dp(220))
                .background(RoundedRectangle(cornerRadius: dp(12)).fill(Color.white))
                .accessibilityLabel("IPTV pairing QR code")
        } else {
            Text(state.status == "loading" ? "Generating QR…" : "QR unavailable")
                .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                .frame(width: dp(220), height: dp(220))
                .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
                .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(colors.border, lineWidth: dp(1)))
        }
    }

    private var codeColumn: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            if let code = state.code {
                Text("Your code").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                Text(code).font(NuvioType.inter(36, .bold)).tracking(dp(4)).foregroundStyle(colors.textPrimary)
                    .fixedSize()
                    .padding(.horizontal, dp(20)).padding(.vertical, dp(14))
                    .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(colors.primary.opacity(0.5), lineWidth: dp(1)))
                Text("On your phone, go to:").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).padding(.top, dp(16))
                Text(TvIptvPairingPolicy.shared.displayUrl(baseUrl: TvIptvPairingPolicy.shared.WEB_BASE_URL))
                    .font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                HStack(spacing: dp(8)) {
                    ProgressView().scaleEffect(0.6)
                    Text(state.status == "saving" ? "Adding your playlist…" : "Waiting for a playlist from your phone…")
                        .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
                .padding(.top, dp(16))
                if let expires = state.expiresAtMs?.int64Value {
                    Text(TvIptvPairingPolicy.shared.expiresText(remainingMs: expires - Int64(now.timeIntervalSince1970 * 1000)))
                        .font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                }
            } else {
                HStack(spacing: dp(8)) {
                    ProgressView().scaleEffect(0.6)
                    Text("Preparing a pairing code…").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
            }
        }
    }
}


/// NuvioTV's catch-up time correction and guide EPG offset pickers: −12 h … +14 h in 30-minute steps.
/// Zero reads "None (UTC)" for catch-up and "Auto" for the guide (unset = detect, not "+0").
enum IptvOffsetPickers {
    static func catchUpLabel(_ minutes: Int32) -> String {
        // 0 is not "UTC": the replay start already follows the panel's measured clock; this only
        // corrects it (B117 parity — same rule as the phone and, now, NuvioTV).
        minutes == 0 ? L("None") : TvIptvContentPolicy.shared.offsetText(minutes: minutes)
    }

    static func guideLabel(_ minutes: Int32) -> String {
        minutes == 0 ? L("Auto") : TvIptvContentPolicy.shared.offsetText(minutes: minutes)
    }

    static func catchUp(_ account: XtreamAccount) -> PickerSpec {
        PickerSpec(title: "Catch-up time correction", subtitle: "Only needed when replays start at the wrong point",
                   options: options(catchUpLabel), selectedId: "\(account.catchUpTimeCorrectionMinutes)") { id in
            guard let minutes = Int32(id) else { return }
            TvIptvContentSettings.shared.setCatchUpCorrection(accountId: account.id, minutes: minutes)
            smokeLog("SMOKE settings catchUpCorrection=%d", minutes)
        }
    }

    static func guide(_ account: XtreamAccount) -> PickerSpec {
        PickerSpec(title: "Guide EPG offset", subtitle: "Auto detects most wrong-clock panels; set this only if guide times are still shifted",
                   options: options(guideLabel), selectedId: "\(account.guideEpgCorrectionMinutes)") { id in
            guard let minutes = Int32(id) else { return }
            Task { try? await TvIptvContentSettings.shared.setGuideOffset(accountId: account.id, minutes: minutes) }
            smokeLog("SMOKE settings guideOffset=%d", minutes)
        }
    }

    private static func options(_ label: (Int32) -> String) -> [SettingsPickerOption] {
        TvIptvContentPolicy.shared.correctionOptions().map { value in
            SettingsPickerOption(id: "\(value.int32Value)", title: label(value.int32Value))
        }
    }
}

/// XtreamContentTypesDialog: one row per content type — the body opens its category checklist (count +
/// chevron), the trailing pill shows or hides the type. Edits go through the shared repository (synced).
private struct ContentTypesDialog: View {
    let accountId: String
    @ObservedObject var model: SettingsModel
    @ObservedObject var dialogs: SettingsDialogs
    @State private var totals: [String: Int] = [:]

    private var account: XtreamAccount? { model.xtream?.accounts.first { $0.id == accountId } }

    var body: some View {
        NuvioDialog(title: "Content & Categories", subtitle: account.map { TvIptvSettingsPolicy.shared.playlistName(name: $0.name) }) {
            if let account {
                ForEach(TvIptvContentPolicy.shared.contentTypes, id: \.first) { pair in
                    let type = pair.first as String? ?? ""
                    let label = pair.second as String? ?? ""
                    let enabled = account.contentTypes.contains(type)
                    ContentTypeRow(label: label, enabled: enabled,
                                   countText: countText(account: account, type: type, enabled: enabled),
                                   initialFocus: type == "live",
                                   onOpen: { dialogs.push(.categoryChecklist(accountId, type)) },
                                   onToggle: {
                                       TvIptvContentSettings.shared.setTypeEnabled(accountId: accountId, type: type, enabled: !enabled)
                                       smokeLog("SMOKE settings contentType=%@ enabled=%d", type, !enabled)
                                   })
                }
                SettingsHelperText(text: "The toggle shows or hides a content type. Select a type to choose its categories.")
            }
        }
        .task {
            for pair in TvIptvContentPolicy.shared.contentTypes {
                let type = pair.first as String? ?? ""
                totals[type] = ((try? await TvIptvContentSettings.shared.categories(accountId: accountId, type: type)) ?? []).count
            }
        }
    }

    private func countText(account: XtreamAccount, type: String, enabled: Bool) -> String {
        let selection = account.categorySelections.forType(type: type)
        let count = TvIptvContentPolicy.shared.count(enabled: enabled, selection: selection,
                                                     total: totals[type].map { KotlinInt(int: Int32($0)) })
        switch count.kind {
        case .hidden: return L("Hidden")
        case .all: return L("All categories")
        case .fraction: return "\(count.selected)/\(count.total)"
        default: return String(format: L("%d selected"), count.selected)
        }
    }
}

/// ContentTypeRow: the row body (label dims when hidden, count, chevron) + a separate toggle pill card.
private struct ContentTypeRow: View {
    let label: String
    let enabled: Bool
    let countText: String
    let initialFocus: Bool
    let onOpen: () -> Void
    let onToggle: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focus: Int?

    var body: some View {
        HStack(spacing: dp(8)) {
            Button(action: onOpen) {
                HStack(spacing: dp(8)) {
                    Text(ui: label).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary.opacity(enabled ? 1 : 0.4))
                    Spacer()
                    Text(verbatim: countText).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
                    Image("md_chevron_right").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                        .foregroundStyle(colors.textTertiary)
                }
                .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
                .background(card(focused: focus == 0))
            }
            .buttonStyle(PlainNoChromeButtonStyle()).focused($focus, equals: 0).reportsFocus(focus == 0)
            Button(action: onToggle) {
                SettingsTogglePill(checked: enabled)
                    .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
                    .background(card(focused: focus == 1))
            }
            .buttonStyle(PlainNoChromeButtonStyle()).focused($focus, equals: 1).reportsFocus(focus == 1)
        }
        .onAppear { if initialFocus { DispatchQueue.main.async { focus = 0 } } }
    }

    private func card(focused: Bool) -> some View {
        RoundedRectangle(cornerRadius: dp(10), style: .continuous).fill(colors.backgroundCard)
            .overlay(RoundedRectangle(cornerRadius: dp(10), style: .continuous)
                .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
    }
}

/// XtreamCategoryChecklistDialog: "Select All" / "Deselect All", then a check row per provider category.
/// Each press sends the OPERATION (not a recomputed list), composed against the latest selection.
private struct CategoryChecklistDialog: View {
    let accountId: String
    let type: String
    @ObservedObject var model: SettingsModel
    @ObservedObject var dialogs: SettingsDialogs
    @State private var categories: [TvCategoryItem]?

    private var account: XtreamAccount? { model.xtream?.accounts.first { $0.id == accountId } }
    private var label: String {
        (TvIptvContentPolicy.shared.contentTypes.first { ($0.first as String?) == type }?.second as String?) ?? type
    }

    var body: some View {
        let selection = account?.categorySelections.forType(type: type)
        NuvioDialog(title: String(format: L("%@ categories"), L(label)), subtitle: subtitle(selection)) {
            if let categories {
                HStack(spacing: dp(8)) {
                    SettingsDialogButton(title: "Select All", primary: true, initialFocus: true) {
                        TvIptvContentSettings.shared.setSelection(accountId: accountId, type: type, selection: nil)
                    }
                    SettingsDialogButton(title: "Deselect All") {
                        TvIptvContentSettings.shared.setSelection(accountId: accountId, type: type, selection: [])
                    }
                    Spacer()
                }
                ForEach(categories, id: \.id) { category in
                    let checked = TvIptvContentPolicy.shared.isChecked(selection: selection, categoryId: category.id)
                    CategoryCheckRow(name: category.name, checked: checked) {
                        TvIptvContentSettings.shared.toggleCategory(accountId: accountId, type: type,
                                                                   allIds: categories.map(\.id), categoryId: category.id, checked: !checked)
                        smokeLog("SMOKE settings category=%@ checked=%d", category.name, !checked)
                    }
                }
            } else {
                ProgressView()
            }
        }
        .task { categories = (try? await TvIptvContentSettings.shared.categories(accountId: accountId, type: type)) ?? [] }
    }

    private func subtitle(_ selection: [String]?) -> String {
        guard let categories else { return L("Loading categories…") }
        let pair = TvIptvContentPolicy.shared.selectedOfTotal(selection: selection, total: Int32(categories.count))
        return String(format: L("%1$d/%2$d selected"), (pair.first as? KotlinInt)?.intValue ?? 0, (pair.second as? KotlinInt)?.intValue ?? 0)
    }
}

/// CategoryCheckRow: radius-10 card, FocusBackground when checked or focused, Primary name + check.
private struct CategoryCheckRow: View {
    let name: String
    let checked: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(10), style: .continuous)
        Button(action: action) {
            HStack(spacing: dp(12)) {
                Text(verbatim: name).font(NuvioType.bodyLarge).foregroundStyle(checked ? colors.primary : colors.textPrimary).lineLimit(1)
                Spacer()
                if checked {
                    Image("md_check").renderingMode(.template).resizable().frame(width: dp(20), height: dp(20)).foregroundStyle(colors.primary)
                }
            }
            .padding(dp(16))
            .background(shape.fill(checked || focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}
