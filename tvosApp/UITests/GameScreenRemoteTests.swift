import XCTest

/// Game screen redesign (2026-11): the focus walk and Menu, driven by the Siri Remote on canned DEBUG data
/// (`-gameDemo football`, see TvGameScreenDemo; no live game needed). Opens the first NCAA card in the Sports hub
/// (any hub game opens the screen; the demo replaces its data).
final class GameScreenRemoteTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        // The demo kind file (debug switch) wins over the launch argument, so set it explicitly.
        try? "football".write(toFile: "/tmp/tuvora-game-demo.txt", atomically: true, encoding: .utf8)
        app = XCUIApplication()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "sports", "-gameDemo", "football"]
        app.launch()
    }

    /// The demo switch file outlives the test otherwise, and every later run (or manual pass) on this Mac then shows the
    /// canned football game instead of the real one.
    override func tearDown() {
        try? FileManager.default.removeItem(atPath: "/tmp/tuvora-game-demo.txt")
        super.tearDown()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedLabel() -> String { focused.exists ? focused.label : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 1.0) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func waitFocus(_ timeout: TimeInterval = 20, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedLabel()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }
    /// Any element whose label contains [text] (SwiftUI texts are not always staticTexts).
    private func has(_ text: String, timeout: TimeInterval = 0) -> Bool {
        let q = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        return timeout > 0 ? q.waitForExistence(timeout: timeout) : q.exists
    }
    private func shot(_ name: String) {
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/game-\(name).png"))
    }

    /// Sports hub -> American Football -> NCAA Football -> first game card -> game screen.
    private func openDemoGame() {
        XCTAssertTrue(waitFocus(30) { $0 != "<none>" }, "Sports: nothing focused after launch")
        // A hub with followed leagues opens on its Live & Upcoming row; walk down to the sport cards first.
        for _ in 0..<4 where !isSportCard(focusedLabel()) { press(.down, settle: 0.6) }
        for _ in 0..<4 where !focusedLabel().hasPrefix("American Football") { press(.right, settle: 0.6) }
        press(.select, settle: 2)
        press(.down, settle: 0.6); press(.select, settle: 6)           // NCAA Football league page
        press(.down, settle: 1)                                         // first match card
        press(.select, settle: 3)
    }

    private func isSportCard(_ label: String) -> Bool {
        ["Football", "Basketball", "American Football", "Baseball", "Hockey"].contains { label.hasPrefix($0 + ",") }
    }

    /// The Play slot reads "Play" with a matched channel, "Find channels" / "Finding channels…" without one (the sim's channel set varies).
    private func isPlayRow(_ label: String) -> Bool { label.hasPrefix("Play") || label.hasPrefix("Find") }

    func testPlayFirstChipsSelectOnFocusDownReturnsAndMenuLeaves() {
        openDemoGame()
        XCTAssertTrue(has("Market-implied", timeout: 20), "game screen did not open on the demo data")
        // Play is the first action.
        XCTAssertTrue(waitFocus(10) { self.isPlayRow($0) }, "Play is not focused on open: \(focusedLabel())")
        shot("1-open")
        // Up reaches the selected chip, Right walks the chips and each shows its panel without OK.
        press(.up)
        XCTAssertEqual(focusedLabel(), "Plays", "Up from Play must reach the selected chip")
        press(.right)
        XCTAssertEqual(focusedLabel(), "Stats")
        XCTAssertTrue(has("Total Yards", timeout: 3), "focusing the Stats chip must show its panel")
        shot("2-stats")
        // Down returns to Play.
        press(.down)
        XCTAssertTrue(isPlayRow(focusedLabel()), "Down from a chip must return to Play: \(focusedLabel())")
        // Right from the Play row: More channels, then the chips.
        press(.right)
        if focusedLabel() == "More channels" { press(.right) }   // only present when several channels matched
        XCTAssertTrue(["Plays", "Stats", "Leaders", "Standings"].contains(focusedLabel()), "Right from More channels must reach a chip: \(focusedLabel())")
        // Menu leaves the screen.
        press(.menu, settle: 2)
        XCTAssertFalse(has("More channels"), "Menu did not leave the game screen")
        shot("3-after-menu")
    }

    /// The demo plays three snapshots nine seconds apart (score 14 -> 14 -> 21, a turnover on the last): the screen ends on the last one
    /// and keeps focus on Play the whole time (an update never moves focus). Frames of the transitions are recorded by the caller.
    func testDemoSnapshotsAnimateAndFocusSurvivesRefresh() {
        openDemoGame()
        XCTAssertTrue(has("Market-implied", timeout: 20), "game screen did not open on the demo data")
        XCTAssertTrue(waitFocus(10) { self.isPlayRow($0) })
        Thread.sleep(forTimeInterval: 26)   // both updates land
        XCTAssertTrue(isPlayRow(focusedLabel()), "focus moved on a refresh: \(focusedLabel())")
        XCTAssertTrue(has("WSH ball"), "the turnover on the last snapshot flips the ball label")
        shot("4-after-updates")
    }

    /// Hide scores: "Show this game's score" sits above the Play row in the left column, but Up from Play used to jump
    /// straight to the section chips on the right, so the remote could never reach it. (`-gameDemoHidden` shows the
    /// canned game as Hide scores does; the reveal itself is covered by the shared TvScoreSpoiler tests.)
    func testRevealChipIsReachableWithScoresHidden() {
        app.terminate()
        app.launchArguments += ["-gameDemoHidden"]
        app.launch()
        openDemoGame()
        XCTAssertTrue(has("Show this game's score", timeout: 20), "the reveal control is not shown with scores hidden")
        XCTAssertTrue(waitFocus(10) { self.isPlayRow($0) }, "Play is not focused on open: \(focusedLabel())")
        press(.up)
        XCTAssertEqual(focusedLabel(), "Show this game's score", "Up from Play must reach the reveal control")
        shot("reveal-focused")
        press(.right)
        XCTAssertEqual(focusedLabel(), "Plays", "Right from the reveal control reaches the selected chip")
        press(.left); press(.down)
        XCTAssertTrue(isPlayRow(focusedLabel()), "Down from the reveal control returns to Play: \(focusedLabel())")
    }
}
