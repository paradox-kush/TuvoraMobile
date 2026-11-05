import XCTest

/// Live pause / rewind on Apple TV, driven with the real Siri Remote (XCUIRemote): Play/Pause pauses a live
/// channel, resuming lands behind live ("−m:ss · LIVE"), the clickpad edges step further back, and selecting the
/// chip goes live. libmpv's own demuxer cache is the buffer, so this needs a live HLS source that keeps moving.
///
/// Needs the mock Stalker/IPTV server on this Mac (port 8899; `/live/u/p/1.m3u8` redirects to a moving test
/// pattern) and, for the guide path, the test M3U on :8901 (`live.m3u`: "Mock Live 1", "Mock Live 2" -> that
/// server). Both tests skip when their server is not running.
///
/// Two ways in, one session class (TvPlayerSession, which owns the live pause):
///  - the Live TV guide (`TvLiveTv.playChannel`): OK previews the channel docked, OK again plays it full screen;
///  - a direct presented player (`-smokePlay … -smokePresent -smokeLive`, the same PlaybackCoordinator path).
final class LivePauseRemoteTests: XCTestCase {
    private let stream = "http://127.0.0.1:8899/live/u/p/1.m3u8"
    private let playlist = "http://127.0.0.1:8901/live.m3u"
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    /// Full screen from the guide: a first launch adds the playlist (kept locally for the guest profile), a second
    /// opens the Live TV guide with `-smokeGuidePlay`, which previews the first channel and then takes it full screen.
    func testPauseRewindAndGoLiveFromTheLiveGuide() throws {
        try XCTSkipUnless(reachable(stream) && reachable(playlist), "start the mock live server (:8899) and the test M3U (:8901)")
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeAddM3uLater", "3", playlist]
        app.launch()
        XCTAssertTrue(app.buttons["hubchip.Live TV"].waitForExistence(timeout: 60), "the IPTV hub never appeared")
        Thread.sleep(forTimeInterval: 25)   // the playlist is added and its channels imported
        attach("0-playlist-added")
        app.terminate()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeGuidePlay"]
        app.launch()
        XCTAssertTrue(now.waitForExistence(timeout: 90), "the guide never took Mock Live 1 full screen")
        XCTAssertTrue(waitUntil(60) { now.label == "Mock Live 1|playing" }, "Mock Live 1 never played full screen: \(label(now))")
        try exerciseLivePause()
    }

    func testPauseRewindAndGoLiveInADirectPlayer() throws {
        try XCTSkipUnless(reachable(stream), "start the mock live server on :8899 (see the class comment)")
        app.launchArguments = ["-smokePlay", stream, "-smokePresent", "-smokeLive"]
        app.launch()
        XCTAssertTrue(now.waitForExistence(timeout: 30), "the player never appeared")
        XCTAssertTrue(waitUntil(60) { now.label == "Smoke test|playing" }, "the live channel never played: \(label(now))")
        try exerciseLivePause()
    }

