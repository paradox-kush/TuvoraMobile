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
        // `-smokeDiagnostics <true|false>` sets Settings -> Diagnostics -> Crash & usage reports, so a UI test
        // starts from a known consent state whatever an earlier run left behind.
        if let i = args.firstIndex(of: "-smokeDiagnostics"), i + 1 < args.count {
            UserDefaults.standard.set(args[i + 1] != "false", forKey: "sentry_enabled")
        }
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
        // Telemetry v2: the Kotlin service and its crash hook come up before any other shared code runs.
        TvTelemetry.shared.start()
        // Lets shared Kotlin capture without linking PostHog; only the telemetry service calls these.
        AnalyticsSink.shared.register { event, properties in
            PostHogSDK.shared.capture(event, properties: properties)
        }
        AnalyticsSink.shared.registerTimed { event, properties, atEpochMs in
            PostHogSDK.shared.capture(event, properties: properties,
                                      timestamp: Date(timeIntervalSince1970: TimeInterval(atEpochMs.int64Value) / 1000.0))
        }
        AnalyticsSink.shared.registerSuperProperties { properties in
            PostHogSDK.shared.register(properties as [String: Any])
        }
        AnalyticsSink.shared.registerFlush { PostHogSDK.shared.flush() }
        // The HDR / Dolby Vision half of the hardware profile (the model and RAM come from the shared Kotlin).
        TvTelemetryBootstrap.registerSuperProperties()
        IosTelemetry.shared.onSdkReady()
        Task.detached(priority: .utility) { try? await IosTelemetry.shared.sendPendingCrash() }
        MemoryPressureObserver.shared.start()
        TvAppGraph.shared.start()
        PlayerEngines.register()
        #if DEBUG
        // UIAppFonts must name the file as it lands in the bundle (root, not Fonts/): a wrong path
        // silently falls back to the system font on every screen.
        if UIFont(name: "Inter", size: 20) == nil { smokeLog("SMOKE font Inter MISSING") }
        #endif
    }

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                // session_health goes out when the app leaves the foreground.
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active: IosTelemetry.shared.onForeground()
                    case .background: IosTelemetry.shared.onBackground()
                    default: break
                    }
                }
        }
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
