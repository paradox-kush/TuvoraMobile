import SwiftUI
import TuvoraCore

// "Enter setup code" (Step 2, build plan 3.6): one screen, both routes.
//
//   LEFT   (not focusable) QR + steps for finishing on a phone, and which account this is adding to.
//   RIGHT  twelve code boxes, a compact keypad of the 31 code characters, Delete / Continue, and the system
//          text field (which is how the iPhone keyboard is used). Then the preview, then the profile, then
//          "Add to <profile>".
//
// While this screen is up and the code has not been typed here, the TV also watches for a phone redeeming
// (SetupWaitPolicy, in the shared Kotlin: first look after 3 s, then every 6 s, one request at a time, 5
// minutes at most) and finishes by itself: pull, then open the new playlist's details.
// All decisions live in the shared Kotlin (TvSetupCodeEntry, TvSetupStateBuilder, SetupWaitPolicy); this
// file only draws them.

private enum SetupFocus: Hashable {
    case key(String)
    case delete, continueButton, keyboard
    case chip(Int32), add, cancel, done, back
}

struct SetupCodeScreen: View {
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @State private var state = TvProviderSetup.shared.state.value
    @State private var typedHere = false
    /// The 5-minute wait ended without a phone redeeming: a persistent line says so (nothing fades).
    @State private var waitEnded = false
    @FocusState private var focus: SetupFocus?

    var body: some View {
        HStack(alignment: .top, spacing: dp(40)) {
            leftPane.frame(width: dp(300), alignment: .topLeading)
            rightPane.frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .padding(.horizontal, 80).padding(.vertical, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(colors.background.ignoresSafeArea())
        .task { for await next in TvProviderSetup.shared.state { state = next } }
        .task(id: waitKey) { await watchForPhone() }
        .onAppear {
            TvProviderSetup.shared.begin()
            DispatchQueue.main.async { focus = .key("A") }
            smokeHooks()
        }
        .onDisappear { TvProviderSetup.shared.cancel() }
        .onChange(of: state.phase) { _, phase in
            let target: SetupFocus
            switch phase {
            case .entry: target = state.needsSignIn ? .back : .key("A")
            case .checking: target = .cancel
            case .preview: target = .add
            case .adding: target = .add
            case .done: target = .done
            }
            for delay in [0.1, 0.4] { DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } } }
            #if DEBUG
            smokeLog("SMOKE setup phase=%@", String(describing: phase))
            #endif
            if phase == .done { openIfReady() }
        }
        .onChange(of: state.typed) { _, text in if !text.isEmpty { typedHere = true } }
    }

    // MARK: Left - not focusable