    private var now: XCUIElement { el("player.now") }
    private var chip: XCUIElement { el("player.live.chip") }
    private var badge: XCUIElement { el("player.live.badge") }
    private var scrub: XCUIElement { el("player.live.scrub") }

    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }
    private func label(_ e: XCUIElement) -> String { e.exists ? e.label : "<missing>" }

    private var focusedId: String {
        let focused = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
        return focused.exists ? focused.identifier : "<none>"
    }

    private func press(_ button: XCUIRemote.Button, settle: TimeInterval = 1.0) {
        remote.press(button)
        Thread.sleep(forTimeInterval: settle)
    }

    private func waitUntil(_ timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return condition()
    }

    /// Seconds behind live from the chip ("−1:05 · LIVE" -> 65); 0 for a plain "LIVE"; nil when no chip is showing.
    private func offsetSeconds() -> Int? {
        guard chip.exists else { return nil }
        let text = chip.label
        guard text.hasPrefix("−") else { return 0 }
        let clock = text.dropFirst().split(separator: " ").first.map(String.init) ?? ""
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        return parts.reduce(0) { $0 * 60 + $1 }
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    private func exerciseLivePause() throws {
        // The live badge says a time shift is available (kind Live, not the plain unavailable badge).
        XCTAssertTrue(badge.waitForExistence(timeout: 15), "no LIVE badge over the live channel")
        attach("1-live")

        // Play/Pause pauses: the player reads "waiting", the chip row appears (paused at the edge).
        press(.playPause)
        XCTAssertTrue(waitUntil(8) { now.label.hasSuffix("|waiting") }, "Play/Pause did not pause live: \(label(now))")
        XCTAssertTrue(chip.waitForExistence(timeout: 8), "no LIVE chip after pausing; badge=\(label(badge))")
        attach("2-paused")

        // 20 s paused, then resume: the chip reads how far behind live the viewer now is.
        Thread.sleep(forTimeInterval: 20)
        press(.playPause)
        XCTAssertTrue(waitUntil(15) { now.label.hasSuffix("|playing") }, "Play/Pause did not resume: \(label(now))")
        XCTAssertTrue(waitUntil(6) { (offsetSeconds() ?? 0) > 0 && chip.label.hasPrefix("−") },
                      "after a 20 s pause the chip should show an offset, got \(label(chip))")
        let afterResume = offsetSeconds() ?? 0
        XCTAssertGreaterThanOrEqual(afterResume, 10, "offset after a 20 s pause was only \(afterResume) s (\(label(chip)))")
        attach("3-behind-live")

        // Two clickpad-edge presses over bare video step further back.
        Thread.sleep(forTimeInterval: 7)   // controls auto-hide; the edges only step over bare video
        press(.left, settle: 1.5)
        let afterFirst = offsetSeconds() ?? -1
        let focusAfterFirst = focusedId
        attach("4a-after-first-left")
        press(.left, settle: 2.5)
        XCTAssertTrue(chip.waitForExistence(timeout: 5), "the chip vanished after stepping back")
        let afterSteps = offsetSeconds() ?? 0
        XCTAssertGreaterThan(afterSteps, afterResume + 8, "two LEFT steps did not rewind further (\(afterResume) -> first \(afterFirst) with focus on \(focusAfterFirst) -> \(afterSteps), focus now \(focusedId))")
        attach("4-stepped-back")

        // The scrub bar and the chip are focusable and visibly highlighted when focused.
        var tries = 0
        while focusedId != "player.live.chip" && tries < 6 { press(.down, settle: 0.6); tries += 1 }
        if focusedId != "player.live.chip" { for _ in 0..<4 where focusedId != "player.live.chip" { press(.up, settle: 0.6) } }
        XCTAssertEqual(focusedId, "player.live.chip", "could not focus the LIVE chip; focus is on \(focusedId)")
        attach("5-chip-focused")
        press(.up, settle: 0.8)
        XCTAssertEqual(focusedId, "player.live.scrub", "UP from the chip should focus the scrub bar, got \(focusedId)")
        attach("6-scrub-focused")
        press(.down, settle: 0.8)
        XCTAssertEqual(focusedId, "player.live.chip")

        // Selecting the chip goes live: no more offset, the plain LIVE badge is back.
        press(.select, settle: 1.0)
        XCTAssertTrue(waitUntil(15) { badge.exists && !chip.exists },
                      "Select on the chip did not go live (chip=\(label(chip)) badge=\(label(badge)))")
        XCTAssertEqual(label(badge), "LIVE")
        XCTAssertTrue(waitUntil(15) { now.label.hasSuffix("|playing") }, "playback did not continue at live: \(label(now))")
        attach("7-live-again")
    }

    private func reachable(_ url: String) -> Bool {
        let request = URLRequest(url: URL(string: url)!, timeoutInterval: 3)
        let done = DispatchSemaphore(value: 0)
        var ok = false
        URLSession.shared.dataTask(with: request) { _, response, _ in
            ok = (response as? HTTPURLResponse)?.statusCode == 200
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 4)
        return ok
    }
}
