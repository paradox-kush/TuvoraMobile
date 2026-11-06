import XCTest

/// The Calendar reminder lives in the "+" button's long-press menu on a show page (the standalone bell was removed).
/// Tooling cannot hold the Siri Remote's Select button, so this drives it with `XCUIRemote.press(.select, forDuration:)`.
/// Leaves no state: the reminder is switched back off at the end (and first, if a previous run left it on).
final class CalendarReminderMenuTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private static let entry = "Remind me of new episodes"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }
    private func snapshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
    private func dumpTree(_ name: String) {
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = name; tree.lifetime = .keepAlways; add(tree)
        print("TREE[\(name)]\n\(app.debugDescription)")
    }
    /// The menu entry, whatever element type tvOS exposes it as.
    private var menuEntry: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR identifier CONTAINS %@", Self.entry, Self.entry)).firstMatch
    }
    private func openMenu() {
        remote.press(.select, forDuration: 1.0)
        if !menuEntry.waitForExistence(timeout: 8) {
            snapshot("menu-missing"); dumpTree("menu-missing")
            XCTFail("long-press on + did not show '\(Self.entry)'; focused=\(focused.identifier)/\(focused.label)")
        }
    }
    /// Whether the entry currently shows the on-state (checkmark): read from every property the tree could carry it in.
    private func entryIsOn() -> Bool {
        let e = menuEntry
        let blob = "\(e.debugDescription) value=\(String(describing: e.value)) sel=\(e.isSelected) label=\(e.label)"
        print("ENTRY: \(blob)")
        let hasCheckImage = app.images.matching(NSPredicate(format: "label CONTAINS[c] 'checkmark' OR identifier CONTAINS[c] 'checkmark'")).count > 0
        return e.isSelected || hasCheckImage || blob.localizedCaseInsensitiveContains("checkmark")
            || (e.value as? String).map { ["1", "on", "true", "selected"].contains($0.lowercased()) } == true
    }

    func testLongPressPlusShowsCalendarReminderEntryAndToggles() {
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeDetails", "series:tt2261391"]
        app.launch()

        let plus = app.buttons["title.save"]
        XCTAssertTrue(plus.waitForExistence(timeout: 90), "show page never loaded the + button")
        Thread.sleep(forTimeInterval: 4)                       // metadata loaded; canBell needs the loaded title

        // Play has default focus; the + button is its right neighbour.
        for _ in 0..<4 where !plus.hasFocus { remote.press(.right); Thread.sleep(forTimeInterval: 0.8) }
        XCTAssertTrue(plus.hasFocus, "focus never reached +; focused=\(focused.identifier)/\(focused.label)")

        // 1. Long-press opens the menu with the reminder entry.
        openMenu()
        snapshot("menu-open"); dumpTree("menu-open-initial")
        // Normalise: if a previous run left it ON, turn it off first so the test starts from OFF.
        if entryIsOn() {
            remote.press(.select); Thread.sleep(forTimeInterval: 1.5)
            XCTAssertFalse(menuEntry.exists, "menu should close after choosing the entry")
            XCTAssertTrue(plus.hasFocus, "focus should return to + after the menu closes")
            openMenu()
        }
        XCTAssertFalse(entryIsOn(), "entry should start OFF")

        // 2. Choose it: reminder on, menu closes, focus returns to +.
        remote.press(.select); Thread.sleep(forTimeInterval: 1.5)
        XCTAssertFalse(menuEntry.exists, "menu should close after choosing the entry")
        XCTAssertTrue(plus.hasFocus, "focus should return to + after the menu closes")

        // 3. Long-press again: the entry now shows the on-state.
        openMenu()
        snapshot("menu-on"); dumpTree("menu-open-after-on")
        XCTAssertTrue(entryIsOn(), "entry should show the checkmark after being turned on")

        // 4. Clean up: toggle OFF and verify.
        remote.press(.select); Thread.sleep(forTimeInterval: 1.5)
        openMenu()
        XCTAssertFalse(entryIsOn(), "entry should be OFF again after the second toggle")
        remote.press(.menu); Thread.sleep(forTimeInterval: 1)  // dismiss the menu without choosing
        snapshot("final")
    }
}
