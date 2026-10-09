import AVFoundation
import XCTest
@testable import TuvoraTV

@MainActor
final class AVPlayerBridgeTests: XCTestCase {
    func testRetryKeepsAllRequestHeadersAndAddress() {
        var requests: [(URL, [String: Any])] = []
        let bridge = AVPlayerBridgeImpl(makeAsset: { url, options in
            requests.append((url, options))
            return AVURLAsset(url: url, options: options)
        })
        defer { bridge.destroy() }
        bridge.loadFileWithAudio(videoUrl: "file:///tmp/tuvora-header-regression.mp4", audioUrl: nil,
            headersJson: #"{"User-Agent":"VLC/3","X-Emby-Token":"test-token","Referer":"https://portal.invalid"}"#,
            subtitlesJson: nil, startOption: nil)
        bridge.retry()
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests.first?.0, requests.last?.0)
        let expected = ["User-Agent": "VLC/3", "X-Emby-Token": "test-token", "Referer": "https://portal.invalid"]
        for request in requests {
            XCTAssertEqual(request.1["AVURLAssetHTTPHeaderFieldsKey"] as? [String: String], expected)
        }
    }

    func testLoadingAnotherSourceDoesNotKeepPreviousCredentials() {
        var options: [[String: Any]] = []
        let bridge = AVPlayerBridgeImpl(makeAsset: { url, config in
            options.append(config)
            return AVURLAsset(url: url, options: config)
        })
        defer { bridge.destroy() }
        bridge.loadFileWithAudio(videoUrl: "file:///tmp/tuvora-first.mp4", audioUrl: nil,
            headersJson: #"{"X-Emby-Token":"first-token"}"#, subtitlesJson: nil, startOption: nil)
        bridge.loadFile(url: "file:///tmp/tuvora-second.mp4")
        bridge.retry()
        XCTAssertEqual(options.count, 3)
        XCTAssertNil(options[1]["AVURLAssetHTTPHeaderFieldsKey"])
        XCTAssertNil(options[2]["AVURLAssetHTTPHeaderFieldsKey"])
    }
}
