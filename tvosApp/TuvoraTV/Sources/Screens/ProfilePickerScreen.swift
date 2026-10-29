import SwiftUI
import TuvoraCore

// NuvioTV "Who's watching?" / "Manage Profiles" (ui/screens/profile/ProfileSelectionScreen.kt): the
// avatar-tinted background, wordmark, heading, profile cards with the focus ring + scale, the Add
// Profile card, and the PIN overlay (ProfilePinOverlay / ProfilePinBoxes) in unlock, set/confirm and
// verify-current modes. Management (options, create/edit, delete) is in ProfileManageViews.swift.
// All sizes are NuvioTV dp ×2.
//
// Entry points: long-press a card (the tvOS context menu, NuvioTV's hold-to-manage), the Add Profile
// card, and Settings → Profiles → Manage Profiles, which opens this screen in manage mode (OK on a
// card opens its options; Menu returns to the app).

/// ProfileSelectionSpacing (ProfileSelectionScreen.kt:125-161).
enum PickerMetrics {
    static let screenPaddingH = dp(56), screenPaddingV = dp(48)
    static let logoHeight = dp(44), logoToHeading = dp(28), headingToSub = dp(12)
    static let gridGap = dp(28), compactGridGap = dp(12)
    static let cardWidth = dp(152), compactCardWidth = dp(128)
    static let cardPadH = dp(10), cardPadV = dp(8)
    static let avatarContainer = dp(126), compactAvatarContainer = dp(104)
    static let avatarToName = dp(12), compactAvatarToName = dp(10)
    static let nameToMeta = dp(8), metaSlot = dp(16)
    static let pinHeadingToBoxes = dp(42), pinBoxesToSupport = dp(26)
    static let pinBox = dp(118), pinBoxGap = dp(14), pinSupportMaxWidth = dp(720)
    static let primaryGold = Color(argb: 0xFFFFB300)
}

/// Settings → Manage Profiles asks for manage mode before opening the picker (TvAppLifecycle knows
/// only that the picker was opened on purpose).
@MainActor
enum ProfilePickerLaunch {
    static var manageRequested = false
    static func consumeManage() -> Bool {
        defer { manageRequested = false }
        return manageRequested || AppArguments.list.contains("-smokeManage")
    }
}

/// What sits over the profile row.
enum ProfileOverlay: Equatable {
    case options(Int32)
    case editor(Int32?)          // nil = create
    case deleteConfirm(Int32)
    case pin(Int32, TvPinMode)
}

struct ProfilePickerView: View {
    @State private var profiles: [NuvioProfile] = []
    @State private var avatars: [AvatarCatalogItem] = []
    @State private var loaded = false
    @State private var focusedIndex: Int32?
    @State private var manage = ProfilePickerLaunch.consumeManage()
    @State private var overlay: ProfileOverlay?
    @State private var toast: String?
    @FocusState private var focus: Int32?

    /// Focus value of the Add Profile card.
    static let addCard: Int32 = -1

    var body: some View {
        ZStack {
            ProfileSelectionBackground(avatarHex: backgroundProfile?.avatarColorHex)
            if case .pin(let index, let mode) = overlay, let profile = profile(index) {
                ProfilePinOverlay(profile: profile, mode: mode, onClose: closeOverlay, onFinished: finished,
                                  onConfirmDelete: { overlay = .deleteConfirm(index) })
                    .id("\(index)-\(mode)")
                    .transition(.opacity.combined(with: .offset(x: dp(40))))
            } else {
                main
                    .disabled(overlay != nil)
                    .transition(.opacity.combined(with: .offset(x: -dp(40))))
            }
            manageLayer
            if let toast { ProfileToast(text: toast).frame(maxHeight: .infinity, alignment: .bottom).transition(.opacity) }
        }
        .animation(.timingCurve(0.2, 0, 0, 1, duration: 0.32), value: isPinOverlay)
        .animation(NuvioTokens.Motion.fast, value: toast)
        .ignoresSafeArea()
        .task { for await items in AvatarRepository.shared.avatars { avatars = items } }
        .task {
            for await state in ProfileRepository.shared.state {
                profiles = state.profiles
                loaded = loaded || state.isLoaded || !state.profiles.isEmpty
                if focusedIndex == nil, let first = state.activeProfile ?? state.profiles.first {
                    focusedIndex = first.profileIndex
                }
                smokeLog("SMOKE profiles=%@", state.profiles.map { "\($0.profileIndex):\($0.pinEnabled ? "pin" : "open")" }.joined(separator: ","))
                runSmokeHooks(state.profiles)
            }
        }
        .task(id: toast) {
            guard toast != nil else { return }
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if !Task.isCancelled { toast = nil }
        }
    }

