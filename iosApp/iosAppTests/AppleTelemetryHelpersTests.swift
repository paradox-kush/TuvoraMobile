import XCTest
#if os(tvOS)
@testable import TuvoraTV
#else
@testable import Nuvio
#endif

/// Pure helpers of the Apple telemetry lane: memory unit conversion and the QA-mirror launch gate.
final class AppleTelemetryHelpersTests: XCTestCase {
    func testMegabytesAreWholeAndUnknownIsMinusOne() {
        XCTAssertEqual(AppleMemoryFacts.megabytes(fromBytes: 1_048_576 * 300 + 5), 300)
        XCTAssertEqual(AppleMemoryFacts.megabytes(fromBytes: 0), -1)
        XCTAssertEqual(AppleMemoryFacts.megabytes(fromBytes: nil), -1)
    }

    func testQaMirrorIsOffUnlessAskedFor() {
        XCTAssertFalse(TuvoraQaLog.isEnabled(arguments: ["app"], environment: [:]))
        XCTAssertFalse(TuvoraQaLog.isEnabled(arguments: ["app", "-TUVORA_QA"], environment: [:]))
        XCTAssertFalse(TuvoraQaLog.isEnabled(arguments: ["app", "-TUVORA_QA", "0"], environment: ["TUVORA_QA": "0"]))
    }

    func testQaMirrorTurnsOnByArgumentOrEnvironment() {
        XCTAssertTrue(TuvoraQaLog.isEnabled(arguments: ["app", "-TUVORA_QA", "1"], environment: [:]))
        XCTAssertTrue(TuvoraQaLog.isEnabled(arguments: ["app"], environment: ["TUVORA_QA": "1"]))
    }

    func testReadyLineIsOneJsonObject() throws {
        let line = TuvoraQaLog.readyLine(platform: "tvos")
        let object = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: String]
        XCTAssertEqual(object, ["ev": "qa_ready", "platform": "tvos"])
        XCTAssertFalse(line.contains("\n"))
    }
}
