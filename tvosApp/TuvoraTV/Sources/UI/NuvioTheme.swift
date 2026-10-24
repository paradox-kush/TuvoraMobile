import SwiftUI
import UIKit
import TuvoraCore

// Apple TV translation of NuvioTV's design system (app/src/main/java/com/nuvio/tv/ui/theme):
// ThemeColors.kt, Color.kt, PrimitiveTokens.kt, SpacingTokens.kt, ShapeTokens.kt, Type.kt,
// MotionFocusTokens.kt. NuvioTV lays out on a 960×540 dp canvas; Apple TV is 1920×1080 pt, so every
// dp/sp value goes through `dp()` (×2). Change a value here only when NuvioTV changes it.

@inline(__always) func dp(_ value: CGFloat) -> CGFloat { value * 2 }

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xFF) / 255,
                  green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

/// PrimitiveTokens.kt
enum NuvioPrimitives {
    static let black = Color(argb: 0xFF000000), white = Color(argb: 0xFFFFFFFF)
    static let neutral950 = Color(argb: 0xFF0D0D0D), neutral925 = Color(argb: 0xFF111111), neutral900 = Color(argb: 0xFF1A1A1A)
    static let neutral875 = Color(argb: 0xFF1E1E1E), neutral850 = Color(argb: 0xFF222222), neutral825 = Color(argb: 0xFF242424)
    static let neutral800 = Color(argb: 0xFF2D2D2D), neutral750 = Color(argb: 0xFF333333), neutral700 = Color(argb: 0xFF4D4D4D)
    static let neutral600 = Color(argb: 0xFF808080), neutral500 = Color(argb: 0xFF9E9E9E), neutral400 = Color(argb: 0xFFB3B3B3)
    static let neutral200 = Color(argb: 0xFFE0E0E0), neutral100 = Color(argb: 0xFFF5F5F5)
    static let marigoldAccent = Color(argb: 0xFFF5B301), marigoldAccentVariant = Color(argb: 0xFFD99A00)
    static let marigoldLive = Color(argb: 0xFFE4572E), marigoldInk = Color(argb: 0xFF0E0E10)
    static let rating = Color(argb: 0xFFFFD700), error = Color(argb: 0xFFCF6679), success = Color(argb: 0xFF4CAF50)
    static let imdb = Color(argb: 0xFFF5C518)
}

/// ThemeColorPalette (ThemeColors.kt) plus the fixed colours every theme shares (Color.kt).
struct NuvioPalette {
    var secondary: Color
    var secondaryVariant: Color
    var onSecondary: Color = NuvioPrimitives.white
    var focusRing: Color
    var focusBackground: Color
    var background: Color = NuvioPrimitives.neutral950
    var backgroundElevated: Color = NuvioPrimitives.neutral900
    var backgroundCard: Color = NuvioPrimitives.neutral825
    var surface: Color = NuvioPrimitives.neutral875
    var surfaceVariant: Color = NuvioPrimitives.neutral800
    var panel: Color = NuvioPrimitives.neutral900
    var overlay: Color = Color(argb: 0xD9000000)
    var playerOverlay: Color = Color(argb: 0xCC000000)

    // Shared by every theme (Color.kt)
    let primary = NuvioPrimitives.neutral500
    let onPrimary = NuvioPrimitives.white
    let textPrimary = NuvioPrimitives.white
    let textSecondary = NuvioPrimitives.neutral400
    let textTertiary = NuvioPrimitives.neutral600
    let textDisabled = NuvioPrimitives.neutral700
    let border = NuvioPrimitives.neutral750
    let rating = NuvioPrimitives.rating
    let error = NuvioPrimitives.error
    let success = NuvioPrimitives.success
    let live = NuvioPrimitives.marigoldLive
    let warning = Color(argb: 0xFFFFB74D)   // PrimitiveTokens.warning
    let info = Color(argb: 0xFF29B6F6)      // PrimitiveTokens.info

