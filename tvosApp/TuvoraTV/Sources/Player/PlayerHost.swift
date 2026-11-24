import SwiftUI
import TuvoraCore
import UIKit

/// Carries Siri Remote presses from the player's UIKit host to the SwiftUI player screen, which asks
/// TvPlayerRemotePolicy what each one does. The screen installs [handler] and [action] when it appears.
@MainActor
final class PlayerRemoteRelay {
    /// What the policy would do with [input] right now; `.passThrough` leaves the press to the focus engine.
    var action: (TvRemoteInput) -> TvRemoteAction = { input in
        input == .back ? .leave : .passThrough
    }
    var handler: ((TvRemoteInput) -> Void)?

    func takes(_ input: TvRemoteInput) -> Bool { action(input) != .passThrough }
    func send(_ input: TvRemoteInput) { handler?(input) }
}

/// The full-screen player's view controller (PlaybackCoordinator presents it modally).
///
/// B112 (Apple TV beta): sound kept playing after the viewer left the player. A UIKit modal on tvOS is
/// dismissed by the Back (Menu) press itself, before SwiftUI's `onExitCommand` runs (Apple DTS,
/// developer.apple.com/forums/thread/843259), so the screen vanished while its engine played on. Here:
/// - Back, Play/Pause, Select and the clickpad arrows are UIKit press recognizers (also immune to the
///   tvOS 18 `onMoveCommand` / `onPlayPauseCommand` missed-first-press bug, forums/thread/764582). Each
///   one only begins when the policy has an action for it, so the focus engine keeps every other press.
/// - Whatever still takes the screen away (any dismissal route) reports [onGone], and the coordinator
///   closes the session: no engine outlives its screen.
final class PlayerHostController: UIHostingController<AnyView>, UIGestureRecognizerDelegate {
    var relay: PlayerRemoteRelay
    var onGone: ((PlayerHostController) -> Void)?
    private var inputs: [ObjectIdentifier: TvRemoteInput] = [:]
    private var reportedGone = false

    init(rootView: AnyView, relay: PlayerRemoteRelay) {
        self.relay = relay
        super.init(rootView: rootView)
    }

    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let types: [UIPress.PressType] = [.menu, .playPause, .select, .leftArrow, .rightArrow, .upArrow, .downArrow]
        for type in types {
            guard let input = Self.input(for: type) else { continue }
            let tap = UITapGestureRecognizer(target: self, action: #selector(pressed(_:)))
            tap.allowedPressTypes = [NSNumber(value: type.rawValue)]
            tap.allowedTouchTypes = []
            tap.delegate = self
            view.addGestureRecognizer(tap)
            inputs[ObjectIdentifier(tap)] = input
        }
    }

    func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        guard let input = inputs[ObjectIdentifier(recognizer)] else { return true }
        return relay.takes(input)
    }

    @objc private func pressed(_ recognizer: UITapGestureRecognizer) {
        guard let input = inputs[ObjectIdentifier(recognizer)] else { return }
        RemoteTrail.add("rec")
        deliver(input)
    }

    // Presses that reach the host through the responder chain rather than a recognizer: with nothing
    // focused (e.g. bare video before the focus engine settles) tvOS hands them to the first responder
    // (the libmpv view controller) and up the chain to here. Taken ones go to the policy; Back never
    // reaches UIKit's default, which would dismiss the modal and leave the engine running.
    private var takenPresses = Set<ObjectIdentifier>()

    private static func input(for type: UIPress.PressType) -> TvRemoteInput? {
        switch type {
        case .menu: return .back
        case .playPause: return .playPause
        case .select: return .select
        case .leftArrow: return .left
        case .rightArrow: return .right
        case .upArrow: return .up
        case .downArrow: return .down
        default: return nil
        }
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var rest = Set<UIPress>()
        for press in presses {
            if let input = Self.input(for: press.type), relay.takes(input) {
                takenPresses.insert(ObjectIdentifier(press))
            } else {
                rest.insert(press)
            }
        }
        if !rest.isEmpty { super.pressesBegan(rest, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var rest = Set<UIPress>()
        for press in presses {
            if takenPresses.remove(ObjectIdentifier(press)) != nil, let input = Self.input(for: press.type) {
                RemoteTrail.add("resp")
                deliver(input)
            } else {
                rest.insert(press)
            }
        }
        if !rest.isEmpty { super.pressesEnded(rest, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var rest = Set<UIPress>()
        for press in presses where takenPresses.remove(ObjectIdentifier(press)) == nil { rest.insert(press) }
        if !rest.isEmpty { super.pressesCancelled(rest, with: event) }
    }

    /// To the screen; before it has appeared, Back still leaves (the dismissal closes the session via [onGone]).
    private func deliver(_ input: TvRemoteInput) {
        if relay.handler == nil {
            if input == .back { dismiss(animated: true) }
            return
        }
        relay.send(input)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // Dismissed by any route (or taken down with the screen that presented it).
        if !reportedGone, isBeingDismissed || presentingViewController == nil {
            reportedGone = true
            // B279: the player is gone, so nothing holds the screen on (covers a dismissal the SwiftUI screen never saw).
            UIApplication.shared.isIdleTimerDisabled = false
            onGone?(self)
        }
    }
}
