import XCTest

/// Interactive Siri Remote driver for simulator passes (screenshot + D-pad, like `adb shell input keyevent` on Android TV).
/// Not a test: it skips unless `/tmp/tuvora-atv-driver/enabled` exists, so the normal suite never waits on it.
///
/// Protocol (driven by `tvosApp/Scripts/atv-remote.sh`): the script writes one command to `cmd`; the driver runs it,
/// writes the result to `out` and deletes `cmd`. Commands:
///   up | down | left | right | select | menu | playpause | home   [count]   press a remote button
///   longselect                                                         hold Select (context menus)
///   launch <args...>   (re)launch the app with launch arguments          terminate   stop the app
///   focus              label + identifier of the focused element
///   texts              every visible label on screen (one per line)
///   shot <path>        PNG screenshot of the simulator screen
///   wait <seconds>     sleep                                             stop        end the driver
final class RemoteDriverTests: XCTestCase {
    private let dir = "/tmp/tuvora-atv-driver"
    private let remote = XCUIRemote.shared
    private let app = XCUIApplication()

    func testDrive() throws {
        continueAfterFailure = true
        try XCTSkipUnless(FileManager.default.fileExists(atPath: "\(dir)/enabled"), "remote driver not enabled")
        let cmdPath = "\(dir)/cmd", outPath = "\(dir)/out"
        try? "ready".write(toFile: "\(dir)/state", atomically: true, encoding: .utf8)
        var deadline = Date().addingTimeInterval(4 * 3600)
        while Date() < deadline {
            guard let raw = try? String(contentsOfFile: cmdPath, encoding: .utf8), !raw.isEmpty else {
                Thread.sleep(forTimeInterval: 0.2); continue
            }
            try? FileManager.default.removeItem(atPath: cmdPath)
            let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ").map(String.init)
            let result = run(parts)
            try? result.write(toFile: outPath, atomically: true, encoding: .utf8)
            if parts.first == "stop" { break }
            deadline = Date().addingTimeInterval(4 * 3600)
        }
    }

    private func run(_ parts: [String]) -> String {
        guard let verb = parts.first else { return "error: empty command" }
        let buttons: [String: XCUIRemote.Button] = [
            "up": .up, "down": .down, "left": .left, "right": .right,
            "select": .select, "menu": .menu, "playpause": .playPause, "home": .home,
        ]
        if let button = buttons[verb] {
            let count = parts.count > 1 ? max(1, Int(parts[1]) ?? 1) : 1
            for _ in 0..<count { remote.press(button); Thread.sleep(forTimeInterval: 0.5) }
            Thread.sleep(forTimeInterval: 0.5)
            return "ok \(verb) x\(count); focus: \(focusLine())"
        }
        switch verb {
        case "longselect":
            remote.press(.select, forDuration: 1.2); Thread.sleep(forTimeInterval: 0.8)
            return "ok longselect; focus: \(focusLine())"
        case "launch":
            app.terminate()
            app.launchArguments = Array(parts.dropFirst())
            app.launch()
            Thread.sleep(forTimeInterval: 3)
            return "ok launched \(app.launchArguments.joined(separator: " ")); focus: \(focusLine())"
        case "terminate":
            app.terminate(); return "ok terminated"
        case "focus":
            return focusLine()
        case "texts":
            guard app.state == .runningForeground, let root = try? app.snapshot() else { return "app not in foreground (state \(app.state.rawValue))" }
            var seen = Set<String>(), lines: [String] = []
            func walk(_ n: XCUIElementSnapshot) {
                let l = n.label.trimmingCharacters(in: .whitespacesAndNewlines)
                if !l.isEmpty, n.frame.width > 0, seen.insert(l).inserted { lines.append((n.hasFocus ? "* " : "  ") + l) }
                n.children.forEach(walk)
            }
            walk(root)
            return lines.joined(separator: "\n")
        case "shot":
            let path = parts.count > 1 ? parts[1] : "\(dir)/shot.png"
            do { try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path)); return "ok \(path)" }
            catch { return "error: \(error)" }
        case "wait":
            Thread.sleep(forTimeInterval: Double(parts.count > 1 ? parts[1] : "1") ?? 1); return "ok"
        case "stop":
            return "ok stopping"
        default:
            return "error: unknown command \(verb)"
        }
    }

    /// One accessibility snapshot, walked in memory: never fails if the UI changes mid-read (per-element queries do).
    private func focusLine() -> String {
        guard app.state == .runningForeground, let root = try? app.snapshot() else { return "<app not in foreground>" }
        func find(_ n: XCUIElementSnapshot) -> XCUIElementSnapshot? {
            if n.hasFocus { return n }
            for c in n.children { if let f = find(c) { return f } }
            return nil
        }
        guard let f = find(root) else { return "<none>" }
        return f.identifier.isEmpty ? f.label : "\(f.label) [\(f.identifier)]"
    }
}
