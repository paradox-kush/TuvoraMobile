import XCTest

/// F48 on Apple TV: Search inside one playlist's Movies / Live, driven only by the Siri Remote (XCUIRemote).
///
/// Data is the project's own demo playlist (Sintel, Tears of Steel, Test Pattern), added through the existing
/// `-smokeAddM3uLater` launch hook — never a person's provider. These tests only read: they add nothing but that
/// demo playlist and press no destructive control.
///
/// What they prove is FUNCTION (the chip, the keyboard sheet, the results page, focus coming back). They do NOT prove
/// the screen feels responsive: that is an Instruments measurement on real hardware (see the Apple TV notes in
/// community-intake/ux-easy-batch-2026-10-31.md).
final class HubSearchTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private static let demo = "https://tuvora.co/demo/reviewer-playlist.m3u"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    // MARK: helpers (same shape as BackupServerTests)

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func waitFocus(_ timeout: TimeInterval = 15, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedId()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }
    private func shot(_ name: String) {
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/tuvora-hubsearch-\(name).png"))
    }

    /// Opens the IPTV tab with the demo playlist and waits for the hub header.
    private func launchHub() throws {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeAddM3uLater", "1", Self.demo]
        app.launch()
        guard app.buttons["hubchip.Movies"].waitForExistence(timeout: 90) else {
            throw XCTSkip("the demo playlist did not load (offline, or the profile already has playlists that hide it)")
        }
        Thread.sleep(forTimeInterval: 3)
    }

    /// Walks focus along the header row to [id] (LEFT/RIGHT), starting from wherever it is; UP first when focus is below the header.
    private func focusChip(_ id: String) -> Bool {
        if waitFocus(2, { $0 == id }) { return true }
        // Leave the sidebar (focus starts there), then UP until a hub chip has focus.
        if focusedId().hasPrefix("sidebar.") { press(.right, settle: 0.8) }
        for _ in 0..<8 where !focusedId().hasPrefix("hubchip.") { press(.up, settle: 0.5) }
        for _ in 0..<8 {
            if focusedId() == id { return true }
            press(.right, settle: 0.5)
        }
        for _ in 0..<8 {
            if focusedId() == id { return true }
            press(.left, settle: 0.5)
        }
        return focusedId() == id
    }

    /// Select the chip with [id] (the section chips select on OK).
    private func openSection(_ id: String) {
        XCTAssertTrue(focusChip(id), "never reached \(id); focus on \(focusedId())")
        press(.select, settle: 2.5)
    }

    /// Opens the Search sheet from the header, types [text] on the native keyboard and submits.
    private func search(_ text: String, input: String = "hub.search.input", submit: String = "hub.search.submit") {
        XCTAssertTrue(focusChip("hubchip.Search"), "never reached the Search chip; focus on \(focusedId())")
        press(.select, settle: 2)
        XCTAssertTrue(app.textFields[input].waitForExistence(timeout: 15), "the search sheet did not open")
        _ = waitFocus(10) { $0 == input }
        press(.select, settle: 2)          // opens the tvOS keyboard on the focused field
        app.typeText(text)
        press(.menu, settle: 1.5)          // back from the keyboard, keeping the text
        for _ in 0..<6 where focusedId() != submit { press(.down, settle: 0.5) }
        XCTAssertEqual(focusedId(), submit, "never reached the Search button; focus on \(focusedId())")
        press(.select, settle: 3)
    }

    // MARK: tests

    /// Movies: a real hit shows on a results page, and Menu closes it with focus back on the Search chip.
    func testMoviesSearchFindsATitleAndMenuReturnsToTheChip() throws {
        try launchHub()
        openSection("hubchip.Movies")
        search("sinte")
        let title = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "“sinte” in")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 30), "no results page for “sinte”")
        let hit = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Sintel")).firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 30), "Sintel (2010) is in the demo playlist but is not in the results")
        shot("movies-hit")
        // Focus is on the header chip here (see the known-gap test below); one DOWN enters the results, as a person would.
        press(.down, settle: 1.5)
        press(.menu, settle: 2)
        XCTAssertFalse(title.exists, "Menu did not close the results page")
        XCTAssertTrue(waitFocus { $0 == "hubchip.Search" }, "focus did not return to the Search chip; on \(focusedId())")
    }

    /// KNOWN GAP (recorded, not hidden): after a search, focus stays on the header's Search chip instead of moving to the first
    /// result, so a person needs one DOWN. Three SwiftUI mechanisms were tried (focusScope + prefersDefaultFocus + resetFocus,
    /// clearing the chip's FocusState, and a FocusState on the result cards); none moved focus in this simulator. Left for the
    /// hardware/Instruments pass. XCTExpectFailure keeps the suite honest: it turns red if this starts passing, so the note can go.
    func testKnownGapResultsDoNotTakeFocusAutomatically() throws {
        try launchHub()
        openSection("hubchip.Movies")
        search("sinte")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Sintel")).firstMatch.waitForExistence(timeout: 30))
        XCTExpectFailure("known gap: the first result does not take focus after a search; focus stays on the Search chip") {
            XCTAssertTrue(waitFocus(8) { $0.contains("Sintel") }, "focus did not move into the results; on \(focusedId())")
        }
    }

    /// A word that is nowhere in the playlist ends on "No matches" (never an error, never an endless wait) and offers Search again.
    func testMoviesSearchWithNoMatchSaysSoAndOffersSearchAgain() throws {
        try launchHub()
        openSection("hubchip.Movies")
        search("zzqxjk")
        XCTAssertTrue(app.staticTexts["No matches"].waitForExistence(timeout: 30), "a word with no match did not end on “No matches”")
        XCTAssertTrue(app.buttons["Search again"].exists, "“No matches” left nothing to press (a dead end for the remote)")
        shot("movies-nomatch")
    }

    /// One letter is below the minimum: no request is made and the results page never opens.
    func testOneLetterNeverOpensTheResultsPage() throws {
        try launchHub()
        openSection("hubchip.Movies")
        search("s")
        Thread.sleep(forTimeInterval: 3)
        XCTAssertFalse(
            app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "“s” in")).firstMatch.exists,
            "a one-letter query opened a results page",
        )
        XCTAssertTrue(app.buttons["hubchip.Search"].exists, "the hub header went missing")
    }

    /// Live: the same chip opens the guide's own channel search, and the channel it finds is shown.
    func testLiveChipOpensTheGuideSearch() throws {
        try launchHub()
        openSection("hubchip.Live TV")
        search("test", input: "guide.search.input", submit: "guide.search.submit")
        let channel = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Test Pattern")).firstMatch
        XCTAssertTrue(channel.waitForExistence(timeout: 30), "searching “test” on Live did not show the Test Pattern channel")
        shot("live-hit")
    }
}
