import SwiftUI
import TuvoraCore
import AVFoundation
import PostHog

// Telemetry v2 on Apple TV. Logic (support code, consent, rate limit, the event itself) lives in the shared
// Kotlin TvTelemetry; this file only renders states and calls it off Main.

/// Every outcome of "Send report" the viewer can see. None of them is shown as success except `sent`.
enum ReportFlowState: Equatable {
    case idle, sending, sent(code: String), consentOff, rateLimited(seconds: Int), failed
}

@MainActor
final class ReportFlow: ObservableObject {
    @Published private(set) var state: ReportFlowState = .idle
    private var deadline: Task<Void, Never>?
    /// The wait ends here even if the work never answers (BoundedLoad's rule for Swift loads).
    static let deadlineSeconds: UInt64 = 15

    func send(category: ReportCategory, surface: ReportSurface, engine: String?, failureCode: String?) {
        // Disabled while sending, so a double OK cannot double-send (the 120 s limit also guards).
        guard state != .sending else { return }
        state = .sending
        deadline?.cancel()
        deadline = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.deadlineSeconds * 1_000_000_000)
            guard !Task.isCancelled, let self, self.state == .sending else { return }
            self.state = .failed
        }
        Task.detached {
            let result = try? await TvTelemetry.shared.sendReport(category: category, surface: surface, engine: engine, failureCode: failureCode)
            await MainActor.run { self.finish(result) }
        }
    }

    private func finish(_ result: ReportResult?) {
        guard state == .sending else { return }   // the deadline already ended the wait
        deadline?.cancel()
        guard let result else { state = .failed; return }
        switch onEnum(of: result) {
        case .sent(let sent): state = .sent(code: sent.supportCode)
        case .consentOff: state = .consentOff
        case .rateLimited(let limited): state = .rateLimited(seconds: Int(limited.retryInSec))
        case .failed: state = .failed
        }
    }

    func reset() { deadline?.cancel(); state = .idle }

    /// The consent-off shortcut: flips the same switch as Settings → Diagnostics, in place.
    func turnOnReports() {
        TvPrivacySettings.shared.setDiagnostics(enabled: true)
        state = .idle
    }
}

extension ReportFlowState {
    /// The sentence under the buttons; nil while there is nothing to say.
    var message: String? {
        switch self {
        case .idle: return nil
        case .sending: return L("Sending…")
        case .sent(let code): return String(format: L("Report sent. Your support code: %@"), code)
        case .consentOff: return L("Turn on Crash & usage reports to send this.")
        case .rateLimited: return L("Please wait a moment before sending another report.")
        case .failed: return L("Couldn't send the report. Try again in a moment.")
        }
    }
}

extension ReportCategory {
    var title: String {
        switch self {
        case .wontPlay: return L("Won't play")
        case .blackScreen: return L("Black screen")
        case .freezes: return L("Freezes")
        case .appClosed: return L("App closed")
        case .other: return L("Other")
        @unknown default: return L("Other")
        }
    }
    var id: String { "\(self)" }
    static var pickerOrder: [ReportCategory] { [.wontPlay, .blackScreen, .freezes, .appClosed, .other] }
}

/// Settings → Diagnostics → Send a report, after the viewer picked a category: sends, then says what happened.
/// Every state keeps a focus target (Close, plus the consent shortcut), and Back closes.
struct ReportSendDialog: View {
    let category: ReportCategory
    @ObservedObject var dialogs: SettingsDialogs
    @StateObject private var flow = ReportFlow()

    var body: some View {
        NuvioDialog(title: "Send a report", subtitle: flow.state.message) {
            VStack(alignment: .leading, spacing: dp(12)) {
                if case .sent = flow.state {
                    Text(L("Tell us this code on Discord."))
                        .font(NuvioType.bodyMedium)
                } else if flow.state == .sending {
                    ProgressView()
                }
                HStack(spacing: dp(8)) {
                    Spacer()
                    if flow.state == .consentOff {
                        SettingsDialogButton(title: "Turn on", primary: true) {
                            flow.turnOnReports()
                            flow.send(category: category, surface: .settings, engine: nil, failureCode: nil)
                        }.accessibilityIdentifier("report.turnOn")
                    }
                    SettingsDialogButton(title: flow.state == .sending ? "Cancel" : "Close", initialFocus: flow.state != .consentOff) {
                        flow.reset(); dialogs.pop()
                    }.accessibilityIdentifier("report.close")
                }
            }
        }
        .onAppear { flow.send(category: category, surface: .settings, engine: nil, failureCode: nil) }
        .accessibilityIdentifier("report.dialog")
    }
}

/// What this Apple TV is, registered as PostHog super properties so every event, crash and report can be grouped by it.
/// No serial number, MAC address, IP, account name or installed-app list.
enum TvHardwareProfile {
    /// The Apple TV model identifier ("AppleTV14,1"); a simulator reports the model it is imitating.
    static func modelIdentifier() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) { String(cString: $0) }
        }
    }

    static func properties() -> [String: Any] {
        let hdr = AVPlayer.availableHDRModes
        return [
            "hw_model": modelIdentifier(),
            "hw_ram_mb": Int(ProcessInfo.processInfo.physicalMemory / 1_048_576),
            "hw_hdr10": hdr.contains(.hdr10),
            "hw_hlg": hdr.contains(.hlg),
            "hw_dolby_vision": hdr.contains(.dolbyVision),
            "hw_os": ProcessInfo.processInfo.operatingSystemVersionString,
        ]
    }
}

enum TvTelemetryBootstrap {
    /// After TuvoraTelemetry.start(): the support code and hardware profile ride every event from here on.
    static func registerSuperProperties() {
        var props = TvHardwareProfile.properties()
        props["support_code"] = TvTelemetry.shared.supportCode
        PostHogSDK.shared.register(props)
    }
}
