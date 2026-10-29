import XCTest
@testable import TuvoraTV

final class ScreenStatePoliciesTests: XCTestCase {
    private func folder(hasFolder: Bool = true, isLoading: Bool = false, tabs: Int = 0, tabLoading: Bool = false,
                        items: Bool = false, error: Bool = false) -> FolderScreenPhase {
        FolderScreenPhase.resolve(hasFolder: hasFolder, isLoading: isLoading, tabCount: tabs, anyTabLoading: tabLoading, anyItems: items, anyTabError: error)
    }

    func testFolderWithNoSourcesIsEmptyNotBlank() {
        XCTAssertEqual(folder(tabs: 0), .empty)
        XCTAssertEqual(folder(tabs: 1), .empty)
    }
    func testFolderStillLoadingStaysContent() {
        XCTAssertEqual(folder(isLoading: true), .content)
        XCTAssertEqual(folder(tabs: 1, tabLoading: true), .content)
    }
    func testFolderWithItemsOrATabErrorShowsContent() {
        XCTAssertEqual(folder(tabs: 1, items: true), .content)
        XCTAssertEqual(folder(tabs: 1, error: true), .content)
    }
    func testMissingFolderIsLoadingThenNotFound() {
        XCTAssertEqual(folder(hasFolder: false, isLoading: true), .loading)
        XCTAssertEqual(folder(hasFolder: false, isLoading: false), .notFound)
    }

    private func match(_ p: Bool = true, matching: Bool = false, failed: Bool = false, groups: Bool = false) -> MatchSheetPhase {
        MatchSheetPhase.resolve(hasPlaylists: p, matching: matching, failed: failed, hasGroups: groups)
    }
    func testMatchSheetPhases() {
        XCTAssertEqual(match(false), .noPlaylists)
        XCTAssertEqual(match(matching: true), .finding)
        XCTAssertEqual(match(failed: true), .failed)
        XCTAssertEqual(match(), .none)
        XCTAssertEqual(match(matching: true, groups: true), .results)
    }
    func testDeadlineFailsOnlyWhenNothingMatched() {
        XCTAssertTrue(MatchSheetPhase.failsAtDeadline(hasGroups: false))
        XCTAssertFalse(MatchSheetPhase.failsAtDeadline(hasGroups: true))
    }

    func testLoadDeadlineDefaultsToTwentySeconds() {
        XCTAssertEqual(LoadDeadline.seconds(arguments: []), 20)
        XCTAssertEqual(LoadDeadline.seconds(arguments: ["-smokeLoadDeadline"]), 20, "a missing value falls back")
        XCTAssertEqual(LoadDeadline.seconds(arguments: ["-smokeLoadDeadline", "nope"]), 20)
    }
    func testLoadDeadlineSmokeOverrideAppliesInDebugSimulatorOnly() {
        #if DEBUG && targetEnvironment(simulator)
        XCTAssertEqual(LoadDeadline.seconds(arguments: ["-smokeLoadDeadline", "3"]), 3)
        XCTAssertEqual(LoadDeadline.seconds(arguments: ["-smokeLoadDeadline", "0"]), 20, "zero is not a deadline")
        #endif
    }
    func testLoadTimesOutAtTheDeadlineNotBefore() {
        XCTAssertFalse(LoadDeadline.hasTimedOut(elapsed: 19.9, deadline: 20))
        XCTAssertTrue(LoadDeadline.hasTimedOut(elapsed: 20, deadline: 20))
    }
}
