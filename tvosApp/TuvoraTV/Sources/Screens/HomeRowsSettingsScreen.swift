import SwiftUI
import TuvoraCore

struct HomeRowsSettingsScreen: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @State private var state: HomeCatalogSettingsUiState?
    @State private var renaming: HomeCatalogSettingsItem?
    @State private var name = ""

    var body: some View {
        NuvioDialog(title: "Home catalogs", subtitle: "Choose which rows appear on Home and their order.", width: dp(660)) {
            if let state {
                SettingsToggleRow(title: "Show catalog type", isOn: state.showCatalogType) {
                    HomeCatalogSettingsRepository.shared.setShowCatalogType(enabled: !state.showCatalogType)
                }
                SettingsToggleRow(title: "Hide unreleased content", isOn: state.hideUnreleasedContent) {
                    HomeCatalogSettingsRepository.shared.setHideUnreleasedContent(enabled: !state.hideUnreleasedContent)
                }
                SettingsToggleRow(title: "Show Live TV on Home", isOn: state.showLiveOnHome) {
                    HomeCatalogSettingsRepository.shared.setShowLiveOnHome(enabled: !state.showLiveOnHome)
                }
                ForEach(state.items, id: \.key) { item in
                    SettingsActionRow(title: item.displayTitle, subtitle: item.addonName,
                                      value: item.enabled ? "Shown" : "Hidden", showChevron: false) {
                        HomeCatalogSettingsRepository.shared.setEnabled(key: item.key, enabled: !item.enabled)
                    }.contextMenu {
                        Button("Move up") { HomeCatalogSettingsRepository.shared.moveUp(key: item.key) }
                        Button("Move down") { HomeCatalogSettingsRepository.shared.moveDown(key: item.key) }
                        Button("Rename") { name = item.displayTitle; renaming = item }
                        if !item.isCollection && !item.isContributed {
                            Button(item.heroSourceEnabled ? "Remove from hero" : "Use for hero") {
                                HomeCatalogSettingsRepository.shared.setHeroSourceEnabled(key: item.key, enabled: !item.heroSourceEnabled)
                            }
                        }
                    }
                }
            } else { ProgressView() }
            SettingsActionRow(title: "Done", showChevron: false) { dialogs.pop() }
        }
        .task {
            TvHome.shared.start(); TvCollections.shared.start()
            for await value in HomeCatalogSettingsRepository.shared.uiState { state = value }
        }
        .sheet(isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            NuvioDialog(title: "Rename") {
                TextField("Title", text: $name)
                SettingsActionRow(title: "Save", showChevron: false) {
                    if let item = renaming { HomeCatalogSettingsRepository.shared.setCustomTitle(key: item.key, title: name) }
                    renaming = nil
                }
            }
        }
    }
}
