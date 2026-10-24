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
        let collectionName = "Parity Collection " + UUID().uuidString.prefix(6)
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "collections"])
        choose(app.buttons["collections.create"])
        let title = app.textFields["collections.title"]
        choose(title); app.typeText(collectionName); remote.press(.menu)
        choose(app.buttons["collections.addFolder"])
        choose(app.buttons["collections.saveFolder"])
        choose(app.buttons["collections.save"])
        let collection = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'collections.edit.' AND label CONTAINS %@", collectionName)).firstMatch
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
    private func replaceText(_ field: XCUIElement, with text: String) {
        choose(field)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 8))
        app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 40) + text)
        remote.press(.menu)
    }

    func testCustomColorValidatesAndPersistsAfterRelaunch() {
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "customTheme"])
        let field = app.textFields["theme.hex"]
        replaceText(field, with: "oops")
        choose(app.buttons["theme.apply"])
        XCTAssertTrue(app.staticTexts["theme.error"].waitForExistence(timeout: 5))
        XCTAssertTrue(field.exists)
        replaceText(field, with: "#1E88E5")
        choose(app.buttons["theme.apply"])
        XCTAssertFalse(field.exists)
        app.terminate()
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "customTheme"])
        XCTAssertTrue(field.waitForExistence(timeout: 20))
        XCTAssertEqual(field.value as? String, "#1E88E5")
        snapshot("custom-color-persisted")
        remote.press(.menu)
    }

    func testTitlePreferencesPersistAndHideEpisodeSpoilers() {
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "titlePresentation"])
        let spoiler = app.buttons["titleSettings.spoilers"]
        XCTAssertTrue(spoiler.waitForExistence(timeout: 20))
        if spoiler.value as? String != "On" { choose(spoiler) }
        let ratings = app.buttons["titleSettings.ratings"]
        if ratings.value as? String != "Off" { choose(ratings) }
        let layout = app.buttons["titleSettings.episodeLayout"]
        if !layout.label.contains("List") { choose(layout) }
        app.terminate()
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "titlePresentation"])
        XCTAssertTrue(spoiler.waitForExistence(timeout: 20))
        XCTAssertEqual(spoiler.value as? String, "On")
        XCTAssertTrue(layout.label.contains("List"))
        XCTAssertEqual(ratings.value as? String, "Off")
        app.terminate()
        launch(["-smokeDetails", "series:parity-sample", "-smokePresentationTitle"])
        XCTAssertTrue(app.staticTexts["Presentation preview"].waitForExistence(timeout: 20))
        let episode = app.buttons["episode.parity-episode-1"]
        for _ in 0..<8 {
            if episode.exists { break }
            remote.press(.down)
        }
        focus(episode)
        XCTAssertTrue(episode.label.contains("Unwatched episode"))
        XCTAssertFalse(episode.label.contains("hidden reveal"))
        XCTAssertFalse(episode.label.contains("spoiler description"))
        XCTAssertFalse(app.staticTexts["8.2"].exists)
        snapshot("episode-spoilers-hidden")
    }

    func testDiscoveryFiltersValidateAndSaveWithCollection() {
        let collectionName = "Discovery Parity " + UUID().uuidString.prefix(6)
        launch(["-smokeTab", "settings", "-smokeSettingsDialog", "collections"])
        choose(app.buttons["collections.create"])
        replaceText(app.textFields["collections.title"], with: collectionName)
        choose(app.buttons["collections.addFolder"])
        choose(app.buttons["collections.addTmdb"])
        choose(app.buttons["collectionSource.mode.discover"])
        choose(app.buttons["collectionSource.filters"])
        choose(app.buttons["filters.year"])
        replaceText(app.textFields["filters.value"], with: "wrong")
        choose(app.buttons["filters.save"])
        XCTAssertTrue(app.staticTexts["filters.error"].waitForExistence(timeout: 5))
        replaceText(app.textFields["filters.value"], with: "2024")
        choose(app.buttons["filters.save"])
        XCTAssertTrue(app.buttons["filters.year"].waitForExistence(timeout: 5))
        remote.press(.menu)
        choose(app.buttons["collectionSource.filters"])
        XCTAssertTrue(app.buttons["filters.year"].label.contains("Any"), "Back should discard the filter draft")
        choose(app.buttons["filters.year"])
        replaceText(app.textFields["filters.value"], with: "2024")
        choose(app.buttons["filters.save"])
        choose(app.buttons["filters.apply"])
        choose(app.buttons["collectionSource.add"])
        let source = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Release year: 2024'")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        choose(app.buttons["collections.saveFolder"])
        choose(app.buttons["collections.save"])
        let collection = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'collections.edit.' AND label CONTAINS %@", collectionName)).firstMatch
        choose(collection)
        choose(app.buttons.matching(NSPredicate(format: "label CONTAINS 'New folder' AND label CONTAINS '1 sources'")).firstMatch)
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        snapshot("discovery-filter-saved")
    }

}
