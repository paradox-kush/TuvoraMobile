import SwiftUI
import TuvoraCore

struct TitlePresentationSettingsScreen: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @State private var state: MetaScreenSettingsUiState?
    private let repo = MetaScreenSettingsRepository.shared
    private let sections: [MetaScreenSectionKey] = [.overview, .episodes, .cast, .comments, .trailers, .production, .collection, .moreLikeThis]

    var body: some View {
        NuvioDialog(title: "Title details", subtitle: "Choose details, episode layout and spoiler protection.", width: dp(660)) {
            if let state {
                SettingsToggleRow(title: "Show overall ratings", isOn: state.showOverallRatings) {
                    repo.setShowOverallRatings(enabled: !state.showOverallRatings)
                }.accessibilityIdentifier("titleSettings.ratings")
                SettingsToggleRow(title: "Blur unwatched episodes", isOn: state.blurUnwatchedEpisodes) {
                    repo.setBlurUnwatchedEpisodes(enabled: !state.blurUnwatchedEpisodes)
                }.accessibilityIdentifier("titleSettings.spoilers")
                SettingsActionRow(title: "Episode layout", value: state.episodeCardStyle == .list ? "List" : "Horizontal", showChevron: false) {
                    repo.setEpisodeCardStyle(style: state.episodeCardStyle == .list ? .horizontal : .list)
                }.accessibilityIdentifier("titleSettings.episodeLayout")
                SettingsActionRow(title: "Episode ratings", value: ratingLabel(state.episodeRatingsVisibility), showChevron: false) {
                    let values: [EpisodeRatingsVisibility] = [.showAll, .hideEpisodes, .hideUnwatchedEpisodes]
                    let index = values.firstIndex(of: state.episodeRatingsVisibility) ?? 0
                    repo.setEpisodeRatingsVisibility(visibility: values[(index + 1) % values.count])
                }.accessibilityIdentifier("titleSettings.episodeRatings")
                Text("Visible sections").font(NuvioType.titleMediumSemi)
                ForEach(state.items.filter { sections.contains($0.key) }, id: \.key) { item in
                    SettingsToggleRow(title: item.title, isOn: item.enabled) { repo.setEnabled(key: item.key, enabled: !item.enabled) }
                        .accessibilityIdentifier("titleSettings.section.\(item.key.name)")
                }
            } else { ProgressView() }
            SettingsActionRow(title: "Done", showChevron: false) { dialogs.pop() }
        }
        .task { repo.ensureLoaded(); for await value in repo.uiState { state = value } }
    }
    private func ratingLabel(_ value: EpisodeRatingsVisibility) -> String {
        switch value { case .hideEpisodes: return "Hide all"; case .hideUnwatchedEpisodes: return "Watched episodes only"; default: return "Show all" }
    }
}

struct CustomThemeSettingsScreen: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @State private var hex = ""
    @State private var error: String?
    var body: some View {
        NuvioDialog(title: "Custom color", subtitle: "Choose the accent color used across the app.", width: dp(660)) {
            Circle().fill(colors.secondary).frame(width: dp(40), height: dp(40))
            TextField("Hex color, e.g. #1E88E5", text: $hex)
                .autocorrectionDisabled().accessibilityIdentifier("theme.hex")
            if let error { Text(error).font(NuvioType.bodyMedium).foregroundStyle(colors.error).accessibilityIdentifier("theme.error") }
            SettingsActionRow(title: "Apply", showChevron: false) {
                error = TvAppearance.shared.saveSolid(hex: hex)
                if error == nil { dialogs.pop() }
            }.accessibilityIdentifier("theme.apply")
            SettingsActionRow(title: "Cancel", showChevron: false) { dialogs.pop() }
        }
        .task {
            ThemeSettingsRepository.shared.ensureLoaded()
            hex = TvAppearance.shared.colorHex()
        }
    }
}