    private var isPinOverlay: Bool { if case .pin = overlay { return true } else { return false } }

    private var backgroundProfile: NuvioProfile? {
        if case .pin(let index, _) = overlay { return profile(index) }
        return focusedProfile
    }

    private func profile(_ index: Int32) -> NuvioProfile? { profiles.first { $0.profileIndex == index } }

    private var focusedProfile: NuvioProfile? {
        if focusedIndex == Self.addCard { return nil }
        return profiles.first { $0.profileIndex == focusedIndex } ?? profiles.first
    }

    private var canAdd: Bool { TvProfileManagePolicy.shared.canAddProfile(profileCount: Int32(profiles.count)) }

    private var main: some View {
        VStack(spacing: 0) {
            Image("app_logo_wordmark").resizable().scaledToFit().frame(height: PickerMetrics.logoHeight)
            Spacer().frame(height: PickerMetrics.logoToHeading)
            Text(manage ? "Manage Profiles" : "Who's watching?")
                .font(NuvioType.inter(44, .bold)).tracking(-1)
                .foregroundStyle(NuvioPrimitives.white)
            Spacer().frame(height: PickerMetrics.headingToSub)
            Text(manage ? "Select a profile to edit, switch, or create a new one" : "Select a profile to continue")
                .font(NuvioType.inter(18, .medium))
                .foregroundStyle(NuvioPrimitives.neutral400)
            Spacer(minLength: 0)
            grid
            Spacer(minLength: 0)
            if TvAppLifecycle.shared.canCloseProfilePicker {
                // A visible way out as well as Menu (the only exit used to be an invisible Menu press).
                HubChip(title: "Done", selected: false) { _ = TvAppLifecycle.shared.closeProfilePicker() }
                    .accessibilityIdentifier("profiles.done")
                    .padding(.bottom, dp(12))
            }
            if !profiles.isEmpty {
                Text(manage ? "Select a profile to manage" : "Hold to manage profile")
                    .font(NuvioType.inter(14, .medium))
                    .foregroundStyle(NuvioPrimitives.neutral600.opacity(0.9))
            }
        }
        .padding(.horizontal, PickerMetrics.screenPaddingH)
        .padding(.vertical, PickerMetrics.screenPaddingV)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Opened from Settings (Manage Profiles / Switch profile): Menu returns to the running app, no
        // profile switch. The startup picker has nothing behind it, so Menu is left to tvOS, which
        // exits to the Apple TV Home screen (HIG: Menu at the root always leaves the app).
        .onExitCommand(perform: overlay == nil && TvAppLifecycle.shared.canCloseProfilePicker
                       ? { _ = TvAppLifecycle.shared.closeProfilePicker() } : nil)
    }

    private var profilesLoading: some View {
        DeadlineLoading(title: "Profiles are taking a while", message: "Tuvora couldn't load your profiles yet.",
                        retry: { TvAppLifecycle.shared.retryProfiles() }) { ProgressView() }
    }

    @ViewBuilder
    private var grid: some View {
        if profiles.isEmpty && !canAdd {
            if loaded {
                Text("No profiles found").font(NuvioType.inter(18, .medium)).foregroundStyle(NuvioPrimitives.neutral400)
            } else {
                profilesLoading
            }
        } else if profiles.isEmpty && !loaded {
            profilesLoading
        } else {
            GeometryReader { geo in
                let layout = TvProfileGridLayout.shared.layout(
                    itemCount: Int32(profiles.count + (canAdd ? 1 : 0)), maxWidth: Float(geo.size.width),
                    cardWidth: Float(PickerMetrics.cardWidth), compactCardWidth: Float(PickerMetrics.compactCardWidth),
                    gap: Float(PickerMetrics.gridGap), compactGap: Float(PickerMetrics.compactGridGap))
                let row = HStack(alignment: .top, spacing: CGFloat(layout.gap)) {
                    ForEach(profiles, id: \.profileIndex) { profile in
                        ProfileCard(profile: profile, avatarURL: avatarURL(profile), compact: layout.compact,
                                    focused: focus == profile.profileIndex) { select(profile) }
                            .focused($focus, equals: profile.profileIndex)
                            .contextMenu { contextMenu(profile) }
                    }
                    if canAdd {
                        AddProfileCard(compact: layout.compact, focused: focus == Self.addCard) { overlay = .editor(nil) }
                            .focused($focus, equals: Self.addCard)
                    }
                }
                .padding(.vertical, dp(12))
                Group {
                    if layout.scrollable {
                        ScrollView(.horizontal, showsIndicators: false) { row }.scrollClipDisabled()
                    } else {
                        row.frame(maxWidth: .infinity)
                    }
                }
                .frame(maxHeight: .infinity)
            }
            .frame(height: PickerMetrics.avatarContainer + dp(120))
            .focusSection()
            .defaultFocus($focus, focusedIndex ?? profiles.first?.profileIndex ?? Self.addCard)
            .onChange(of: focus) { _, new in if let new { focusedIndex = new } }
        }
    }

