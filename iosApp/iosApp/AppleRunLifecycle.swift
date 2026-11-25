#if os(tvOS)
import TuvoraCore
#else
import ComposeApp
#endif
import Foundation
import UIKit
import os

/// Feeds the pure state machines (`UncleanExitMachine`) from UIKit lifecycle notifications and hands their findings to
/// the shared Kotlin telemetry port (`AppleTelemetryBridge`). One file for iPhone/iPad and Apple TV: the notifications
/// and the marker mean the same on both. Call `start()` once, on the main thread, AFTER `IosTelemetry.onSdkReady()`.
final class AppleRunLifecycle {
    static let shared = AppleRunLifecycle()

    private let lock = NSLock()
    private let store: RunMarkerStore
    private var marker: RunMarker?
    private var started = false
    private var observers: [NSObjectProtocol] = []

    init(store: RunMarkerStore = UserDefaultsRunMarkerStore()) {
        self.store = store
    }

    func start() {
        lock.lock()
        if started { lock.unlock(); return }
        started = true
        lock.unlock()

        let identity = RunIdentityProbe.current()
        let verdict = UncleanExitMachine.evaluate(
            previous: store.load(), current: identity, reportsEnabled: TuvoraTelemetry.crashReportsEnabled()
        )
        // The marker for THIS run replaces the old one before anything is reported: a crash while reporting must not
        // report the same run again.
        let inBackground = UIApplication.shared.applicationState == .background
        mutate { _ in UncleanExitMachine.launched(identity: identity, inBackground: inBackground) }

        if case let .uncleanSuspected(wasPlaying, kind, engine, memWarnings) = verdict {
            AppleTelemetryBridge.shared.recordUncleanExit(
                wasPlaying: wasPlaying, kind: kind, engine: engine, memWarnings: Int32(clamping: memWarnings)
            )
        }

        AppleTelemetryBridge.shared.registerPlaybackSink { [weak self] kind, engine in
            self?.update { UncleanExitMachine.playbackChanged(kind: kind, engine: engine, $0) }
        }
        AppleTelemetryBridge.shared.installPlaybackBreadcrumbs()

        let center = NotificationCenter.default
        func observe(_ name: Notification.Name, _ handler: @escaping () -> Void) {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in handler() })
        }
        observe(UIApplication.didEnterBackgroundNotification) { [weak self] in
            self?.update { UncleanExitMachine.entered(.background, $0) }
        }
        observe(UIApplication.didBecomeActiveNotification) { [weak self] in
            self?.update { UncleanExitMachine.entered(.foreground, $0) }
        }
        observe(UIApplication.willTerminateNotification) { [weak self] in
            self?.update { UncleanExitMachine.entered(.terminated, $0) }
        }
        // iOS and tvOS both post this; it is counted by where the app was, never sent as an event.
        observe(UIApplication.didReceiveMemoryWarningNotification) { [weak self] in
            let foreground = UIApplication.shared.applicationState != .background
            AppleTelemetryBridge.shared.recordMemoryWarning(foreground: foreground)
            self?.update { UncleanExitMachine.memoryWarned($0) }
        }
    }

    private func mutate(_ change: (RunMarker?) -> RunMarker?) {
        lock.lock()
        defer { lock.unlock() }
        guard let next = change(marker) else { return }
        marker = next
        store.save(next)
    }

    private func update(_ change: (RunMarker) -> RunMarker) {
        mutate { (current: RunMarker?) -> RunMarker? in current.map(change) }
    }
}
