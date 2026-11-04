import XCTest
@testable import TuvoraTV

final class SubtitleHeaderScopeTests: XCTestCase {
    func testNoSubtitleHeadersMeansNothingSoStreamCookieIsCleared() {
        XCTAssertEqual(SubtitleHeaderScope.requestHeaders(subtitleHeaders: nil), [:])
    }

    func testKeepsOnlyOwnForwardableHeaders() {
        let out = SubtitleHeaderScope.requestHeaders(subtitleHeaders: [
            "X-Sub-Key": "k", "Range": "bytes=0-", "Host": "evil", " ": "x", "Blank": " ",
        ])
        XCTAssertEqual(out, ["X-Sub-Key": "k"])
    }
}
