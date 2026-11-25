import Foundation

// Unclean-exit detection for the Apple apps (iPhone, iPad, Apple TV). This file is Foundation-only so it tests without
// UIKit; the app compiles it into both targets (tvosApp/project.yml lists it next to TuvoraTelemetry.swift).
//
// How it works: at launch the app writes a marker saying "foreground". When it enters the background (or is told it is
// terminating) the marker is changed. Next launch: if the marker still says "foreground", the previous run ended while
// it was on screen and nobody told us: a crash, a watchdog or foreground memory kill, a debugger stop, or a force-quit
// that happened on screen. See `LaunchVerdict` for what each outcome means and what it cannot tell apart.

/// What identifies one boot of one build. A different value at launch means the previous run did not end in the way
/// the marker alone would suggest (update, OS upgrade, restart), so it is never reported.
struct RunIdentity: Codable, Equatable {
    var appBuild: String
    var osVersion: String
    /// Seconds since 1970 of the device's last boot (`kern.boottime`).
    var bootTime: Int64
}

enum RunPhase: String, Codable {
    case foreground
    case background
    case terminated
}

/// The persisted record of the run in progress. Local only; nothing in it leaves the device except through
/// `AppleTelemetryBridge.recordUncleanExit`, which takes closed values and applies the user's reports switch.
struct RunMarker: Codable, Equatable {
    var identity: RunIdentity
    var phase: RunPhase
    var wasPlaying: Bool
    /// Contract values (`live`, `vod`, `preview`, `multiview_tile`, `other`) or "" when nothing played.
    var playbackKind: String
    /// `libmpv`, `avplayer`, `media3`, `other` or "".
    var playbackEngine: String
    var memWarnings: Int
}

enum LaunchVerdict: Equatable {
    /// No marker yet (new install, or the marker could not be read). Never reported.
    case firstLaunch
    /// The previous run left through the background or a terminate callback. A process killed after that (suspended
    /// and then ended by the OS, or swiped away in the app switcher) lands here too, on purpose: it is not a crash.
    case cleanExit
    /// A different build is running: the update or reinstall itself ends the old process. Never reported.
    case appUpdated
    /// A different OS version: the upgrade restarted the device. Never reported.
    case osUpdated
    /// The device booted since: power loss, user restart or a kernel panic. The run's end is unknowable. Never reported.
    case deviceRestarted
    /// Would be reported, but the user's "Crash & Usage Reports" switch is off: nothing is sent (the marker is still
    /// reset so a later switch-on does not report an old run).
    case reportsOff
    /// The previous run ended in the foreground with no word. The fields are the last state the marker recorded.
    case uncleanSuspected(wasPlaying: Bool, kind: String, engine: String, memWarnings: Int)
}

/// The state machine. Pure: every transition takes a marker and returns the next one.
enum UncleanExitMachine {
    /// `kern.boottime` can move by a few seconds when the clock is corrected; a real restart moves it by far more.
    static let bootTimeToleranceSeconds: Int64 = 60

    static func evaluate(previous: RunMarker?, current: RunIdentity, reportsEnabled: Bool) -> LaunchVerdict {
        guard let previous else { return .firstLaunch }
        if previous.identity.appBuild != current.appBuild { return .appUpdated }
        if previous.identity.osVersion != current.osVersion { return .osUpdated }
        if abs(previous.identity.bootTime - current.bootTime) > bootTimeToleranceSeconds { return .deviceRestarted }
        switch previous.phase {
        case .background, .terminated:
            return .cleanExit
        case .foreground:
            guard reportsEnabled else { return .reportsOff }
            return .uncleanSuspected(
                wasPlaying: previous.wasPlaying,
                kind: previous.wasPlaying ? previous.playbackKind : "",
                engine: previous.wasPlaying ? previous.playbackEngine : "",
                memWarnings: previous.memWarnings
            )
        }
    }

    /// A process that the system started in the background (no UI) is not "on screen": a kill there is not a crash.
    static func launched(identity: RunIdentity, inBackground: Bool) -> RunMarker {
        RunMarker(
            identity: identity,
            phase: inBackground ? .background : .foreground,
            wasPlaying: false, playbackKind: "", playbackEngine: "", memWarnings: 0
        )
    }

    static func entered(_ phase: RunPhase, _ marker: RunMarker) -> RunMarker {
        var next = marker
        next.phase = phase
        return next
    }

    /// Empty [kind] means playback stopped.
    static func playbackChanged(kind: String, engine: String, _ marker: RunMarker) -> RunMarker {
        var next = marker
        next.wasPlaying = !kind.isEmpty
        next.playbackKind = kind
        next.playbackEngine = kind.isEmpty ? "" : engine
        return next
    }

    static func memoryWarned(_ marker: RunMarker) -> RunMarker {
        var next = marker
        next.memWarnings = min(next.memWarnings + 1, 1000)
        return next
    }
}

protocol RunMarkerStore: AnyObject {
    func load() -> RunMarker?
    func save(_ marker: RunMarker)
}

/// `UserDefaults` keeps the marker: it works unchanged on iOS and on tvOS (whose Documents folder is not writable).
/// A value the decoder cannot read counts as "no marker": the detector can then under-report, never over-report.
final class UserDefaultsRunMarkerStore: RunMarkerStore {
    static let key = "tuvora.run_marker.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func load() -> RunMarker? {
        guard let data = defaults.data(forKey: Self.key) else { return nil }
        return try? JSONDecoder().decode(RunMarker.self, from: data)
    }

    func save(_ marker: RunMarker) {
        guard let data = try? JSONEncoder().encode(marker) else { return }
        defaults.set(data, forKey: Self.key)
    }
}

enum RunIdentityProbe {
    static func current(bundle: Bundle = .main, processInfo: ProcessInfo = .processInfo) -> RunIdentity {
        let short = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        return RunIdentity(appBuild: "\(short)(\(build))", osVersion: processInfo.operatingSystemVersionString, bootTime: bootTimeSeconds())
    }

    static func bootTimeSeconds() -> Int64 {
        var tv = timeval()
        var size = MemoryLayout<timeval>.stride
        guard sysctlbyname("kern.boottime", &tv, &size, nil, 0) == 0 else { return 0 }
        return Int64(tv.tv_sec)
    }
}
