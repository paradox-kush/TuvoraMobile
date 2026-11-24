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
        app.launchArguments = ["-smokeLocalGuest", "-smokeTab", "iptv", "-smokeAddXtream", mock, "mock", "mock"]
        app.launch()
    }

    override func tearDown() {
        _ = get("\(mock)/arm-series-info?status=off")
        super.tearDown()
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

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedLabel() -> String { focused.exists ? focused.label : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func has(_ text: String, timeout: TimeInterval) -> Bool {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch.waitForExistence(timeout: timeout)
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
        press(.down)                                       // "See all"
        press(.down)                                       // first series of the first category
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
