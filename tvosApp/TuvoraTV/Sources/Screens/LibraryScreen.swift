import SwiftUI
import TuvoraCore

/// NuvioTV's Library (ui/screens/library/LibraryScreen.kt, Saved view), translated: "Library" headline
/// with the source on the right (TUVORA / TRAKT / SIMKL / MDBLIST), the pickers — List (tracking
/// sources), Type, Sort; then Genre, Year, Watched — a Sync button for tracking sources, and an adaptive
/// poster grid. Data: the phone's shared library via TvLibrary (TvLibraryProjection does the filtering).
struct LibraryScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var view: TvLibraryView?
    @State private var details: PreviewBox?
    /// NuvioTV LibraryViewMode (Saved / Cloud). Smoke hook: `-smokeLibraryCloud`. Cloud is the debrid
    /// cloud library, so store builds (AppFeaturePolicy.debridEnabled false) are Saved only.
    private static let cloudAvailable = AppFeaturePolicy.shared.debridEnabled
    @State private var cloud = cloudAvailable && AppArguments.list.contains("-smokeLibraryCloud")
    @State private var managingLists = AppArguments.list.contains("-smokeManageLists")
    /// The Servers source (Jellyfin / Emby libraries). Only offered once a server is connected. Smoke hook: `-smokeLibraryServers`.
    private let hasServers = TvServerLibrary.shared.hasServers()
    @State private var servers = AppArguments.list.contains("-smokeLibraryServers")

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(16)) {
                header
                if Self.cloudAvailable || hasServers { viewModeRow }
                if servers && hasServers {
                    ServerLibraryView(open: open)
                } else if cloud && Self.cloudAvailable {
                    CloudLibraryView()
                } else if let view {
                    selectors(view).focusSection()
                    if view.sourceMode != .local {
                        HStack(spacing: dp(12)) {
                            if TvLibraryLists.shared.info() != nil {
                                NuvioTextButton(title: "Manage Lists", enabled: !view.isLoading) { managingLists = true }
                            }
                            NuvioTextButton(title: view.isLoading ? "Syncing…" : "Sync", enabled: !view.isLoading) { TvLibrary.shared.refresh() }
                            Spacer()
                        }
                        .focusSection()
                    }
                    grid(view)
                } else {
                    loading
                }
            }
            .padding(.horizontal, dp(48)).padding(.top, dp(24)).padding(.bottom, dp(32))
        }
        .scrollClipDisabled()
        .task {
            for await next in TvLibrary.shared.views() {
                view = next
                smokeLog("SMOKE library source=%@ items=%d loaded=%d loading=%d", String(describing: next.sourceMode), next.items.count, next.isLoaded ? 1 : 0, next.isLoading ? 1 : 0)
            }
        }
        .sheet(isPresented: $managingLists) {
            ManageListsSheet { managingLists = false }.environment(\.nuvio, colors)
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    /// Saved IPTV channels play straight away, as in Search and the IPTV hub; titles open Details.
    private func open(_ item: MetaPreview) {
        guard IptvContentClassifierAccess.shared.classifier.isLiveId(id: item.id) else { details = PreviewBox(preview: item); return }
        Task {
            if let session = try? await TvIptvBrowse.shared.playChannel(contentId: item.id, name: item.name, logo: item.logo) {
                playback.play(session)
            } else {
                playback.notify("\(item.name) isn't available right now.")
            }
        }
    }

    /// LibraryViewModeRow: Saved / Cloud buttons (selected FocusBackground); the cloud view adds its
    /// refresh button on the right.
    private var viewModeRow: some View {
        HStack(spacing: dp(12)) {
            NuvioTextButton(title: "Saved", selected: !cloud && !servers) { cloud = false; servers = false }
            if Self.cloudAvailable { NuvioTextButton(title: "Cloud", selected: cloud) { cloud = true; servers = false } }
            if hasServers { NuvioTextButton(title: "Servers", selected: servers) { servers = true; cloud = false } }
            Spacer()
            if cloud { NuvioTextButton(title: "Refresh cloud library") { TvCloudLibrary.shared.refresh() } }
        }
        .focusSection()
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Library").font(NuvioType.headlineMedium).kerning(dp(0.5)).foregroundStyle(colors.textPrimary)
            Spacer()
            if let view {
                Text(servers && hasServers ? "SERVERS" : cloud && Self.cloudAvailable ? "CLOUD" : sourceLabel(view.sourceMode)).font(NuvioType.labelLarge).kerning(dp(2)).foregroundStyle(colors.textTertiary)
            }
        }
    }

    private func selectors(_ view: TvLibraryView) -> some View {
        VStack(spacing: dp(10)) {
            HStack(spacing: dp(12)) {
                if view.sourceMode != .local {
                    NuvioDropdownPicker(title: "List", value: view.lists.first { $0.key == view.selectedListKey }?.label ?? "Select",
                                        selectedValue: view.selectedListKey,
                                        options: view.lists.map { NuvioPickerOption(value: $0.key, label: $0.label) }) {
                        TvLibrary.shared.selectList(key: $0.value)
                    }
                }
                NuvioDropdownPicker(title: "Type", value: view.selectedType.map(Self.typeLabel) ?? "All",
                                    selectedValue: view.selectedType ?? Self.allKey,
                                    options: [NuvioPickerOption(value: Self.allKey, label: "All (\(view.allTypesCount))")]
                                        + view.types.map { NuvioPickerOption(value: $0.key, label: "\(Self.typeLabel($0.key)) (\($0.count))") }) {
                    TvLibrary.shared.selectType(key: $0.value == Self.allKey ? nil : $0.value)
                }
                NuvioDropdownPicker(title: "Sort", value: Self.sortLabel(view.selectedSort),
                                    selectedValue: Self.sortLabel(view.selectedSort),
                                    options: view.sortOptions.map { NuvioPickerOption(value: Self.sortLabel($0), label: Self.sortLabel($0)) }) { picked in
                    if let option = view.sortOptions.first(where: { Self.sortLabel($0) == picked.value }) { TvLibrary.shared.selectSort(option: option) }
                }
            }
            HStack(spacing: dp(12)) {
                if !view.genres.isEmpty {
                    NuvioDropdownPicker(title: "Genre", value: view.selectedGenre ?? "All", selectedValue: view.selectedGenre ?? Self.allKey,
                                        options: [NuvioPickerOption(value: Self.allKey, label: "All")]
                                            + view.genres.map { NuvioPickerOption(value: $0.key, label: "\($0.label) (\($0.count))") }) {
                        TvLibrary.shared.selectGenre(genre: $0.value == Self.allKey ? nil : $0.value)
                    }
                }
                if !view.years.isEmpty {
                    NuvioDropdownPicker(title: "Year", value: view.selectedYear ?? "All", selectedValue: view.selectedYear ?? Self.allKey,
                                        options: [NuvioPickerOption(value: Self.allKey, label: "All")]
                                            + view.years.map { NuvioPickerOption(value: $0.key, label: "\($0.label) (\($0.count))") }) {
                        TvLibrary.shared.selectYear(year: $0.value == Self.allKey ? nil : $0.value)
                    }
                }
                NuvioDropdownPicker(title: "Watched", value: Self.watchedLabel(view.watched), selectedValue: Self.watchedLabel(view.watched),
                                    options: [TvLibraryWatchedFilter.all, .watched, .unwatched].map { NuvioPickerOption(value: Self.watchedLabel($0), label: Self.watchedLabel($0)) }) { picked in
                    if let filter = [TvLibraryWatchedFilter.all, .watched, .unwatched].first(where: { Self.watchedLabel($0) == picked.value }) {
                        TvLibrary.shared.selectWatched(filter: filter)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func grid(_ view: TvLibraryView) -> some View {
        let size = SearchCardSize.poster
        let columns = [GridItem(.adaptive(minimum: size.width), spacing: dp(12), alignment: .top)]
        if view.items.isEmpty && (view.isLoading || !view.isLoaded) {
            loading
        } else if view.items.isEmpty, let error = view.errorMessage {
            NuvioStateMessage(title: "Failed to refresh library", message: error) { TvLibrary.shared.refresh() }
                .frame(height: dp(300))
        } else if view.items.isEmpty {
            let kind = view.selectedType.map { Self.typeLabel($0).lowercased() } ?? "items"
            NuvioEmptyState(icon: "md_bookmark_border",
                            title: view.sourceMode == .local ? "No \(kind) yet"
                                : view.sourceMode == .simkl ? "No \(kind) in this status" : "No \(kind) in this list",
                            subtitle: view.sourceMode == .local ? "Start saving your favorites to see them here"
                                : view.sourceMode == .simkl ? "Use + in details to add items to a Simkl status" : "Use + in details to add items to watchlist or lists")
        } else {
            LazyVGrid(columns: columns, alignment: .leading, spacing: dp(16)) {
                ForEach(view.items, id: \.self) { item in
                    NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                    width: size.width, height: size.height) { open(item.toMetaPreview()) }
                        .titleActions(item.toMetaPreview(), libraryItem: item) { details = PreviewBox(preview: item.toMetaPreview()) }
                }
            }
            .focusSection()
        }
    }

    /// NuvioTV shows a spinner and "Syncing library…"; the grid's shimmer stands in for the spinner.
    private var loading: some View {
        let size = SearchCardSize.poster
        return DeadlineLoading(title: "Library is taking a while", message: "Tuvora couldn't sync your library yet.",
                               retry: { TvLibrary.shared.refresh() }) {
            VStack(alignment: .leading, spacing: dp(14)) {
                Text("Syncing library…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: size.width), spacing: dp(12))], alignment: .leading, spacing: dp(16)) {
                    ForEach(0..<14, id: \.self) { _ in NuvioShimmer().frame(width: size.width, height: size.height) }
                }
            }
        }
    }

    private func sourceLabel(_ mode: LibrarySourceMode) -> String {
        switch mode {
        case .trakt: return "TRAKT"
        case .simkl: return "SIMKL"
        case .mdblist: return "MDBLIST"
        default: return "TUVORA"
        }
    }

    static let allKey = "__all__"

    /// TypeLabelFormatter.localizedContentType.
    static func typeLabel(_ key: String) -> String {
        switch key.lowercased() {
        case "movie": return "Movie"
        case "series": return "Series"
        case "tv": return "TV"
        default: return key.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ").capitalized
        }
    }

    static func sortLabel(_ option: LibrarySortOption) -> String {
        switch option {
        case .addedDesc: return "Added ↓"
        case .addedAsc: return "Added ↑"
        case .titleAsc: return "Title A-Z"
        case .titleDesc: return "Title Z-A"
        default: return "Provider Order"
        }
    }

    static func watchedLabel(_ filter: TvLibraryWatchedFilter) -> String {
        switch filter {
        case .watched: return "Watched"
        case .unwatched: return "Unwatched"
        default: return "All"
        }
    }
}
