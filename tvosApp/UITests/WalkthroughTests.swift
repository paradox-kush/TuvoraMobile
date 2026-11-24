import XCTest

/// App-wide focus/navigation walkthrough, driven by the Siri Remote. Each check comes from
/// TVOS-FOCUS-CHECKLIST.md (researched HIG / WWDC / forum pitfalls); a failure is a finding.
final class WalkthroughTests: XCTestCase {
    private let remote = XCUIRemote.shared
    /// Disposable loopback lineup (Scripts/parity-guide-fixture.py); a guest profile has no playlists of its own.
    private static let lineup = "http://127.0.0.1:18124/lineup.m3u"
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true   // a walkthrough reports every finding, not just the first
        app = XCUIApplication()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "home", "-smokeCollections"]
        app.launch()
    }

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
    /// Debug: screenshot to the host (simulator only) so a failure can be looked at.
    private func shot(_ name: String) {
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/walk-\(name).png"))
        print("WALK \(name) focus=\(focusedId())")
    }
    /// A guest profile has no add-ons, so Home's catalog rows are the in-memory sample (-smokeCollections falls back to
    /// two rows of well-known titles when Home has none). Walks DOWN to the first sample poster.
    private static let sampleTitles = ["The Shawshank Redemption", "The Godfather", "The Dark Knight", "12 Angry Men", "Inception", "Interstellar", "The Matrix", "Forrest Gump", "Fight Club"]
    private func focusFirstPoster() -> Bool {
        for _ in 0..<14 {
            if Self.sampleTitles.contains(where: { focused.label.hasPrefix($0) }) { return true }
            press(.down, settle: 1.2)
        }
        return false
    }
    private func inContent(_ id: String) -> Bool { id != "<none>" && !id.hasPrefix("sidebar.") }

    private func openTab(_ tab: String) {
        if !focusedId().hasPrefix("sidebar.") { press(.menu) }
        XCTAssertTrue(waitFocus(5) { $0.hasPrefix("sidebar.") }, "Menu did not open the sidebar (on \(focusedId()))")
        let order = ["home", "search", "library", "iptv", "sports", "calendar", "settings"]
        guard let from = order.firstIndex(where: { "sidebar.\($0)" == focusedId() }), let to = order.firstIndex(of: tab) else { return }
        for _ in 0..<abs(to - from) { press(to > from ? .down : .up, settle: 0.4) }
        XCTAssertEqual(focusedId(), "sidebar.\(tab)")
        press(.select, settle: 2)
    }

    /// Checklist B/C: picking a tab from the sidebar hands focus to that tab's content, and LEFT from
    /// its leftmost column gets back to the sidebar.
    func testEveryTabTakesFocusAndReachesSidebar() {
        XCTAssertTrue(waitFocus { inContent($0) }, "Home: nothing focused after launch")
        for tab in ["search", "library", "iptv", "sports", "calendar", "settings", "home"] {
            openTab(tab)
            let ok = waitFocus(15) { inContent($0) }
            XCTAssertTrue(ok, "\(tab): after choosing it in the sidebar, focus is \(focusedId()) - nothing in the tab took focus")
            guard ok else { continue }
            var trail: [String] = []
            for _ in 0..<8 where !focusedId().hasPrefix("sidebar.") { press(.left, settle: 0.6); trail.append(focusedId()) }
            XCTAssertTrue(focusedId().hasPrefix("sidebar.") || tab == "search",
                          "\(tab): LEFT never reached the sidebar; trail \(trail)")
        }
    }

    /// Checklist A: Menu at the root leaves the app (tvOS Home screen); it must never be swallowed.
    func testMenuFromSidebarExitsTheApp() {
        XCTAssertTrue(waitFocus { inContent($0) })
        press(.menu)
        XCTAssertTrue(waitFocus(5) { $0.hasPrefix("sidebar.") }, "Menu in content did not open the sidebar")
        press(.menu, settle: 2)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5) || app.state == .runningBackgroundSuspended,
                      "Menu from the sidebar did not exit to the Apple TV Home screen (state \(app.state.rawValue))")
    }

    /// Checklist A/E: details opens from a card, Menu closes it, and focus returns to the card.
    func testDetailsMenuReturnsFocusToOpener() {
        XCTAssertTrue(waitFocus { inContent($0) }, "Home: nothing focused")
        XCTAssertTrue(focusFirstPoster(), "Home: no catalog poster reachable with DOWN (focus \(focusedId()))")
        let opener = focusedId()
        press(.select, settle: 4)
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play' OR label BEGINSWITH 'Resume' OR label BEGINSWITH 'Watch'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 15), "details did not open from \(opener)")
        XCTAssertTrue(waitFocus(8) { $0 != "<none>" }, "details: nothing focused")
        let detailsFocus = focusedId()
        for _ in 0..<6 { press(.down, settle: 0.6) }
        let afterDown = focusedId()
        XCTAssertNotEqual(afterDown, "<none>", "details: DOWN lost focus")
        press(.menu, settle: 2)
        XCTAssertFalse(play.exists, "Menu did not close details")
        XCTAssertTrue(waitFocus(5) { inContent($0) }, "after closing details focus is \(focusedId()) (opened from \(opener))")
        XCTAssertEqual(focusedId(), opener, "focus did not return to the card that opened details (details focus \(detailsFocus), after DOWN \(afterDown))")
    }

    /// Checklist A: Search's keyboard and results: Menu steps back, never strands focus.
    func testSearchMenuSteps() {
        XCTAssertTrue(waitFocus { inContent($0) })
        openTab("search")
        XCTAssertTrue(waitFocus(10) { inContent($0) }, "search: nothing focused")
        press(.menu, settle: 1.5)
        XCTAssertTrue(waitFocus(5) { $0 != "<none>" }, "search: Menu left nothing focused")
    }

    // MARK: - Round 2

    /// Guide: RIGHT into channels hides the categories; LEFT from a channel brings them back (never
    /// the sidebar); RIGHT from a channel enters the timeline, where LEFT travels, not the sidebar.
    func testGuideLeftAndTimeline() {
        app.terminate()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeClearPlaylists", "-smokeAddM3uLater", "1", Self.lineup]
        app.launch()
        // The lineup is added a second after launch; the first channel row ("#1, name") then claims focus.
        func onChannelRow(_ id: String) -> Bool { id.hasPrefix("#") && id.dropFirst().first?.isNumber == true }
        var landed = waitFocus(30, onChannelRow)
        // Focus can still be on the sidebar item that opened the tab (the lineup arrived after the first focus pass):
        // a viewer presses RIGHT, then DOWN off the header chips, to reach the channels.
        for _ in 0..<4 where !landed {
            if focusedId().hasPrefix("sidebar.") { press(.right, settle: 1.5) } else if focusedId().hasPrefix("hubchip.") { press(.down, settle: 1.5) }
            landed = waitFocus(8, onChannelRow)
        }
        XCTAssertTrue(landed, "iptv: no channel row could be focused (focus \(focusedId()))")
        let categories = app.buttons["All channels"]
        shot("guide-0")
        // IPTV entry lands on the first channel (2e333ea9d, F7), with the categories tucked away: LEFT
        // from a channel brings the column back on the current category (All channels).
        let channel = focusedId()
        XCTAssertFalse(categories.exists, "guide: the category column should start tucked away while a channel has focus (focus \(channel))")
        press(.left, settle: 1.5)
        XCTAssertEqual(focusedId(), "#All channels", "guide: could not reach the All channels category")
        press(.select, settle: 3)
        press(.right, settle: 1.5)
        let onChannel = focusedId()
        shot("guide-1-channel")
        XCTAssertFalse(onChannel.hasPrefix("sidebar.") || onChannel == "<none>", "guide: RIGHT from categories went to \(onChannel)")
        press(.left, settle: 1.2)
        shot("guide-2-left")
        XCTAssertFalse(focusedId().hasPrefix("sidebar."), "guide: LEFT from channel \(onChannel) opened the sidebar instead of the categories")
        XCTAssertTrue(categories.exists, "guide: LEFT from a channel did not bring the category column back (focus \(focusedId()))")
        XCTAssertEqual(focusedId(), "#All channels", "guide: LEFT from a channel should focus its category, got \(focusedId())")
        // Back to a channel, then into its timeline.
        press(.right, settle: 1)
        press(.right, settle: 1.5)
        let cell = focusedId()
        press(.left, settle: 2)
        XCTAssertFalse(focusedId().hasPrefix("sidebar."), "guide: LEFT in the timeline (from \(cell)) opened the sidebar")
        XCTAssertNotEqual(focusedId(), "<none>", "guide: LEFT in the timeline lost focus")
    }

    /// Details -> Play -> sources: Menu closes the picker back to details, then details back to Home.
    func testSourcePickerMenuStepsBack() {
        XCTAssertTrue(waitFocus { inContent($0) })
        XCTAssertTrue(focusFirstPoster(), "Home: no catalog poster reachable with DOWN (focus \(focusedId()))")
        press(.select, settle: 4)
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play' OR label BEGINSWITH 'Resume' OR label BEGINSWITH 'Watch'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 15), "details did not open")
        XCTAssertTrue(waitFocus(8) { $0 != "<none>" })
        for _ in 0..<3 where !(focused.label.hasPrefix("Play") || focused.label.hasPrefix("Resume") || focused.label.hasPrefix("Watch")) { press(.up, settle: 0.6) }
        press(.select, settle: 6)   // sources (or straight to the player with auto-play)
        let stillDetails = play.exists && play.isHittable
        press(.menu, settle: 3)
        XCTAssertTrue(waitFocus(8) { $0 != "<none>" }, "after Menu from sources/player nothing is focused")
        if !stillDetails {
            XCTAssertTrue(play.exists, "Menu from the source picker/player did not return to details")
        }
        press(.menu, settle: 2)
        XCTAssertTrue(waitFocus(5) { inContent($0) }, "Menu from details did not return to Home content (focus \(focusedId()))")
    }

    /// Settings dialogs (option pickers): Menu closes the dialog and focus returns to its row.
    func testSettingsDialogMenuReturnsToRow() {
        XCTAssertTrue(waitFocus { inContent($0) })
        openTab("settings")
        XCTAssertTrue(waitFocus(10) { $0.hasPrefix("settings.rail.") })
        for _ in 0..<8 where focusedId() != "settings.rail.playback" { press(.down, settle: 0.5) }
        XCTAssertEqual(focusedId(), "settings.rail.playback")
        press(.right, settle: 1)
        for _ in 0..<4 where !focused.label.contains("Engine") { press(.down, settle: 0.6) }
        let row = focusedId()
        press(.select, settle: 2)
        shot("settings-dialog")
        let dialogFocus = focusedId()
        XCTAssertNotEqual(dialogFocus, row, "selecting \(row) opened nothing")
        press(.menu, settle: 1.5)
        XCTAssertEqual(focusedId(), row, "Menu from the \(row) dialog left focus on \(focusedId())")
    }

    /// "On the info page, can not scroll down to see all the options like movie actors etc."
    func testDetailsScrollsPastTheButtons() {
        XCTAssertTrue(waitFocus { inContent($0) })
        XCTAssertTrue(focusFirstPoster(), "Home: no catalog poster reachable with DOWN (focus \(focusedId()))")
        press(.select, settle: 5)
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play' OR label BEGINSWITH 'Resume' OR label BEGINSWITH 'Watch'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 15), "details did not open")
        XCTAssertTrue(waitFocus(8) { $0 != "<none>" })
        var seen: [String] = [focusedId()]
        for _ in 0..<6 { press(.down, settle: 0.8); if seen.last != focusedId() { seen.append(focusedId()) } }
        shot("details-bottom")
        XCTAssertGreaterThanOrEqual(seen.count, 3, "DOWN on details never got past the action buttons; trail \(seen)")
        for _ in 0..<10 { press(.up, settle: 0.5) }
        XCTAssertTrue(play.isHittable, "UP did not bring the page back to the top")
    }

    /// Player (live, full screen from the guide): Menu steps out one level at a time and focus lands
    /// back in the guide - never on the sidebar or nowhere. The remote's arrows never reach the shell.
    func testPlayerMenuReturnsToGuide() {
        app.terminate()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeClearPlaylists", "-smokeAddM3uLater", "1", Self.lineup, "-smokeGuidePlay"]
        app.launch()
        Thread.sleep(forTimeInterval: 22)   // preview, then full screen
        shot("player-0")
        press(.select, settle: 1.5)         // controls
        shot("player-1-controls")
        press(.left, settle: 1)
        press(.right, settle: 1)
        XCTAssertFalse(focusedId().hasPrefix("sidebar."), "player: an arrow press reached the sidebar")
        var steps: [String] = []
        for _ in 0..<3 {
            press(.menu, settle: 2)
            steps.append(focusedId())
            if app.buttons["hubchip.Live TV"].isHittable && inContent(focusedId()) { break }
        }
        shot("player-2-closed")
        XCTAssertTrue(app.buttons["hubchip.Live TV"].isHittable, "Menu did not close the player; steps \(steps)")
        XCTAssertTrue(inContent(focusedId()), "after closing the player focus is \(focusedId()); steps \(steps)")
        XCTAssertEqual(app.state, .runningForeground, "Menu from the player left the app")
        // B112: the closed player's engine must be gone too (it kept playing sound on Apple TV build 4). Back in the
        // guide its docked preview strip may legitimately hold ONE engine of its own (the guide previews the focused
        // channel since 979fd07d1), so "gone" is proved in two steps: never more than that one engine while the guide
        // is up, and none at all once the viewer has left Live TV (a leaked player engine would still be alive).
        let engines = app.descendants(matching: .any)["engines.live"]
        func count() -> String { engines.exists ? engines.label : "<missing>" }
        let settle = Date().addingTimeInterval(10)
        while Date() < settle && !(count() == "engines=0" || count() == "engines=1") { Thread.sleep(forTimeInterval: 1) }
        XCTAssertTrue(["engines=0", "engines=1"].contains(count()), "more than the guide's own preview engine is alive after the player closed (\(count())); steps \(steps)")
        // Leave Live TV: Menu to the sidebar, Home, and the guide's preview goes with its screen.
        for _ in 0..<3 where !focusedId().hasPrefix("sidebar.") { press(.menu, settle: 1.5) }
        XCTAssertTrue(focusedId().hasPrefix("sidebar."), "Menu did not open the sidebar from the guide (focus \(focusedId()))")
        for _ in 0..<6 where focusedId() != "sidebar.home" { press(.up, settle: 0.5) }
        XCTAssertEqual(focusedId(), "sidebar.home")
        press(.select, settle: 2)
        let end = Date().addingTimeInterval(15)
        while Date() < end && count() != "engines=0" { Thread.sleep(forTimeInterval: 1) }
        XCTAssertEqual(count(), "engines=0", "an engine outlived the player and the guide; steps \(steps)")
    }
}
