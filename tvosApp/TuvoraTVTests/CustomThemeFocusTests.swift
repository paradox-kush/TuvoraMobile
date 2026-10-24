import XCTest
@testable import TuvoraTV

final class CustomThemeFocusTests: XCTestCase {
    func testDarkAccentRetainsVisibleFocusCue() {
        XCTAssertEqual(NuvioPalette.focusRingArgb(0xFF000000, background: 0xFF0C0D0F), 0xFFFFFFFF)
        XCTAssertEqual(NuvioPalette.focusRingArgb(0xFF121212, background: 0xFF0C0D0F), 0xFFFFFFFF)
    }
    func testReadableAccentRemainsTheFocusColor() {
        XCTAssertEqual(NuvioPalette.focusRingArgb(0xFF1E88E5, background: 0xFF0C0D0F), 0xFF1E88E5)
        XCTAssertEqual(NuvioPalette.focusRingArgb(0xFFFFFFFF, background: 0xFF0C0D0F), 0xFFFFFFFF)
    }
}
