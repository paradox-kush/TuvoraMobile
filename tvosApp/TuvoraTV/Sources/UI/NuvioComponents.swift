import SwiftUI
import UIKit
import TuvoraCore

// NuvioTV building blocks translated to SwiftUI. Every size is NuvioTV's dp ×2 (see NuvioTheme.swift).

/// Whether content has held focus yet. The shell arms its left-edge catcher only after that, so the
/// focus engine's launch pick can't land in the sidebar (NuvioTV's `canFocus = expanded`).
@MainActor
final class ContentFocusActivity: ObservableObject {
    static let shared = ContentFocusActivity()
    /// True until content takes focus - at launch, and again after a tab switch - so the focus
    /// engine's automatic pick can't land on the edge catcher (and bounce into the sidebar).
    @Published private(set) var awaitingContent = true
    var everFocused: Bool { !awaitingContent }
    func expectContentFocus() { awaitingContent = true }
    /// A focused view that pages with LEFT itself (hero carousel, guide timeline) keeps the catcher off.
    @Published var leftEdgeOwned = false
    /// Such a view asks for the drawer explicitly once it reaches its own start.
    @Published private(set) var railRequests = 0
    static func touched() { if shared.awaitingContent { shared.awaitingContent = false } }

    /// Screens ping this when their data changes (rows reloaded, a profile session started). The shell
    /// then checks, once things settle, that SOMETHING still has focus: a refresh can drop the focused
    /// view, and tvOS never re-picks on its own - the remote goes dead (checklist item 8; found after
    /// an in-app profile switch, 2026-10-01).
    @Published private(set) var recoveryTick = 0
    static func contentChanged() { shared.recoveryTick &+= 1 }

    /// Whether the window holds any focus at all (UIKit's focus system; SwiftUI can't ask this).
    static var windowHasFocus: Bool {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first,
              let system = scene.focusSystem else { return true }
        return system.focusedItem != nil
    }
    static func requestRail() { shared.railRequests += 1 }
}

extension View {
    /// Nuvio focusables report their first focus so the shell knows content is live.
    func reportsFocus(_ focused: Bool) -> some View {
        onChange(of: focused) { _, now in if now { ContentFocusActivity.touched() } }
    }
}

/// HubChip (XtreamHubScreen.kt:760-821, HubChipVisualPolicy.kt): radius 12, padding 16×8, labelLarge,
/// focus scale 1.02, no border. Focused = grey Primary fill; selected = FocusBackground; idle = clear.
struct HubChip: View {
    let title: String
    var trailingIcon: String? = nil
    let selected: Bool
    /// Called when the chip takes focus (tab rows that select on focus, like NuvioTV's TabRow).
    var onFocus: (() -> Void)? = nil
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(4)) {
                Text(ui: title).font(NuvioType.labelLarge).lineLimit(1)
                if let trailingIcon {
                    Image(trailingIcon).renderingMode(.template).resizable().frame(width: dp(16), height: dp(16))
                }
            }
            .foregroundStyle(focused || selected ? colors.textPrimary : colors.textSecondary)
            .padding(.horizontal, dp(16)).padding(.vertical, dp(8))
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous)
                .fill(focused ? colors.primary : (selected ? colors.focusBackground : .clear)))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, now in if now { onFocus?() } }
    }
}

/// ContentCard (components/ContentCard.kt): artwork with the poster radius, 2dp FocusRing and scale
/// 1.02 on focus, title (titleMedium) + release info (labelMedium) below; artwork-less fallback shows the
/// name centred in TextSecondary with a hairline Border.
struct NuvioPosterCard: View {
    let title: String
    let subtitle: String?
    let imageURL: String?
    let width: CGFloat
    let height: CGFloat
    var showLabel = NuvioLayoutPrefs.posterLabels
    var onFocus: (() -> Void)? = nil
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(8)) {
                ZStack {
                    colors.backgroundCard
                    CachedPosterArtwork(urlString: imageURL, width: width, height: height, maximumWidth: width * 2) {
                        Text(ui: title).font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                            .multilineTextAlignment(.center).lineLimit(3).padding(.horizontal, dp(12))
                            .frame(width: width, height: height)
                            .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                    }
                }
                .frame(width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous)
                    .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
                .hoverEffect(.highlight)   // tvOS-native focus: lift, parallax and shadow under the Tuvora ring
                if showLabel {
                    VStack(alignment: .leading, spacing: dp(2)) {
                        Text(ui: title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                        if let subtitle { Text(ui: subtitle).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1) }
                    }
                    .frame(width: width, alignment: .leading)
                }
            }
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, isFocused in if isFocused { onFocus?() } }
    }
}

