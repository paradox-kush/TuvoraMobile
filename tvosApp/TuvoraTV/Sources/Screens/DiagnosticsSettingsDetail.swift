import SwiftUI
import TuvoraCore

/// Settings → Diagnostics (Telemetry v2): the crash & usage reports switch (the same `sentry_enabled` key as
/// the iPhone app) and "Send a report". The toggle moved here from Advanced so there is one place for it.
struct DiagnosticsSettingsDetail: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @State private var diagnostics = true

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Diagnostics", subtitle: "Crash reports and help requests")
            SettingsGroupCard(title: "Reports") {
                SettingsToggleRow(title: "Crash & usage reports", subtitle: "Send crash and anonymous usage reports with safe context. Turn it off to stop sending reports from this device.", isOn: diagnostics) {
                    TvPrivacySettings.shared.setDiagnostics(enabled: !diagnostics)
                }.accessibilityIdentifier("privacy.diagnostics")
                SettingsActionRow(title: "Send a report", subtitle: "Tell us something is wrong. We get your support code and what happened just before.") {
                    dialogs.push(.picker(PickerSpec(
                        title: "What went wrong?",
                        options: ReportCategory.pickerOrder.map { SettingsPickerOption(id: $0.id, title: $0.title) },
                        selectedId: ReportCategory.wontPlay.id
                    ) { chosen in
                        let category = ReportCategory.pickerOrder.first { $0.id == chosen } ?? .other
                        dialogs.push(.custom(AnyView(ReportSendDialog(category: category, dialogs: dialogs))))
                    }))
                }.accessibilityIdentifier("diagnostics.sendReport")
            }
            SettingsGroupCard(title: "Support code") {
                SupportCodeRow(code: TvTelemetry.shared.displaySupportCode)
            }
        }
        .task { for await value in TvPrivacySettings.shared.diagnostics { diagnostics = value.boolValue } }
    }
}

/// The support code, large enough to read from the couch. Informational, so it is a plain focusable row
/// (the rail's focus must be able to land on it as it can on every other row).
struct SupportCodeRow: View {
    let code: String
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            Text(L("Support code")).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
            Text(code)
                .font(.system(size: dp(44), weight: .bold, design: .monospaced))
                .foregroundStyle(colors.textPrimary)
                .accessibilityIdentifier("about.supportCode")
            Text(L("Tell us this code when you report a problem."))
                .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
        .background(RoundedRectangle(cornerRadius: dp(10)).fill(focused ? colors.focusBackground : colors.backgroundCard))
        .focusable()
        .focused($focused)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("support.code.row")
    }
}
