#if os(tvOS)
import TuvoraCore
#else
import ComposeApp
#endif
import Foundation
import os

/// QA mirror for the Apple apps: every playback signal and memory sample the shared telemetry produces is ALSO written
/// as one JSON line to the unified log (subsystem `co.tuvora.qa`, category `TuvoraQa`), same shape as the Android
/// logcat mirror. Local only, never the network. Off unless the app is launched with `-TUVORA_QA 1` or the
/// environment variable `TUVORA_QA=1`; nothing is written otherwise.
///
/// Read it from the Mac: `xcrun simctl spawn <udid> log stream --level info --predicate 'subsystem == "co.tuvora.qa"'`
/// Lines are `public`, so they are not redacted in the log.
enum TuvoraQaLog {
    static let subsystem = "co.tuvora.qa"
    static let category = "TuvoraQa"
    static let flag = "TUVORA_QA"

    /// Pure, so it tests: `-TUVORA_QA 1` among the launch arguments, or `TUVORA_QA=1` in the environment.
    static func isEnabled(arguments: [String], environment: [String: String]) -> Bool {
        if environment[flag] == "1" { return true }
        guard let index = arguments.firstIndex(of: "-\(flag)"), index + 1 < arguments.count else { return false }
        return arguments[index + 1] == "1"
    }

    /// The first line, so a reader knows the mirror is alive. `platform` is `ios` or `tvos`.
    static func readyLine(platform: String) -> String { #"{"ev":"qa_ready","platform":"\#(platform)"}"# }

    /// Call BEFORE `IosTelemetry.start()`: the shared host reads whether a sink exists once, when it is created.
    static func installIfRequested(
        arguments: [String] = ProcessInfo.processInfo.arguments,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) {
        guard isEnabled(arguments: arguments, environment: environment) else { return }
        let log = OSLog(subsystem: subsystem, category: category)
        AppleTelemetryBridge.shared.registerQaSink { line in
            os_log("%{public}@", log: log, type: .default, line)
        }
        #if os(tvOS)
        let platform = "tvos"
        #else
        let platform = "ios"
        #endif
        os_log("%{public}@", log: log, type: .default, readyLine(platform: platform))
    }
}
