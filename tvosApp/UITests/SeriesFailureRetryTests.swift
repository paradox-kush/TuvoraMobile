import XCTest

/// B134 on Apple TV: an Xtream series whose lookup fails says so with a focused Retry, and Retry loads it once the
/// provider answers. (The title screen used to pass Retry as a trailing closure, which bound to the unused second
/// action, so the message showed with no button and nothing focused.)
///
/// Needs the Stalker/Xtream mock portal on 127.0.0.1:8912 (`research/stalker-mock-portal/mock_portal.py`, user and
/// password `mock`); skips when it is not running.
final class SeriesFailureRetryTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private let mock = "http://127.0.0.1:8912"
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        try XCTSkipUnless(get("\(mock)/arm-series-info?status=off") != nil, "Xtream mock portal not running on 8912")
        app = XCUIApplication()
        clearPlaylists()                                   // start from an empty hub, whatever earlier tests added
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeAddXtream", mock, "mock", "mock"]
        app.launch()
    }

    override func tearDown() {
        _ = get("\(mock)/arm-series-info?status=off")
        clearPlaylists()                                   // later tests (the guide walkthrough) expect no mock playlist
        super.tearDown()
    }

    private func clearPlaylists() {
        app.terminate()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeClearPlaylists"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        app.terminate()
    }

    private func get(_ url: String) -> String? {
        var body: String?
        let done = expectation(description: "GET")
        URLSession.shared.dataTask(with: URL(string: url)!) { data, response, _ in
            if (response as? HTTPURLResponse)?.statusCode == 200, let data { body = String(decoding: data, as: UTF8.self) }
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 5)
        return body
    }

    /// Labels and focus come from ONE accessibility snapshot per check (as the remote driver does): per-element
    /// queries over the Live TV guide's large tree time out on a freshly imported playlist.
    private func snapshotLabels() -> (focused: String, all: [String]) {
        guard let root = try? app.snapshot() else { return ("<none>", []) }
        var focused = "<none>", all: [String] = []
        func walk(_ n: XCUIElementSnapshot) {
            if !n.label.isEmpty { all.append(n.label) }
            if n.hasFocus, focused == "<none>" { focused = n.label }
            n.children.forEach(walk)
        }
        walk(root)
        return (focused, all)
    }
    private func focusedLabel() -> String { snapshotLabels().focused }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func has(_ text: String, timeout: TimeInterval) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        repeat {
            if snapshotLabels().all.contains(where: { $0.contains(text) }) { return true }
            Thread.sleep(forTimeInterval: 0.5)
        } while Date() < end
        return false
    }

    func testFailedSeriesOffersRetryThatLoadsIt() {
        // A first import on a fresh simulator keeps the accessibility tree busy for a while; let it settle first.
        Thread.sleep(forTimeInterval: 15)
        XCTAssertTrue(has("Smoke Xtream", timeout: 45), "the mock Xtream playlist did not load")
        XCTAssertNotNil(get("\(mock)/arm-series-info?status=403"))
        // Header chips: Up until a section chip has focus, then Series.
        for _ in 0..<6 where !["Live TV", "Movies", "Series"].contains(focusedLabel()) { press(.up) }
        for _ in 0..<3 where focusedLabel() != "Series" { press(.right) }
        XCTAssertEqual(focusedLabel(), "Series", "could not reach the Series chip")
        press(.select, settle: 3)
        XCTAssertTrue(has("See all", timeout: 30), "the Series section never loaded")
        // Down past "See all" to the first series of the first category (the list may still be settling).
        for _ in 0..<6 where ["Series", "See all"].contains(focusedLabel()) || focusedLabel() == "<none>" { press(.down, settle: 1.5) }
        let title = focusedLabel()
        press(.select, settle: 6)
        XCTAssertTrue(has("Couldn't load this series", timeout: 20), "a failed series lookup must say so (opened \(title))")
        XCTAssertEqual(focusedLabel(), "Retry", "Retry is shown and focused on the failure")
        XCTAssertNotNil(get("\(mock)/arm-series-info?status=off"))
        press(.select, settle: 6)
        XCTAssertFalse(has("Couldn't load this series", timeout: 2), "Retry after the provider recovers loads the series")
        XCTAssertTrue(has(title, timeout: 10), "the series page shows \(title) after Retry")
    }
}
