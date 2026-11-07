import SwiftUI
import ComposeApp
import PostHog

/// Feeds the OS's real-time memory-pressure events into the shared Kotlin memory tier
/// (`AppMemory`): warning/critical escalate (and trim every registered cache), normal
/// relaxes. This is the dynamic half of the iOS probe — the static half is
/// ProcessInfo.physicalMemory read Kotlin-side. os_proc_available_memory() needs a
/// cinterop the shared framework doesn't have, so the event source lives here in Swift.
private final class MemoryPressureObserver {
    static let shared = MemoryPressureObserver()
    private var source: DispatchSourceMemoryPressure?

    func start() {
        guard source == nil else { return }
        let source = DispatchSource.makeMemoryPressureSource(
            eventMask: [.normal, .warning, .critical],
            queue: .main
        )
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

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        // stdout is block-buffered when attached to `devicectl --console` (not a TTY), so the last
        // few KB of logs — the ones that matter when the app hangs and gets SIGKILLed — were lost.
        setvbuf(stdout, nil, _IOLBF, 0)
        #endif
        // PostHog: crash autocapture, event scrubbing and the diagnostics consent switch
        // (TuvoraTelemetry.swift, shared with the Apple TV app).
        TuvoraTelemetry.start()
        // Telemetry v2: the Kotlin service and its crash hook come up first, before any other shared code runs.
        IosTelemetry.shared.start()
        // Lets shared Kotlin code capture without linking a PostHog SDK into the framework.
        // Registered before any shared code can run, so no early event is dropped. Only the Kotlin
        // telemetry service calls these (send policy, consent, support code, trail live there).
        AnalyticsSink.shared.register { event, properties in
            PostHogSDK.shared.capture(event, properties: properties)
        }
        // A recovered crash keeps the time it happened, not the time it was sent.
        AnalyticsSink.shared.registerTimed { event, properties, atEpochMs in
            PostHogSDK.shared.capture(
                event,
                properties: properties,
                timestamp: Date(timeIntervalSince1970: TimeInterval(atEpochMs.int64Value) / 1000.0)
            )
        }
        AnalyticsSink.shared.registerSuperProperties { properties in
            PostHogSDK.shared.register(properties as [String: Any])
        }
        AnalyticsSink.shared.registerFlush {
            PostHogSDK.shared.flush()
        }
        IosTelemetry.shared.onSdkReady()
        Task.detached(priority: .utility) {
            // Off Main; the Kotlin side has its own deadline and never throws.
            try? await IosTelemetry.shared.sendPendingCrash()
        }
        // Registered next to the AnalyticsSink bridge, before shared code can allocate:
        // pressure events trim the Kotlin-side budget registry from the very first screen.
        MemoryPressureObserver.shared.start()

        if #available(iOS 14.0, *) {
            // MetricKit supplies delayed hangs and resource failures that exception
            // autocapture cannot observe. The singleton remains subscribed for app life.
            MetricKitReliabilityReporter.shared.start()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
                // session_health goes out when the app leaves the foreground (Telemetry v2).
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active: IosTelemetry.shared.onForeground()
                    case .background: IosTelemetry.shared.onBackground()
                    default: break
                    }
                }
        }
    }
}
