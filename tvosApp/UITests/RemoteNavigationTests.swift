import XCTest

/// Drives the real Siri Remote through the focus engine (XCUIRemote), so focus bugs that unit tests
/// can't see get a regression test. Needs the simulator's signed-in test account (IPTV playlists).
/// Each test is one tester report from 2026-09-29.
final class RemoteNavigationTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(_ args: [String]) {
        app.launchArguments = ["-smokePickProfile", "1"] + args
        app.launch()
    }

    private func launchGuest(_ args: [String]) { launch(["-smokeLocalGuest"] + args) }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }

    private func focusedId() -> String { focused.exists ? focused.identifier : "<none>" }

    private func press(_ button: XCUIRemote.Button, settle: TimeInterval = 0.8) {
        remote.press(button)
        Thread.sleep(forTimeInterval: settle)
    }

    private func waitForFocus(where match: (String) -> Bool, timeout: TimeInterval = 30) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if match(focusedId()) { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }

    // MARK: - IPTV hub

    /// "In the IPTV tab, moving Series -> Movies -> Live TV goes to the nav bar."
    func testHubChipsMoveLeftWithoutOpeningSidebar() {
        launch(["-smokeTab", "iptv"])
        XCTAssertTrue(app.buttons["hubchip.Live TV"].waitForExistence(timeout: 30), "IPTV hub header never appeared")
        Thread.sleep(forTimeInterval: 3)

        for _ in 0..<8 where !focusedId().hasPrefix("hubchip.") { press(.up) }
        XCTAssertTrue(focusedId().hasPrefix("hubchip."), "could not reach the hub chips; focus on \(focusedId())")

        for _ in 0..<3 where focusedId() != "hubchip.Series" { press(.right) }
        XCTAssertEqual(focusedId(), "hubchip.Series")
        press(.select, settle: 3)
        XCTAssertEqual(focusedId(), "hubchip.Series", "focus left the chip after opening Series")

        press(.left)
        XCTAssertEqual(focusedId(), "hubchip.Movies", "LEFT from Series went to \(focusedId())")
        press(.select, settle: 3)
        XCTAssertEqual(focusedId(), "hubchip.Movies", "focus left the chip after opening Movies")
        press(.left)
        XCTAssertEqual(focusedId(), "hubchip.Live TV", "LEFT from Movies went to \(focusedId())")
        press(.select, settle: 3)
        XCTAssertFalse(focusedId().hasPrefix("sidebar."), "opening Live TV put focus in the sidebar")

        // At the true left edge LEFT still opens the drawer (NuvioTV's behaviour).
        for _ in 0..<4 where focusedId() != "hubchip.Live TV" { press(.up) }
        if focusedId() == "hubchip.Live TV" {
            press(.left)
            XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT at the left edge should open the sidebar, got \(focusedId())")
        }
    }

    // MARK: - Sidebar

    /// "When scrolling down on the nav bar from Settings it goes out of the nav bar."
    func testSidebarKeepsFocusAtBothEnds() {
        launch(["-smokeTab", "home"])
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" }), "nothing focused on Home")
        press(.menu)   // Menu in content opens the drawer
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("sidebar.") }, timeout: 5), "Menu did not open the sidebar; focus \(focusedId())")

        var trail: [String] = []
        for _ in 0..<8 { press(.down, settle: 0.5); trail.append(focusedId()) }
        XCTAssertEqual(focusedId(), "sidebar.settings", "DOWN past Settings left the nav bar; trail \(trail)")
        press(.down)
        XCTAssertEqual(focusedId(), "sidebar.settings", "a second DOWN past Settings left the nav bar")

        // The top is the profile item when the account has 2+ profiles (the switcher), else Home.
        for _ in 0..<9 { press(.up, settle: 0.5) }
        let top = app.buttons["sidebar.profile"].exists ? "sidebar.profile" : "sidebar.home"
        XCTAssertEqual(focusedId(), top, "UP past the top of the drawer left the nav bar")
    }

    // MARK: - Home

    /// "On the big poster at app start, going to the nav bar needs a scroll down to the list first."
    func testLeftFromHomeAtLaunchOpensSidebar() {
        launch(["-smokeTab", "home"])
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" }), "nothing is focused on Home after launch")
        for _ in 0..<6 where !focusedId().hasPrefix("sidebar.") { press(.left) }
        XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT from Home's first row never reached the sidebar; focus \(focusedId())")
    }

    // MARK: - Settings

    /// "In Settings, from a sub-setting like Integrations, LEFT opens the nav bar."
    func testLeftFromSettingsDetailReturnsToCategoryRail() {
        launch(["-smokeTab", "settings", "-smokeSettings", "integrations"])
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("settings.rail.") }), "settings rail never took focus; \(focusedId())")
        press(.right)
        let inDetail = focusedId()
        XCTAssertFalse(inDetail.hasPrefix("settings.rail.") || inDetail.hasPrefix("sidebar."), "RIGHT did not enter the detail pane; \(inDetail)")
        press(.left)
        XCTAssertTrue(focusedId().hasPrefix("settings.rail."), "LEFT from the detail pane went to \(focusedId()), not the category rail")
        press(.left)
        XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT from the category rail should open the sidebar; \(focusedId())")
    }

    /// Navigation audit 2026-10-28 (finding 1): LEFT from a detail row must go back to the SELECTED
    /// category, never to whichever rail row sits level with the row you left - landing there selects it.
    func testLeftFromAccountDetailKeepsAccountSelected() {
        launchGuest(["-smokeTab", "settings"])
        let account = app.buttons["settings.rail.account"]
        XCTAssertTrue(account.waitForExistence(timeout: 30), "settings rail never appeared")
        XCTAssertTrue(waitForFocus(where: { $0 == "settings.rail.account" }), "launch focus is \(focusedId())")
        press(.right)
        XCTAssertFalse(focusedId().hasPrefix("settings.rail."), "RIGHT did not enter the detail pane; \(focusedId())")
        // Rows further down sit level with other rail items: the drift showed there.
        for row in 0..<3 {
            press(.left, settle: 1.2)
            XCTAssertEqual(focusedId(), "settings.rail.account", "row \(row): LEFT went to \(focusedId()), not the selected category")
            press(.right)
            for _ in 0...row where !focusedId().hasPrefix("settings.rail.") { press(.down, settle: 0.5) }
        }
    }

    /// Navigation audit 2026-10-28 (finding 2): LEFT from a rail whose detail has no control opens the
    /// app sidebar on the CURRENT tab (Settings), not on Home.
    func testLeftFromControllessSettingsDetailOpensSidebarOnSettings() throws {
        launchGuest(["-smokeTab", "settings", "-smokeSettings", "party"])
        let party = app.buttons["settings.rail.party"]
        guard party.waitForExistence(timeout: 30) else { throw XCTSkip("Watch party is not available in this build") }
        XCTAssertTrue(waitForFocus(where: { $0 == "settings.rail.party" }), "launch focus is \(focusedId())")
        press(.left, settle: 2)
        XCTAssertEqual(focusedId(), "sidebar.settings", "the sidebar opened on \(focusedId()), not the current tab")
    }

    /// App Store build: add-ons and plugins are compiled out, so the rail has no Content & Discovery
    /// (the add-on installer), and asking for it by launch hook lands on Account instead.
    func testStoreBuildHasNoContentAndDiscovery() {
        launch(["-smokeTab", "settings", "-smokeSettings", "discovery"])
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("settings.rail.") }), "settings rail never took focus; \(focusedId())")
        XCTAssertTrue(app.buttons["settings.rail.integrations"].exists, "the rail did not render")
        XCTAssertFalse(app.buttons["settings.rail.discovery"].exists, "Content & Discovery (add-on installer) is showing in the store build")
        XCTAssertFalse(app.staticTexts["Install addon"].exists, "the add-on installer opened from the launch hook")
    }

    // MARK: - Profiles

    /// "Manage Profiles ... there is no way to come out / cannot go back to the home screen."
    func testManageProfilesHasAWayOut() {
        launch(["-smokeTab", "settings", "-smokeSettings", "profiles"])
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("settings.rail.") }), "settings rail never took focus")
        press(.right)
        let row = focusedId() + " / " + focused.label
        press(.select, settle: 3)   // Manage Profiles
        let rail = app.buttons["settings.rail.profiles"]
        let gone = NSPredicate(format: "exists == false")
        XCTAssertEqual(XCTWaiter.wait(for: [expectation(for: gone, evaluatedWith: rail)], timeout: 10), .completed,
                       "Manage Profiles did not open; select pressed on \(row); texts: \(app.staticTexts.allElementsBoundByIndex.prefix(12).map { $0.label })")
        XCTAssertTrue(app.buttons["profiles.done"].waitForExistence(timeout: 5),
                      "Manage Profiles has no visible Done; buttons: \(app.buttons.allElementsBoundByIndex.map { $0.identifier + "|" + $0.label })")
        press(.menu, settle: 3)
        XCTAssertTrue(app.buttons["sidebar.home"].waitForExistence(timeout: 10), "Menu on Manage Profiles did not return to the app")
        XCTAssertFalse(app.buttons["profiles.done"].exists, "still on Manage Profiles after Menu")
    }

    /// Navigation audit 2026-10-28 (finding 5): Back from Manage Profiles returns to Settings > Profiles,
    /// where the viewer came from - not to Account (or Home).
    func testManageProfilesBackReturnsToSettingsProfiles() {
        app.launchArguments = ["-smokeLocalGuest", "-smokeTab", "settings"]   // no auto-pick: the hook masks this
        app.launch()
        let profiles = app.buttons["settings.rail.profiles"]
        XCTAssertTrue(profiles.waitForExistence(timeout: 40), "settings rail never appeared")
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("settings.rail.") }), "no rail focus; \(focusedId())")
        press(.down); press(.right)   // Account -> Profiles -> its Manage Profiles row
        press(.select, settle: 3)
        XCTAssertTrue(app.buttons["profiles.done"].waitForExistence(timeout: 10), "Manage Profiles did not open")
        press(.menu, settle: 3)
        XCTAssertTrue(profiles.waitForExistence(timeout: 10), "Back did not return to Settings")
        XCTAssertTrue(app.staticTexts["Manage user profiles for this account"].waitForExistence(timeout: 5),
                      "Back returned to a different Settings category than Profiles")
    }

    // MARK: - Batch 2 (navigation audit 2026-10-28)

    /// Finding 3: the Search keyboard's delete key must be fully on screen (it was drawn past the right edge).
    func testSearchDeleteKeyIsFullyOnScreen() {
        launchGuest(["-smokeTab", "search"])
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 30), "Search never appeared")
        Thread.sleep(forTimeInterval: 2)
        let delete = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] 'delete'")).firstMatch
        XCTAssertTrue(delete.exists, "no delete key found on the Search keyboard")
        let screenWidth = app.windows.firstMatch.frame.width
        XCTAssertLessThanOrEqual(delete.frame.maxX, screenWidth, "the delete key ends at x=\(delete.frame.maxX), past the \(screenWidth) pt screen edge")
    }

    /// F1: a failed source must be visible. The toast used to be drawn only under the source picker's
    /// full-screen cover, so picking a dead source showed nothing. The hook raises the toast as the picker opens.
    func testSourcePickerShowsPlaybackToast() {
        launchGuest(["-smokeDetails", "series:parity-sample", "-smokePresentationTitle", "-smokePickerToast"])
        XCTAssertTrue(app.staticTexts["Presentation preview"].waitForExistence(timeout: 40), "the sample title never loaded")
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 20), "no Play button on the title page")
        for _ in 0..<6 where !play.hasFocus { press(.up, settle: 0.5) }
        XCTAssertTrue(play.hasFocus, "could not focus Play; focus on \(focusedId())")
        press(.select, settle: 1)
        XCTAssertTrue(app.staticTexts["Smoke source toast"].waitForExistence(timeout: 15), "no playback toast appeared")
        // Covered views stay in the accessibility tree, so only a toast that is on top (hittable) is one the viewer sees.
        let visible = app.staticTexts.matching(NSPredicate(format: "label == 'Smoke source toast'")).allElementsBoundByIndex.contains { $0.isHittable }
        XCTAssertTrue(visible, "the playback toast is hidden under the source picker")
    }

    /// Bounded loads: a page whose load never answers must stop spinning, say so, and let Retry ask again.
    func testStalledTitleStopsSpinningAndOffersRetry() {
        launchGuest(["-smokeDetails", "series:parity-sample", "-smokeStallTitle", "-smokeLoadDeadline", "3"])
        let message = app.staticTexts["Taking longer than expected"]
        XCTAssertTrue(message.waitForExistence(timeout: 20), "the stalled title page is still spinning after the deadline")
        let retry = app.buttons["Retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 5), "no Retry on the timed-out page")
        for _ in 0..<4 where !retry.hasFocus { press(.down, settle: 0.5) }
        press(.select, settle: 1)
        let gone = NSPredicate(format: "exists == false")
        XCTAssertEqual(XCTWaiter.wait(for: [expectation(for: gone, evaluatedWith: message)], timeout: 10), .completed, "Retry did not start a new attempt")
    }

    /// "Add the in-app profile switch": the drawer's profile item opens "Who's watching?"; Menu backs
    /// out with no switch; picking the other profile switches and Home fills in (catalogs reload).
    func testSidebarProfileSwitch() {
        launch(["-smokeTab", "home"])
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" && !$0.hasPrefix("sidebar.") }), "nothing focused on Home")
        press(.menu)
        XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("sidebar.") }, timeout: 5), "Menu did not open the sidebar")
        for _ in 0..<8 where focusedId() != "sidebar.profile" { press(.up, settle: 0.5) }
        XCTAssertEqual(focusedId(), "sidebar.profile", "UP from Home did not reach the profile item")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/walk-profile-item.png"))
        press(.up)
        XCTAssertEqual(focusedId(), "sidebar.profile", "UP past the profile item left the drawer")

        // Open the switcher, back out with Menu: same profile, no switch.
        press(.select, settle: 3)
        XCTAssertTrue(app.staticTexts["Who's watching?"].waitForExistence(timeout: 10), "profile item did not open the picker")
        press(.menu, settle: 3)
        XCTAssertTrue(app.buttons["sidebar.home"].waitForExistence(timeout: 10), "Menu on the switcher did not return to the app")
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" && !$0.hasPrefix("sidebar.") }, timeout: 15),
                      "back from the switcher, focus is \(focusedId()) - the drawer must stay closed")

        // Switch to the other profile, then back, and Home must have its rows.
        for round in 0..<2 {
            let before = focusedId()
            press(.menu)
            XCTAssertTrue(waitForFocus(where: { $0.hasPrefix("sidebar.") }, timeout: 5),
                          "round \(round): Menu did not open the sidebar; focus before Menu \(before), now \(focusedId())")
            for _ in 0..<8 where focusedId() != "sidebar.profile" { press(.up, settle: 0.5) }
            press(.select, settle: 3)
            XCTAssertTrue(app.staticTexts["Who's watching?"].waitForExistence(timeout: 10))
            press(round == 0 ? .right : .left, settle: 1)
            press(.select, settle: 8)
            XCTAssertTrue(app.buttons["sidebar.home"].waitForExistence(timeout: 30), "round \(round): the switch never reached the app")
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/walk-switch-\(round).png"))
            // The switch re-syncs: wait (as a viewer would) until Home's content holds focus.
            XCTAssertTrue(waitForFocus(where: { $0 != "<none>" && !$0.hasPrefix("sidebar.") }, timeout: 40),
                          "round \(round): nothing in Home took focus after the switch (focus \(focusedId()))")
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/walk-switch-\(round)-after.png"))
            XCTAssertFalse(app.staticTexts["Nothing to show yet"].exists, "round \(round): Home is empty after the switch")
        }
    }

    /// Tester report 2026-10-01 (Apple TV, French): with Settings -> Layout -> Collapse Sidebar on, Home
    /// stayed shifted right, leaving an empty strip where the hidden sidebar would be.
    func testCollapsedSidebarLetsContentFillTheScreen() {
        launch(["-smokeTab", "home", "-smokeCollapseSidebar", "true"])
        addTeardownBlock { [self] in   // put the setting back for the other tests
            app.terminate(); app.launchArguments = ["-smokePickProfile", "1", "-smokeCollapseSidebar", "false"]; app.launch()
            Thread.sleep(forTimeInterval: 3); app.terminate()
        }
        XCTAssertTrue(waitForFocus(where: { $0 != "<none>" && !$0.hasPrefix("sidebar.") }), "nothing focused on Home")
        // Leftmost card of the focused row: walk LEFT until the next press would leave the content.
        var minX = focused.frame.minX
        for _ in 0..<8 {
            press(.left, settle: 0.6)
            if focusedId().hasPrefix("sidebar.") { break }
            minX = min(minX, focused.frame.minX)
        }
        XCTAssertLessThan(minX, 140, "with the sidebar collapsed, content still starts at x=\(minX) - the sidebar's inset was kept")
        XCTAssertTrue(focusedId().hasPrefix("sidebar."), "LEFT from the leftmost card should still open the hidden sidebar; focus \(focusedId())")
    }
}
