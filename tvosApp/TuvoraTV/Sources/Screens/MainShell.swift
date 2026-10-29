import SwiftUI
import TuvoraCore

/// NuvioTV's legacy sidebar shell (MainActivity.kt:1414-1812), translated: a 72dp icon rail that opens
/// into a 196dp labelled drawer when it takes focus (LEFT from content), dimming the content; RIGHT or
/// a selection closes it. Content sits 54dp in from the left edge.
/// Where to land when the shell is rebuilt after the profile picker (which replaces it): set by the
/// screen that opened the picker, taken once by the new shell, so Back returns the viewer to that screen.
enum ShellResume {
    private static var destination: MainShell.Destination?
    private static var settingsCategory: String?
    static func set(destination: MainShell.Destination, settingsCategory: String? = nil) {
        self.destination = destination
        self.settingsCategory = settingsCategory
    }
    static func takeDestination() -> MainShell.Destination? { defer { destination = nil }; return destination }
    static func takeSettingsCategory() -> String? { defer { settingsCategory = nil }; return settingsCategory }
}

struct MainShell: View {
    enum Destination: String, CaseIterable, Identifiable {
        case home, search, library, iptv, sports, settings
        var id: String { rawValue }
        var title: String {
            switch self {
            case .home: return "Home"
            case .search: return "Search"
            case .library: return "Library"
            case .iptv: return "IPTV"
            case .sports: return "Sports"
            case .settings: return "Settings"
            }
        }
        /// Same icons as NuvioTV: Material Home/LiveTv/SportsSoccer, its own sidebar SVGs for the rest.
        var icon: String {
            switch self {
            case .home: return "md_home"
            case .search: return "sidebar_search"
            case .library: return "sidebar_library"
            case .iptv: return "md_live_tv"
            case .sports: return "md_sports_soccer"
            case .settings: return "sidebar_settings"
            }
        }
    }

