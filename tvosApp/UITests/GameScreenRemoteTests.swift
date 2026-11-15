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
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "sports", "-gameDemo", "football"]
        app.launch()
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
        press(.right, settle: 0.6); press(.right, settle: 0.6)          // Football, Basketball, American Football
        press(.select, settle: 2)
        press(.down, settle: 0.6); press(.select, settle: 6)           // NCAA Football league page
        press(.down, settle: 1)                                         // first match card
        press(.select, settle: 3)
    }

    func testPlayFirstChipsSelectOnFocusDownReturnsAndMenuLeaves() {
        openDemoGame()
        XCTAssertTrue(has("Market-implied", timeout: 20), "game screen did not open on the demo data")
        // Play is the first action.
        XCTAssertTrue(waitFocus(10) { $0.hasPrefix("Play") }, "Play is not focused on open: \(focusedLabel())")
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
        XCTAssertTrue(focusedLabel().hasPrefix("Play"), "Down from a chip must return to Play: \(focusedLabel())")
        // Right from the Play row: More channels, then the chips.
        press(.right)
        XCTAssertEqual(focusedLabel(), "More channels")
        press(.right)
        XCTAssertTrue(["Plays", "Stats", "Leaders", "Standings"].contains(focusedLabel()), "Right from More channels must reach a chip: \(focusedLabel())")
        // Menu leaves the screen.
        press(.menu, settle: 2)
        XCTAssertFalse(has("More channels"), "Menu did not leave the game screen")
        shot("3-after-menu")
    }
}