    static let marigold = NuvioPalette(secondary: NuvioPrimitives.marigoldAccent, secondaryVariant: NuvioPrimitives.marigoldAccentVariant,
                                       onSecondary: NuvioPrimitives.marigoldInk, focusRing: NuvioPrimitives.marigoldAccent,
                                       focusBackground: Color(argb: 0xFF3D2E0A), background: NuvioPrimitives.marigoldInk,
                                       backgroundElevated: Color(argb: 0xFF1A1712), backgroundCard: Color(argb: 0xFF201C14))
    static let crimson = NuvioPalette(secondary: Color(argb: 0xFFE53935), secondaryVariant: Color(argb: 0xFFC62828), focusRing: Color(argb: 0xFFFF5252),
                                      focusBackground: Color(argb: 0xFF3D1A1A), backgroundCard: Color(argb: 0xFF241A1A))
    static let ocean = NuvioPalette(secondary: Color(argb: 0xFF1E88E5), secondaryVariant: Color(argb: 0xFF1565C0), focusRing: Color(argb: 0xFF42A5F5),
                                    focusBackground: Color(argb: 0xFF1A2D3D), background: Color(argb: 0xFF0D0D0F),
                                    backgroundElevated: Color(argb: 0xFF1A1A1E), backgroundCard: Color(argb: 0xFF1A1F24))
    static let violet = NuvioPalette(secondary: Color(argb: 0xFF8E24AA), secondaryVariant: Color(argb: 0xFF6A1B9A), focusRing: Color(argb: 0xFFAB47BC),
                                     focusBackground: Color(argb: 0xFF2D1A3D), background: Color(argb: 0xFF0D0D0F),
                                     backgroundElevated: Color(argb: 0xFF1A1A1E), backgroundCard: Color(argb: 0xFF1F1A24))
    static let emerald = NuvioPalette(secondary: Color(argb: 0xFF43A047), secondaryVariant: Color(argb: 0xFF2E7D32), focusRing: Color(argb: 0xFF66BB6A),
                                      focusBackground: Color(argb: 0xFF1A3D1E), backgroundCard: Color(argb: 0xFF1A241A))
    static let amber = NuvioPalette(secondary: Color(argb: 0xFFFB8C00), secondaryVariant: Color(argb: 0xFFEF6C00), focusRing: Color(argb: 0xFFFFA726),
                                    focusBackground: Color(argb: 0xFF3D2D1A), background: Color(argb: 0xFF0F0D0D),
                                    backgroundElevated: Color(argb: 0xFF1E1A1A), backgroundCard: Color(argb: 0xFF24201A))
    static let rose = NuvioPalette(secondary: Color(argb: 0xFFD81B60), secondaryVariant: Color(argb: 0xFFC2185B), focusRing: Color(argb: 0xFFEC407A),
                                   focusBackground: Color(argb: 0xFF3D1A2D), backgroundCard: Color(argb: 0xFF241A1F))
    static let white = NuvioPalette(secondary: NuvioPrimitives.neutral100, secondaryVariant: NuvioPrimitives.neutral200,
                                    onSecondary: NuvioPrimitives.neutral925, focusRing: NuvioPrimitives.white,
                                    focusBackground: Color(argb: 0xFF303030), backgroundCard: NuvioPrimitives.neutral850)

    /// Fixed theme previews. Applied custom colors come from the shared palette via TvAppearance.
    static func of(_ theme: AppTheme) -> NuvioPalette {
        switch theme {
        case .crimson: return .crimson
        case .ocean: return .ocean
        case .violet: return .violet
        case .emerald: return .emerald
        case .amber: return .amber
        case .rose: return .rose
        case .white: return .white
        default: return .marigold
        }
    }

    /// tvOS settings use a ring as their main focus cue. Preserve visibility even for a black custom accent.
    static func focusRingArgb(_ ring: UInt32, background: UInt32) -> UInt32 {
        func luminance(_ c: UInt32) -> Double {
            func linear(_ byte: UInt32) -> Double {
                let value = Double(byte) / 255
                return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear((c >> 16) & 255) + 0.7152 * linear((c >> 8) & 255) + 0.0722 * linear(c & 255)
        }
        let a = luminance(ring), b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05) >= 3 ? ring : 0xFFFFFFFF
    }

    /// Color.kt AMOLED: background goes black; "AMOLED surfaces" blacks every surface too.
    func amoled(surfaces: Bool) -> NuvioPalette {
        var p = self
        p.background = NuvioPrimitives.black
        if surfaces {
            p.backgroundElevated = .black; p.backgroundCard = .black; p.surface = .black
            p.surfaceVariant = .black; p.panel = .black
        }
        return p
    }
}

/// Follows the profile's synced theme (ThemeSettingsRepository), like every other Tuvora client.
@MainActor
final class NuvioThemeModel: ObservableObject {
    @Published private(set) var colors: NuvioPalette = .marigold

