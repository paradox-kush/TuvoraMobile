import XCTest

/// Telemetry v2 on Apple TV, driven with the Siri Remote (XCUIRemote). SIMULATOR ONLY: this proves the screens,
/// the focus targets and the Kotlin port's outcomes on the tvOS simulator, not a real Apple TV or the network.
/// The player-error tests need nothing running: they play an address that refuses the connection.
final class TelemetryReportTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private let codePattern = "[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}"

    override func setUp() { continueAfterFailure = false }

    private func launch(_ args: [String], consent: Bool = true) {
        app = XCUIApplication()
        app.launchArguments = ["-smokeDiagnostics", consent ? "true" : "false"] + args
        app.launch()
    }

    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.8) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
    private func focused(_ e: XCUIElement) -> Bool { e.exists && e.hasFocus }
    private func text(_ e: XCUIElement) -> String { e.label.isEmpty ? ((e.value as? String) ?? "") : e.label }
    private func matches(_ s: String, _ pattern: String) -> Bool { s.range(of: pattern, options: .regularExpression) != nil }

    private var focusedElement: XCUIElement { app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch }

    /// Moves focus to `target` with the remote, the way a viewer would (rail -> right, then by geometry).
    private func focus(_ target: XCUIElement) {
        XCTAssertTrue(target.waitForExistence(timeout: 40), "Target never appeared: \(target)")
        for _ in 0..<30 {
            guard target.exists else { break }
            if target.hasFocus { return }
            let from = focusedElement
            guard from.exists else { remote.press(.down); continue }
            let dx = target.frame.midX - from.frame.midX, dy = target.frame.midY - from.frame.midY
            if from.identifier.hasPrefix("settings.rail.") && !target.identifier.hasPrefix("settings.rail.") { remote.press(.right) }
            else if abs(dy) > 20 { remote.press(dy > 0 ? .down : .up) }
            else { remote.press(dx > 0 ? .right : .left) }
            Thread.sleep(forTimeInterval: 0.5)
        }
        let dump = app.buttons.allElementsBoundByIndex.map { "\($0.identifier)@\(Int($0.frame.midX)),\(Int($0.frame.midY))\($0.hasFocus ? "*" : "")" }.joined(separator: " ")
        XCTFail("Could not focus \(target.identifier); focused=\(focusedElement.identifier); buttons: \(dump); target frame \(target.frame)")
    }

    private func waitForFocus(_ e: XCUIElement, timeout: TimeInterval = 20, _ message: String) {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if focused(e) { return }; Thread.sleep(forTimeInterval: 0.4) }
        XCTFail(message)
    }

    private func waitForText(_ e: XCUIElement, containing needle: String, timeout: TimeInterval = 20) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if e.exists, text(e).contains(needle) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }

    private let refused = ["-smokePlay", "http://127.0.0.1:1/refused.mkv"]

    /// Settings -> About shows the per-install code, XXXX-XXXX, from the unambiguous alphabet.
    func testAboutShowsTheSupportCode() {
        launch(["-smokeTab", "settings", "-smokeSettings", "about"])
        let code = el("about.supportCode")
        XCTAssertTrue(code.waitForExistence(timeout: 30), "no support code row in About")
        XCTAssertTrue(matches(text(code), "^" + codePattern + "$"), "bad support code: \(text(code))")
    }

    /// The code is made once per install: a relaunch shows the same one.
    func testTheSupportCodeSurvivesARelaunch() {
        launch(["-smokeTab", "settings", "-smokeSettings", "about"])
        let first = text(el("about.supportCode"))
        XCTAssertTrue(matches(first, "^" + codePattern + "$"))
        app.terminate()
        launch(["-smokeTab", "settings", "-smokeSettings", "about"])
        XCTAssertTrue(el("about.supportCode").waitForExistence(timeout: 30))
        XCTAssertEqual(text(el("about.supportCode")), first)
    }

    /// Error card: Retry has initial focus, Send report is to its right, OK sends, the card says the code,
    /// and Back leaves the player.
    func testErrorCardSendsAReportAndBackExits() {
        launch(refused)
        let retry = el("player.error.retry")
        XCTAssertTrue(retry.waitForExistence(timeout: 45), "no error card")
        waitForFocus(retry, "Retry did not take initial focus")
        press(.right)
        let send = el("player.error.send")
        XCTAssertTrue(focused(send), "Send report is not the next focusable to the right of Retry")
        press(.select, settle: 1.5)
        let state = el("player.error.reportState")
        XCTAssertTrue(waitForText(state, containing: "Report sent. Your support code:"), "no confirmation: \(text(state))")
        XCTAssertTrue(matches(text(state), codePattern), "confirmation lacks the code: \(text(state))")
        // The sent state still has a focus target (the sent pill), and Retry is one press away.
        XCTAssertTrue(focused(send) || focused(retry), "focus lost after sending")
        press(.menu, settle: 2)
        XCTAssertFalse(el("player.error.retry").exists, "Back did not leave the error card")
    }

    /// A second report inside two minutes says to wait, and is not shown as sent.
    func testSecondReportIsRateLimited() {
        launch(refused)
        XCTAssertTrue(el("player.error.retry").waitForExistence(timeout: 45))
        press(.right); press(.select, settle: 1.5)
        XCTAssertTrue(waitForText(el("player.error.reportState"), containing: "Report sent"))
        press(.left)                       // Retry
        press(.select, settle: 6)          // refused again -> the card is back, flow reset
        XCTAssertTrue(el("player.error.retry").waitForExistence(timeout: 45))
        press(.right); press(.select, settle: 1.5)
        let state = el("player.error.reportState")
        XCTAssertTrue(waitForText(state, containing: "Please wait a moment"), "no rate-limit message: \(text(state))")
        XCTAssertFalse(text(state).contains("Report sent"))
    }

    /// Settings -> Diagnostics: pick a category, see the confirmation with the code, Close returns focus to the row.
    func testSettingsReportFlowWithCategoryPicker() {
        launch(["-smokeTab", "settings", "-smokeSettings", "diagnostics"])
        let row = el("diagnostics.sendReport")
        XCTAssertTrue(row.waitForExistence(timeout: 30), "no Send a report row")
        focus(row)
        press(.select, settle: 1)          // category picker (focus starts on the first category)
        press(.select, settle: 1.5)        // Won't play -> sends
        let dialog = el("report.dialog")
        XCTAssertTrue(dialog.waitForExistence(timeout: 10), "no report dialog")
        let sent = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Report sent. Your support code:'")).firstMatch
        XCTAssertTrue(sent.waitForExistence(timeout: 20), "no confirmation in the dialog")
        XCTAssertTrue(focused(el("report.close")), "Close has no focus")
        press(.select, settle: 1)
        XCTAssertFalse(dialog.exists)
        waitForFocus(row, "Close did not return focus to the Send a report row")
    }

    /// Consent off: nothing is sent, the dialog says so and offers the shortcut; the shortcut turns reports on and sends.
    func testConsentOffExplainsAndTheShortcutTurnsItOn() {
        launch(["-smokeTab", "settings", "-smokeSettings", "diagnostics"], consent: false)
        let row = el("diagnostics.sendReport")
        XCTAssertTrue(row.waitForExistence(timeout: 30))
        focus(row)
        press(.select, settle: 1); press(.select, settle: 1.5)
        let msg = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Turn on Crash & usage reports'")).firstMatch
        XCTAssertTrue(msg.waitForExistence(timeout: 15), "consent-off message missing")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Report sent'")).firstMatch.exists, "consent off was shown as sent")
        XCTAssertTrue(el("report.close").exists, "no focus target besides the shortcut")
        let turnOn = el("report.turnOn")
        XCTAssertTrue(turnOn.exists)
        waitForFocus(turnOn, "the shortcut does not take focus")
        XCTAssertFalse(focused(el("report.close")), "focus stayed on Close")
        press(.select, settle: 1.5)
        let sent = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Report sent. Your support code:'")).firstMatch
        XCTAssertTrue(sent.waitForExistence(timeout: 20), "turning reports on from the dialog did not send")
    }

    private let mkv = "http://127.0.0.1:8093/vod_h264_ac3.mkv"

    private func reachable(_ url: String) -> Bool {
        var request = URLRequest(url: URL(string: url)!, timeoutInterval: 3)
        request.httpMethod = "HEAD"
        let done = DispatchSemaphore(value: 0)
        var ok = false
        URLSession.shared.dataTask(with: request) { _, response, _ in ok = (response as? HTTPURLResponse)?.statusCode == 200; done.signal() }.resume()
        _ = done.wait(timeout: .now() + 4)
        return ok
    }

    /// Review finding 1: a stream that PLAYS and then fails with the controls hidden. The error card's buttons must
    /// take the remote (Select and the edges), not retry / skip. Needs the VOD range server (research/tvos-spike).
    func testErrorCardAppearingMidPlayWithHiddenControlsIsReachable() throws {
        try XCTSkipUnless(reachable(mkv), "start the range server: cd research/tvos-spike/media && python3 ../range_server.py 8093")
        launch(["-smokePlay", mkv, "-smokePresent", "-smokeFailAfter", "16"])   // presented path = the UIKit press recognizers
        let now = el("player.now")
        XCTAssertTrue(now.waitForExistence(timeout: 30))
        let end = Date().addingTimeInterval(45)
        while Date() < end, !now.label.hasSuffix("|playing") { Thread.sleep(forTimeInterval: 1) }
        XCTAssertTrue(now.label.hasSuffix("|playing"), "never played: \(now.label)")
        let retry = el("player.error.retry")
        XCTAssertTrue(retry.waitForExistence(timeout: 40), "the failure card never appeared")
        XCTAssertFalse(el("player.sendReport").exists, "the controls were still showing; the test needs them hidden")
        waitForFocus(retry, "Retry did not take initial focus over hidden controls")
        press(.right)
        let send = el("player.error.send")
        XCTAssertTrue(focused(send), "Right did not move focus from Retry to Send report (the edge was taken as skip)")
        press(.select, settle: 1.5)
        let state = el("player.error.reportState")
        XCTAssertTrue(waitForText(state, containing: "Report sent. Your support code:"), "Select did not send: \(text(state))")
    }

    /// Review finding 3: a sent report must not stick to the next channel after a zap. Needs two live relays:
    /// cd research/tvos-spike && python3 live_relay.py 8941 h264 & python3 live_relay.py 8942 h264 &
    /// Each channel plays, then fails (-smokeFailAfter) with the controls hidden, so Up zaps.
    func testSentReportDoesNotFollowTheNextChannel() throws {
        let first = "http://127.0.0.1:8941/live.ts", second = "http://127.0.0.1:8942/live.ts"
        try XCTSkipUnless(reachable(first) && reachable(second), "start the two live relays (see the comment)")
        launch(["-smokePlay", first, "-smokeZapTo", second, "-smokeFailAfter", "16"])
        let retry = el("player.error.retry")
        XCTAssertTrue(retry.waitForExistence(timeout: 60), "the first channel never failed")
        press(.right); press(.select, settle: 1.5)
        XCTAssertTrue(waitForText(el("player.error.reportState"), containing: "Report sent"))
        Thread.sleep(forTimeInterval: 8)           // the presses above raised the controls; Up zaps only over hidden ones
        press(.up, settle: 1)
        XCTAssertTrue(waitForText(el("player.now"), containing: "Smoke zap", timeout: 30), "the zap never happened: \(text(el("player.now")))")
        XCTAssertTrue(retry.waitForExistence(timeout: 60), "the second channel never failed")
        waitForFocus(el("player.error.retry"), "Retry did not take focus on the next channel")
        let send = el("player.error.send")
        XCTAssertEqual(text(send), "Send report", "the next channel inherited the old report state")
        XCTAssertFalse(el("player.error.reportState").exists, "the next channel shows the old report line")
    }

    /// Review finding 2: pressing the controls' flag button with reports off says so and offers the shortcut
    /// (`-smokeSendReport` presses it for us: the simulator's focus engine will not walk to a control over live video).
    func testFlagButtonExplainsConsentOffAndOffersTheShortcut() throws {
        try XCTSkipUnless(reachable(mkv), "start the range server")
        launch(["-smokePlay", mkv, "-smokePresent", "-smokeSendReport", "10"], consent: false)
        let state = el("player.reportState")
        XCTAssertTrue(waitForText(state, containing: "Turn on Crash & usage reports", timeout: 60), "consent-off not shown: \(text(state))")
        XCTAssertTrue(el("player.report.turnOn").exists, "no consent shortcut next to the message")
    }

    /// The same with reports on: the controls say it was sent, with the code.
    func testFlagButtonConfirmsWithTheCode() throws {
        try XCTSkipUnless(reachable(mkv), "start the range server")
        launch(["-smokePlay", mkv, "-smokePresent", "-smokeSendReport", "10"])
        let state = el("player.reportState")
        XCTAssertTrue(waitForText(state, containing: "Report sent. Your support code:", timeout: 60), "no confirmation: \(text(state))")
    }
}
