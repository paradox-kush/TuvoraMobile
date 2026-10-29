import SwiftUI
import TuvoraCore

/// NuvioTV's Search (ui/screens/search/SearchScreen.kt), translated. tvOS-native entry: the system
/// search field and keyboard (`.searchable`), never a custom keyboard. Under it: fewer than 2 characters
/// shows the Discover button and recent searches (or "Start Searching"); a query shows skeleton rows,
/// then one row per catalog that answered — add-on catalogs and the IPTV lane from the shared search.
/// Live channels play; everything else opens Details.
struct SearchScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    /// Simulator smoke hooks: `-smokeSearch <query>` types a query; `-smokeDiscover` opens Discover.
    @State private var query: String = SearchScreen.argument("-smokeSearch") ?? ""
    @State private var showDiscover = AppArguments.list.contains("-smokeDiscover")
    @State private var requested: String?
    @State private var results: SearchUiState = TvSearch.shared.results.value
    @State private var recent: [String] = []
    @State private var details: PreviewBox?
    @State private var openingChannel = false

    private var mode: TvSearchMode { TvSearchPolicy.shared.mode(query: query, requested: requested, state: results) }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(alignment: .leading, spacing: dp(16)) { content }
                        .padding(.vertical, dp(16))
                }
                .scrollClipDisabled()
                .onChange(of: results.sections.count) { _, _ in
                    // Smoke hook: `-smokeSearchRow <section key>` scrolls a result row into view.
                    if let key = SearchScreen.argument("-smokeSearchRow"), results.sections.contains(where: { $0.key == key }) {
                        proxy.scrollTo(key, anchor: .top)
                    }
                }
            }
            .searchable(text: $query, prompt: "Search movies & series")
            .background(colors.background)
        }
        // No extra inset: the shell already clears the floating sidebar pill (80 dp), and the system keyboard row
        // is as wide as the screen's safe area, so any inset pushes its trailing delete key off the right edge
        // (a further 24 dp here clipped it; trailing padding only crops it harder). Navigation audit 2026-10-28.
        .task {
            TvSearch.shared.start()
            for await next in TvSearch.shared.results {
                results = next
                if TvSearchPolicy.shared.shouldRecord(query: query, requested: requested, state: next) {
                    TvSearch.shared.record(query: TvSearchPolicy.shared.submittedQuery(raw: query))
                }
                if !next.sections.isEmpty { smokeLog("SMOKE search rows=%d loading=%d titles=%@", next.sections.count, next.isLoading ? 1 : 0, next.sections.map { "\($0.title) (\($0.items.count))" }.joined(separator: " / ")) }
            }
        }
        .task { for await next in TvSearch.shared.recent { recent = next } }
        .task {
            // The phone re-runs the search when the installed add-ons change (e.g. manifests finish loading).
            for await _ in TvSearch.shared.addonSignature {
                if let requested { TvSearch.shared.search(query: requested, force: false) }
            }
        }
        .task(id: query) {
            let submitted = TvSearchPolicy.shared.submittedQuery(raw: query)
            guard !submitted.isEmpty else {
                requested = nil
                TvSearch.shared.search(query: "", force: false)
                return
            }
            try? await Task.sleep(nanoseconds: UInt64(TvSearchPolicy.shared.DEBOUNCE_MS) * 1_000_000)
            guard !Task.isCancelled else { return }
            TvSearch.shared.search(query: submitted, force: false)
            requested = submitted
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
        .fullScreenCover(isPresented: $showDiscover) {
            DiscoverScreen().environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch mode {
        case .start:
            HStack {
                NuvioTextButton(title: "Discover", icon: "md_explore") { showDiscover = true }
                Spacer()
            }
            .padding(.horizontal, dp(48))
            .focusSection()
            if recent.isEmpty {
                NuvioEmptyState(icon: "md_search", title: "Start Searching", subtitle: "Enter at least 2 characters")
            } else {
                RecentSearches(searches: recent) { query = $0 }
                    .padding(.horizontal, dp(52))
            }
        case .loading:
            DeadlineLoading(title: "Search is taking a while", message: "Your add-ons haven't answered yet.", retry: { retry() }) {
                VStack(spacing: 0) { ForEach(0..<2, id: \.self) { _ in SkeletonRow() } }
            }
        case .noCatalogs:
            SearchErrorState(message: StoreCopy.noSearchCatalogsMessage) { retry() }
        case .error:
            SearchErrorState(message: results.errorMessage ?? "Search failed") { retry() }
        case .noResults:
            NuvioEmptyState(icon: "md_search", title: "No Results", subtitle: "Try searching with different keywords")
        case .results:
            ForEach(results.sections.filter { !$0.items.isEmpty }, id: \.key) { section in
                CatalogRowSection(section: section, cardSize: SearchCardSize.poster, onOpen: { open($0) }, onDetails: { details = PreviewBox(preview: $0) }).id(section.key)
            }
            if TvSearchPolicy.shared.showsLoadingMore(query: query, requested: requested, state: results) {
                SkeletonRow()
            }
        }
    }

    private func retry() {
        TvSearch.shared.search(query: TvSearchPolicy.shared.submittedQuery(raw: query), force: true)
    }

    private func open(_ item: MetaPreview) {
        // IPTV live channels play straight away (the hub does the same); anything else opens Details.
        guard IptvContentClassifierAccess.shared.classifier.isLiveId(id: item.id) else { details = PreviewBox(preview: item); return }
        guard !openingChannel else { return }
        openingChannel = true
        Task {
            defer { openingChannel = false }
            if let session = try? await TvIptvBrowse.shared.playChannel(contentId: item.id, name: item.name, logo: item.logo) {
                playback.play(session)
            } else {
                playback.notify("\(item.name) isn't available right now.")
            }
        }
    }

    static func argument(_ flag: String) -> String? {
        let args = AppArguments.list
        guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
        return args[i + 1]
    }
}