    func observe() async {
        ThemeSettingsRepository.shared.ensureLoaded()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await theme in ThemeSettingsRepository.shared.selectedTheme { self.apply(theme: theme, amoled: nil) }
            }
            group.addTask { @MainActor in
                for await _ in ThemeSettingsRepository.shared.customThemeColors { self.apply(theme: nil, amoled: nil) }
            }
            group.addTask { @MainActor in
                for await on in ThemeSettingsRepository.shared.amoledEnabled { self.apply(theme: nil, amoled: on.boolValue) }
            }
        }
    }

    private var theme: AppTheme = .marigold
    private var amoledOn = false

    private func apply(theme: AppTheme?, amoled: Bool?) {
        if let theme { self.theme = theme }
        if let amoled { amoledOn = amoled }
        let base: NuvioPalette
        if self.theme == .custom {
            let p = TvAppearance.shared.palette()
            base = NuvioPalette(secondary: Color(argb: UInt32(p.accent)), secondaryVariant: Color(argb: UInt32(p.variant)),
                onSecondary: Color(argb: UInt32(p.onAccent)), focusRing: Color(argb: NuvioPalette.focusRingArgb(UInt32(p.focus), background: UInt32(p.background))),
                focusBackground: Color(argb: UInt32(p.focusBackground)), background: Color(argb: UInt32(p.background)),
                backgroundElevated: Color(argb: UInt32(p.elevated)), backgroundCard: Color(argb: UInt32(p.card)))
        } else { base = NuvioPalette.of(self.theme) }
        colors = amoledOn ? base.amoled(surfaces: false) : base
    }
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = NuvioPalette.marigold }
extension EnvironmentValues {
    var nuvio: NuvioPalette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

/// SpacingTokens.kt (dp; use through `dp()`), ShapeTokens.kt, StrokeElevationEffectTokens.kt, MotionFocusTokens.kt
enum NuvioTokens {
    enum Space { static let xxs: CGFloat = 2, xs: CGFloat = 4, sm: CGFloat = 8, md: CGFloat = 12, lg: CGFloat = 16, xl: CGFloat = 24, xxl: CGFloat = 32, xxxl: CGFloat = 48 }
    enum Layout {
        static let gutter = dp(52)            // Modern home / IPTV hub gutter
        static let itemGap = dp(12)
        static let rowGap = dp(24)
        static let headerBottom = dp(14)
        static let sidebarCollapsed = dp(72)
        static let sidebarExpanded = dp(196)
        static let sidebarContentOffset = dp(54)
    }
    enum Radius { static let posterCard = dp(12), backdrop = dp(16), button = dp(12), badge = dp(4), dialog = dp(16), sidePanel = dp(20), menu = dp(14), progress = dp(2) }
    enum Stroke { static let hairline = dp(1), focus = dp(2), heavy = dp(3) }
    enum Motion {
        static let fast = Animation.timingCurve(0.4, 0, 0.2, 1, duration: 0.18)   // focus tween (FastOutSlowIn)
        static let medium = Animation.timingCurve(0.4, 0, 0.2, 1, duration: 0.35)
        static let overlay = Animation.timingCurve(0.4, 0, 0.2, 1, duration: 0.40)
        static let focusScale: CGFloat = 1.02
    }
}

/// Type.kt — Inter (res/font/inter_variable.ttf), sizes ×2.
enum NuvioType {
    /// HIG › Typography: tvOS text is never below 23 pt (NuvioTV's 10sp labels would be 20 pt).
    static func inter(_ sp: CGFloat, _ weight: Font.Weight) -> Font { .custom("Inter", size: max(23, dp(sp))).weight(weight) }
    static let displayMedium = inter(36, .bold)
    static let headlineLarge = inter(28, .semibold)
    static let headlineMedium = inter(24, .semibold)
    static let headlineSmall = inter(22, .semibold)
    static let titleLarge = inter(20, .medium)
    static let titleMedium = inter(16, .medium)
    static let titleMediumSemi = inter(16, .semibold)
    static let titleSmall = inter(14, .medium)
    static let bodyLarge = inter(16, .regular)
    static let bodyMedium = inter(14, .regular)
    static let bodySmall = inter(12, .regular)
    static let labelLarge = inter(14, .medium)
    static let labelLargeSemi = inter(14, .semibold)
    static let labelMedium = inter(12, .medium)
    static let labelSmall = inter(10, .semibold)
}

/// The one full-screen player. Any screen asks it to play; it presents over whatever is on screen.
///
/// Presented through UIKit from the top-most view controller, not a SwiftUI `.fullScreenCover` on the
/// shell: a cover on the shell cannot present while another screen (details from the IPTV hub, the
/// source picker) is already covering it — SwiftUI silently drops the request, and playback never
/// appeared (simulator, 2026-09-29).
@MainActor
final class PlaybackCoordinator: ObservableObject {
    @Published var session: TvPlayerSessionBox?
    @Published var message: String?
    /// Kept for callers that observe it; the picker itself is presented through UIKit like the player.
    @Published var pendingPicker: TvCwTargetBox?
    /// The palette the presented screens use (set by the shell as the theme changes).
    var palette: NuvioPalette = .marigold