    /// Long-press: NuvioTV's Profile Options, as the native tvOS context menu.
    @ViewBuilder
    private func contextMenu(_ profile: NuvioProfile) -> some View {
        ForEach(TvProfileManagePolicy.shared.options(profileIndex: profile.profileIndex, pinEnabled: profile.pinEnabled), id: \.self) { option in
            Button(TvProfileManagePolicy.shared.optionTitle(option: option), role: option == .delete ? .destructive : nil) {
                choose(option, for: profile)
            }
        }
    }

    // MARK: Management layer

    @ViewBuilder
    private var manageLayer: some View {
        switch overlay {
        case .options(let index):
            if let profile = profile(index) {
                ProfileOptionsDialog(profile: profile, onChoose: { choose($0, for: profile) }, onDismiss: closeOverlay)
            }
        case .editor(let index):
            ProfileEditorOverlay(profile: index.flatMap(profile), avatars: avatars,
                                 onDismiss: closeOverlay,
                                 onSaved: { closeOverlay() })
        case .deleteConfirm(let index):
            if let profile = profile(index) {
                ProfileDeleteDialog(profile: profile, onDismiss: closeOverlay) {
                    smokeLog("SMOKE profile delete index=%d", index)
                    closeOverlay()
                    Task { try? await ProfileRepository.shared.deleteProfile(profileIndex: index) }
                }
            }
        case .pin, .none:
            EmptyView()
        }
    }

    private func choose(_ option: TvProfileOption, for profile: NuvioProfile) {
        let policy = TvProfileManagePolicy.shared
        if let mode = policy.pinModeFor(option: option, pinEnabled: profile.pinEnabled) {
            overlay = .pin(profile.profileIndex, mode)
        } else if option == .edit {
            overlay = .editor(profile.profileIndex)
        } else if option == .delete {
            overlay = .deleteConfirm(profile.profileIndex)
        }
    }

    private func closeOverlay() {
        let returnTo = focusedIndex
        overlay = nil
        if let returnTo { DispatchQueue.main.async { focus = returnTo } }
    }

    private func finished(_ message: String?) {
        closeOverlay()
        toast = message
    }

    private func avatarURL(_ profile: NuvioProfile) -> String? {
        let avatar = profile.avatarId.flatMap { id in avatars.first { $0.id == id } }
        return ProfileModelsKt.profileAvatarImageUrl(profile: profile, avatar: avatar)
    }

    private func select(_ profile: NuvioProfile) {
        if manage {
            overlay = .options(profile.profileIndex)
        } else if profile.pinEnabled {
            overlay = .pin(profile.profileIndex, .unlock)
        } else {
            smokeLog("SMOKE pick profile=%d", profile.profileIndex)
            TvAppLifecycle.shared.pickProfile(profileIndex: profile.profileIndex)
        }
    }

