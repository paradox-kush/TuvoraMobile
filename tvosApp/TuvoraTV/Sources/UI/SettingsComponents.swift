import SwiftUI
import CoreImage.CIFilterBuiltins

// NuvioTV's settings design system, CLASSIC style (ui/screens/settings/SettingsDesignSystem.kt), dp ×2.
// SettingsActionRow and NuvioDialog live in NuvioDialogs.swift (shared with other screens).

/// SettingsRailButton (CLASSIC): 56dp pill, Background idle / BackgroundCard when selected or focused,
/// 1dp FocusRing when selected, 2dp when focused, 18dp icon + 10 gap, titleMedium (SemiBold when
/// selected/focused), 18dp trailing chevron in TextTertiary.
struct SettingsRailButton: View {
    let title: String
    let icon: String
    let selected: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let lit = selected || focused
        Button(action: action) {
            HStack(spacing: dp(10)) {
                Image(icon).renderingMode(.template).resizable().scaledToFit()
                    .frame(width: dp(18), height: dp(18))
                    .foregroundStyle(lit ? colors.textPrimary : colors.textSecondary)
                Text(ui: title).font(NuvioType.inter(16, lit ? .semibold : .medium))
                    .foregroundStyle(lit ? colors.textPrimary : colors.textSecondary).lineLimit(1)
                Spacer(minLength: 0)
                Image("md_chevron_right").renderingMode(.template).resizable()
                    .frame(width: dp(18), height: dp(18)).foregroundStyle(colors.textTertiary)
            }
            .padding(.horizontal, dp(18))
            .frame(maxWidth: .infinity, minHeight: dp(56))
            .background(Capsule().fill(lit ? colors.backgroundCard : colors.background))
            .overlay(Capsule().stroke(colors.focusRing, lineWidth: focused ? NuvioTokens.Stroke.focus : (selected ? NuvioTokens.Stroke.hairline : 0)))
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .padding(.vertical, dp(2))
        .reportsFocus(focused)
    }
}

/// SettingsDetailHeader (CLASSIC): headlineMedium title, bodyMedium TextSecondary subtitle, 6dp apart.
struct SettingsDetailHeader: View {
    let title: String
    let subtitle: String
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            Text(ui: title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
            Text(ui: subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// SettingsGroupCard (CLASSIC): BackgroundCard, radius 18, 1dp Border, padding 14, 10dp between rows;
/// optional titleMedium title and bodySmall subtitle.
struct SettingsGroupCard<Content: View>: View {
    var title: String? = nil
    var subtitle: String? = nil
    @ViewBuilder var content: Content
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            if let title { Text(ui: title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary) }
            if let subtitle { Text(ui: subtitle).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary) }
            content
        }
        .padding(dp(14))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: dp(18), style: .continuous).fill(colors.backgroundCard))
        .overlay(RoundedRectangle(cornerRadius: dp(18), style: .continuous).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
    }
}

/// SettingsToggleRow (CLASSIC): the action-row pill with a SettingsTogglePill instead of the chevron.
struct SettingsToggleRow: View {
    let title: String
    var subtitle: String? = nil
    let isOn: Bool
    /** NuvioTV `enabled = false`: dims to 40% and can't take focus. */
    var enabled = true
    let toggle: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: dp(12)) {
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(ui: title).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if let subtitle { Text(ui: subtitle).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3) }
                }
                Spacer(minLength: 0)
                SettingsTogglePill(checked: isOn)
            }
            .padding(.horizontal, dp(18)).padding(.vertical, dp(12))
            .frame(minHeight: dp(62))
            .background(Capsule().fill(colors.background))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .opacity(enabled ? 1 : 0.4)
        }
        .accessibilityValue(Text(L(isOn ? "On" : "Off")))
        .buttonStyle(PlainNoChromeButtonStyle())
        .disabled(!enabled)
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// SettingsTogglePill (CLASSIC): 46×24 track, Secondary 35% on / Border off, 20dp white knob.
struct SettingsTogglePill: View {
    let checked: Bool
    @Environment(\.nuvio) private var colors

    var body: some View {
        Capsule().fill(checked ? colors.secondary.opacity(0.35) : colors.border)
            .frame(width: dp(46), height: dp(24))
            .overlay(alignment: checked ? .trailing : .leading) {
                Circle().fill(Color.white).frame(width: dp(20), height: dp(20)).padding(dp(2))
            }
            .animation(NuvioTokens.Motion.fast, value: checked)
    }
}