    private var leftPane: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            Text("Add a setup code").font(NuvioType.headlineLarge).foregroundStyle(colors.textPrimary)
            Text("Easiest on your phone").font(NuvioType.labelLarge).foregroundStyle(colors.secondary).padding(.top, dp(4))
            VStack(alignment: .leading, spacing: dp(8)) {
                step(1, "Scan this code with your phone.")
                step(2, "Open your provider's setup link and confirm.")
                step(3, "This screen finishes by itself.")
            }
            if let image = QrCode.image(for: "https://tuvora.co/s") {
                Image(decorative: image, scale: 1).interpolation(.none).resizable()
                    .padding(dp(8)).frame(width: dp(132), height: dp(132))
                    .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                    .accessibilityLabel("Setup link QR code")
            }
            Text("Or type the code here with the keypad.").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let profile = state.watchedProfile, !state.needsSignIn {
                Text(verbatim: String(format: L("A code redeemed on your phone into %@ shows up here."), profile))
                    .font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("setup.watching")
            }
            if waitEnded {
                Text("Still not here? Type the code on this screen, or scan the code again.")
                    .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("setup.waitEnded")
            }
            if let account = state.accountLabel {
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text("Adding to").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                    Text(verbatim: account).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                        .accessibilityIdentifier("setup.account")
                }
                .padding(.top, dp(6))
            }
            Spacer(minLength: 0)
        }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: dp(10)) {
            Text(verbatim: "\(n)").font(NuvioType.labelLarge).foregroundStyle(colors.onSecondary)
                .frame(width: dp(20), height: dp(20)).background(Circle().fill(colors.secondary))
            Text(ui: text).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary).fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Right

    @ViewBuilder
    private var rightPane: some View {
        switch state.phase {
        case .entry: entryPane
        case .checking:
            VStack(alignment: .leading, spacing: dp(16)) {
                HStack(spacing: dp(10)) { ProgressView(); Text("Checking your code\u{2026}").font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary) }
                SettingsDialogButton(title: "Cancel") { TvProviderSetup.shared.cancel() }.focused($focus, equals: .cancel)
            }
        case .preview, .adding: previewPane
        case .done: donePane
        }
    }

    private var entryPane: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            if state.needsSignIn {
                Text(verbatim: state.problem ?? "").font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                SettingsDialogButton(title: "Back", primary: true) { dialogs.pop() }.focused($focus, equals: .back)
            } else {
                codeBoxes
                if let problem = state.problem {
                    Text(verbatim: problem).font(NuvioType.bodyMedium).foregroundStyle(colors.error)
                        .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("setup.problem")
                    contactsLine
                }
                keypad
                HStack(spacing: dp(10)) {
                    SettingsDialogButton(title: "Delete") { TvProviderSetup.shared.backspace() }
                        .focused($focus, equals: .delete).accessibilityIdentifier("setup.delete")
                    ContinueButton(enabled: state.canContinue) { TvProviderSetup.shared.continueWithCode() }
                        .focused($focus, equals: .continueButton).accessibilityIdentifier("setup.continue")
                }
                .padding(.top, dp(4))
                SettingsTextField(label: "Or use the iPhone keyboard", hint: "TUV-XXXX-XXXX-XXXX",
                                  text: Binding(get: { state.typed }, set: { TvProviderSetup.shared.typeText(raw: $0) }),
                                  onSubmit: { TvProviderSetup.shared.continueWithCode() }, id: "setup.field")
                    .focused($focus, equals: .keyboard)
            }
        }
        .focusSection()
    }

    /// Twelve boxes in three groups of four, filled from the left as the code is typed.
    private var codeBoxes: some View {
        HStack(spacing: dp(5)) {
            ForEach(0..<3, id: \.self) { group in
                HStack(spacing: dp(4)) {
                    ForEach(0..<4, id: \.self) { i in
                        let index = group * 4 + i
                        let text = index < state.boxes.count ? state.boxes[index] : ""
                        Text(verbatim: text).font(NuvioType.inter(20, .bold)).foregroundStyle(colors.textPrimary)
                            .frame(width: dp(26), height: dp(34))
                            .background(RoundedRectangle(cornerRadius: dp(6)).fill(colors.backgroundCard))
                            .overlay(RoundedRectangle(cornerRadius: dp(6))
                                .stroke(index == filledCount ? colors.focusRing.opacity(0.8) : colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                    }
                }
                if group < 2 { Text(verbatim: "\u{2013}").font(NuvioType.titleMedium).foregroundStyle(colors.textTertiary) }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(state.typed)
        .accessibilityIdentifier("setup.code")
    }

    private var filledCount: Int { state.boxes.filter { !$0.isEmpty }.count }

    @ViewBuilder
    private var contactsLine: some View {
        if !state.problemContacts.isEmpty {
            VStack(alignment: .leading, spacing: dp(4)) {
                ForEach(state.problemContacts, id: \.kind) { contact in
                    Text(verbatim: "\(L(contact.label)): \(contact.text)").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
            }
        }
    }

    private var keypad: some View {
        VStack(alignment: .leading, spacing: dp(6)) {
            ForEach(Array(TvSetupCodeEntry.shared.rows().enumerated()), id: \.offset) { _, row in
                HStack(spacing: dp(6)) {
                    ForEach(row, id: \.self) { key in
                        KeypadKey(label: key) { TvProviderSetup.shared.typeKey(key: key) }
                            .focused($focus, equals: .key(key))
                            .accessibilityIdentifier("setup.key.\(key)")
                    }
                }
            }
        }
    }

    private var previewPane: some View {
        let adding = state.phase == .adding
        return VStack(alignment: .leading, spacing: dp(12)) {
            Text("SETUP FROM YOUR PROVIDER").font(NuvioType.labelMedium).tracking(dp(1)).foregroundStyle(colors.textTertiary)
            Text(verbatim: state.providerName ?? "").font(NuvioType.headlineLarge).foregroundStyle(colors.textPrimary)
                .accessibilityIdentifier("setup.provider")
            if let package = state.packageName, !package.isEmpty {
                Text(verbatim: package).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            }
            VStack(alignment: .leading, spacing: dp(6)) {
                Text("Will be added").font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
                ForEach(Array(state.playlists.enumerated()), id: \.offset) { _, line in
                    HStack(spacing: dp(10)) {
                        Text(verbatim: line.name).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                        Text(ui: line.typeLabel).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
                    }
                }
                if !state.addons.isEmpty {
                    Text(verbatim: "\(L("Add-ons")): \(state.addons.joined(separator: ", "))")
                        .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
            }
            .padding(dp(14)).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(14)).fill(colors.backgroundCard))
            if state.profiles.count > 1 {
                Text("Add to profile").font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
                HStack(spacing: dp(8)) {
                    ForEach(state.profiles, id: \.index) { profile in
                        SettingsChoiceChip(label: profile.name, selected: profile.index == state.selectedProfile) {
                            TvProviderSetup.shared.selectProfile(index: profile.index)
                        }
                        .focused($focus, equals: .chip(profile.index))
                    }
                }
                .focusSection()
            }
            if let problem = state.problem {
                Text(verbatim: problem).font(NuvioType.bodyMedium).foregroundStyle(colors.error)
                    .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("setup.problem")
            }
            HStack(spacing: dp(10)) {
                PrimaryActionButton(title: adding ? L("Adding\u{2026}") : String(format: L("Add to %@"), selectedProfileName)) {
                    if !adding { TvProviderSetup.shared.confirm() }
                }
                .focused($focus, equals: .add).accessibilityIdentifier("setup.add")
                SettingsDialogButton(title: "Enter a different code") { TvProviderSetup.shared.cancel() }
                    .focused($focus, equals: .cancel).accessibilityIdentifier("setup.cancel")
            }
            .padding(.top, dp(6))
            .focusSection()
        }
        .onExitCommand { TvProviderSetup.shared.cancel() }
    }

    private var selectedProfileName: String {
        state.profiles.first { $0.index == state.selectedProfile }?.name ?? ""
    }

    private var donePane: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            HStack(spacing: dp(10)) {
                Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(26), height: dp(26)).foregroundStyle(colors.success)
                Text(verbatim: state.doneText ?? "").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                    .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("setup.done")
            }
            SettingsDialogButton(title: "Done", primary: true) { TvProviderSetup.shared.finish(); dialogs.pop() }
                .focused($focus, equals: .done).accessibilityIdentifier("setup.doneButton")
        }
    }

    /// Simulator smoke hooks: `-smokeSetupCode <code>` types a code through the system-keyboard route and
    /// `-smokeSetupContinue` presses Continue (test codes against a local backend only).
    private func smokeHooks() {
        #if DEBUG
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokeSetupCode"), i + 1 < args.count else { return }
        TvProviderSetup.shared.typeText(raw: args[i + 1])
        if args.contains("-smokeSetupContinue") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { TvProviderSetup.shared.continueWithCode() }
        }
        #endif
    }

    // MARK: Finishing

    private func openIfReady() {
        guard let key = state.openPlaylistKey else { return }
        open(key: key, banner: state.doneText)
    }

    private func open(key: String, banner: String?) {
        TvProviderSetup.shared.finish()
        dialogs.pop()
        dialogs.push(.playlistDetails(key, banner: banner))
    }

    private var waitKey: String { "\(scenePhase == .active)-\(typedHere)-\(state.phase == .entry)-\(state.needsSignIn)" }

    /// The "finishes by itself" wait: only while this screen is showing, the app is active and the code has
    /// not been typed here. Leaving the screen cancels this task, which ends the Kotlin loop at once.
    private func watchForPhone() async {
        guard scenePhase == .active, !typedHere, state.phase == .entry, !state.needsSignIn else { return }
        guard let outcome = try? await TvProviderSetup.shared.waitForPhone() else { return }
        if outcome.reason == "timeout" { waitEnded = true }
        guard let key = outcome.foundKey else { return }
        let banner = outcome.providerName.map { String(format: L("%@ added your playlist"), $0) }
        open(key: key, banner: banner)
    }
}