    /// Simulator hooks: `-smokePickProfile <i>` picks; `-smokePin <i>` opens the unlock overlay;
    /// `-smokeSetPin <i>` the set-PIN overlay; `-smokeProfileOptions <i>`, `-smokeEditProfile <i|new>`
    /// and `-smokeDeleteProfile <i>` the management overlays; `-smokeManage` opens manage mode.
    @State private var smokeHandled = false
    /// Once per launch: a picker reopened later (Settings -> Manage Profiles) must not re-run them.
    private static var smokeRanThisLaunch = false
    private func runSmokeHooks(_ profiles: [NuvioProfile]) {
        guard !smokeHandled, !Self.smokeRanThisLaunch, !profiles.isEmpty else { return }
        let args = AppArguments.list
        func value(after flag: String) -> String? {
            guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
            return args[i + 1]
        }
        func index(after flag: String) -> Int32? {
            guard let v = value(after: flag), let i = Int32(v), profiles.contains(where: { $0.profileIndex == i }) else { return nil }
            return i
        }
        smokeHandled = true
        Self.smokeRanThisLaunch = true
        if let i = index(after: "-smokePickProfile") {
            TvAppLifecycle.shared.pickProfile(profileIndex: i)
        } else if let i = index(after: "-smokePin") {
            overlay = .pin(i, .unlock)
        } else if let i = index(after: "-smokeSetPin") {
            overlay = .pin(i, .set)
        } else if let i = index(after: "-smokeProfileOptions") {
            overlay = .options(i)
        } else if let i = index(after: "-smokeDeleteProfile") {
            overlay = .deleteConfirm(i)
        } else if let v = value(after: "-smokeEditProfile") {
            overlay = .editor(v == "new" ? nil : Int32(v))
        } else {
            smokeHandled = false
            Self.smokeRanThisLaunch = false
        }
    }
}

// MARK: - Background

/// ProfileSelectionBackground (ProfileSelectionScreen.kt:810-883): fixed dark base tinted by the
/// focused avatar colour — vertical lerp(#1E1E1E, avatar, .30) → lerp(#121212, avatar, .14) @ .42 →
/// #121212, plus a left-side wash avatar 26% → 8% @ .45 → clear @ .72. Crossfades over 520 ms.
private struct ProfileSelectionBackground: View {
    let avatarHex: String?

    var body: some View {
        let avatar = RGB(hex: avatarHex ?? "#555555")
        ZStack {
            layer(avatar).id(avatarHex ?? "none").transition(.opacity)
        }
        .animation(.easeInOut(duration: 0.52), value: avatarHex)
        .ignoresSafeArea()
    }

    private func layer(_ avatar: RGB) -> some View {
        let base = RGB(0x12, 0x12, 0x12), elevated = RGB(0x1E, 0x1E, 0x1E)
        return ZStack {
            LinearGradient(stops: [
                .init(color: elevated.lerp(avatar, 0.3).color, location: 0),
                .init(color: base.lerp(avatar, 0.14).color, location: 0.42),
                .init(color: base.color, location: 1),
            ], startPoint: .top, endPoint: .bottom)
            LinearGradient(stops: [
                .init(color: avatar.color.opacity(0.26), location: 0),
                .init(color: avatar.color.opacity(0.08), location: 0.45),
                .init(color: .clear, location: 0.72),
                .init(color: .clear, location: 1),
            ], startPoint: .leading, endPoint: .trailing)
        }
    }
}

/// sRGB triple for Compose-style `lerp` between colours.
private struct RGB {
    var r: Double, g: Double, b: Double
    init(_ r: Int, _ g: Int, _ b: Int) { self.r = Double(r) / 255; self.g = Double(g) / 255; self.b = Double(b) / 255 }
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        let value = UInt32(cleaned.suffix(6), radix: 16) ?? 0x1E88E5
        self.init(Int((value >> 16) & 0xFF), Int((value >> 8) & 0xFF), Int(value & 0xFF))
    }
    func lerp(_ other: RGB, _ t: Double) -> RGB {
        var out = self
        out.r += (other.r - r) * t; out.g += (other.g - g) * t; out.b += (other.b - b) * t
        return out
    }
    var color: Color { Color(.sRGB, red: r, green: g, blue: b, opacity: 1) }
}

// MARK: - Profile card

/// ProfileCard (ProfileSelectionScreen.kt:1055-1246): avatar 96 → 102 inside a ring 114 → 122 whose
/// focus stroke grows 1 → 3dp; the card scales to 1.04 over 210 ms; name 17sp, secondary → primary.
/// The primary profile carries NuvioTV's gold star badge and "PRIMARY" caption.
private struct ProfileCard: View {
    let profile: NuvioProfile
    let avatarURL: String?
    let compact: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    private var isPrimary: Bool { profile.profileIndex == ProfileModelsKt.PRIMARY_PROFILE_INDEX }

