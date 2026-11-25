import ComposeApp
import Foundation
import MetricKit

/// Apple's delayed reliability reports (iPhone and iPad only: MetricKit's manager, payloads and diagnostics are
/// `API_UNAVAILABLE(tvos)`; Apple TV relies on the unclean-exit marker instead).
///
/// One compact `metrickit_payload` event per payload, built by the shared Kotlin (`MetricKitEvent`) from COUNTS under
/// a closed key set: crash / hang / CPU / disk-write diagnostics, and the app-exit counters split foreground vs
/// background (watchdog, memory limit, memory pressure, ...). Nothing else from the payload is read: no call-stack tree,
/// no termination reason or exception text, no app-version string, no signposts. A payload that holds only normal exits
/// sends nothing (routine successes are not events). The user's reports switch is applied by the shared port.
@available(iOS 14.0, *)
final class MetricKitReliabilityReporter: NSObject, MXMetricManagerSubscriber {
    static let shared = MetricKitReliabilityReporter()

    private let stateLock = NSLock()
    private var isStarted = false

    private override init() {
        super.init()
    }

    func start() {
        stateLock.lock()
        defer { stateLock.unlock() }

        guard !isStarted else { return }
        isStarted = true
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let counts = MetricKitCounts.diagnostics(
                crash: payload.crashDiagnostics?.count ?? 0,
                hang: payload.hangDiagnostics?.count ?? 0,
                cpu: payload.cpuExceptionDiagnostics?.count ?? 0,
                diskWrite: payload.diskWriteExceptionDiagnostics?.count ?? 0
            )
            send(source: "diagnostics", counts: counts, begin: payload.timeStampBegin, end: payload.timeStampEnd)
        }
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        for payload in payloads {
            guard let exits = payload.applicationExitMetrics else { continue }
            let fg = exits.foregroundExitData
            let bg = exits.backgroundExitData
            let counts = MetricKitCounts.exits(
                fg: .init(
                    normal: fg.cumulativeNormalAppExitCount, memoryLimit: fg.cumulativeMemoryResourceLimitExitCount,
                    badAccess: fg.cumulativeBadAccessExitCount, abnormal: fg.cumulativeAbnormalExitCount,
                    illegal: fg.cumulativeIllegalInstructionExitCount, watchdog: fg.cumulativeAppWatchdogExitCount
                ),
                bg: .init(
                    normal: bg.cumulativeNormalAppExitCount, memoryLimit: bg.cumulativeMemoryResourceLimitExitCount,
                    cpuLimit: bg.cumulativeCPUResourceLimitExitCount, memoryPressure: bg.cumulativeMemoryPressureExitCount,
                    badAccess: bg.cumulativeBadAccessExitCount, abnormal: bg.cumulativeAbnormalExitCount,
                    illegal: bg.cumulativeIllegalInstructionExitCount, watchdog: bg.cumulativeAppWatchdogExitCount,
                    suspendedLocked: bg.cumulativeSuspendedWithLockedFileExitCount,
                    taskTimeout: bg.cumulativeBackgroundTaskAssertionTimeoutExitCount
                )
            )
            send(source: "metrics", counts: counts, begin: payload.timeStampBegin, end: payload.timeStampEnd)
        }
    }

    private func send(source: String, counts: [String: Int], begin: Date, end: Date) {
        let hours = Int(max(0, min(168, end.timeIntervalSince(begin) / 3_600)).rounded())
        AppleTelemetryBridge.shared.recordMetricKit(
            source: source,
            counts: counts.mapValues { KotlinInt(int: Int32(clamping: $0)) },
            windowHours: Int32(hours)
        )
    }
}

/// The closed key set of the shared Kotlin `MetricKitEvent.COUNT_KEYS`, filled from plain integers (so it tests
/// without MetricKit objects). A key the Kotlin side does not know is dropped there.
enum MetricKitCounts {
    struct Foreground {
        var normal = 0, memoryLimit = 0, badAccess = 0, abnormal = 0, illegal = 0, watchdog = 0
        init(normal: UInt = 0, memoryLimit: UInt = 0, badAccess: UInt = 0, abnormal: UInt = 0, illegal: UInt = 0, watchdog: UInt = 0) {
            self.normal = Int(clamping: normal); self.memoryLimit = Int(clamping: memoryLimit)
            self.badAccess = Int(clamping: badAccess); self.abnormal = Int(clamping: abnormal)
            self.illegal = Int(clamping: illegal); self.watchdog = Int(clamping: watchdog)
        }
    }

    struct Background {
        var normal = 0, memoryLimit = 0, cpuLimit = 0, memoryPressure = 0, badAccess = 0, abnormal = 0
        var illegal = 0, watchdog = 0, suspendedLocked = 0, taskTimeout = 0
        init(
            normal: UInt = 0, memoryLimit: UInt = 0, cpuLimit: UInt = 0, memoryPressure: UInt = 0, badAccess: UInt = 0,
            abnormal: UInt = 0, illegal: UInt = 0, watchdog: UInt = 0, suspendedLocked: UInt = 0, taskTimeout: UInt = 0
        ) {
            self.normal = Int(clamping: normal); self.memoryLimit = Int(clamping: memoryLimit)
            self.cpuLimit = Int(clamping: cpuLimit); self.memoryPressure = Int(clamping: memoryPressure)
            self.badAccess = Int(clamping: badAccess); self.abnormal = Int(clamping: abnormal)
            self.illegal = Int(clamping: illegal); self.watchdog = Int(clamping: watchdog)
            self.suspendedLocked = Int(clamping: suspendedLocked); self.taskTimeout = Int(clamping: taskTimeout)
        }
    }

    static func diagnostics(crash: Int, hang: Int, cpu: Int, diskWrite: Int) -> [String: Int] {
        ["crash": crash, "hang": hang, "cpu": cpu, "disk_write": diskWrite]
    }

    static func exits(fg: Foreground, bg: Background) -> [String: Int] {
        [
            "fg_normal": fg.normal, "fg_memory_limit": fg.memoryLimit, "fg_bad_access": fg.badAccess,
            "fg_abnormal": fg.abnormal, "fg_illegal": fg.illegal, "fg_watchdog": fg.watchdog,
            "bg_normal": bg.normal, "bg_memory_limit": bg.memoryLimit, "bg_cpu_limit": bg.cpuLimit,
            "bg_memory_pressure": bg.memoryPressure, "bg_bad_access": bg.badAccess, "bg_abnormal": bg.abnormal,
            "bg_illegal": bg.illegal, "bg_watchdog": bg.watchdog, "bg_suspended_locked": bg.suspendedLocked,
            "bg_task_timeout": bg.taskTimeout,
        ]
    }
}
