import XCTest
#if os(tvOS)
@testable import TuvoraTV
#else
@testable import Nuvio
#endif

/// The unclean-exit state machine (UncleanExitMarker.swift): no UIKit, no Kotlin, no clock.
final class UncleanExitMarkerTests: XCTestCase {
    private let boot: Int64 = 1_800_000_000
    private func identity(build: String = "1.20(200)", os: String = "Version 26.5", boot: Int64? = nil) -> RunIdentity {
        RunIdentity(appBuild: build, osVersion: os, bootTime: boot ?? self.boot)
    }

    private func foregroundMarker(playing: Bool = false, warnings: Int = 0) -> RunMarker {
        var m = UncleanExitMachine.launched(identity: identity(), inBackground: false)
        if playing { m = UncleanExitMachine.playbackChanged(kind: "live", engine: "libmpv", m) }
        for _ in 0..<warnings { m = UncleanExitMachine.memoryWarned(m) }
        return m
    }

    func testFirstLaunchIsNeverReported() {
        XCTAssertEqual(UncleanExitMachine.evaluate(previous: nil, current: identity(), reportsEnabled: true), .firstLaunch)
    }

    func testCleanBackgroundIsNotReported() {
        let m = UncleanExitMachine.entered(.background, foregroundMarker(playing: true))
        XCTAssertEqual(UncleanExitMachine.evaluate(previous: m, current: identity(), reportsEnabled: true), .cleanExit)
    }

    func testTerminatedIsNotReported() {
        let m = UncleanExitMachine.entered(.terminated, foregroundMarker())
        XCTAssertEqual(UncleanExitMachine.evaluate(previous: m, current: identity(), reportsEnabled: true), .cleanExit)
    }

    func testForegroundMarkerIsUncleanAndKeepsPlaybackAndWarnings() {
        let verdict = UncleanExitMachine.evaluate(previous: foregroundMarker(playing: true, warnings: 2), current: identity(), reportsEnabled: true)
        XCTAssertEqual(verdict, .uncleanSuspected(wasPlaying: true, kind: "live", engine: "libmpv", memWarnings: 2))
    }

    func testIdleForegroundExitReportsNoPlaybackFields() {
        var m = foregroundMarker(playing: true)
        m = UncleanExitMachine.playbackChanged(kind: "", engine: "", m)
        XCTAssertEqual(
            UncleanExitMachine.evaluate(previous: m, current: identity(), reportsEnabled: true),
            .uncleanSuspected(wasPlaying: false, kind: "", engine: "", memWarnings: 0)
        )
    }

    func testReturningToForegroundAfterBackgroundIsUncleanAgainOnlyIfItDiesThere() {
        var m = UncleanExitMachine.entered(.background, foregroundMarker())
        m = UncleanExitMachine.entered(.foreground, m)
        guard case .uncleanSuspected = UncleanExitMachine.evaluate(previous: m, current: identity(), reportsEnabled: true) else {
            return XCTFail("a run that came back to the foreground and died there is unclean")
        }
    }

    func testUpdateLaunchIsNotReported() {
        XCTAssertEqual(
            UncleanExitMachine.evaluate(previous: foregroundMarker(), current: identity(build: "1.21(201)"), reportsEnabled: true),
            .appUpdated
        )
    }

    func testOsUpdateAndRestartAreNotReported() {
        XCTAssertEqual(
            UncleanExitMachine.evaluate(previous: foregroundMarker(), current: identity(os: "Version 26.6"), reportsEnabled: true),
            .osUpdated
        )
        XCTAssertEqual(
            UncleanExitMachine.evaluate(previous: foregroundMarker(), current: identity(boot: boot + 3_600), reportsEnabled: true),
            .deviceRestarted
        )
    }

    func testSmallBootTimeDriftIsNotARestart() {
        let verdict = UncleanExitMachine.evaluate(previous: foregroundMarker(), current: identity(boot: boot + 5), reportsEnabled: true)
        guard case .uncleanSuspected = verdict else { return XCTFail("a clock correction must not hide a crash") }
    }

    func testSwitchOffReportsNothing() {
        XCTAssertEqual(UncleanExitMachine.evaluate(previous: foregroundMarker(playing: true), current: identity(), reportsEnabled: false), .reportsOff)
    }

    func testBackgroundLaunchStartsInBackground() {
        let m = UncleanExitMachine.launched(identity: identity(), inBackground: true)
        XCTAssertEqual(UncleanExitMachine.evaluate(previous: m, current: identity(), reportsEnabled: true), .cleanExit)
    }

    func testMemoryWarningCountIsCapped() {
        var m = foregroundMarker()
        for _ in 0..<1_100 { m = UncleanExitMachine.memoryWarned(m) }
        XCTAssertEqual(m.memWarnings, 1000)
    }

    func testStoreRoundTripsAndTreatsGarbageAsNoMarker() {
        let suite = UserDefaults(suiteName: "tuvora.tests.marker.\(UUID().uuidString)")!
        let store = UserDefaultsRunMarkerStore(defaults: suite)
        XCTAssertNil(store.load())
        let m = foregroundMarker(playing: true, warnings: 1)
        store.save(m)
        XCTAssertEqual(store.load(), m)
        suite.set(Data("not json".utf8), forKey: UserDefaultsRunMarkerStore.key)
        XCTAssertNil(store.load())
    }
}
