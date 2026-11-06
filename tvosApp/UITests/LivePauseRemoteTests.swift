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
        waitForCleanStreamPhase()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeAddM3uLater", "3", playlist]
        app.launch()
        XCTAssertTrue(app.buttons["hubchip.Live TV"].waitForExistence(timeout: 60), "the IPTV hub never appeared")
        Thread.sleep(forTimeInterval: 25)   // the playlist is added and its channels imported
        attach("0-playlist-added")
        app.terminate()
        app.launchArguments = ["-smokeLocalGuest", "-smokePickProfile", "1", "-smokeTab", "iptv", "-smokeGuidePlay"]
        app.launch()
        XCTAssertTrue(now.waitForExistence(timeout: 90), "the guide never took Mock Live 1 full screen")
        // The player's title is the stream's own ("Test Pattern"), not the playlist's channel name.
        XCTAssertTrue(waitUntil(60) { now.label.hasSuffix("|playing") }, "Mock Live 1 never played full screen: \(label(now))")
        try exerciseLivePause()
    }

    func testPauseRewindAndGoLiveInADirectPlayer() throws {
        try XCTSkipUnless(reachable(stream), "start the mock live server on :8899 (see the class comment)")
        waitForCleanStreamPhase()
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

    /// Identifier of the focused element; an ancestor that is focused too (a focus section, the host view) carries
    /// no identifier, so the first focused element WITH one wins. "<none>" when nothing is focused, "<unnamed:…>"
    /// (with the element types) when only unidentified elements are.
    private var focusedId: String {
        let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).allElementsBoundByIndex
        if focused.isEmpty { return "<none>" }
        if let named = focused.first(where: { !$0.identifier.isEmpty }) { return named.identifier }
        return "<unnamed:" + focused.map { "\($0.elementType.rawValue)" }.joined(separator: ",") + ">"
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

    /// Seconds the playhead sits into the window the engine holds, from the scrub bar's label ("−1:17 34": the
    /// window's length, then how far along it the playhead is). This, not the chip, is what a step moves: the chip
    /// counts back from the END of mpv's cache, which stops short of live while it is throttled while paused and
    /// then catches up, so "seconds behind live" can shrink while the viewer steps back. Nil without a scrub bar.
    private func playheadSeconds() -> Double? {
        guard scrub.exists else { return nil }
        let parts = scrub.label.split(separator: " ").map(String.init)
        guard parts.count >= 2, let pct = Double(parts[parts.count - 1]) else { return nil }
        let clock = parts[parts.count - 2].dropFirst().split(separator: ":").compactMap { Int($0) }
        let reach = clock.reduce(0) { $0 * 60 + $1 }
        return Double(reach) * pct / 100
    }

    /// One line of the shift's state for the log: the chip, the scrub bar's label (reach text + percent) and, from
    /// both, the window length the engine holds (offset / (1 - progress)), so a clamped step is visible as such.
    private func snap(_ tag: String) {
        let chipText = label(chip), scrubText = label(scrub)
        var window = "?"
        if let o = offsetSeconds(), let pct = scrubText.split(separator: " ").last.flatMap({ Double($0) }), pct < 100 {
            window = String(format: "%.0f s", Double(o) / (1 - pct / 100))
        }
        let line = "LPSNAP \(tag): chip=\(chipText) scrub=\(scrubText) window~\(window) focus=\(focusedId) now=\(label(now))"
        NSLog("%@", line)
        let note = XCTAttachment(string: line)
        note.name = "snap-\(tag)"
        note.lifetime = .keepAlways
        add(note)
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
        snap("paused-20s")
        press(.playPause)
        XCTAssertTrue(waitUntil(15) { now.label.hasSuffix("|playing") }, "Play/Pause did not resume: \(label(now))")
        XCTAssertTrue(waitUntil(6) { (offsetSeconds() ?? 0) > 0 && chip.label.hasPrefix("−") },
                      "after a 20 s pause the chip should show an offset, got \(label(chip))")
        let afterResume = offsetSeconds() ?? 0
        XCTAssertGreaterThanOrEqual(afterResume, 10, "offset after a 20 s pause was only \(afterResume) s (\(label(chip)))")
        attach("3-behind-live")
        snap("resumed")

        // Clickpad-edge presses over bare video step further back (three in all: the first opens the scrub bar from live). The past the engine holds starts at the
        // pause point (plus the few seconds mpv keeps behind the playhead), and resuming plays on from there, so the
        // viewer must have watched on for a while before there is room for two 10 s steps: wait until the playhead
        // is ~35 s into the window (-> the three steps end ~25, ~17 and ~9 s in: none clamps at the window start).
        // Waiting also lets the controls auto-hide; the edges only step over bare video.
        Thread.sleep(forTimeInterval: 30)
        snap("before-steps")
        press(.left, settle: 1.0)   // from live-over-bare-video: opens the scrub bar and steps back
        XCTAssertTrue(scrub.waitForExistence(timeout: 5), "the scrub bar did not open on the first LEFT (focus=\(focusedId))")
        // Every further LEFT must land: the playhead moves 10 s back each time, less the ~1-2 s it plays on between
        // the presses (so >= 5 s here). A dropped second step is exactly this failing.
        var positions: [Double] = []
        for step in 1...2 {
            guard let p = playheadSeconds() else { XCTFail("no scrub bar before LEFT #\(step)"); return }
            positions.append(p)
            press(.left, settle: 1.0)
        }
        guard let last = playheadSeconds() else { XCTFail("no scrub bar after the last LEFT"); return }
        positions.append(last)
        NSLog("LPSNAP playhead into window per LEFT (s): \(positions)")
        for i in 1..<positions.count {
            XCTAssertLessThanOrEqual(positions[i], positions[i - 1] - 5,
                                     "LEFT #\(i + 1) did not land: playhead \(positions[i - 1]) -> \(positions[i]) s into the window (all: \(positions))")
        }

        // The scrub bar and the chip are focusable and visibly highlighted when focused. Move focus while the chrome
        // is still up (every press keeps it up for 4 s): over bare video DOWN opens the tracks panel (or zaps), it
        // does not reveal the controls, so no snapshots before this is done.
        var tries = 0
        while focusedId != "player.live.chip" && tries < 3 { press(.down, settle: 0.6); tries += 1 }
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

    /// The mock portal's live stream replays its 61 ten-second segments in a loop and the timeline jumps at every
    /// wrap (a discontinuity: mpv then reports a seekable range of hours, "−26:21:27 · LIVE", and the playhead sits
    /// outside it, so no step can move). A run needs ~3 minutes of clean timeline, so it starts just after a wrap
    /// (no wrap for the next 5 minutes) instead of at whatever phase the loop happens to be in.
    private func waitForCleanStreamPhase() {
        let loop = 61, runwaySegments = 30
        let request = URLRequest(url: URL(string: stream)!, timeoutInterval: 3)
        let done = DispatchSemaphore(value: 0)
        var sequence: Int?
        URLSession.shared.dataTask(with: request) { data, _, _ in
            if let text = data.flatMap({ String(data: $0, encoding: .utf8) }),
               let line = text.split(separator: "\n").first(where: { $0.hasPrefix("#EXT-X-MEDIA-SEQUENCE:") }) {
                sequence = Int(line.dropFirst("#EXT-X-MEDIA-SEQUENCE:".count))
            }
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 4)
        guard let sequence else { return }
        let phase = sequence % loop
        guard phase > loop - runwaySegments - 4 else { return }   // a wrap is not within the next ~5 minutes
        let wait = Double(loop - phase + 1) * 10
        NSLog("LPSNAP the mock stream wraps in %.0f s; waiting for it (phase %d of %d)", wait - 10, phase, loop)
        Thread.sleep(forTimeInterval: wait)
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
