import SwiftUI
import Combine
import UIKit

/// NuvioDialog (components/NuvioDialog.kt): 520dp wide, BackgroundElevated, radius 16, 1dp Border,
/// padding 24, 16dp spacing, titleLarge title.
struct NuvioDialog<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    /// NuvioDialog's `width` parameter (default 520dp).
    var width: CGFloat = dp(520)
    /// Long forms keep scrolled fields out of their fixed title and subtitle.
    var clipsContent = false
    @ViewBuilder var content: Content
    @Environment(\.nuvio) private var colors
    @State private var contentHeight: CGFloat = 1

    var body: some View {
        VStack(alignment: .leading, spacing: dp(16)) {
            Text(ui: title).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
            if let subtitle { Text(ui: subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
            // One content tree: ViewThatFits instantiated the TextField twice, allowing
            // a hidden candidate to take keyboard focus from the visible native field.
            ScrollView {
                VStack(spacing: dp(8)) { content }
                    .padding(.vertical, dp(4))
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
            }
            .frame(height: min(contentHeight, max(dp(80), UIScreen.main.bounds.height - dp(subtitle == nil ? 160 : 220))))
            .scrollBounceBehavior(.basedOnSize)
            .scrollClipDisabled(!clipsContent)
        }
        .padding(dp(24))
        .frame(width: width)
        .background(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).fill(colors.backgroundElevated))
        .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
    }
}

/// SettingsActionRow (SettingsDesignSystem.kt:780-910, CLASSIC): pill on Background, min height 62,
/// padding 18×12, bodyLarge title, bodySmall subtitle, labelLarge value, 18dp chevron; 2dp FocusRing.
struct SettingsActionRow: View {
    let title: String
    var subtitle: String? = nil
    var value: String? = nil
    var showChevron = true
    /** Material icon asset drawn 24dp before the title (NuvioTV `leadingIcon`). */
    var leadingIcon: String? = nil
    /** NuvioTV `trailingIcon` (chevron by default, open-in-new for links). */
    var trailingIcon = "md_chevron_right"
    /** Disabled rows dim to 40% and can't take focus, as NuvioTV's `enabled = false`. */
    var enabled = true
    /** NuvioTV `valueColor` (TextSecondary by default; tracking status uses its own). */
    var valueColor: Color? = nil
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                if let leadingIcon {
                    Image(leadingIcon).renderingMode(.template).resizable().scaledToFit()
                        .frame(width: dp(24), height: dp(24)).foregroundStyle(colors.textPrimary)
                        .padding(.trailing, dp(4))
                }
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if let subtitle, !subtitle.isEmpty { Text(ui: subtitle).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3) }
                }
                Spacer()
                if let value { Text(ui: value).font(NuvioType.labelLarge).foregroundStyle(valueColor ?? colors.textSecondary).lineLimit(1) }
                if showChevron {
                    Image(trailingIcon).renderingMode(.template).resizable().frame(width: dp(18), height: dp(18)).foregroundStyle(colors.textTertiary)
                }
            }
            .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
            .frame(minHeight: dp(62))
            .background(Capsule().fill(colors.background))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .disabled(!enabled)
        .focused($focused)
        .reportsFocus(focused)
        .remembersFocus(title, focused: $focused)
    }
}


/// Remembers which settings row opened a dialog, so closing the dialog puts focus back on it: tvOS
/// does not restore focus to a view that was disabled while the dialog was up (walkthrough finding).
@MainActor
final class RowFocusMemory: ObservableObject {
    var last: String?
    @Published private(set) var restoreTick = 0
    func restore() { restoreTick += 1 }
}

private struct RowFocusMemoryKey: EnvironmentKey {
    static let defaultValue: RowFocusMemory? = nil
}

extension EnvironmentValues {
    var rowFocusMemory: RowFocusMemory? {
        get { self[RowFocusMemoryKey.self] }
        set { self[RowFocusMemoryKey.self] = newValue }
    }
}

private struct RemembersFocus: ViewModifier {
    let key: String
    var focused: FocusState<Bool>.Binding
    @Environment(\.rowFocusMemory) private var memory

    func body(content: Content) -> some View {
        content
            .onChange(of: focused.wrappedValue) { _, now in if now { memory?.last = key } }
            .onReceive(memory?.$restoreTick.dropFirst().eraseToAnyPublisher() ?? Empty().eraseToAnyPublisher()) { _ in
                if memory?.last == key { focused.wrappedValue = true }
            }
    }
}

extension View {
    /// Inside a RowFocusMemory (Settings), this row takes focus back after a dialog it opened closes.
    func remembersFocus(_ key: String, focused: FocusState<Bool>.Binding) -> some View {
        modifier(RemembersFocus(key: key, focused: focused))
    }
}
