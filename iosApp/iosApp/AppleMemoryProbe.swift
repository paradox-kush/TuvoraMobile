#if os(tvOS)
import TuvoraCore
#else
import ComposeApp
#endif
import Foundation
import os

/// The two memory numbers Apple gives an app, handed to the shared sampler (`AppleTelemetryBridge`).
/// `phys_footprint` is what the OS charges against the app's limit (the number Xcode's memory gauge shows);
/// `os_proc_available_memory()` is the headroom left under that limit. Both exist on iOS 13+ and tvOS 13+. There is
/// deliberately no PSS, Java heap or fd count: Apple has no such figures and nothing stands in for them.
enum AppleMemoryFacts {
    /// Bytes to whole megabytes; zero or negative means "unknown" (-1), because both APIs report 0 when they cannot answer.
    static func megabytes(fromBytes bytes: UInt64?) -> Int64 {
        guard let bytes, bytes > 0 else { return -1 }
        return Int64(bytes / 1_048_576)
    }

    static func footprintBytes() -> UInt64? {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return status == KERN_SUCCESS ? UInt64(info.phys_footprint) : nil
    }

    static func availableBytes() -> UInt64? {
        let bytes = os_proc_available_memory()
        return bytes > 0 ? UInt64(bytes) : nil
    }
}

final class AppleMemoryProbeImpl: NSObject, AppleMemoryProbe {
    func footprintMb() -> Int64 { AppleMemoryFacts.megabytes(fromBytes: AppleMemoryFacts.footprintBytes()) }
    func availableMb() -> Int64 { AppleMemoryFacts.megabytes(fromBytes: AppleMemoryFacts.availableBytes()) }
}
