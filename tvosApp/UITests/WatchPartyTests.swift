import XCTest

/// Watch party on Apple TV, driven by the Siri Remote (XCUIRemote). Needs the signed-in simulator.
///
/// Which test applies depends on the backend the build points at:
///  - no watch-party RPCs (PostgREST PGRST202): the feature must disappear (PartyAvailabilityPolicy), so
///    Settings shows no Watch party category - nothing dead to press;
///  - RPCs present: the join keypad types a clean six-character code into the boxes and enables Join.
final class WatchPartyTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "party"]
        app.launch()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.6) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    /// A simulator without an account: continue with a local profile (never a real sign-in).
    private func passSignInGate() {
        let skip = app.buttons["Continue without account"]
        guard skip.waitForExistence(timeout: 15) else { return }
        for _ in 0..<4 where !skip.hasFocus { press(.right) }
        if skip.hasFocus { press(.select, settle: 3) }
        let args = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "party"]
        app.terminate(); app.launchArguments = args; app.launch()
    }

    /// The rail item, once the launch-time availability check has answered (it runs on app start).
    private func partyRailAfterProbe() -> Bool {
        passSignInGate()
        XCTAssertTrue(element("settings.rail.account").waitForExistence(timeout: 40), "Settings did not open")
        Thread.sleep(forTimeInterval: 8)
        return element("settings.rail.party").exists
    }

    func testWatchPartyIsHiddenOrWorksNeverDead() {
        guard partyRailAfterProbe() else {
            // Backend without watch-party RPCs: no category, so no entry point that can only fail.
            XCTAssertFalse(element("party.join").exists, "a join button with no backend behind it")
            return
        }
        XCTAssertTrue(element("party.join").waitForExistence(timeout: 10) || element("party.signIn").exists || element("party.activeCode").exists,
                      "the Watch party pane shows neither join, sign-in nor the active party")
    }

    func testKeypadTypesACleanCode() throws {
        guard partyRailAfterProbe() else { throw XCTSkip("this backend has no watch-party RPCs: the category is hidden") }
        guard element("party.key.K").waitForExistence(timeout: 10) else { throw XCTSkip("not on the join pane (signed out or already in a party)") }
        // Into the pane, onto the keypad.
        press(.right, settle: 1.0)
        let end = Date().addingTimeInterval(10)
        while !focusedId().hasPrefix("party.key.") && Date() < end { press(.down) }
        XCTAssertTrue(focusedId().hasPrefix("party.key."), "focus never reached the keypad; on \(focusedId())")
        for _ in 0..<6 { press(.select, settle: 0.4) }
        XCTAssertEqual(element("party.code").label.count, 6, "six presses should fill the six boxes")
        press(.select, settle: 0.4)
        XCTAssertEqual(element("party.code").label.count, 6, "a seventh character is ignored")
    }
}
