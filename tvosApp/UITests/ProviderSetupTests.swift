import XCTest

/// Step 2 on Apple TV: the playlist details page, hold-to-confirm, the contact dialog and the setup-code
/// screen, driven by the Siri Remote (XCUIRemote). Needs the simulator signed in to the LOCAL test backend
/// (the app is built with SUPABASE_URL pointing at it) with a managed playlist "Starshare Live" on profile 1
/// and an unmanaged one ("Plain List") - see the lane notes. Nothing here touches a real account.
final class ProviderSetupTests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(_ dialog: String, account: String? = nil, extra: [String] = []) {
        var args = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations", "-smokeSettingsDialog", dialog]
        if let account { args += ["-smokeAccount", account] }
        app.launchArguments = args + extra
        app.launch()
    }

    private var focused: XCUIElement {
        app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
    }
    private func focusedId() -> String { focused.exists ? (focused.identifier.isEmpty ? "#" + focused.label : focused.identifier) : "<none>" }
    private func press(_ b: XCUIRemote.Button, settle: TimeInterval = 0.7) { remote.press(b); Thread.sleep(forTimeInterval: settle) }
    private func waitFocus(_ timeout: TimeInterval = 40, _ match: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if match(focusedId()) { return true }; Thread.sleep(forTimeInterval: 0.4) }
        return false
    }
    private func label(_ id: String) -> String { app.descendants(matching: .any)[id].exists ? app.descendants(matching: .any)[id].label : "" }

    // MARK: details page

    /// A managed playlist: facts on the left (never focusable), STARSHARE / YOUR LIBRARY / REMOVE shelves on the
    /// right; focus starts on Contact; Up/Down changes shelf and returns to the card that shelf last had.
    func testManagedDetailsShelvesAndFocusMemory() {
        launch("playlistDetails", account: "Starshare Live")
        XCTAssertTrue(waitFocus { $0 == "details.card.contact" }, "the page did not start on the first card; focus \(focusedId())")
        XCTAssertTrue(app.staticTexts["details.expiry"].exists || app.descendants(matching: .any)["details.expiry"].exists, "no expiry line")
        XCTAssertTrue(app.descendants(matching: .any)["details.locked"].exists, "a managed playlist has no lock note for server and login")
        XCTAssertFalse(app.buttons["details.card.edit"].exists, "a managed playlist offers Edit URL / credentials")

        press(.down)
        XCTAssertEqual(focusedId(), "details.card.content", "Down from the provider shelf should land on the first library card")
        press(.right); press(.right)
        XCTAssertEqual(focusedId(), "details.card.toggle")
        press(.down)
        XCTAssertEqual(focusedId(), "details.card.detach", "Down from the library shelf should land on the first remove card")
        press(.up)
        XCTAssertEqual(focusedId(), "details.card.toggle", "Up should return to the card the library shelf last had, not the nearest one")
        press(.up)
        XCTAssertEqual(focusedId(), "details.card.contact")
        press(.up)
        XCTAssertEqual(focusedId(), "details.card.contact", "Up from the first shelf must stay put")
        XCTAssertNotEqual(focusedId(), "details.expiry", "a read-only fact took focus")
    }

    /// The contact dialog is text + a QR per contact the provider set (Apple TV cannot open WhatsApp).
    func testContactOpensQrDialog() {
        launch("playlistDetails", account: "Starshare Live")
        XCTAssertTrue(waitFocus { $0 == "details.card.contact" }, "focus \(focusedId())")
        press(.select, settle: 1.5)
        XCTAssertTrue(app.descendants(matching: .any)["contact.telegram"].waitForExistence(timeout: 5), "no Telegram contact")
        XCTAssertTrue(app.descendants(matching: .any)["contact.email"].exists, "no Email contact")
        XCTAssertTrue(app.images.count > 0, "no QR image on the contact dialog")
        XCTAssertEqual(focusedId(), "contact.close")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus { $0 == "details.card.contact" }, "focus did not come back to Contact; on \(focusedId())")
    }

    /// Detach: the dialog opens on Cancel; a quick press on the destructive button does nothing.
    func testDetachNeedsAHoldAndQuickPressDoesNothing() {
        launch("playlistDetails", account: "Starshare Live")
        XCTAssertTrue(waitFocus { $0 == "details.card.contact" }, "focus \(focusedId())")
        press(.down); press(.down)
        XCTAssertEqual(focusedId(), "details.card.detach")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus(10) { $0 == "confirm.cancel" }, "the confirmation did not start on Cancel; focus \(focusedId())")
        press(.down)
        XCTAssertEqual(focusedId(), "confirm.hold.detach")
        XCTAssertEqual(app.descendants(matching: .any)["confirm.hold.detach"].value as? String, "Hold OK for 2 seconds")
        press(.select, settle: 1.5)   // a quick press
        XCTAssertTrue(app.descendants(matching: .any)["confirm.hold.detach"].exists, "a quick press closed the confirmation")
        XCTAssertEqual(app.descendants(matching: .any)["confirm.hold.detach"].value as? String, "Keep holding OK until the ring is full",
                       "an early release did not say to keep holding")
        XCTAssertTrue(app.descendants(matching: .any)["details.card.detach"].exists, "a quick press detached the playlist")
        press(.up)
        press(.select, settle: 1.5)   // Cancel
        XCTAssertFalse(app.descendants(matching: .any)["confirm.hold.detach"].exists, "Cancel left the dialog open")
    }

    /// Remove: holding OK for 2.6 s confirms; the page goes with the playlist.
    func testRemoveHoldConfirms() {
        launch("playlistDetails", account: "Plain List")
        XCTAssertTrue(waitFocus { $0.hasPrefix("details.card.") }, "focus \(focusedId())")
        XCTAssertTrue(app.descendants(matching: .any)["details.card.edit"].exists, "an unmanaged playlist lost Edit URL / credentials")
        XCTAssertFalse(app.descendants(matching: .any)["details.card.detach"].exists, "an unmanaged playlist offers Detach")
        for _ in 0..<4 where focusedId() != "details.card.remove" { press(.down) }
        XCTAssertEqual(focusedId(), "details.card.remove")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus(10) { $0 == "confirm.cancel" }, "focus \(focusedId())")
        press(.down)
        XCTAssertEqual(focusedId(), "confirm.hold.remove")
        remote.press(.select, forDuration: 0.6)   // let go early: nothing happens
        Thread.sleep(forTimeInterval: 1)
        XCTAssertTrue(app.descendants(matching: .any)["confirm.hold.remove"].exists, "letting go early confirmed")
        remote.press(.select, forDuration: 2.8)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(app.descendants(matching: .any)["confirm.hold.remove"].exists, "holding OK for 2.8 s did not confirm")
        XCTAssertFalse(app.buttons["playlist.row.Plain List"].exists, "the removed playlist is still listed")
    }

    /// Detach: holding OK for 2.8 s detaches; the page then has no provider shelf, no Detach card and no lock note.
    func testDetachHoldConfirms() {
        launch("playlistDetails", account: "Starshare Movies")
        XCTAssertTrue(waitFocus { $0.hasPrefix("details.card.") }, "focus \(focusedId())")
        XCTAssertTrue(app.descendants(matching: .any)["details.card.detach"].exists, "the managed playlist has no Detach")
        for _ in 0..<4 where focusedId() != "details.card.detach" { press(.down) }
        XCTAssertEqual(focusedId(), "details.card.detach")
        press(.select, settle: 1.5)
        XCTAssertTrue(waitFocus(10) { $0 == "confirm.cancel" }, "focus \(focusedId())")
        press(.down)
        XCTAssertEqual(focusedId(), "confirm.hold.detach")
        remote.press(.select, forDuration: 2.8)
        Thread.sleep(forTimeInterval: 6)
        XCTAssertFalse(app.descendants(matching: .any)["confirm.hold.detach"].exists, "holding OK for 2.8 s did not confirm")
        XCTAssertFalse(app.descendants(matching: .any)["details.card.detach"].waitForExistence(timeout: 20), "the playlist is still detachable after Detach")
        XCTAssertFalse(app.descendants(matching: .any)["details.locked"].exists, "a detached playlist still shows the lock note")
    }

    // MARK: settings list

    func testManagedRowSaysWhoManagesIt() {
        app.launchArguments = ["-smokePickProfile", "1", "-smokeTab", "settings", "-smokeSettings", "integrations", "-smokeIntegration", "iptv"]
        app.launch()
        let row = app.buttons["playlist.row.Starshare Live"]
        XCTAssertTrue(row.waitForExistence(timeout: 40), "the managed playlist is not listed")
        XCTAssertTrue(row.label.contains("Managed by Starshare ATV Lane"), "row says: \(row.label)")
        let plain = app.buttons["playlist.row.Plain List"]
        if plain.exists { XCTAssertFalse(plain.label.contains("Managed by"), "an unmanaged row says it is managed") }
    }

    // MARK: setup code screen

    private let alphabet = Array("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
    private func keyPosition(_ c: Character) -> (row: Int, col: Int) {
        let i = alphabet.firstIndex(of: c)!; return (i / 8, i % 8)
    }
    private func currentKey() -> Character? {
        let id = focusedId()
        guard id.hasPrefix("setup.key."), let c = id.dropFirst("setup.key.".count).first else { return nil }
        return c
    }
    private func moveToKey(_ target: Character) -> Bool {
        for _ in 0..<24 {
            guard let cur = currentKey() else { return false }
            if cur == target { return true }
            let a = keyPosition(cur), b = keyPosition(target)
            if a.row < b.row { press(.down, settle: 0.35) } else if a.row > b.row { press(.up, settle: 0.35) }
            else if a.col < b.col { press(.right, settle: 0.35) } else { press(.left, settle: 0.35) }
        }
        return currentKey() == target
    }

    /// Type a code with the keypad, preview it, add it: lands on the new playlist's details with the banner.
    func testTypeCodeWithKeypadThenAdd() throws {
        let code = try XCTUnwrap(ProcessInfo.processInfo.environment["ATV_TYPED_CODE"] ?? UserDefaults.standard.string(forKey: "ATV_TYPED_CODE"), "pass the code through ATV_TYPED_CODE")
        launch("setupCode")
        XCTAssertTrue(waitFocus { $0 == "setup.key.A" }, "the code screen did not start on the first key; focus \(focusedId())")
        let digits = code.replacingOccurrences(of: "TUV-", with: "").replacingOccurrences(of: "-", with: "")
        for ch in digits {
            XCTAssertTrue(moveToKey(ch), "could not reach key \(ch); focus \(focusedId())")
            press(.select, settle: 0.35)
        }
        XCTAssertEqual(label("setup.code"), code, "the boxes do not show the typed code")
        // down to Continue
        // Where DOWN lands depends on the column of the last key typed: Delete, Continue, or (past a
        // disabled-looking gap) the text field. Step down into the action row, then correct sideways/up.
        for _ in 0..<3 where !["setup.continue", "setup.delete", "setup.field"].contains(focusedId()) { press(.down, settle: 0.4) }
        if focusedId() == "setup.field" { press(.up, settle: 0.4) }
        for _ in 0..<2 where focusedId() != "setup.continue" { press(.right, settle: 0.4) }
        XCTAssertEqual(focusedId(), "setup.continue")
        press(.select, settle: 3)
        XCTAssertTrue(app.descendants(matching: .any)["setup.provider"].waitForExistence(timeout: 20), "no preview")
        XCTAssertTrue(waitFocus(10) { $0 == "setup.add" }, "the preview did not focus Add; on \(focusedId())")
        press(.select, settle: 6)
        XCTAssertTrue(app.descendants(matching: .any)["details.banner"].waitForExistence(timeout: 40), "did not land on the details page with the banner")
    }

    /// An expired code names its cause and offers the provider's contacts.
    func testExpiredCodeShowsContacts() throws {
        let code = try XCTUnwrap(ProcessInfo.processInfo.environment["ATV_EXPIRED_CODE"] ?? UserDefaults.standard.string(forKey: "ATV_EXPIRED_CODE"))
        launch("setupCode")
        XCTAssertTrue(waitFocus { $0 == "setup.key.A" }, "focus \(focusedId())")
        let digits = code.replacingOccurrences(of: "TUV-", with: "").replacingOccurrences(of: "-", with: "")
        for ch in digits { XCTAssertTrue(moveToKey(ch)); press(.select, settle: 0.35) }
        for _ in 0..<6 where focusedId() != "setup.continue" { press(.down, settle: 0.4) }
        for _ in 0..<2 where focusedId() != "setup.continue" { press(.right, settle: 0.4) }
        press(.select, settle: 3)
        let problem = app.descendants(matching: .any)["setup.problem"]
        XCTAssertTrue(problem.waitForExistence(timeout: 20), "no problem line")
        XCTAssertTrue(problem.label.contains("expired"), "problem says: \(problem.label)")
    }

    /// The phone route: the screen sits on the code entry; a redeem from outside finishes it by itself.
    func testFinishesByItselfWhenAPhoneRedeems() {
        launch("setupCode")
        XCTAssertTrue(waitFocus { $0 == "setup.key.A" }, "focus \(focusedId())")
        XCTAssertTrue(app.descendants(matching: .any)["setup.account"].exists, "the screen does not say which account it is adding to")
        XCTAssertTrue(app.descendants(matching: .any)["setup.watching"].exists, "the screen does not say which profile it watches")
        // Waiting happens while the test runner redeems out of band (see the lane notes); allow 4 minutes.
        XCTAssertTrue(app.descendants(matching: .any)["details.banner"].waitForExistence(timeout: 240), "the screen did not finish by itself")
    }

    /// An empty IPTV hub (profile 2 has no playlists) offers "I have a setup code" and it opens the same code screen.
    func testEmptyHubOffersSetupCode() {
        app.launchArguments = ["-smokePickProfile", "2", "-smokeTab", "iptv"]
        app.launch()
        let button = app.buttons["iptv.empty.setupCode"]
        XCTAssertTrue(button.waitForExistence(timeout: 40), "the empty IPTV hub has no setup-code button")
        XCTAssertTrue(waitFocus(10) { $0 == "iptv.empty.setupCode" }, "the button is not focused; focus \(focusedId())")
        press(.select, settle: 3)
        XCTAssertTrue(waitFocus(15) { $0 == "setup.key.A" }, "the code screen did not open; focus \(focusedId())")
        XCTAssertTrue(app.descendants(matching: .any)["setup.account"].exists)
    }
}
