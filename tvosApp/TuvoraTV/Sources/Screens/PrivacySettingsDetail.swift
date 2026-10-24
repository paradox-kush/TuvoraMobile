import SwiftUI
import TuvoraCore

struct PrivacySettingsDetail: View {
    @State private var diagnostics = true
    @State private var recommendations = true
    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Advanced", subtitle: "Privacy and diagnostics")
            SettingsGroupCard(title: "Privacy") {
                SettingsToggleRow(title: "Crash & usage reports", subtitle: "Send crash and anonymous usage reports with safe context. Turn it off to stop sending reports from this device.", isOn: diagnostics) {
                    TvPrivacySettings.shared.setDiagnostics(enabled: !diagnostics)
                }.accessibilityIdentifier("privacy.diagnostics")
                SettingsToggleRow(title: "Share anonymous usage to improve recommendations", isOn: recommendations) {
                    TvPrivacySettings.shared.setRecommendations(enabled: !recommendations)
                }.accessibilityIdentifier("privacy.recommendations")
            }
        }
        .task { for await value in TvPrivacySettings.shared.diagnostics { diagnostics = value.boolValue } }
        .task { for await value in TvPrivacySettings.shared.recommendations { recommendations = value.boolValue } }
    }
}
