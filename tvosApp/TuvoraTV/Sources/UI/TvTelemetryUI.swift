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

    private var work: Task<Void, Never>?

    /// Sends once from `.idle` (re-appearing after a result never sends again).
    func sendIfIdle(category: ReportCategory, surface: ReportSurface, engine: String?, failureCode: String?) {
        guard state == .idle else { return }
        send(category: category, surface: surface, engine: engine, failureCode: failureCode)
    }

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
        work?.cancel()
        work = Task { [weak self] in
            let result = await Self.perform(category, surface, engine, failureCode)   // off Main
            guard !Task.isCancelled, let self else { return }
            self.finish(result)
        }
    }

    private nonisolated static func perform(_ category: ReportCategory, _ surface: ReportSurface, _ engine: String?, _ failureCode: String?) async -> ReportResult? {
        if Task.isCancelled { return nil }   // Cancel before the work starts sends nothing
        return try? await TvTelemetry.shared.sendReport(category: category, surface: surface, engine: engine, failureCode: failureCode)
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

    func reset() { deadline?.cancel(); work?.cancel(); state = .idle }

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
                        SettingsDialogButton(title: "Turn on", primary: true, initialFocus: true) {
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
        .onAppear { flow.sendIfIdle(category: category, surface: .settings, engine: nil, failureCode: nil) }
        .accessibilityIdentifier("report.dialog")
    }
}

/// The HDR half of the hardware profile, registered as PostHog super properties (the shared Kotlin registers model,
/// RAM, cores and OS). Display capability needs AVFoundation, so it is the one part Swift owns.
/// `AVPlayer.availableHDRModes` is deprecated in the tvOS 26 SDK in favour of `eligibleForHDRPlayback` (a single
/// yes/no), so tvOS 26+ reports only `hw_hdr_eligible`; earlier systems also report the per-format flags.
enum TvTelemetryBootstrap {
    static func registerSuperProperties() {
        var props: [String: Any] = ["hw_hdr_eligible": AVPlayer.eligibleForHDRPlayback]
        if #unavailable(tvOS 26) { props.merge(legacyHdrModes()) { $1 } }
        PostHogSDK.shared.register(props)
    }

    @available(tvOS, deprecated: 26, message: "only reached on tvOS < 26")
    private static func legacyHdrModes() -> [String: Any] {
        let modes = AVPlayer.availableHDRModes
        return ["hw_hdr10": modes.contains(.hdr10), "hw_hlg": modes.contains(.hlg), "hw_dolby_vision": modes.contains(.dolbyVision)]
    }
}
