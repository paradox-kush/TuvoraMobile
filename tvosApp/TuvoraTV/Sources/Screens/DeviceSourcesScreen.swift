import SwiftUI
import TuvoraCore

@MainActor
final class DeviceSourcesModel: ObservableObject {
    @Published var state: TvDeviceSourceState?
    @Published var error: String?
    @Published var working = false

    func observe() async {
        for await value in TvDeviceSources.shared.state { state = value }
    }

    func action(_ operation: @escaping () async throws -> String?) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            do { error = try await operation() }
            catch is CancellationError { }
            catch { self.error = "Could not save your choice. Please retry." }
        }
    }
}

/// Startup cannot use account sources until this installation has resolved its own saved choices.
struct DeviceSourcesGate<Content: View>: View {
    @StateObject private var model = DeviceSourcesModel()
    @ViewBuilder var content: Content
    var body: some View {
        Group {
            if model.state?.ready == true { content }
            else { DeviceSourcesScreen(model: model, onDone: nil).frame(maxWidth: .infinity, maxHeight: .infinity) }
        }
        .task { await model.observe() }
    }
}

/// Native tvOS counterpart of DeviceSourceSettings: same engine, outbox and explicit restore decision.
struct DeviceSourcesScreen: View {
    @ObservedObject var model: DeviceSourcesModel
    let onDone: (() -> Void)?
    @Environment(\.nuvio) private var colors
    @State private var name = "Apple TV"
    @State private var manageSetups = false
    @State private var restoring: DeviceSetupSummary?

    var body: some View {
        NuvioDialog(title: model.state?.ready == true ? "Sources on this device" : "Set up this device",
                    subtitle: model.state?.ready == true ? model.state?.label : "Use your account’s addons, servers and IPTV.", width: dp(660)) {
            if let state = model.state {
                if let message = model.error ?? state.message {
                    Text(message).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                }
                if state.busy || model.working {
                    HStack { ProgressView(); Text("Syncing…").font(NuvioType.bodyMedium) }
                }
                if state.ready {
                    sourceRows(state)
                    if state.mode != "local" {
                        SettingsActionRow(title: "Manage saved setups", showChevron: false) { manageSetups.toggle() }
                            .accessibilityIdentifier("deviceSources.manage")
                    }
                }
                if !state.ready || manageSetups {
                    setupRows(state)
                }
                SettingsActionRow(title: "Refresh", showChevron: false) {
                    model.action { try await TvDeviceSources.shared.refresh() }
                }.accessibilityIdentifier("deviceSources.refresh")
                if state.ready, let onDone {
                    SettingsActionRow(title: "Done", showChevron: false, action: onDone)
                        .accessibilityIdentifier("deviceSources.done")
                }
            } else {
                ProgressView()
            }
        }
        .focusSection()
        .confirmationDialog("Replace previous installation?", isPresented: Binding(get: { restoring != nil }, set: { if !$0 { restoring = nil } }), titleVisibility: .visible) {
            if let setup = restoring {
                Button("Restore setup") {
                    restoring = nil
                    model.action { try await TvDeviceSources.shared.select(label: setup.label, setupId: setup.id, copy: false) }
                }
            }
            Button("Cancel", role: .cancel) { restoring = nil }
        } message: {
            Text("Its previous installation will stop saving changes to this setup. Source sign-ins are not copied.")
        }
    }

    @ViewBuilder private func sourceRows(_ state: TvDeviceSourceState) -> some View {
        ForEach([("addon", "Addons"), ("media_server", "Media servers"), ("iptv", "IPTV playlists")], id: \.0) { kind, title in
            let rows = state.sources.filter { $0.kind == kind }
            if !rows.isEmpty {
                Text(title).font(NuvioType.titleMediumSemi).frame(maxWidth: .infinity, alignment: .leading)
                ForEach(rows, id: \.id) { source in
                    SettingsActionRow(title: source.name,
                                      subtitle: source.accountEnabled ? nil : "Disabled for all devices",
                                      value: source.enabled ? "On" : "Off", showChevron: false) {
                        guard source.accountEnabled else { return }
                        model.action { try await TvDeviceSources.shared.setEnabled(sourceId: source.id, enabled: !source.enabled) }
                    }.accessibilityIdentifier("deviceSources.source.\(source.id)")
                    if source.conflict {
                        Text("Changed elsewhere. Choose which setting to keep.").font(NuvioType.bodySmall)
                        SettingsActionRow(title: "Use saved choice", showChevron: false) {
                            model.action { try await TvDeviceSources.shared.resolve(sourceId: source.id, applyMine: false) }
                        }
                        SettingsActionRow(title: "Apply my choice", showChevron: false) {
                            model.action { try await TvDeviceSources.shared.resolve(sourceId: source.id, applyMine: true) }
                        }
                    }
                }
            }
        }
        if state.sources.isEmpty {
            Text("No sources in this profile yet.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
        }
    }

    @ViewBuilder private func setupRows(_ state: TvDeviceSourceState) -> some View {
        if state.mode == "storage_error", state.localOnly {
            SettingsActionRow(title: "Start local setup again",
                              subtitle: "Saved choices could not be read. Starting again uses account defaults.", showChevron: false) {
                model.action { try await TvDeviceSources.shared.startLocal() }
            }
        } else if !state.localOnly && state.mode != "unavailable" {
            TextField("Device name", text: $name).onChange(of: name) { _, value in name = String(value.prefix(120)) }
                .accessibilityIdentifier("deviceSources.name")
            SettingsActionRow(title: "Continue", showChevron: false) {
                guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                model.action { try await TvDeviceSources.shared.select(label: name, setupId: nil, copy: false) }
            }.accessibilityIdentifier("deviceSources.continue")
            ForEach(state.setups, id: \.id) { setup in
                SettingsActionRow(title: setup.label, subtitle: "Restore saved settings", value: "Restore") { restoring = setup }
                SettingsActionRow(title: "Copy to this device", subtitle: setup.label, showChevron: false) {
                    guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    model.action { try await TvDeviceSources.shared.select(label: name, setupId: setup.id, copy: true) }
                }
            }
        }
    }
}

struct DeviceSourcesSettingsDialog: View {
    @StateObject private var model = DeviceSourcesModel()
    @EnvironmentObject private var dialogs: SettingsDialogs
    var body: some View {
        DeviceSourcesScreen(model: model, onDone: { dialogs.pop() })
            .task { await model.observe() }
            .task { _ = try? await TvDeviceSources.shared.refresh() }
    }
}
