import Foundation
import PostHog

/// PostHog setup shared by the iPhone app (iOSApp.swift) and the Apple TV app (tvosApp/project.yml
/// compiles this same file), so both platforms scrub events and honour consent identically.
enum TuvoraTelemetry {
    private static let crashReportsEnabledKey = "sentry_enabled"
    static let geoIpDisableProperty = "$geoip_disable"
    /// Written by the shared Kotlin (`IosTelemetryHost`); same key.
    private static let supportCodeKey = "tuvora.support_code"
    private static let sensitivePropertyNames: Set<String> = [
        "url", "uri", "href", "referrer", "code", "state", "token", "access_token",
        "refresh_token", "authorization", "password", "secret", "cookie", "api_key"
    ]

    /// Mirrors `resolveDiagnosticsEnabled` in the shared Kotlin: absent means on, a stored value wins.
    ///
    /// This drives PostHog's opt-out, so it governs every event the app sends, not only crashes. It
    /// shipped defaulting to off in build 114, and the platform went dark — two weeks later only one
    /// iOS user on a post-114 build was reporting at all, against 75 on builds that predated the gate.
    /// Someone who has explicitly turned it off keeps that choice.
    static func crashReportsEnabled() -> Bool {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: crashReportsEnabledKey) != nil else { return true }
        return defaults.bool(forKey: crashReportsEnabledKey)
    }

    private static func isSensitiveProperty(_ key: String) -> Bool {
        let normalized = key.lowercased().replacingOccurrences(of: "-", with: "_")
        return sensitivePropertyNames.contains(normalized)
            || normalized.hasSuffix("_url")
            || normalized.hasSuffix("_uri")
            || normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("authorization")
            || normalized.contains("cookie")
    }

    static func sanitizedString(_ value: String) -> String {
        let withoutURLs = value.replacingOccurrences(
            of: #"(?i)\b[a-z][a-z0-9+.-]*://\S+"#,
            with: "[REDACTED_URL]",
            options: .regularExpression
        )
        let withoutAuthorization = withoutURLs.replacingOccurrences(
            of: #"(?i)\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+"#,
            with: "[REDACTED_AUTH]",
            options: .regularExpression
        )
        return withoutAuthorization.replacingOccurrences(
            of: #"(?i)\b(code|state|access_token|refresh_token|token|authorization|password|secret)=([^\s&]+)"#,
            with: "$1=[REDACTED]",
            options: .regularExpression
        )
    }

    private static func sanitizedValue(_ value: Any) -> Any {
        if let string = value as? String {
            return sanitizedString(string)
        }
        if let dictionary = value as? [String: Any] {
            return sanitizedProperties(dictionary)
        }
        if let array = value as? [Any] {
            return array.map(sanitizedValue)
        }
        return value
    }

    static func sanitizedProperties(_ properties: [String: Any]) -> [String: Any] {
        var sanitized: [String: Any] = [:]
        for (key, value) in properties where !isSensitiveProperty(key) {
            sanitized[key] = sanitizedValue(value)
        }
        sanitized[geoIpDisableProperty] = true
        return sanitized
    }

    /// Configures and starts PostHog: crash autocapture on, every optional collector off, events
    /// scrubbed before send. The caller registers its Kotlin AnalyticsSink bridge right after.
    static func start() {
        // Public client-side key — safe to ship in the binary.
        let config = PostHogConfig(
            projectToken: "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye",
            host: "https://us.i.posthog.com"
        )
        // Capture crashes as $exception events (Mach exceptions on iOS; tvOS has no Mach
        // exception handling, so the SDK falls back to POSIX signals there).
        config.errorTrackingConfig.autoCapture = true
        config.optOut = !crashReportsEnabled()
        config.captureApplicationLifecycleEvents = false
        config.captureScreenViews = false
        config.sendFeatureFlagEvent = false
        config.preloadFeatureFlags = false
        #if os(iOS)
        config.surveys = false
        config.sessionReplay = false
        config.sessionReplayConfig.captureNetworkTelemetry = false
        config.sessionReplayConfig.captureLogs = false
        config.sessionReplayConfig.screenshotMode = false
        config.tracingHeaders = []
        // Telemetry v2: element taps and the $rageclick event are the iPhone's biggest success-path noise
        // (~2k $rageclick / week). rageClickConfig is separate from captureElementInteractions: disable both.
        config.captureElementInteractions = false
        config.rageClickConfig.enabled = false
        #endif
        config.logs.setBeforeSend { _ in nil }
        config.setBeforeSend { event in
            if event.event.caseInsensitiveCompare("Deep Link Opened") == .orderedSame {
                return nil
            }
            event.properties = sanitizedProperties(event.properties)
            return event
        }
        // Upload queued events quickly after launch: a crash queued by the previous
        // run must ship before the user navigates back into whatever crashed
        // (the default 30s starved uploads during crash-loops).
        config.flushIntervalSeconds = 10
        PostHogSDK.shared.setup(config)
        PostHogSDK.shared.register([geoIpDisableProperty: true])
        // Telemetry v2: the install's support code rides on every event. The shared Kotlin generates and stores it
        // (UserDefaults); registering what is already stored here covers events before Kotlin finishes starting.
        // Super properties persist across launches and are cleared by reset(), so Kotlin re-registers on every start.
        if let code = UserDefaults.standard.string(forKey: supportCodeKey) {
            PostHogSDK.shared.register(["support_code": code])
        }
        if crashReportsEnabled() {
            PostHogSDK.shared.optIn()
        } else {
            PostHogSDK.shared.optOut()
        }
        ConsentObserver.shared.start()
    }

    /// Follows the diagnostics switch while the app runs.
    private final class ConsentObserver {
        static let shared = ConsentObserver()
        private var observer: NSObjectProtocol?

        func start() {
            guard observer == nil else { return }
            observer = NotificationCenter.default.addObserver(
                forName: UserDefaults.didChangeNotification,
                object: nil,
                queue: .main
            ) { _ in
                if TuvoraTelemetry.crashReportsEnabled() {
                    PostHogSDK.shared.optIn()
                } else {
                    PostHogSDK.shared.optOut()
                }
            }
        }
    }
}
