import XCTest
@testable import Nuvio

/// MetricKitCounts (iPhone only: MetricKit is unavailable on tvOS) must use exactly the closed keys the shared Kotlin accepts.
final class MetricKitCountsTests: XCTestCase {
    private let kotlinKeys: Set<String> = [
        "crash", "hang", "cpu", "disk_write",
        "fg_normal", "fg_memory_limit", "fg_bad_access", "fg_abnormal", "fg_illegal", "fg_watchdog",
        "bg_normal", "bg_memory_limit", "bg_cpu_limit", "bg_memory_pressure", "bg_bad_access",
        "bg_abnormal", "bg_illegal", "bg_watchdog", "bg_suspended_locked", "bg_task_timeout",
    ]

    func testKeysMatchTheSharedKotlinSet() {
        let diagnostics = Set(MetricKitCounts.diagnostics(crash: 1, hang: 2, cpu: 3, diskWrite: 4).keys)
        let exits = Set(MetricKitCounts.exits(fg: .init(), bg: .init()).keys)
        XCTAssertEqual(diagnostics.union(exits), kotlinKeys)
        XCTAssertTrue(diagnostics.isDisjoint(with: exits))
    }

    func testExitCountsAreCarriedPerSide() {
        let counts = MetricKitCounts.exits(fg: .init(watchdog: 2), bg: .init(memoryPressure: 5))
        XCTAssertEqual(counts["fg_watchdog"], 2)
        XCTAssertEqual(counts["bg_memory_pressure"], 5)
        XCTAssertEqual(counts["bg_watchdog"], 0)
    }
}