    @StateObject private var playback = PlaybackCoordinator()
    @StateObject private var theme = NuvioThemeModel()
    /// Simulator smoke hook: `-smokeTab <home|search|library|iptv|sports|settings>`.
    @State private var destination: Destination = {
        if let resumed = ShellResume.takeDestination() { return resumed }
        let args = AppArguments.list
        if let i = args.firstIndex(of: "-smokeTab"), i + 1 < args.count, let d = Destination(rawValue: args[i + 1]) { return d }
        return .home
    }()
    @FocusState private var railFocus: Destination?
    @State private var profile: NuvioProfile?
    @State private var profileCount = 0
    /// The drawer's profile item (NuvioTV SidebarProfileItem): shown with 2+ profiles, opens the picker.
    @FocusState private var profileFocused: Bool
    /// Simulator smoke hook: `-smokeDetails <type>:<id>` opens a title's details.
    @State private var smokeDetails: PreviewBox? = {
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokeDetails"), i + 1 < args.count else { return nil }
        let parts = args[i + 1].split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return nil }
        return PreviewBox(preview: MetaPreview(id: parts[1], type: parts[0], name: "", poster: nil, banner: nil, logo: nil,
                                               posterShape: .poster, description: nil, releaseInfo: nil, rawReleaseDate: nil,
                                               popularity: nil, voteCount: nil, imdbRating: nil, genres: [], pinned: false,
                                               rawPosterUrl: nil, landscapePoster: nil, rawLandscapePosterUrl: nil,
                                               runtime: nil, status: nil, ageRating: nil, language: nil, externalRatings: []))
    }()
    @AppStorage(NuvioLayoutPrefs.collapseSidebarKey) private var collapseSidebar = false

    /// The drawer is open only when the viewer brought focus there (LEFT at the edge, or Menu). Collapsed
    /// items cannot take focus, exactly as NuvioTV's `canFocus = expanded`, so launch focus lands in content.
    @State private var railEngaged = false
    /// Open exactly while engaged: tying it to "an item has focus" collapsed the drawer for the instant
    /// a fence held focus, removing the profile item the fence was bouncing back to.
    private var expanded: Bool { railEngaged }
    /// The left-edge catcher: an invisible focusable under the collapsed sidebar. The focus engine only
    /// reaches it when nothing in the content lies further left, so "LEFT at the edge opens the drawer"
    /// needs no timing guess (the old 150 ms heuristic misfired wherever focus reporting lagged).
    @FocusState private var edgeFocused: Bool
    @ObservedObject private var contentFocus = ContentFocusActivity.shared

    private func openRail() {
        railEngaged = true
        DispatchQueue.main.async { railFocus = destination }
        // Enabling the drawer lets tvOS grab its nearest item (the profile item, level with content at the
        // top of the screen) after the first assignment: the drawer always opens on the current tab.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            if railEngaged && (profileFocused || railFocus == nil) { railFocus = destination }
        }
    }

    var body: some View {
        let colors = theme.colors
        ZStack(alignment: .leading) {
            colors.background.ignoresSafeArea()

            content
                // "Collapse Sidebar" hides the drawer until LEFT/Menu: the content then takes the full width
                // (it kept the drawer's inset, leaving an empty strip - tester report 2026-10-01).
                .padding(.leading, collapseSidebar ? 0 : dp(80))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .overlay(Color.black.opacity(expanded ? 0.55 : 0).allowsHitTesting(false).ignoresSafeArea())
                .animation(NuvioTokens.Motion.medium, value: expanded)
                .onExitCommand { openRail() }
                .task(id: contentFocus.recoveryTick) {
                    try? await Task.sleep(nanoseconds: 600_000_000)   // let the refresh settle
                    guard !Task.isCancelled, !railEngaged, !ContentFocusActivity.windowHasFocus else { return }
                    // Empty content has no focus target. Keep navigation available by handing
                    // focus to a real sidebar control instead of a transient invisible target.
                    openRail()
                }
                // Native views (search keyboard, system lists) don't report focus: any press inside the
                // content also proves the content holds focus.
                .onMoveCommand { _ in ContentFocusActivity.touched() }



            Sidebar(destination: $destination, focus: $railFocus, profileFocus: $profileFocused, expanded: expanded, engaged: railEngaged,
                    profile: profile, profileSwitchable: profileCount > 1,
                    onSwitchProfile: {
                        railEngaged = false
                        railFocus = nil
                        ProfilePickerLaunch.manageRequested = false
                        TvAppLifecycle.shared.openProfilePicker()
                    },
                    onChoose: {
                        contentFocus.expectContentFocus()
                        railEngaged = false
                        railFocus = nil
                    })
                // Focus left the drawer: close it - unless a fence is bouncing focus straight back
                // (its bounce is queued first, so check after it has run).
                .onChange(of: profileFocused) { _, f in
                    guard !f else { return }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { if railFocus == nil && !profileFocused { railEngaged = false } }
                }
                .onChange(of: railFocus) { _, f in
                    guard f == nil else { return }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { if railFocus == nil && !profileFocused { railEngaged = false } }
                }
                // Settings → Layout "Collapse Sidebar": hidden until focus arrives (NuvioTV).
                .opacity(collapseSidebar && !railEngaged ? 0 : 1)
                .animation(NuvioTokens.Motion.fast, value: railEngaged)

            // Above the panel so the focus engine sees it (it skips covered views), and gone while the
            // drawer is open so it can't cover the drawer's own fences.
            if !railEngaged {
            Rectangle().fill(Color.white.opacity(0.001))   // Color.clear is not a focus target
                // A sliver at the screen edge when the sidebar is collapsed: anything wider would cover the
                // leftmost cards (now at the 104 pt gutter), and the focus engine skips covered views.
                .frame(width: collapseSidebar ? 40 : dp(70))
                .frame(maxHeight: .infinity)
                .focusable(contentFocus.everFocused && !contentFocus.leftEdgeOwned)
                .focused($edgeFocused)
                .onChange(of: edgeFocused) { _, now in if now { openRail() } }
                .accessibilityIdentifier("sidebar.edge")
            }
        }
        .environment(\.nuvio, colors)
        .environmentObject(playback)
        .ignoresSafeArea()
        .onChange(of: contentFocus.railRequests) { _, _ in openRail() }
        .overlay(alignment: .bottom) { toast(colors) }
        .onChange(of: theme.colors.secondary, initial: true) { _, _ in playback.palette = theme.colors }
        .fullScreenCover(item: $smokeDetails) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
        .task { await theme.observe() }
        .task { TopShelfPublisher.start() }
        .onReceive(DeepLinkCenter.shared.$openSetupCode) { open in if open { destination = .settings } }
        .onReceive(DeepLinkCenter.shared.$partyCode) { code in if code != nil { destination = .settings } }
        // Watch party news (joins, leaves, the party ending, what someone did to playback) as the shell's toast.
        .task { for await text in TvParty.shared.messages { playback.notify(text) } }
        .onReceive(DeepLinkCenter.shared.$pending) { link in
            guard let link else { return }
            DeepLinkCenter.shared.pending = nil
            open(link)
        }
        // A new shell (launch, or back from the profile picker): nothing in content has focus yet, so
        // the edge catcher must not be armed - it would take the launch focus and open the drawer.
        .onAppear { contentFocus.expectContentFocus() }
        .task { for await state in ProfileRepository.shared.state { profile = state.activeProfile; profileCount = state.profiles.count } }
    }

    /// A Top Shelf item: Play resumes that title's sources, Select opens its details.
    private func open(_ link: DeepLink) {
        Task {
            if link.play, let meta = try? await MetaDetailsRepository.shared.fetch(type: link.type, id: link.id, cacheResult: true) {
                let video = meta.videos.first { $0.id == link.videoId }
                playback.openSources(meta: meta, video: video)
            } else {
                smokeDetails = PreviewBox(preview: MetaPreview(id: link.id, type: link.type, name: "", poster: nil, banner: nil, logo: nil,
                                                               posterShape: .poster, description: nil, releaseInfo: nil, rawReleaseDate: nil,
                                                               popularity: nil, voteCount: nil, imdbRating: nil, genres: [], pinned: false,
                                                               rawPosterUrl: nil, landscapePoster: nil, rawLandscapePosterUrl: nil,
                                               runtime: nil, status: nil, ageRating: nil, language: nil, externalRatings: []))
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch destination {
        case .home: HomeScreen()
        case .search: SearchScreen()
        case .library: LibraryScreen()
        case .iptv: IptvHubScreen()
        case .sports: SportsScreen()
        case .settings: SettingsScreen()
        }
    }

    @ViewBuilder
    private func toast(_ colors: NuvioPalette) -> some View {
        if let message = playback.message {
            Text(ui: message)
                .font(NuvioType.bodyMedium)
                .foregroundStyle(colors.textPrimary)
                .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                .background(colors.backgroundElevated, in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                .padding(.bottom, dp(32))
                .transition(.opacity)
        }
    }
}

private struct Sidebar: View {
    @Binding var destination: MainShell.Destination
    var focus: FocusState<MainShell.Destination?>.Binding
    var profileFocus: FocusState<Bool>.Binding
    let expanded: Bool
    let engaged: Bool
    let profile: NuvioProfile?
    let profileSwitchable: Bool
    let onSwitchProfile: () -> Void
    /// Hand focus to the chosen tab: close the drawer at once (items stop being focus targets).
    let onChoose: () -> Void
    @Environment(\.nuvio) private var colors
    /// The drawer item focus last rested on while the drawer was open. The top fence bounces to it, so UP
    /// past Home stays on Home; with none yet (the drawer just opened) it bounces to the current tab,
    /// not Home (navigation audit 2026-10-28, finding 2).
    @State private var lastItem: MainShell.Destination?

    /// NuvioTV's Modern floating sidebar (ModernSidebarBlurPanel.kt), drawn in Liquid Glass: a rounded
    /// (30dp) panel inset from the edge, icons only until focus arrives, then labels.
    var body: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            // Top fence above the profile item when there is one, so UP from Home can reach it.
            SidebarFence(active: engaged) {
                if profileSwitchable && profile != nil { profileFocus.wrappedValue = true }
                else { focus.wrappedValue = lastItem ?? destination }
            }
            if profileSwitchable, expanded, let profile {
                // Only while the drawer is open, and removed instantly (no transition): a fading view
                // stays focusable, and tvOS picked it when a tab was chosen; a collapsed-but-present one
                // skewed the focus engine near the IPTV chips (both found by the remote UI tests).
                SidebarProfileButton(profile: profile, focused: profileFocus.wrappedValue, action: onSwitchProfile)
                    .focused(profileFocus)
                    .disabled(!engaged)
                    .accessibilityIdentifier("sidebar.profile")
                    .padding(.bottom, dp(12))
            } else if expanded {
                header.padding(.bottom, dp(12)).transition(.opacity)
            }
            ForEach(MainShell.Destination.allCases) { item in
                SidebarItem(item: item, selected: destination == item, expanded: expanded, focused: focus.wrappedValue == item) {
                    destination = item
                    onChoose()
                }
                .focused(focus, equals: item)
                .disabled(!engaged)
            }
            fence(bouncesTo: MainShell.Destination.allCases.last!)
        }
        .padding(dp(10))
        .navigationGlass(in: RoundedRectangle(cornerRadius: dp(30), style: .continuous))
        .padding(.leading, dp(12))
        .frame(maxHeight: .infinity)
        .focusSection()
        .defaultFocus(focus, destination, priority: .userInitiated)
        .animation(NuvioTokens.Motion.fast, value: expanded)
        .onMoveCommand { direction in
            if direction == .right { focus.wrappedValue = nil }
        }
        .onChange(of: focus.wrappedValue) { _, item in if let item { lastItem = item } }
        .onChange(of: engaged) { _, now in if !now { lastItem = nil } }
    }

    /// UP from the first item / DOWN from the last must stay in the drawer (tvOS would otherwise hand
    /// focus to the nearest content view). An invisible focusable just past the end takes the move
    /// and gives focus straight back.
    private func fence(bouncesTo item: MainShell.Destination) -> some View {
        SidebarFence(active: engaged) { focus.wrappedValue = item }
    }

    @ViewBuilder
    private var header: some View {
        if let profile {
            HStack(spacing: dp(10)) {
                ProfileAvatar(profile: profile, size: dp(30))
                Text(profile.name).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
            }
            .padding(.leading, dp(6))
        } else {
            Image("app_logo_wordmark").resizable().scaledToFit().frame(height: dp(36))
        }
    }
}