/// SettingsChoiceChip (CLASSIC): pill, FocusRing 20% fill + hairline ring when selected, labelMedium,
/// padding 16×10. Focus draws the ring at the focus weight so it reads from across the room.
struct SettingsChoiceChip: View {
    let label: String
    let selected: Bool
    var dimmed = false
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(ui: label).font(NuvioType.labelMedium)
                .foregroundStyle((selected || focused ? colors.textPrimary : colors.textSecondary).opacity(dimmed ? 0.4 : 1))
                .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
                .background(Capsule().fill(selected ? colors.focusRing.opacity(0.2) : colors.background))
                .overlay(Capsule().stroke(colors.focusRing, lineWidth: focused ? NuvioTokens.Stroke.focus : (selected ? NuvioTokens.Stroke.hairline : 0)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// SettingsDialogActionButton / tv-material Button: BackgroundCard (FocusBackground when primary),
/// white with dark text when focused.
struct SettingsDialogButton: View {
    let title: String
    var primary = false
    var destructive = false
    var fullWidth = false
    /// Take focus when the dialog appears (e.g. Cancel beside an irreversible action).
    var initialFocus = false
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(ui: title).font(NuvioType.labelLarge).lineLimit(1)
                .foregroundStyle(focused ? Color.black : colors.textPrimary)
                .padding(.horizontal, dp(18)).padding(.vertical, dp(10))
                .frame(maxWidth: fullWidth ? .infinity : nil)
                .background(Capsule().fill(focused ? Color.white : fill))
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onAppear { if initialFocus { DispatchQueue.main.async { focused = true } } }
    }

    private var fill: Color {
        if destructive { return Color(argb: 0xFF4A2323) }
        return primary ? colors.focusBackground : colors.backgroundCard
    }
}

/// One option in a SettingsSingleChoiceDialog.
struct SettingsPickerOption: Identifiable {
    let id: String
    let title: String
    var description: String? = nil
}

/// SettingsSingleChoiceDialog: NuvioDialog of radius-10 rows, BackgroundCard idle, FocusBackground when
/// selected/focused, Primary title + check when selected. Focus starts on the selected option.
struct SettingsSingleChoiceDialog: View {
    let title: String
    var subtitle: String? = nil
    let options: [SettingsPickerOption]
    let selectedId: String
    var width: CGFloat = dp(420)
    let onSelect: (String) -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focus: String?

    var body: some View {
        NuvioDialog(title: title, subtitle: subtitle, width: width) {
            ForEach(options) { option in
                let selected = option.id == selectedId
                let focused = focus == option.id
                Button { onSelect(option.id) } label: {
                    HStack(spacing: dp(12)) {
                        VStack(alignment: .leading, spacing: dp(4)) {
                            Text(ui: option.title).font(NuvioType.bodyLarge)
                                .foregroundStyle(selected ? colors.primary : colors.textPrimary)
                            if let d = option.description {
                                Text(d).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                            }
                        }
                        Spacer(minLength: 0)
                        if selected {
                            Image("md_check").renderingMode(.template).resizable()
                                .frame(width: dp(20), height: dp(20)).foregroundStyle(colors.primary)
                        }
                    }
                    .padding(dp(16))
                    .background(RoundedRectangle(cornerRadius: dp(10)).fill(selected || focused ? colors.focusBackground : colors.backgroundCard))
                    .overlay(RoundedRectangle(cornerRadius: dp(10)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
                }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focus, equals: option.id)
                .reportsFocus(focused)
            }
        }
        .frame(maxHeight: dp(460))
        .defaultFocus($focus, selectedId)
    }
}

/// XtreamField (XtreamSettingsScreen.kt): labelMedium label (Primary when focused), BackgroundElevated
/// field with radius 10 and a 1dp Border (Primary when focused), padding 14×12, bodyMedium text and a
/// TextTertiary hint. Text entry is the native tvOS keyboard.
struct SettingsTextField: View {
    let label: String
    var hint: String = ""
    @Binding var text: String
    var secure = false
    var onSubmit: () -> Void = {}
    /// Accessibility identifier of the field itself (UITests read focus by it).
    var id: String? = nil
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            Text(ui: label).font(NuvioType.labelMedium).foregroundStyle(focused ? colors.primary : colors.textSecondary)
            Group {
                if secure {
                    SecureField(L(label), text: $text, prompt: Text(ui: hint).foregroundStyle(colors.textTertiary))
                } else {
                    TextField(L(label), text: $text, prompt: Text(ui: hint).foregroundStyle(colors.textTertiary))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            }
            // tvOS draws its own text-field platter (and lifts it on focus); a second box around it read
            // as a field inside a field, so only NuvioTV's label sits above the native field.
            .font(NuvioType.bodyMedium)
            .focused($focused)
            .onSubmit(onSubmit)
            .frame(maxWidth: .infinity, alignment: .leading)
            .reportsFocus(focused)
            .accessibilityIdentifier(id ?? "")
        }
        .padding(.top, dp(12))
    }
}

/// FormHelperText: bodySmall TextTertiary.
struct SettingsHelperText: View {
    let text: String
    @Environment(\.nuvio) private var colors
    var body: some View {
        Text(ui: text).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).fixedSize(horizontal: false, vertical: true)
    }
}

/// QrHandOffDialog: Apple TV has no browser, so links hand off to the phone by QR (as NuvioTV does
/// when no browser resolves).
struct QrHandOffDialog: View {
    let url: String
    let instruction: String
    let close: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        NuvioDialog(title: instruction) {
            VStack(spacing: dp(12)) {
                if let image = Self.qrImage(url) {
                    Image(uiImage: image).interpolation(.none).resizable()
                        .frame(width: dp(206), height: dp(206))
                        .padding(dp(10))
                        .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                }
                Text(url).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                SettingsDialogButton(title: "Close", primary: true, action: close)
            }
            .frame(maxWidth: .infinity)
        }
    }

    static func qrImage(_ string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
