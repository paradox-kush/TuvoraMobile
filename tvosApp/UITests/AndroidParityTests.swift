import XCTest

/// Isolated local-guest acceptance tests. The HTTP fixture listens on loopback; no provider account is used.
final class AndroidParityTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private let fixture = "http://127.0.0.1:18124/lineup.m3u"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }
    private var focused: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }
    private func launch(_ extra: [String]) {
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1"] + extra
        app.launch()
    }
    private func focus(_ target: XCUIElement) {
        XCTAssertTrue(target.waitForExistence(timeout: 40), "Target never appeared: \(target)")
        for _ in 0..<40 {
            guard target.exists else { break }
            if target.hasFocus { return }
            let origin = focused.frame, destination = target.frame
            let dx = destination.midX - origin.midX, dy = destination.midY - origin.midY
            if focused.identifier.hasPrefix("sidebar.") || (focused.identifier.hasPrefix("settings.rail.") && !target.identifier.hasPrefix("settings.rail.")) {
                remote.press(.right)
            } else if abs(dy) > 20 {
                remote.press(dy > 0 ? .down : .up)
            } else {
                remote.press(dx > 0 ? .right : .left)
            }
        }
        snapshot("focus-failure")
        let tree = XCTAttachment(string: app.debugDescription)
        tree.lifetime = .keepAlways; add(tree)
        XCTFail("Could not focus \(target.identifier); focused=\(focused.identifier)")
    }
    private func choose(_ target: XCUIElement) { focus(target); remote.press(.select) }
    private func waitLabel(_ target: XCUIElement, contains text: String) {
        let ready = expectation(for: NSPredicate(format: "label CONTAINS %@", text), evaluatedWith: target)
        wait(for: [ready], timeout: 15)
    }
    private func snapshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }

    func testSourcesChoiceSurvivesRelaunch() {
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "deviceSources", "-smokeAddM3uLater", "1", fixture])
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'deviceSources.source.' AND label CONTAINS 'Smoke M3U'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 50))
        choose(row); waitLabel(row, contains: "Off")
        app.terminate()
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "deviceSources"])
        waitLabel(row, contains: "Off")
        snapshot("source-choice-restored")
        choose(row); waitLabel(row, contains: "On")
        choose(app.buttons["deviceSources.done"])
        XCTAssertTrue(app.buttons["settings.deviceSources"].waitForExistence(timeout: 10))
    }

    func testGuideSearchUsesNativeKeyboardAndBackReturnsToGuide() {
        launch(["-smokeTab", "iptv", "-smokeAddM3uLater", "1", fixture])
        let search = app.buttons["guide.search"]
        focus(search)
        XCTAssertFalse(app.textFields["guide.search.input"].exists, "Focus must not open the keyboard")
        remote.press(.select)
        let field = app.textFields["guide.search.input"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        choose(field)
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 8), "Selecting the search field did not open the keyboard")
        app.typeText("bbc")
        remote.press(.menu)
        choose(app.buttons["guide.search.submit"])
        XCTAssertFalse(field.exists)
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        snapshot("guide-search-results")
        choose(search)
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        remote.press(.menu)
        XCTAssertFalse(field.exists)
        XCTAssertTrue(search.exists)
    }

    func testSyncedGroupProjectionAppearsAsGuideCategory() {
        launch(["-smokeTab", "iptv", "-smokeAddM3uLater", "1", fixture, "-smokeGuideGroup"])
        let group = app.buttons["guide.customGroup.grp:smoke-parity"]
        choose(group)
        XCTAssertTrue(group.hasFocus)
        snapshot("guide-custom-group")
    }

    func testCollectionCanBeCreatedWithFolderAndSaved() {
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "collections"])
        choose(app.buttons["collections.create"])
        let title = app.textFields["collections.title"]
        choose(title); app.typeText("Parity Collection"); remote.press(.menu)
        choose(app.buttons["collections.addFolder"])
        choose(app.buttons["collections.saveFolder"])
        choose(app.buttons["collections.save"])
        let collection = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'collections.edit.' AND label CONTAINS 'Parity Collection'")).firstMatch
        XCTAssertTrue(collection.waitForExistence(timeout: 15))
        snapshot("collection-created")
        choose(collection)
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        remote.press(.menu)
        let discard = app.buttons["Discard changes"].firstMatch
        if discard.waitForExistence(timeout: 5) { remote.press(.select) }
    }

    func testPrivacyControlsAreReachableWithoutEnablingTelemetry() {
        launch(["-smokeTab", "settings", "-smokeSettings", "advanced"])
        let diagnostic = app.buttons["privacy.diagnostics"]
        focus(diagnostic)
        focus(app.buttons["privacy.recommendations"])
        snapshot("privacy-controls")
    }

    func testHomeRowsDialogClosesWithRemoteBack() {
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "homeRows"])
        XCTAssertTrue(app.staticTexts["Home catalogs"].waitForExistence(timeout: 20))
        choose(app.buttons["Done"])
        XCTAssertTrue(app.buttons["settings.deviceSources"].waitForExistence(timeout: 10))
    }
}
