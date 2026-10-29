import Foundation

/// What a collection folder page shows. Pure, so the decision tests without the shared layer: the page
/// used to render only the folder title when a folder held no sources, which looked like a failed load.
enum FolderScreenPhase: Equatable {
    case loading, notFound, empty, content

    static func resolve(hasFolder: Bool, isLoading: Bool, tabCount: Int, anyTabLoading: Bool, anyItems: Bool, anyTabError: Bool) -> FolderScreenPhase {
        if !hasFolder { return isLoading ? .loading : .notFound }
        if anyItems || isLoading || anyTabLoading || anyTabError { return .content }
        return .empty
    }
}

/// What the Sports "channels for this match" sheet shows, and how long it waits for the providers before
/// it stops waiting and offers Retry (the matching call can outlive its screen).
enum MatchSheetPhase: Equatable {
    case noPlaylists, finding, failed, none, results

    static let deadlineSeconds: UInt64 = 20

    static func resolve(hasPlaylists: Bool, matching: Bool, failed: Bool, hasGroups: Bool) -> MatchSheetPhase {
        if !hasPlaylists { return .noPlaylists }
        if hasGroups { return .results }
        if matching { return .finding }
        return failed ? .failed : .none
    }

    /// At the deadline a sheet that still has nothing has failed; one with partial matches keeps them.
    static func failsAtDeadline(hasGroups: Bool) -> Bool { !hasGroups }
}

/// How long a screen waits on a load before it stops showing a spinner and offers Retry. A spinner that
/// nothing ever ends is the "spins forever" bug; the work may keep running, the screen stops waiting.
enum LoadDeadline {
    static let defaultSeconds: Double = 20

    /// `-smokeLoadDeadline <seconds>` shortens the wait for simulator tests (debug builds only).
    static func seconds(arguments: [String]) -> Double {
        #if DEBUG && targetEnvironment(simulator)
        if let i = arguments.firstIndex(of: "-smokeLoadDeadline"), i + 1 < arguments.count,
           let value = Double(arguments[i + 1]), value > 0 { return value }
        #endif
        return defaultSeconds
    }

    static func hasTimedOut(elapsed: Double, deadline: Double) -> Bool { elapsed >= deadline }
}