/// PlaceholderShimmer.kt: white 7% / 13% / 7% sweep, 1600 ms linear, in the card's shape.
struct NuvioShimmer: View {
    var cornerRadius: CGFloat = NuvioTokens.Radius.posterCard
    @State private var phase: CGFloat = -1

    var body: some View {
        GeometryReader { geo in
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Color.white.opacity(0.07))
                .overlay(
                    LinearGradient(colors: [.white.opacity(0.07), .white.opacity(0.13), .white.opacity(0.07)],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: geo.size.width)
                        .offset(x: phase * geo.size.width)
                        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                )
        }
        .onAppear { withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) { phase = 1 } }
    }
}

/// Modern shelf header (ModernHomeRows.kt:534-547): titleMedium SemiBold, TextPrimary, 14dp below.
struct NuvioShelfHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(12)) {
            Text(ui: title).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
            trailing
            Spacer()
        }
        .padding(.leading, NuvioTokens.Layout.gutter)
        .padding(.bottom, NuvioTokens.Layout.headerBottom)
    }
}

/// "See all" beside a hub shelf header: BackgroundCard / TextSecondary, radius 8, padding 12×4;
/// focused FocusBackground with Primary text.
struct SeeAllButton: View {
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text("See all").font(NuvioType.labelMedium)
                .foregroundStyle(focused ? colors.primary : colors.textSecondary)
                .padding(.horizontal, dp(12)).padding(.vertical, dp(4))
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.focusBackground : colors.backgroundCard))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// ErrorState / EmptyScreenState (shared TV components): centred title + message, optional retry.
/// The playback toast (`PlaybackCoordinator.message`), same look as MainShell's. A full-screen cover hides the
/// shell's copy, so every screen presented as one repeats it.
struct PlaybackToast: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors

    var body: some View {
        if let message = playback.message {
            Text(ui: message).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary)
                .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                .background(colors.backgroundElevated, in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                .padding(.bottom, dp(32))
                .transition(.opacity)
                .accessibilityIdentifier("playback.toast")
        }
    }
}

/// A loading placeholder that cannot spin forever: shows `spinner` while the load runs, and after
/// `LoadDeadline` swaps it for a message with Retry. Retry re-asks (it is the viewer's event, never a timer
/// loop) and starts a fresh wait. Put it only where the placeholder is on screen exactly while loading.
struct DeadlineLoading<Spinner: View>: View {
    var title = "Taking longer than expected"
    var message = "Check your connection, then try again."
    /// A strip inside a page (a Home row) instead of the whole screen.
    var compact = false
    let retry: () -> Void
    @ViewBuilder let spinner: () -> Spinner
    @State private var attempt = 0
    @State private var timedOut = false

    var body: some View {
        Group {
            if timedOut {
                if compact {
                    NuvioStateMessage(title: title, message: message, retry: restart).frame(height: dp(200))
                } else {
                    NuvioStateMessage(title: title, message: message, retry: restart)
                }
            } else {
                spinner()
            }
        }
        .task(id: attempt) {
            let deadline = LoadDeadline.seconds(arguments: AppArguments.list)
            try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
            if !Task.isCancelled { timedOut = true }
        }
    }

    private func restart() {
        timedOut = false
        attempt += 1
        retry()
    }
}

struct NuvioStateMessage: View {
    let title: String
    let message: String
    var retry: (() -> Void)? = nil
    /// An outlined second action under the message ("I have a setup code" on the empty IPTV hub).
    var actionTitle: String? = nil
    var actionId: String = ""
    var action: (() -> Void)? = nil
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool
    @FocusState private var actionFocused: Bool

