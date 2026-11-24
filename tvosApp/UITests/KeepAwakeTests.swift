import XCTest

/// B279 on Apple TV: while the viewer means to watch, the player holds the screen awake (the screensaver stays off);
/// a pause lets it sleep again; resuming holds it again. Read through the hidden `player.awake` marker, which mirrors
/// `UIApplication.isIdleTimerDisabled` as TvPlayerScreen sets it from PlaybackKeepAwakePolicy.
///
/// Needs the guide fixture (`python3 tvosApp/Scripts/parity-guide-fixture.py`, port 18124) for a playable stream;
/// skips when it is not running.
final class KeepAwakeTests: XCTestCase {
    private let stream = "http://127.0.0.1:18124/stream/bbc-one.ts"
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        try XCTSkipUnless(reachable(stream), "start tvosApp/Scripts/parity-guide-fixture.py (port 18124)")
        app = XCUIApplication()
        app.launchArguments = ["-smokePlay", stream, "-smokePresent"]
        app.launch()
    }

    private func reachable(_ url: String) -> Bool {
        var ok = false
        let done = expectation(description: "HEAD")
        var request = URLRequest(url: URL(string: url)!)
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        URLSession.shared.dataTask(with: request) { _, response, _ in
            ok = ((response as? HTTPURLResponse)?.statusCode).map { $0 == 200 || $0 == 206 } ?? false
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 5)
        return ok
    }

    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }
    private func label(_ id: String) -> String { let e = el(id); return e.exists ? e.label : "<missing>" }
    private func waitUntil(_ timeout: TimeInterval, _ check: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if check() { return true }; Thread.sleep(forTimeInterval: 0.5) }
        return check()
    }

    func testPlayingHoldsTheScreenAndPauseReleasesIt() {
        XCTAssertTrue(el("player.now").waitForExistence(timeout: 30), "the player never appeared")
        XCTAssertTrue(waitUntil(60) { label("player.now").hasSuffix("|playing") }, "never played: \(label("player.now"))")
        XCTAssertEqual(label("player.awake"), "awake=1", "watching holds the screen awake")
        remote.press(.playPause)
        XCTAssertTrue(waitUntil(10) { label("player.awake") == "awake=0" }, "a pause lets the screen sleep: \(label("player.awake"))")
        Thread.sleep(forTimeInterval: 2)                  // the pause controls settle before the next press
        remote.press(.playPause)
        XCTAssertTrue(waitUntil(10) { label("player.now").hasSuffix("|playing") }, "Play/Pause resumes: \(label("player.now"))")
        XCTAssertTrue(waitUntil(10) { label("player.awake") == "awake=1" }, "resuming holds it again: \(label("player.awake"))")
    }
}
