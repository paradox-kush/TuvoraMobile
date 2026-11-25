import SwiftUI
import TuvoraCore

struct HomeRowsSettingsScreen: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @State private var state: HomeCatalogSettingsUiState?
    @State private var renaming: HomeCatalogSettingsItem?
    @State private var name = ""
    @AppStorage(NuvioLayoutPrefs.showHeroKey) private var showHero = true
    @AppStorage(NuvioLayoutPrefs.cwEnabledKey) private var cwEnabled = true
    // Top Shelf switches: device-local (this TV's shared screen), never synced. Defaults live in TopShelfSwitches.
    @AppStorage(TopShelfSwitches.continueWatchingKey) private var topShelfContinue = true
    @AppStorage(TopShelfSwitches.newEpisodesKey) private var topShelfNewEpisodes = true
    @AppStorage(TopShelfSwitches.recentChannelsKey) private var topShelfChannels = false
    @State private var resumeFirst = false

    /// Home sections as Apple TV renders them (the hero is pinned to the top: on/off only).
    private func sections(_ state: HomeCatalogSettingsUiState) -> [HomeSectionEntry] {
        TvHomeSectionsPolicy.shared.resolve(state: state, heroEnabled: showHero, continueWatchingVisible: cwEnabled)
    }

    private func sectionTitle(_ id: String) -> String {
        switch id {
        case "hero": return LK("home_section_hero", "Hero")
        case "continue_watching": return LK("home_section_continue_watching", "Continue Watching")
        case "live_recents": return LK("home_section_recent_channels", "Recent Channels")
        case "new_episodes": return LK("home_section_new_episodes", "New episodes")
        default: return LK("home_section_catalogs", "Catalogs and collections")
        }
    }

    /// Shown / hidden, then the same switches in Layout settings follow (a customised list is the source of truth).
    private func setSection(_ id: String, enabled: Bool) {
        TvHomeSections.shared.setEnabled(id: id, enabled: enabled)
        if id == "hero" { showHero = enabled }
        if id == "continue_watching" { cwEnabled = enabled }
    }

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
                Text(LK("settings_cw_order_title", "CONTINUE WATCHING")).font(NuvioType.labelLarge)
                SettingsActionRow(
                    title: LK("settings_cw_order", "Order"),
                    subtitle: LK("settings_cw_order_helper", "Half-watched things first, then next episodes. We guess; you overrule."),
                    value: resumeFirst ? LK("settings_cw_order_resume", "Pick up where I left off") : LK("settings_cw_order_recent", "Most recent first"),
                    showChevron: false) {
                    TvContinueWatchingOrder.shared.select(resumeFirst: !resumeFirst)
                }
                Text(LK("settings_top_shelf_title", "TOP SHELF")).font(NuvioType.labelLarge)
                SettingsToggleRow(
                    title: LK("settings_top_shelf_cw", "Show Continue Watching in Top Shelf"),
                    subtitle: LK("settings_top_shelf_cw_helper", "Shown when Tuvora is in the top row of your Apple TV. Off shows the plain Tuvora banner."),
                    isOn: topShelfContinue) { topShelfContinue.toggle(); TopShelfPublisher.refresh() }
                SettingsToggleRow(title: LK("settings_top_shelf_new_episodes", "New episodes"), isOn: topShelfNewEpisodes) {
                    topShelfNewEpisodes.toggle(); TopShelfPublisher.refresh()
                }
                SettingsToggleRow(
                    title: LK("settings_top_shelf_channels", "Recent channels"),
                    subtitle: LK("settings_top_shelf_channels_helper", "Channel names show on the Top Shelf, which anyone in the room can see."),
                    isOn: topShelfChannels) { topShelfChannels.toggle(); TopShelfPublisher.refresh() }
                Text(LK("settings_home_order_title", "HOME ORDER")).font(NuvioType.labelLarge)
                let entries = sections(state)
                ForEach(entries, id: \.id) { entry in
                    SettingsActionRow(title: sectionTitle(entry.id), subtitle: entry.id == "catalogs" ? LK("home_section_catalogs_sub_tv", "Their order is set below") : nil,
                                      value: entry.enabled ? "Shown" : "Hidden", showChevron: false) {
                        setSection(entry.id, enabled: !entry.enabled)
                    }.contextMenu {
                        if let up = TvHomeSectionsPolicy.shared.targetIndex(entries: entries, id: entry.id, direction: -1) {
                            Button("Move up") { TvHomeSections.shared.move(id: entry.id, toIndex: up.int32Value) }
                        }
                        if let down = TvHomeSectionsPolicy.shared.targetIndex(entries: entries, id: entry.id, direction: 1) {
                            Button("Move down") { TvHomeSections.shared.move(id: entry.id, toIndex: down.int32Value) }
                        }
                    }
                }
                SettingsActionRow(title: "Reset to Default", showChevron: false) {
                    HomeCatalogSettingsRepository.shared.resetHomeSections()
                    showHero = true; cwEnabled = true
                }
                Text(LK("settings_home_catalogs_title", "HOME CATALOGS")).font(NuvioType.labelLarge)
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
        .task { for await value in TvContinueWatchingOrder.shared.isResumeFirst { resumeFirst = value.boolValue } }
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
