import XCTest
@testable import Nuvio

/// DecodeHealthShadow is shared verbatim with the iOS app (iosApp/iosApp/Player, test copy in iosAppTests).
/// These cover the parts that need no mpv: the wire line Kotlin parses, the log-prefix rule, and the counts.
final class DecodeHealthShadowTests: XCTestCase {

    func testWireLineMatchesTheKotlinFormat() {
        let line = DecodeHealthShadow.wireLine(
            avSyncSeconds: 6.2, dropped: 120, audioWarns: 2, videoWarns: 1,
            audioSelected: true, audioAdvancing: false, muted: false, fps: 50
        )
        XCTAssertEqual(line, "v1,6200,120,2,1,1,0,0,5000")
    }

    func testUnknownFieldsAreDashes() {
        let line = DecodeHealthShadow.wireLine(
            avSyncSeconds: nil, dropped: 0, audioWarns: 0, videoWarns: 0,
            audioSelected: false, audioAdvancing: nil, muted: true, fps: 0
        )
        XCTAssertEqual(line, "v1,-,0,0,0,0,-,1,-")
    }

    func testNegativeOffsetKeepsItsSignAndNonFiniteIsUnknown() {
        XCTAssertTrue(DecodeHealthShadow.wireLine(
            avSyncSeconds: -7, dropped: 0, audioWarns: 0, videoWarns: 0,
            audioSelected: true, audioAdvancing: true, muted: false, fps: 25
        ).hasPrefix("v1,-7000,"))
        XCTAssertTrue(DecodeHealthShadow.wireLine(
            avSyncSeconds: .nan, dropped: -3, audioWarns: 0, videoWarns: 0,
            audioSelected: true, audioAdvancing: true, muted: false, fps: .infinity
        ).hasPrefix("v1,-,0,"))
    }

    func testOnlyAudioAndVideoDecoderModulesAreCounted() {
        XCTAssertEqual(DecodeHealthShadow.side(ofLogPrefix: "ad"), .audio)
        XCTAssertEqual(DecodeHealthShadow.side(ofLogPrefix: "ao"), .audio)
        XCTAssertEqual(DecodeHealthShadow.side(ofLogPrefix: "ao/coreaudio"), .audio)
        XCTAssertEqual(DecodeHealthShadow.side(ofLogPrefix: "vd"), .video)
        XCTAssertEqual(DecodeHealthShadow.side(ofLogPrefix: " VD "), .video)
        for other in ["cplayer", "stream", "ffmpeg", "ffmpeg/video", "vo", "file", "demux", ""] {
            XCTAssertNil(DecodeHealthShadow.side(ofLogPrefix: other), "prefix '\(other)' must not be counted")
        }
    }

    func testLogLinesAreCountedByLevelAndNeverStored() {
        let shadow = DecodeHealthShadow()
        shadow.noteLog(prefix: "ad", level: "warn")
        shadow.noteLog(prefix: "ao", level: "error")
        shadow.noteLog(prefix: "vd", level: "fatal")
        shadow.noteLog(prefix: "vd", level: "info")       // below warn: not counted
        shadow.noteLog(prefix: "stream", level: "error")  // module can quote a URL: not counted
        XCTAssertEqual(shadow.line(voDropped: 0, fps: 0), "v1,-,0,2,1,0,-,0,-")
    }

    func testFramesDroppedAreTheVoAndDecoderCountsTogether() {
        let shadow = DecodeHealthShadow()
        XCTAssertEqual(shadow.line(voDropped: 40, fps: 25), "v1,-,40,0,0,0,-,0,2500")
    }

    func testMuteIsReported() {
        let shadow = DecodeHealthShadow()
        shadow.setMuted(true)
        XCTAssertTrue(shadow.line(voDropped: 0, fps: 0).hasSuffix(",1,-"))
        shadow.setMuted(false)
        XCTAssertTrue(shadow.line(voDropped: 0, fps: 0).hasSuffix(",0,-"))
    }

    func testStreamFactsAreUnknownUntilMpvReportsThem() {
        let facts = DecodeHealthShadow().streamFacts
        XCTAssertNil(facts.decodeMode)
        XCTAssertNil(facts.hdr)
    }

    func testShapeLineMatchesTheKotlinFormat() {
        XCTAssertEqual(
            DecodeHealthShadow.wireShapeLine(
                videoCodec: "HEVC", width: 3840, height: 2160, fps: 50,
                audioCodec: "eac3", channels: 6, sampleRate: 48000, hdr: true, decodeMode: "hw"),
            "s1,hevc,3840,2160,5000,eac3,6,48000,1,hw"
        )
        XCTAssertEqual(
            DecodeHealthShadow.wireShapeLine(
                videoCodec: nil, width: 0, height: 0, fps: 0,
                audioCodec: "", channels: 0, sampleRate: 0, hdr: nil, decodeMode: nil),
            "s1,-,-,-,-,-,-,-,-,-"
        )
    }

    func testFreeFormCodecTextNeverCrossesTheBridge() {
        let line = DecodeHealthShadow.wireShapeLine(
            videoCodec: "Some Channel Name HD", width: 1920, height: 1080, fps: 25,
            audioCodec: "http://provider.example/x", channels: 2, sampleRate: 44100, hdr: false, decodeMode: "sw")
        XCTAssertEqual(line, "s1,-,1920,1080,2500,-,2,44100,0,sw")
    }

    func testShapeLineIsEmptyOfFactsUntilMpvReportsTracks() {
        XCTAssertEqual(DecodeHealthShadow().shapeLine(), "s1,-,-,-,-,-,-,-,-,-")
    }
}