private struct SidebarProfileButton: View {
    let profile: NuvioProfile
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(10)) {
                ProfileAvatar(profile: profile, size: dp(30))
                Text(profile.name).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, dp(9))
            .frame(width: dp(190), height: dp(48), alignment: .leading)
            .background(Capsule().fill(focused ? colors.focusBackground : .clear))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.05 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
    }
}

private struct SidebarFence: View {
    let active: Bool
    let bounce: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        Color.clear
            .frame(width: dp(48), height: 2)   // an item's width: never stretches the glass panel
            .focusable(active)
            .focused($focused)
            .onChange(of: focused) { _, now in if now { DispatchQueue.main.async(execute: bounce) } }
    }
}

private struct SidebarItem: View {
    let item: MainShell.Destination
    let selected: Bool
    let expanded: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(14)) {
                Image(item.icon).renderingMode(.template).resizable().scaledToFit()
                    .frame(width: dp(22), height: dp(22))
                    .foregroundStyle(selected ? colors.secondary : colors.textPrimary.opacity(focused ? 1 : 0.8))
                if expanded {
                    Text(ui: item.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                }
            }
            .padding(.horizontal, dp(13))
            .frame(width: expanded ? dp(190) : dp(48), height: dp(48), alignment: .leading)
            .background(Capsule().fill(fill))
            .scaleEffect(focused ? 1.05 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .accessibilityIdentifier("sidebar.\(item.rawValue)")
    }

    /// Modern sidebar item fills: focused+selected accent 28%, focused white 12%, selected accent 15%.
    private var fill: Color {
        if focused && selected { return colors.secondary.opacity(0.28) }
        if focused { return Color.white.opacity(0.12) }
        if selected && expanded { return colors.secondary.opacity(0.15) }
        return .clear
    }
}

/// Buttons draw their own NuvioTV focus treatment; this removes tvOS's default lift/highlight.
struct PlainNoChromeButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

struct ProfileAvatar: View {
    let profile: NuvioProfile
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color(hex: profile.avatarColorHex))
            if let url = profile.avatarUrl, !url.isEmpty {
                CachedPosterArtwork(urlString: url, width: size, height: size, maximumWidth: size * 2) { Color.clear }
                    .clipShape(Circle())
            } else {
                Text(String(profile.name.prefix(1)).uppercased())
                    .font(NuvioType.inter(size / 2 / 2, .semibold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }
}

extension Color {
    /// "#RRGGBB" / "#AARRGGBB", falling back to NuvioTV's default avatar blue.
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        let value = UInt32(cleaned, radix: 16) ?? 0x1E88E5
        self.init(argb: cleaned.count == 6 ? 0xFF000000 | value : (cleaned.count == 8 ? value : 0xFF1E88E5))
    }
}