/// A key on the compact pad: one focus target, gold ring and a lift on focus (also the watch-party keypad).
struct KeypadKey: View {
    let label: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(8), style: .continuous)
        Button(action: action) {
            Text(verbatim: label).font(NuvioType.inter(18, .semibold))
                .foregroundStyle(focused ? Color.black : colors.textPrimary)
                .frame(width: dp(32), height: dp(32))
                .background(shape.fill(focused ? Color.white : colors.backgroundCard))
                .overlay(shape.stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
                .scaleEffect(focused ? 1.08 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// Continue: dimmed until twelve valid characters are in, but still focusable (a disabled button would drop
/// focus out from under the viewer mid-typing); pressing it early does nothing.
struct ContinueButton: View {
    var title = "Continue"
    let enabled: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button { if enabled { action() } } label: {
            Text(ui: title).font(NuvioType.labelLarge)
                .foregroundStyle(focused ? Color.black : colors.textPrimary.opacity(enabled ? 1 : 0.4))
                .padding(.horizontal, dp(22)).padding(.vertical, dp(10))
                .background(Capsule().fill(focused ? Color.white : (enabled ? colors.focusBackground : colors.backgroundCard)))
                .overlay(Capsule().stroke(enabled ? colors.focusRing : .clear, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// The gold "Add to <profile>" button.
private struct PrimaryActionButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(verbatim: title).font(NuvioType.labelLargeSemi)
                .foregroundStyle(focused ? Color.black : colors.onSecondary)
                .padding(.horizontal, dp(22)).padding(.vertical, dp(10))
                .background(Capsule().fill(focused ? Color.white : colors.secondary))
                .scaleEffect(focused ? 1.03 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}