    var body: some View {
        let avatarSize = compact ? (focused ? dp(88) : dp(82)) : (focused ? dp(102) : dp(96))
        let ringSize = compact ? (focused ? dp(104) : dp(98)) : (focused ? dp(122) : dp(114))
        let container = compact ? PickerMetrics.compactAvatarContainer : PickerMetrics.avatarContainer
        Button(action: action) {
            VStack(spacing: 0) {
                ZStack {
                    ZStack {
                        Circle().stroke(colors.border.opacity(0.75), lineWidth: NuvioTokens.Stroke.hairline)
                        Circle().stroke(colors.focusRing.opacity(focused ? 1 : 0), lineWidth: focused ? dp(3) : dp(1))
                        ProfileAvatarCircle(name: profile.name, colorHex: profile.avatarColorHex, imageURL: avatarURL, size: avatarSize)
                    }
                    .frame(width: ringSize, height: ringSize)
                    if isPrimary {
                        Text("\u{2605}").font(NuvioType.inter(compact ? 12 : 14, .bold)).foregroundStyle(.white)
                            .frame(width: compact ? dp(22) : dp(26), height: compact ? dp(22) : dp(26))
                            .background(Circle().fill(PickerMetrics.primaryGold))
                            .overlay(Circle().stroke(colors.background, lineWidth: dp(2)))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                            .offset(x: dp(2), y: dp(1))
                    }
                }
                .frame(width: container, height: container)
                Spacer().frame(height: compact ? PickerMetrics.compactAvatarToName : PickerMetrics.avatarToName)
                Text(profile.name)
                    .font(NuvioType.inter(compact ? 15 : 17, focused ? .semibold : .medium))
                    .foregroundStyle(focused ? colors.textPrimary : colors.textSecondary)
                    .lineLimit(1).truncationMode(.tail)
                Spacer().frame(height: PickerMetrics.nameToMeta)
                Group {
                    if isPrimary {
                        Text("PRIMARY").font(NuvioType.inter(11, .semibold)).tracking(dp(0.8))
                            .foregroundStyle(PickerMetrics.primaryGold)
                    } else {
                        Color.clear
                    }
                }
                .frame(height: PickerMetrics.metaSlot * 1.6, alignment: .top)
            }
            .padding(.horizontal, PickerMetrics.cardPadH)
            .padding(.vertical, PickerMetrics.cardPadV)
            .frame(width: compact ? PickerMetrics.compactCardWidth : PickerMetrics.cardWidth)
            .scaleEffect(focused ? 1.04 : 1)
            .animation(.timingCurve(0.2, 0, 0, 1, duration: 0.21), value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}

/// ProfileAvatarCircle (components/ProfileAvatarCircle.kt): the avatar colour disc with the image
/// cropped over it, or the initial at 40% of the size when there is no image or it fails to load.
struct ProfileAvatarCircle: View {
    let name: String
    let colorHex: String
    let imageURL: String?
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color(hex: colorHex))
            let initial = Text(name.first.map { String($0).uppercased() } ?? "?")
                .font(.custom("Inter", size: size * 0.4).weight(.bold)).foregroundStyle(.white)
            if let imageURL, !imageURL.isEmpty {
                CachedPosterArtwork(urlString: imageURL, width: size, height: size, maximumWidth: size * 2) { initial }
            } else {
                initial
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

// MARK: - PIN overlay

/// ProfilePinOverlay (ProfileSelectionScreen.kt:2044-2360): heading, four PIN boxes, support / error
/// line, the forgot-PIN hint and the back hint; the fourth digit submits. Modes (TvPinMode): unlock,
/// set (enter + confirm), and verify-current for change / remove / delete. The flow's decisions are
/// TvProfileManagePolicy; this view only runs the calls it asks for.
///
/// Input: a focusable 0–9 digit row under the boxes plus delete, the way tvOS's own passcode screens
/// (Restrictions, parental controls) take a PIN with the Siri Remote — the boxes stay visible and the
/// fourth digit submits, with no full-screen keyboard and no "Done" step. A hardware keyboard's digit
/// and delete keys also work (NuvioTV: "Use your remote or keyboard").
struct ProfilePinOverlay: View {
    let profile: NuvioProfile
    let onClose: () -> Void
    /// A PIN was saved or removed: close with NuvioTV's confirmation toast.
    let onFinished: (String?) -> Void
    /// Delete-verify passed: show the delete confirmation.
    let onConfirmDelete: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var flow: TvPinFlowState
    @State private var pin = ""
    @State private var working = false
    @State private var error: String?
    @State private var shake: CGFloat = 0
    @FocusState private var focusedKey: String?

    private static let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫"]
    private var policy: TvProfileManagePolicy { TvProfileManagePolicy.shared }

    init(profile: NuvioProfile, mode: TvPinMode, onClose: @escaping () -> Void,
         onFinished: @escaping (String?) -> Void = { _ in }, onConfirmDelete: @escaping () -> Void = {}) {
        self.profile = profile
        self.onClose = onClose
        self.onFinished = onFinished
        self.onConfirmDelete = onConfirmDelete
        _flow = State(initialValue: TvPinFlowState(mode: mode, confirming: false, draft: nil, currentPin: nil, localError: nil))
    }

    private var shownError: String? { error ?? flow.localError }

    var body: some View {
        VStack(spacing: 0) {
            Text(policy.heading(state: flow, name: profile.name))
                .font(NuvioType.inter(42, .bold)).lineSpacing(dp(6))
                .foregroundStyle(colors.textPrimary)
                .multilineTextAlignment(.center)
            Spacer().frame(height: PickerMetrics.pinHeadingToBoxes)
            PinBoxes(value: pin, working: working, error: shownError != nil)
                .offset(x: shake)
            Spacer().frame(height: PickerMetrics.pinBoxesToSupport)
            Text(supportText)
                .font(NuvioType.inter(18, .medium))
                .foregroundStyle(shownError != nil ? Color(argb: 0xFFFF8E8E) : colors.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: PickerMetrics.pinSupportMaxWidth)
            if policy.showsForgotHint(mode: flow.mode) {
                Spacer().frame(height: dp(10))
                Text("Forgot PIN? Reset it from your Tuvora account on tuvora website.")
                    .font(NuvioType.inter(14, .medium)).foregroundStyle(colors.textTertiary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: PickerMetrics.pinSupportMaxWidth)
            }
            Spacer().frame(height: dp(14))
            Text("Press back to cancel").font(NuvioType.inter(14, .medium)).foregroundStyle(colors.textTertiary)
            Spacer().frame(height: dp(28))
            keypad
        }
        .padding(.horizontal, PickerMetrics.screenPaddingH)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onExitCommand(perform: onClose)
        .onKeyPress(phases: .down) { press in
            if press.key == .delete { type("⌫"); return .handled }
            let chars = press.characters
            if chars.count == 1, chars.first?.isNumber == true { type(chars); return .handled }
            return .ignored
        }
        .onAppear { DispatchQueue.main.async { focusedKey = "1" } }
    }

    private var supportText: String {
        if let shownError { return shownError }
        if working { return policy.workingText(mode: flow.mode) }
        return policy.support(state: flow)
    }

    private var keypad: some View {
        HStack(spacing: dp(8)) {
            ForEach(Self.keys, id: \.self) { key in
                PinKey(label: key, focused: focusedKey == key) { type(key) }
                    .focused($focusedKey, equals: key)
                    .disabled(working)
            }
        }
        .focusSection()
    }

    private func type(_ key: String) {
        let pinPolicy = TvProfilePinPolicy.shared
        if key == "⌫" {
            pin = pinPolicy.backspace(pin: pin, isWorking: working)
        } else if let digit = key.utf16.first {
            pin = pinPolicy.append(pin: pin, digit: digit, isWorking: working)
        }
        error = nil
        if pinPolicy.isComplete(pin: pin) { submit() }
    }

    private func submit() {
        let submitted = pin
        pin = ""
        let action = policy.submit(state: flow, pin: submitted)
        switch action.kind {
        case .advance:
            flow = action.next
            if action.next.localError != nil { Task { await playErrorShake() } }
        case .verify:
            run { await verify(submitted) }
        case .setPin:
            run {
                let result = try? await ProfileRepository.shared.setPin(profileIndex: profile.profileIndex, pin: submitted, currentPin: action.currentPin)
                let outcome = policy.afterSet(state: flow, result: result)
                if outcome.kind == .saved {
                    smokeLog("SMOKE pin saved profile=%d", profile.profileIndex)
                    onFinished("PIN saved for \(profile.name).")
                    return
                }
                if let next = outcome.next { flow = next }
                error = outcome.message
                await playErrorShake()
            }
        case .clearPin:
            run {
                let result = try? await ProfileRepository.shared.clearPin(profileIndex: profile.profileIndex, currentPin: action.currentPin)
                let outcome = policy.afterClear(result: result)
                if outcome.kind == .saved {
                    smokeLog("SMOKE pin cleared profile=%d", profile.profileIndex)
                    onFinished("PIN lock removed for \(profile.name).")
                    return
                }
                error = outcome.message
                await playErrorShake()
            }
        default: break
        }
    }

    private func run(_ work: @escaping () async -> Void) {
        working = true
        Task { await work(); working = false }
    }

    private func verify(_ submitted: String) async {
        let result = try? await ProfileRepository.shared.verifyPin(profileIndex: profile.profileIndex, pin: submitted)
        let outcome = TvProfilePinPolicy.shared.outcome(result: result)
        switch outcome.kind {
        case .unlocked:
            switch policy.verified(mode: flow.mode) {
            case .openProfile:
                smokeLog("SMOKE pin unlocked profile=%d", profile.profileIndex)
                TvAppLifecycle.shared.pickProfile(profileIndex: profile.profileIndex)
            case .startNewPin:
                flow = policy.startNewPin(currentPin: submitted)
            default:
                onConfirmDelete()
            }
            return
        case .locked: error = "Profile is locked. Try again in \(outcome.retryAfterSeconds)s."
        case .incorrect: error = "Current PIN is incorrect."
        default: error = "Could not verify PIN. Try again."
        }
        smokeLog("SMOKE pin rejected profile=%d", profile.profileIndex)
        await playErrorShake()
    }

    /// NuvioTV's shake: -22, 18, -14, 10, -6, 0 px at 42 ms a step.
    private func playErrorShake() async {
        for offset in [-22.0, 18, -14, 10, -6, 0] {
            withAnimation(.linear(duration: 0.042)) { shake = offset }
            try? await Task.sleep(nanoseconds: 42_000_000)
        }
    }
}

/// ProfilePinBoxes (ProfileSelectionScreen.kt:2362-2469): 118dp squares, radius 2, 14dp apart.
private struct PinBoxes: View {
    let value: String
    let working: Bool
    let error: Bool
    @Environment(\.nuvio) private var colors
    @State private var cursorOn = true

    var body: some View {
        HStack(spacing: PickerMetrics.pinBoxGap) {
            ForEach(0..<Int(TvProfilePinPolicy.shared.PIN_LENGTH), id: \.self) { index in
                box(index)
            }
        }
        .environment(\.layoutDirection, .leftToRight)
        .onAppear { withAnimation(.easeInOut(duration: 0.52).repeatForever(autoreverses: true)) { cursorOn = false } }
    }

    private func box(_ index: Int) -> some View {
        let filled = index < value.count
        let active = index == value.count && !working
        let shape = RoundedRectangle(cornerRadius: dp(2))
        let fill: Color = error ? Color(argb: 0xFF311818).opacity(0.76)
            : filled ? Color.white.opacity(0.07) : active ? Color.white.opacity(0.04) : .clear
        let stroke: Color = error ? Color(argb: 0xFFE35D5D)
            : active ? colors.focusRing : filled ? Color.white.opacity(0.92) : Color.white.opacity(0.72)
        return ZStack {
            shape.fill(fill)
            shape.stroke(stroke, lineWidth: (active || error) ? dp(2) : dp(1))
            if filled {
                Circle().fill(Color.white).frame(width: dp(16), height: dp(16))
            } else if active {
                Rectangle().fill(Color.white.opacity(cursorOn ? 1 : 0.25)).frame(width: dp(2), height: dp(40))
            }
        }
        .frame(width: PickerMetrics.pinBox, height: PickerMetrics.pinBox)
        .animation(.easeInOut(duration: 0.14), value: filled)
        .animation(.easeInOut(duration: 0.14), value: error)
    }
}

/// One key of the digit row, styled like NuvioTV's auth buttons: white 5% + 9% hairline, white fill
/// with black text on focus.
private struct PinKey: View {
    let label: String
    let focused: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(ui: label)
                .font(NuvioType.inter(18, .semibold))
                .foregroundStyle(focused ? Color.black : Color(argb: 0xFFF5F7F8))
                .frame(width: dp(44), height: dp(44))
                .background(RoundedRectangle(cornerRadius: dp(10), style: .continuous).fill(focused ? Color.white : Color.white.opacity(0.05)))
                .overlay(RoundedRectangle(cornerRadius: dp(10), style: .continuous).stroke(Color.white.opacity(focused ? 0 : 0.09), lineWidth: dp(1)))
                .scaleEffect(focused ? 1.08 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}
