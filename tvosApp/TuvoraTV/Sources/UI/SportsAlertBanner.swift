import SwiftUI
import TuvoraCore
import UIKit

// Apple TV's in-app sports alerts (spec research/sports-alerts-spec-2026-11-24.md §5): a top-right banner drawn by
// MainShell and over the player host, never focusable, gone after 10 s (the shared controller's TV dismiss time).
// While a banner is ON SCREEN, holding Select runs its action; a short press is never touched. The decision, the
// text and the timer are Kotlin's (TvSportsAlerts / TvSportsAlertsPolicy); this file only draws and forwards.

/// The banner the screens draw, and what its action does on Apple TV. One per process, Main only.
@MainActor
final class SportsAlertCenter: ObservableObject {
    static let shared = SportsAlertCenter()

    @Published private(set) var banner: TvAlertBanner?
    /// An alert's "Open game": the Sports hub opens this event's game page once its fixture is held, or drops it.
    @Published var pendingGameId: String?

    /// MainShell is mounted (not the gate, picker or sign-in screens): its overlay can be on screen.
    var shellMounted = false
    /// Set by MainShell: the player to play a Watch channel in (or leave for Open game), and the switch to Sports.
    weak var playback: PlaybackCoordinator?
    var openSports: () -> Void = {}

    private init() {}

    /// MainShell's task: mirrors the Kotlin banner for as long as the shell is up.
    func observe() async {
        for await next in TvSportsAlerts.shared.banner { banner = next }
    }

    /// Whether the viewer can see a banner right now: the shell with nothing presented over it, or the player.
    /// A SwiftUI cover (details, game page) hides the shell's overlay, so Select must stay untouched there.
    var bannerOnScreen: Bool {
        guard banner != nil, let top = PlaybackCoordinator.topViewController() else { return false }
        if top is PlayerHostController { return true }
        return shellMounted && TvAppLifecycle.shared.screen.value == .main && top.presentingViewController == nil
    }

    /// Hold Select on a visible banner.
    func perform() {
        guard bannerOnScreen, let action = TvSportsAlerts.shared.act() else { return }
        switch action.kind {
        case TvAlertAction.companion.WATCH:
            guard let channelId = action.channelId else { return }
            let name = action.channelName ?? ""
            Task { [weak self] in
                let session = try? await TvIptvBrowse.shared.playChannel(contentId: channelId, name: name, logo: nil)
                guard let playback = self?.playback else { session?.close(); return }
                // Not silent: a channel with no playable address says so (the same toast the shell uses).
                if let session { playback.play(session) } else { playback.notify("This channel can't play right now") }
            }
        case TvAlertAction.companion.OPEN_GAME:
            let eventId = action.eventId
            leavePlayer { [weak self] in
                self?.pendingGameId = eventId
                self?.openSports()
            }
        case TvAlertAction.companion.OPEN_SPORTS:
            leavePlayer { [weak self] in self?.openSports() }
        default:
            break
        }
    }

    /// Open game / Open Sports over the player: the session closes first (B112), and the Sports tab shows once the
    /// player is off screen, so the game page's cover is not asked for mid-dismissal.
    private func leavePlayer(then: @escaping () -> Void) {
        guard let playback, playback.session != nil else { then(); return }
        playback.stop(then: then)
    }
}

/// The overlay MainShell and the player host place top-right. Text only: no Button, nothing focusable, no hit testing.
/// Sized to the card (empty with no banner), never a full-screen layer: the focus engine skips views it sees as covered.
struct SportsAlertOverlay: View {
    @ObservedObject private var center = SportsAlertCenter.shared

    var body: some View {
        Group {
            if let banner = center.banner {
                SportsAlertBannerCard(banner: banner)
                    .id(banner.id)
                    // tvOS's safe area is 60 pt top, 80 pt sides: measured from the screen edge (ignoresSafeArea below) on both hosts.
                    .padding(.top, 60).padding(.trailing, 80)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(NuvioTokens.Motion.medium, value: center.banner?.id)
        .allowsHitTesting(false)
        .ignoresSafeArea()
    }
}

private struct SportsAlertBannerCard: View {
    let banner: TvAlertBanner
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            HStack(alignment: .firstTextBaseline, spacing: dp(10)) {
                Image(systemName: "bell.fill").foregroundStyle(colors.secondary)
                Text(verbatim: banner.title).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(2)
            }
            if let subtitle = banner.subtitle, !subtitle.isEmpty {
                Text(verbatim: subtitle).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(1)
            }
            ForEach(Array(banner.lines.prefix(3).enumerated()), id: \.offset) { _, line in
                Text(verbatim: line).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(1)
            }
            Text(ui: banner.hint).font(NuvioType.labelMedium).foregroundStyle(colors.secondary).padding(.top, dp(2))
        }
        .padding(.horizontal, dp(18)).padding(.vertical, dp(14))
        .frame(width: dp(460), alignment: .leading)
        .background(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).fill(colors.backgroundElevated.opacity(0.96)))
        .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
        .shadow(color: .black.opacity(0.4), radius: dp(12), y: dp(4))
        .accessibilityElement(children: .combine)
    }
}

