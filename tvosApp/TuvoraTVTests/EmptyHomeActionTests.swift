import XCTest
import TuvoraCore
@testable import TuvoraTV

final class EmptyHomeActionTests: XCTestCase {
    func testNoSourcesOffersSetup() {
        XCTAssertEqual(EmptyHomeAction.choose(hint: .addPlaylist, hasError: false), .setUpSources)
        XCTAssertEqual(EmptyHomeAction.choose(hint: .installSources, hasError: false), .setUpSources)
    }
    func testPlaylistInIptvTabOffersOpenIptv() {
        XCTAssertEqual(EmptyHomeAction.choose(hint: .playlistInIptvTab, hasError: false), .openIptv)
    }
    func testErrorOffersRetryWhateverTheHint() {
        for hint in [TvEmptyHomeHint.addPlaylist, .installSources, .playlistInIptvTab] {
            XCTAssertEqual(EmptyHomeAction.choose(hint: hint, hasError: true), .retry)
        }
    }
}