    var body: some View {
        VStack(spacing: dp(12)) {
            Text(ui: title).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
            Text(ui: message).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                .multilineTextAlignment(.center).frame(maxWidth: dp(420))
            if let retry {
                Button(action: retry) {
                    Text("Retry").font(NuvioType.labelLargeSemi)
                        .foregroundStyle(focused ? colors.onSecondary : colors.textPrimary)
                        .padding(.horizontal, dp(24)).padding(.vertical, dp(10))
                        .background(Capsule().fill(focused ? colors.secondary : colors.backgroundCard))
                }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focused)
        .reportsFocus(focused)
                .padding(.top, dp(8))
            }
            if let actionTitle, let action {
                Button(action: action) {
                    Text(ui: actionTitle).font(NuvioType.labelLargeSemi)
                        .foregroundStyle(actionFocused ? colors.onSecondary : colors.textPrimary)
                        .padding(.horizontal, dp(24)).padding(.vertical, dp(10))
                        .background(Capsule().fill(actionFocused ? colors.secondary : Color.clear))
                        .overlay(Capsule().stroke(actionFocused ? colors.secondary : colors.border, lineWidth: NuvioTokens.Stroke.focus))
                }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($actionFocused)
                .reportsFocus(actionFocused)
                .accessibilityIdentifier(actionId)
                .padding(.top, dp(8))
                .onAppear { DispatchQueue.main.async { actionFocused = true } }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Apple TV layout preferences (Settings → Layout). Device-local like NuvioTV's LayoutPreferenceDataStore;
/// SwiftUI screens read them with `@AppStorage(key)`, plain code through these accessors.
enum NuvioLayoutPrefs {
    static let posterWidthKey = "tvos.layout.posterWidthDp"
    static let posterLabelsKey = "tvos.layout.posterLabels"
    static let showHeroKey = "tvos.layout.showHero"
    static let cwEnabledKey = "tvos.layout.cwEnabled"
    static let cwStyleKey = "tvos.layout.cwStyle"
    static let collapseSidebarKey = "tvos.layout.collapseSidebar"
    /** NuvioTV HomeLayout: "modern" (default here), "grid" or "classic". */
    static let homeLayoutKey = "tvos.layout.homeLayout"

    static var posterLabels: Bool { UserDefaults.standard.object(forKey: posterLabelsKey) as? Bool ?? true }
}

/// Poster size preference (LayoutPreferenceDataStore `poster_card_width_dp`, default 126; height ×1.5).
enum NuvioCardSize {
    static var posterWidthDp: CGFloat {
        let stored = UserDefaults.standard.double(forKey: NuvioLayoutPrefs.posterWidthKey)
        return stored > 0 ? CGFloat(stored) : 126
    }
    /// Hub portrait card: pref × 0.9072 (XtreamHubScreen.kt:824-829).
    static var hubPortrait: CGSize { CGSize(width: dp(posterWidthDp * 0.9072), height: dp(posterWidthDp * 0.9072 * 1.5)) }
    /// Hub live tile: 1.6616 × pref wide at 1.77:1.
    static var hubLandscape: CGSize { let w = dp(posterWidthDp * 1.6616); return CGSize(width: w, height: w / 1.77) }
}


/// The IMDb badge NuvioTV draws beside ratings: yellow (#F5C518) rounded tag with bold black "IMDb".
struct ImdbBadge: View {
    /// NuvioTV shows one decimal ("7.8"); providers sometimes send "7.802".
    static func format(_ rating: String) -> String {
        guard let value = Double(rating) else { return rating }
        return String(format: "%.1f", value)
    }

    var body: some View {
        Text("IMDb").font(NuvioType.inter(10, .heavy)).foregroundStyle(.black)
            .padding(.horizontal, dp(4)).padding(.vertical, dp(1))
            .background(RoundedRectangle(cornerRadius: dp(3)).fill(NuvioPrimitives.imdb))
    }
}

/// EmptyScreenState (components/EmptyScreenState.kt): optional 80dp TextTertiary icon, 24dp gap,
/// headlineSmall title, 8dp gap, bodyMedium TextSecondary subtitle, centred in a 400dp-tall block.
struct NuvioEmptyState: View {
    var icon: String? = nil
    let title: String
    var subtitle: String? = nil
    var height: CGFloat = dp(400)
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(spacing: 0) {
            if let icon {
                Image(icon).renderingMode(.template).resizable().scaledToFit()
                    .frame(width: dp(80), height: dp(80)).foregroundStyle(colors.textTertiary)
                    .padding(.bottom, dp(24))
            }
            Text(ui: title).font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary).multilineTextAlignment(.center)
            if let subtitle {
                Text(ui: subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    .multilineTextAlignment(.center).padding(.top, dp(8))
            }
        }
        .frame(maxWidth: .infinity).frame(height: height)
    }
}

/// NuvioTV's TV Material Button as the Search / Library screens use it: BackgroundCard fill,
/// TextPrimary label, radius md (12); focused FocusBackground with a white label, scale 1.02.
struct NuvioTextButton: View {
    let title: String
    var icon: String? = nil
    var selected = false
    var enabled = true
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(8)) {
                if let icon {
                    Image(icon).renderingMode(.template).resizable().scaledToFit().frame(width: dp(18), height: dp(18))
                }
                Text(ui: title).font(NuvioType.labelLarge).lineLimit(1)
            }
            .foregroundStyle(enabled ? colors.textPrimary : colors.textDisabled)
            .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous)
                .fill(focused || selected ? colors.focusBackground : colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous)
                .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .disabled(!enabled)
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// One choice in a NuvioDropdownPicker.
struct NuvioPickerOption: Identifiable, Hashable {
    let value: String
    let label: String
    var id: String { value }
}

/// The dropdown card Library and Discover share (LibraryScreen.kt LibraryDropdownPicker,
/// SearchDiscoverSection.kt DiscoverDropdownPicker): BackgroundCard, radius 14, 1dp Border; focused
/// FocusBackground with a 2dp FocusRing. labelSmall TextTertiary caption over a titleMedium value and a
/// chevron (FocusRing when focused). OK opens the options in a NuvioDialog (tvOS has no anchored
/// dropdown); the current choice is marked and takes focus.
struct NuvioDropdownPicker: View {
    let title: String
    let value: String
    let selectedValue: String?
    let options: [NuvioPickerOption]
    let onSelect: (NuvioPickerOption) -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool
    /// Simulator smoke hook: `-smokeOpenPicker <title>` opens that picker's options at launch.
    @State private var open = false
    @State private var smokeChecked = false

