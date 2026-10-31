import XCTest

/// UX179 on Apple TV: the playlist details page's "Refresh now" card, driven only by the Siri Remote (XCUIRemote).
///
/// Data is the project's own demo playlist (a 733-byte M3U on tuvora.co), added through the existing `-smokeAddM3uLater`
/// launch hook, so even the second press (the cooldown check) never touches a person's provider.
///
/// This proves FUNCTION (the card exists, a press refreshes and reads honestly, a second press inside a minute says to wait).
/// It does not prove the page feels responsive: that is an Instruments measurement on real hardware.
final class RefreshNowTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private static let demo = "https://tuvora.co/demo/reviewer-playlist.m3u"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

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
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/tuvora-refresh-\(name).png"))
    }

    /// The Refresh card's text (title + status line), empty when the card is not on screen.
    private var refreshCardText: String {
        let card = app.buttons["details.card.refresh"]
        return card.exists ? card.label : ""
    }
    private func waitCard(containing text: String, timeout: TimeInterval = 30) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if refreshCardText.contains(text) { return true }; Thread.sleep(forTimeInterval: 0.5) }
        return false
    }

    /// Opens Settings → Integrations → IPTV with the demo playlist, opens its details page and focuses the Refresh card.
    private func openDetailsOnRefreshCard() throws {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations",
                               "-smokeIntegration", "iptv", "-smokeAddM3uLater", "1", Self.demo]
        app.launch()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "playlist.row.")).firstMatch
        guard row.waitForExistence(timeout: 90) else {
            throw XCTSkip("the demo playlist did not appear (offline, or the profile already has other playlists)")
        }
        Thread.sleep(forTimeInterval: 3)
        // Focus starts on the settings rail: RIGHT enters the pane, then DOWN until a playlist row has focus; OK opens its details page.
        if focusedId().hasPrefix("settings.rail") || focusedId().hasPrefix("sidebar.") { press(.right, settle: 1.2) }
        for _ in 0..<14 where !focusedId().hasPrefix("playlist.row.") { press(.down, settle: 0.5) }
        XCTAssertTrue(focusedId().hasPrefix("playlist.row."), "never reached the playlist row; focus on \(focusedId())")
        press(.select, settle: 2.5)
        XCTAssertTrue(app.buttons["details.card.refresh"].waitForExistence(timeout: 30), "the details page has no Refresh now card")
        // Focus starts on the first card of the library shelf; the cards are one row: RIGHT walks along it.
        for _ in 0..<8 where focusedId() != "details.card.refresh" { press(.right, settle: 0.6) }
        XCTAssertEqual(focusedId(), "details.card.refresh", "never reached the Refresh now card; focus on \(focusedId())")
    }

    /// The card exists with its words, a press refreshes and ends on an honest "Updated …" line, and a second press inside the
    /// minute is turned away with the cooldown message instead of re-downloading.
    func testRefreshNowRefreshesThenAsksToWaitOnASecondPress() throws {
        try openDetailsOnRefreshCard()
        XCTAssertTrue(refreshCardText.contains("Refresh now"), "the card is not titled Refresh now: “\(refreshCardText)”")
        shot("before")

        press(.select, settle: 1)
        XCTAssertTrue(waitCard(containing: "Updated", timeout: 60), "a refresh did not end on an “Updated …” line; card says “\(refreshCardText)”")
        XCTAssertFalse(refreshCardText.contains("Couldn"), "the refresh reported a failure on a healthy playlist: “\(refreshCardText)”")
        shot("after-first")

        // Inside the cooldown minute: nothing is fetched and the card says why.
        press(.select, settle: 1)
        XCTAssertTrue(waitCard(containing: "Please wait a minute", timeout: 20), "a second press inside a minute was not turned away; card says “\(refreshCardText)”")
        shot("cooldown")
    }
}
