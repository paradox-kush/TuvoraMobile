import XCTest

/// B193: on a fresh install with no sources Home is "ready" with zero rows. The remote must still work:
/// the empty state offers a focusable "Set up sources" action (focused on arrival), Left/Menu open the
/// sidebar, and the action reaches the setup-code screen. `-smokeEmptyHome` shows Home as a source-less install does, because the shared simulator profile keeps
/// content from earlier runs (an uninstall is the only other clean state). Remote-driven only (XCUIRemote).
final class EmptyHomeFocusTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "home", "-smokeEmptyHome"]
        app.launch()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func waitFocus(_ timeout: TimeInterval = 40, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedId()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }

    /// Something is focused as soon as the empty Home shows, and it is the setup action.
    func testEmptyHomeFocusesSetupAction() {
        let action = app.buttons["home.empty.action"]
        XCTAssertTrue(action.waitForExistence(timeout: 60), "empty Home offers no setup action")
        XCTAssertTrue(waitFocus(10) { $0 == "home.empty.action" }, "the action is not focused; focus \(focusedId())")
    }

    /// Left and Menu from the empty Home reach the sidebar (the remote is not dead), and Settings is reachable.
    func testEmptyHomeRemoteReachesSidebar() {
        XCTAssertTrue(app.buttons["home.empty.action"].waitForExistence(timeout: 60))
        XCTAssertTrue(waitFocus(10) { $0 == "home.empty.action" })
        XCTContext.runActivity(named: "Menu opens the sidebar") { _ in
            remote.press(.menu)
            XCTAssertTrue(waitFocus(10) { $0.hasPrefix("sidebar.") }, "Menu did not reach the sidebar; focus \(focusedId())")
        }
        let settings = app.buttons["sidebar.settings"]
        for _ in 0..<8 where !settings.hasFocus { remote.press(.down); Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertTrue(settings.hasFocus, "Settings unreachable from the sidebar; focus \(focusedId())")
    }

    /// Select on the action opens the existing setup-code screen (Settings > Sources).
    func testSetupActionOpensSetupCode() {
        XCTAssertTrue(app.buttons["home.empty.action"].waitForExistence(timeout: 60))
        XCTAssertTrue(waitFocus(10) { $0 == "home.empty.action" })
        remote.press(.select)
        XCTAssertTrue(waitFocus(20) { $0 == "setup.key.A" }, "the code screen did not open; focus \(focusedId())")
    }

    /// A viewer WHO HAS content must never see the empty state flash and take focus, and Home loading must not open
    /// the drawer on its own. Launches without the empty hook. Whether the simulator profile has content is decided from
    /// real content elements (never from the empty action, which is the very bug): no content -> skip; content and the
    /// action ever appearing -> fail.
    func testHomeWithContentNeverShowsEmptyActionOrOpensDrawer() throws {
        app.terminate()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "home"]
        app.launch()
        XCTAssertTrue(app.buttons["sidebar.home"].waitForExistence(timeout: 60), "Home never appeared")
        let end = Date().addingTimeInterval(6)   // past the empty-state settle delay
        var sawAction = false
        while Date() < end { if app.buttons["home.empty.action"].exists { sawAction = true }; Thread.sleep(forTimeInterval: 0.3) }
        let hasContent = app.buttons.allElementsBoundByIndex.contains {
            !$0.identifier.hasPrefix("sidebar.") && $0.identifier != "home.empty.action"
        }
        if !hasContent { throw XCTSkip("the simulator profile holds no content: nothing to prove here") }
        XCTAssertFalse(sawAction, "Home has content, yet the empty-state action appeared")
        XCTAssertFalse(focusedId().hasPrefix("sidebar."), "Home loading opened the drawer; focus \(focusedId())")
    }
}