enum SearchCardSize {
    /// PosterCardDefaults: the poster preference, height ×1.5.
    static var poster: CGSize { CGSize(width: dp(NuvioCardSize.posterWidthDp), height: dp(NuvioCardSize.posterWidthDp * 1.5)) }
}

/// The skeleton CatalogRowSection NuvioTV shows while catalogs answer: blank header, 8 shimmer posters.
private struct SkeletonRow: View {
    var body: some View {
        let size = SearchCardSize.poster
        VStack(alignment: .leading, spacing: dp(12)) {
            NuvioShimmer(cornerRadius: dp(6)).frame(width: dp(220), height: dp(22))
            HStack(spacing: NuvioTokens.Layout.itemGap) {
                ForEach(0..<8, id: \.self) { _ in NuvioShimmer().frame(width: size.width, height: size.height) }
            }
        }
        .padding(.horizontal, dp(48))
        .padding(.bottom, dp(24))
    }
}

/// ErrorState (components/ErrorState.kt): bodyLarge TextSecondary message, 16dp, Retry button.
private struct SearchErrorState: View {
    let message: String
    let retry: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(spacing: dp(16)) {
            Text(ui: message).font(NuvioType.bodyLarge).foregroundStyle(colors.textSecondary).multilineTextAlignment(.center)
            NuvioTextButton(title: "Retry", action: retry)
        }
        .frame(maxWidth: .infinity).frame(height: dp(400))
    }
}

/// RecentSearchesSection: "Recent searches" titleMedium with a "Clear history" button on the right,
/// then one row per query — a full-width button (BackgroundCard, radius md, focused FocusBackground,
/// scale 1.02) and a 36dp close button (2dp FocusRing when focused), 10dp apart.
private struct RecentSearches: View {
    let searches: [String]
    let onSelect: (String) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            HStack {
                Text("Recent searches").font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                Spacer()
                NuvioTextButton(title: "Clear history") { TvSearch.shared.clearRecent() }
            }
            ForEach(searches, id: \.self) { search in
                HStack(spacing: dp(16)) {
                    RecentSearchButton(title: search) { onSelect(search) }
                    RemoveRecentButton { TvSearch.shared.removeRecent(query: search) }
                }
                .focusSection()
            }
        }
        .focusSection()
    }
}

private struct RecentSearchButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(ui: title).font(NuvioType.labelLarge).lineLimit(1)
                .foregroundStyle(focused ? colors.primary : colors.textPrimary)
                .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(focused ? colors.focusBackground : colors.backgroundCard))
                .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1, anchor: .leading)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

