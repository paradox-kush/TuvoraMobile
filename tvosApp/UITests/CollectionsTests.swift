import XCTest

/// Beta report 2026-10-01: a user's collections never appeared on Apple TV Home. With in-memory sample
/// collections (`-smokeCollections`, nothing saved or synced) this walks the remote to a collection
/// folder, opens it, checks its screen, and comes back with Menu to the same folder card.
final class CollectionsTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func waitFocus(_ timeout: TimeInterval = 20, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedId()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }
    private func shot(_ name: String) {
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/collections-\(name).png"))
    }

    private func launch(layout: String) {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "home", "-smokeCollections", "-smokeHomeLayout", layout]
        app.launch()
    }

    private func openFolderAndComeBack(layout: String) {
        launch(layout: layout)
        XCTAssertTrue(waitFocus { $0 != "<none>" && !$0.hasPrefix("sidebar.") }, "\(layout): nothing focused on Home")
        // The pinned sample collection sits above the catalog rows: walk DOWN until a folder card has focus.
        var trail: [String] = []
        // Grid lays catalogs out as long poster grids (lazy), so the folder can sit many rows down.
        // Grid's launch focus lands on the first catalog, BELOW a collection pinned at the top: look up
        // first, then down.
        for _ in 0..<4 where !focusedId().hasPrefix("collection.folder.") { press(.up, settle: 1); trail.append(focusedId()) }
        for _ in 0..<45 where !focusedId().hasPrefix("collection.folder.") { press(.down, settle: 1); trail.append(focusedId()) }
        let opener = focusedId()
        shot("\(layout)-home")
        XCTAssertTrue(opener.hasPrefix("collection.folder."), "\(layout): no collection folder reachable with DOWN; trail \(trail)")
        press(.select, settle: 3)
        let title = app.staticTexts["folder.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "\(layout): selecting \(opener) did not open the folder screen")
        XCTAssertTrue(waitFocus(8) { $0 != "<none>" }, "\(layout): folder screen has nothing focused")
        shot("\(layout)-folder")
        press(.menu, settle: 2)
        XCTAssertFalse(title.exists, "\(layout): Menu did not close the folder screen")
        XCTAssertTrue(waitFocus(5) { $0 == opener }, "\(layout): focus did not return to \(opener) (now \(focusedId()))")
    }

    func testModernOpensFolderAndMenuReturns() { openFolderAndComeBack(layout: "modern") }
    func testClassicOpensFolderAndMenuReturns() { openFolderAndComeBack(layout: "classic") }
    func testGridOpensFolderAndMenuReturns() { openFolderAndComeBack(layout: "grid") }
}
