import SwiftUI
import TuvoraCore
import PostHog

/// Apple TV entry point. Order matters (TvAppGraph.kt): analytics and the memory-pressure source
/// first, then the Kotlin bootstrap, before any screen reads shared state.
@main
struct TuvoraTVApp: App {
    init() {
        // Before anything reads storage: route the shared code's defaults to Apple TV's tiers.
        TieredUserDefaults.install()
        // Simulator hook: `-smokeCollapseSidebar <true|false>` sets Settings -> Layout -> Collapse Sidebar
        // (the tiered defaults don't read the launch-argument domain, so `-key value` alone does nothing).
        let args = AppArguments.list
        if let i = args.firstIndex(of: "-smokeCollapseSidebar"), i + 1 < args.count {
            UserDefaults.standard.set(args[i + 1] != "false", forKey: NuvioLayoutPrefs.collapseSidebarKey)
        }
        #if DEBUG && targetEnvironment(simulator)
        // Disposable simulator acceptance tests: local-only profile, no account sign-in or telemetry.
        if args.contains("-smokeLocalGuest") {
            if AuthStorage.shared.loadAnonymousUserId() == nil {
                AuthStorage.shared.saveAnonymousUserId(userId: UUID().uuidString)
            }
            UserDefaults.standard.set(false, forKey: "sentry_enabled")
            RecEventSettings.shared.setEnabled(value: false)
        }
        #endif
        // PostHog with crash autocapture, the iPhone app's scrubbing and its consent switch.
        TuvoraTelemetry.start()
        AnalyticsSink.shared.register { event, properties in
            PostHogSDK.shared.capture(event, properties: properties)
        }
        MemoryPressureObserver.shared.start()
        TvAppGraph.shared.start()
        PlayerEngines.register()
        #if DEBUG
        // UIAppFonts must name the file as it lands in the bundle (root, not Fonts/): a wrong path
        // silently falls back to the system font on every screen.
        if UIFont(name: "Inter", size: 20) == nil { smokeLog("SMOKE font Inter MISSING") }
        #endif
    }

    var body: some Scene {
        WindowGroup { RootView() }
    }
}

/// Feeds AppMemory from the system's memory-pressure events, as the iPhone app does (iOSApp.swift).
final class MemoryPressureObserver {
    static let shared = MemoryPressureObserver()
    private var source: DispatchSourceMemoryPressure?

    func start() {
        guard source == nil else { return }
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.normal, .warning, .critical], queue: .main)
        source.setEventHandler {
            let event = source.data
            if !event.intersection([.warning, .critical]).isEmpty {
                AppMemory.shared.onPressure()
            } else if event.contains(.normal) {
                AppMemory.shared.onRelax()
            }
        }
        source.activate()
        self.source = source
    }
}