/// Hold Select (≥ 0.5 s) on a visible banner. One window-level long-press recognizer (the RemoteTouchCatcher
/// pattern), so it also covers the player, which UIKit presents in the same window. Gated three ways, so a short
/// press and every press without a banner reach the focus engine and the player exactly as before:
/// - `isEnabled` follows the banner (no banner: the recognizer does not exist for UIKit);
/// - `shouldReceive` refuses the press unless a banner is actually on screen ([SportsAlertCenter.bannerOnScreen]);
/// - a press released before 0.5 s fails the recognizer, and the button / player sees its normal press-up.
/// Once it recognizes it cancels the press for the views underneath (`cancelsTouchesInView`), so the focused button
/// or the player's Select does not also fire.
struct SelectHoldCatcher: UIViewRepresentable {
    let armed: Bool

    func makeUIView(context: Context) -> SelectHoldHostView { SelectHoldHostView() }

    func updateUIView(_ uiView: SelectHoldHostView, context: Context) { uiView.setArmed(armed) }

    static func dismantleUIView(_ uiView: SelectHoldHostView, coordinator: ()) { uiView.removeRecognizer() }
}

final class SelectHoldHostView: UIView, UIGestureRecognizerDelegate {
    private var hold: UILongPressGestureRecognizer?
    private weak var attachedWindow: UIWindow?
    private var armed = false

    override var canBecomeFocused: Bool { false }

    func setArmed(_ armed: Bool) {
        self.armed = armed
        hold?.isEnabled = armed
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        removeRecognizer()
        guard let window else { return }
        let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(held(_:)))
        recognizer.allowedPressTypes = [NSNumber(value: UIPress.PressType.select.rawValue)]
        recognizer.allowedTouchTypes = []
        recognizer.minimumPressDuration = 0.5
        recognizer.cancelsTouchesInView = true
        recognizer.delegate = self
        recognizer.isEnabled = armed
        window.addGestureRecognizer(recognizer)
        hold = recognizer
        attachedWindow = window
    }

    func removeRecognizer() {
        if let attachedWindow, let hold { attachedWindow.removeGestureRecognizer(hold) }
        hold = nil
        attachedWindow = nil
    }

    @objc private func held(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        SportsAlertCenter.shared.perform()
    }

    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive press: UIPress) -> Bool {
        SportsAlertCenter.shared.bannerOnScreen
    }

    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        false
    }

    /// With a banner up, the hold is the banner's: another long press (a card's context menu) waits for this one to fail.
    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        other is UILongPressGestureRecognizer
    }
}

/// What the player shows, fed to the shared alerts (gate 3 and "while something plays"), from PlaybackCoordinator:
/// the one place every Apple TV player session starts, swaps (zap, replay walk, next source) and ends. Clears only
/// the channel it published itself.
@MainActor
final class SportsAlertsPlaybackFeed {
    private var context: (any Disposable)?
    private var channelId: String?

    /// [session] is now on screen (a first open or an in-place swap).
    func showing(_ session: TvPlayerSession) {
        let alerts = TvSportsAlerts.shared
        // Enter the new context before leaving the old one, so a zap never reads as "nothing playing" in between.
        let previous = context
        context = alerts.enterPlayback(live: alerts.isLiveLaunch(session: session))
        previous?.dispose()
        let next = alerts.playingChannelOf(session: session)
        if let next { alerts.setPlayingChannel(contentId: next, name: session.title) }
        if let old = channelId, old != next { alerts.clearPlayingChannel(contentId: old) }
        channelId = next
    }

    /// The player is gone (any route).
    func gone() {
        context?.dispose()
        context = nil
        if let old = channelId { TvSportsAlerts.shared.clearPlayingChannel(contentId: old) }
        channelId = nil
    }
}