private struct RemoveRecentButton: View {
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Image("md_close").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                .foregroundStyle(colors.textPrimary)
                .frame(width: dp(36), height: dp(36))
                .background(RoundedRectangle(cornerRadius: dp(12), style: .continuous).fill(focused ? colors.focusBackground : .clear))
                .overlay(RoundedRectangle(cornerRadius: dp(12), style: .continuous).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Discover

/// NuvioTV's Discover (DiscoverScreen.kt + SearchDiscoverSection.kt), reached from Search's Discover
/// button (discover_location IN_SEARCH, the default): "Discover" headline, Type / Catalog / Genre
/// pickers, an "add-on • type • genre" line, then an adaptive poster grid that pages in as you scroll,
/// ending in a "Load more" card. Menu goes back to Search.
private struct DiscoverScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var state: DiscoverUiState = TvSearch.shared.discover.value
    @State private var details: PreviewBox?

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: dp(12)) {
                    Text("Discover").font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                    pickers.focusSection()
                    if let catalog = state.selectedCatalog {
                        Text(([catalog.addonName, TvSearch.shared.typeLabel(type: catalog.type)] + [state.selectedGenre].compactMap { $0 })
                            .joined(separator: " • "))
                            .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    }
                    grid
                }
                .padding(.horizontal, dp(48)).padding(.top, 60).padding(.bottom, dp(32))
            }
            .scrollClipDisabled()
        }
        .task {
            TvSearch.shared.start()
            TvSearch.shared.refreshDiscover()
            for await next in TvSearch.shared.discover {
                state = next
                smokeLog("SMOKE discover items=%d loading=%d", next.items.count, next.isLoading ? 1 : 0)
            }
        }
        .task { for await _ in TvSearch.shared.addonSignature { TvSearch.shared.refreshDiscover() } }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private var pickers: some View {
        HStack(spacing: dp(12)) {
            NuvioDropdownPicker(title: "Type", value: state.selectedType.map { TvSearch.shared.typeLabel(type: $0) } ?? "Select",
                                selectedValue: state.selectedType,
                                options: state.typeOptions.map { NuvioPickerOption(value: $0, label: TvSearch.shared.typeLabel(type: $0)) }) {
                TvSearch.shared.selectDiscoverType(type: $0.value)
            }
            NuvioDropdownPicker(title: "Catalog", value: state.selectedCatalog?.catalogName ?? "Select",
                                selectedValue: state.selectedCatalogKey,
                                options: state.catalogOptions.map { NuvioPickerOption(value: $0.key, label: $0.catalogName) }) {
                TvSearch.shared.selectDiscoverCatalog(key: $0.value)
            }
            NuvioDropdownPicker(title: "Genre", value: state.selectedGenre ?? "Default",
                                selectedValue: state.selectedGenre ?? "__default__",
                                options: [NuvioPickerOption(value: "__default__", label: "Default")]
                                    + state.genreOptions.map { NuvioPickerOption(value: $0, label: $0) }) {
                TvSearch.shared.selectDiscoverGenre(genre: $0.value == "__default__" ? nil : $0.value)
            }
        }
    }

    @ViewBuilder
    private var grid: some View {
        let size = SearchCardSize.poster
        if state.isLoading && state.items.isEmpty {
            DeadlineLoading(title: "Discover is taking a while", message: "Your add-ons haven't answered yet.",
                            retry: { TvSearch.shared.refreshDiscover() }) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: size.width), spacing: dp(10))], alignment: .leading, spacing: dp(16)) {
                    ForEach(0..<12, id: \.self) { _ in NuvioShimmer().frame(width: size.width, height: size.height) }
                }
                .padding(.top, dp(6))
            }
        } else if !state.items.isEmpty {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: size.width), spacing: dp(10))], alignment: .leading, spacing: dp(16)) {
                ForEach(Array(state.items.enumerated()), id: \.element.id) { index, item in
                    NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                    width: size.width, height: size.height) { details = PreviewBox(preview: item) }
                        .titleActions(item) { details = PreviewBox(preview: item) }
                        .onAppear {
                            // DiscoverGrid auto-loads when the last 6 cards come into view.
                            if index >= state.items.count - 6 && state.canLoadMore && !state.isLoading { TvSearch.shared.loadMoreDiscover() }
                        }
                }
                if state.canLoadMore || state.isLoading {
                    DiscoverActionCard(loading: state.isLoading, size: size) { TvSearch.shared.loadMoreDiscover() }
                }
            }
            .padding(.top, dp(6))
            .focusSection()
        } else if state.selectedCatalog == nil {
            NuvioEmptyState(icon: "md_search", title: "Select a catalog", subtitle: "Choose a discover catalog to browse")
        } else {
            NuvioEmptyState(icon: "md_search", title: "No content found", subtitle: state.errorMessage ?? "Try a different genre or catalog")
        }
    }
}

/// DiscoverActionCard: a poster-sized BackgroundCard tile reading "Load more" / "Loading...".
private struct DiscoverActionCard: View {
    let loading: Bool
    let size: CGSize
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(loading ? "Loading..." : "Load more").font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                .frame(width: size.width, height: size.height)
                .background(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous).fill(colors.backgroundCard))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous)
                    .stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}