    /// Live channel zapping (NuvioTV fullscreen: D-pad up/down changes channel): opens the channel
    /// [offset] away in the list the viewer started from, or nil when there is none.
    var zapper: ((Int) async -> TvPlayerSession?)?

    private weak var playerHost: PlayerHostController?
    private weak var pickerHost: UIViewController?

    func play(_ session: TvPlayerSession, zapper: ((Int) async -> TvPlayerSession?)? = nil) {
        self.zapper = zapper
        if let current = self.session?.session, current !== session { current.close() }   // switching source/channel
        let box = TvPlayerSessionBox(session: session)
        self.session = box
        let relay = PlayerRemoteRelay()
        // The identity is the session: swapping rootView to the same view type otherwise keeps the old
        // screen's identity, so onAppear (attach), the state task and the engine host never moved to the
        // new channel — a live zap showed the new title over a closed player (UITest LiveZapTests).
        let screen = TvPlayerScreen(session: session, relay: relay) { [weak self] in self?.stop() }
            .id(ObjectIdentifier(session))
            .environment(\.nuvio, palette)
            .environmentObject(self)
        if let host = playerHost {
            host.relay = relay
            host.rootView = AnyView(screen)          // already on screen: swap the session in place
            return
        }
        guard let top = Self.topViewController() else { session.close(); self.session = nil; return }
        let host = PlayerHostController(rootView: AnyView(screen), relay: relay)
        host.modalPresentationStyle = .fullScreen
        host.view.backgroundColor = .black
        host.onGone = { [weak self] in self?.playerHostGone($0) }
        top.present(host, animated: true)
        playerHost = host
    }

    /// Leaves the player: the session is closed (engine destroyed) before the screen goes (B112).
    func stop() {
        session?.session.close()
        session = nil
        zapper = nil
        playerHost?.dismiss(animated: true)
        playerHost = nil
    }

    /// The player screen went away by a route that didn't come through [stop] (UIKit dismissed it):
    /// nothing may keep playing without a screen (B112: sound after leaving the player).
    private func playerHostGone(_ host: PlayerHostController) {
        if let current = playerHost, current !== host { return }   // an older host; a new player is up
        playerHost = nil
        session?.session.close()
        session = nil
        zapper = nil
    }

    /// Plays [next] only while the player that asked for it (showing [from]) is still up; a session that
    /// resolves after the viewer left is closed instead of re-opening the player (live zap, replay walk).
    func continuePlaying(_ next: TvPlayerSession, from: TvPlayerSession, zapper: ((Int) async -> TvPlayerSession?)? = nil) {
        guard session?.session === from, !from.isClosed else { next.close(); return }
        play(next, zapper: zapper)
    }

    /// Leave the player and open the source picker for [video] (next episode, or one chosen in the Episodes panel).
    func openSources(meta: MetaDetails, video: MetaVideo?) {
        session?.session.close()
        session = nil
        let picker = AnyView(StreamPickerScreen(meta: meta, video: video) { [weak self] in self?.pickerHost?.dismiss(animated: true) }
            .environment(\.nuvio, palette).environmentObject(self))
        if let host = playerHost {
            playerHost = nil
            host.dismiss(animated: true) { [weak self] in self?.present(picker) { self?.pickerHost = $0 } }
        } else {
            present(picker) { [weak self] in self?.pickerHost = $0 }
        }
    }

    func notify(_ text: String) {
        message = text
        Task { try? await Task.sleep(nanoseconds: 3_500_000_000); if message == text { message = nil } }
    }

    private func present(_ view: AnyView, onPresented: @escaping (UIViewController) -> Void) {
        guard let top = Self.topViewController() else { return }
        let host = UIHostingController(rootView: view)
        host.modalPresentationStyle = .fullScreen
        host.view.backgroundColor = .black
        top.present(host, animated: true)
        onPresented(host)
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController ?? scene?.windows.first?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed { top = presented }
        return top
    }
}

struct TvPlayerSessionBox: Identifiable {
    let id = UUID()
    let session: TvPlayerSession
}


extension View {
    /// Liquid Glass for the navigation layer (HIG › Materials: never on content). tvOS 26 draws real
    /// glass (Apple TV 4K 2nd gen and newer); tvOS 17.5–18 falls back to the ultra-thin material.
    @ViewBuilder
    func navigationGlass<S: Shape>(in shape: S) -> some View {
        if #available(tvOS 26.0, *) {
            self.glassEffect(.regular, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
    }
}