    var body: some View {
        Button { open = true } label: {
            VStack(alignment: .leading, spacing: dp(2)) {
                Text(ui: title).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                HStack {
                    Text(ui: value).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    Spacer(minLength: dp(8))
                    Image(open ? "md_expand_less" : "md_expand_more").renderingMode(.template).resizable()
                        .frame(width: dp(20), height: dp(20))
                        .foregroundStyle(focused ? colors.focusRing : colors.textSecondary)
                }
            }
            .padding(.horizontal, dp(14)).padding(.vertical, dp(10))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(14), style: .continuous).fill(focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(14), style: .continuous)
                .stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onAppear {
            guard !smokeChecked else { return }
            smokeChecked = true
            let args = AppArguments.list
            if let i = args.firstIndex(of: "-smokeOpenPicker"), i + 1 < args.count { open = args[i + 1] == title } else { open = false }
        }
        .sheet(isPresented: $open) {
            PickerDialog(title: title, selectedValue: selectedValue, options: options) { option in
                open = false
                onSelect(option)
            }
            .environment(\.nuvio, colors)
        }
    }
}

/// Dropdown menu rows: radius 10; focused Secondary fill with OnSecondary text, selected FocusBackground.
private struct PickerDialog: View {
    let title: String
    let selectedValue: String?
    let options: [NuvioPickerOption]
    let onSelect: (NuvioPickerOption) -> Void
    @FocusState private var focusedValue: String?
    @Environment(\.nuvio) private var colors

    var body: some View {
        NuvioDialog(title: title) {
            ForEach(options) { option in
                let isFocused = focusedValue == option.value
                let isSelected = option.value == selectedValue
                Button { onSelect(option) } label: {
                    HStack {
                        Text(ui: option.label).font(NuvioType.bodyLarge).lineLimit(1)
                            .foregroundStyle(isFocused ? colors.onSecondary : colors.textPrimary)
                        Spacer()
                        if isSelected {
                            Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                                .foregroundStyle(isFocused ? colors.onSecondary : colors.secondary)
                        }
                    }
                    .padding(.horizontal, dp(14)).padding(.vertical, dp(10))
                    .background(RoundedRectangle(cornerRadius: dp(10), style: .continuous)
                        .fill(isFocused ? colors.secondary : (isSelected ? colors.focusBackground : .clear)))
                }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focusedValue, equals: option.value)
            }
        }
        .defaultFocus($focusedValue, selectedValue ?? options.first?.value)
    }
}

/// Looks a runtime UI string up in Localizable.strings (generated from NuvioTV's translations by
/// Scripts/gen-localizable.py). String literals passed to Text already localize; String parameters
/// do not, so shared components route their labels through this. Unknown strings (titles, channel
/// names) fall back to themselves.
func L(_ s: String) -> String { Bundle.main.localizedString(forKey: s, value: s, table: nil) }

extension Text {
    /// A runtime label, localized; rendered verbatim (no Markdown or format parsing of data).
    init(ui s: String) { self.init(verbatim: L(s)) }
}

/// A string Scripts/gen-localizable.py emits under a NuvioTV key ("tv:<key>"), with its English form.
func LK(_ key: String, _ english: String) -> String { Bundle.main.localizedString(forKey: "tv:" + key, value: english, table: nil) }

/// The Continue Watching badge in the viewer's language ("Next Up", "1h 49m left"), NuvioTV's copy.
func cwBadge(_ item: TvCwItem) -> String? {
    if item.isNextUp { return LK("cw_next_up", "Next Up") }
    guard let left = TvContinueWatching.shared.minutesLeft(item: item)?.intValue else { return nil }
    return left >= 60 ? String(format: LK("cw_hours_min_left", "%1$ldh %2$ldm left"), left / 60, left % 60)
                      : String(format: LK("cw_min_left", "%1$ldm left"), left)
}
