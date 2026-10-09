import SwiftUI
import TuvoraCore

// NuvioTV's other two home layouts (ui/screens/home/HomeScreen.kt: HomeLayout CLASSIC / GRID), and the
// pieces all three share: the Continue Watching row in its three card styles and the catalog row.

/// Settings → Layout → Continue Watching style (NuvioTV ContinueWatchingCardStyle).
enum CwStyle: String {
    case card, wide, poster

    /// ModernHomeContent.kt:656-667 — CARD pref×1.24×1.34 at 1.77:1; WIDE pref×2.1 at 0.4; POSTER the
    /// Modern portrait catalog card.
    var modernSize: CGSize {
        let w = NuvioCardSize.posterWidthDp
        switch self {
        case .card: return ModernCardSize.landscape
        case .wide: return CGSize(width: dp(w * 2.1), height: dp(w * 2.1 * 0.4))
        case .poster: return ModernCardSize.portrait
        }
    }

    /// ClassicHomeContent.kt:115-140 — catalog posters ×1.35, secondary ×1.2.
    var classicSize: CGSize {
        let w = NuvioCardSize.posterWidthDp
        switch self {
        case .card: return CGSize(width: dp(w * 1.2 * 16 / 9), height: dp(w * 1.2))
        case .wide: return CGSize(width: dp(w * 1.2 * 2.5), height: dp(w * 1.2 * 2.5 * 0.4))
        case .poster: return CGSize(width: dp(w * 1.35), height: dp(w * 1.5 * 1.35))
        }
    }

    /// GridContinueWatchingSection.kt:170-178 — fixed sizes.
    var gridSize: CGSize {
        switch self {
        case .card: return CGSize(width: dp(220), height: dp(124))
        case .wide: return CGSize(width: dp(320), height: dp(128))
        case .poster: return CGSize(width: dp(120), height: dp(180))
        }
    }
}

/// The Continue Watching row: Modern's titleMedium SemiBold shelf header at the 52dp gutter, or Classic /
/// Grid's headlineMedium header at 48dp. Long press offers "Remove from Continue Watching".
struct ContinueWatchingRow: View {
    enum Header { case modern, classic, grid }
    let items: [TvCwItem]
    let style: CwStyle
    let size: CGSize
    let header: Header
    var onFocus: (TvCwItem) -> Void = { _ in }
    let onOpen: (TvCwItem) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let gutter = header == .modern ? NuvioTokens.Layout.gutter : dp(48)
        VStack(alignment: .leading, spacing: 0) {
            switch header {
            case .modern:
                NuvioShelfHeader(title: "Continue Watching") { EmptyView() }
            case .classic, .grid:
                Text("Continue Watching").font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                    .padding(.horizontal, gutter).padding(.top, header == .grid ? dp(24) : 0)
                    .padding(.bottom, header == .grid ? dp(12) : dp(16))
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: header == .classic ? dp(16) : NuvioTokens.Layout.itemGap) {
                    ForEach(items, id: \.videoId) { item in
                        ContinueWatchingCard(item: item, style: style, size: size, onFocus: { onFocus(item) }) { onOpen(item) }
                            .contextMenu {
                                Button("Remove from Continue Watching", role: .destructive) {
                                    WatchProgressRepository.shared.removeProgress(
                                        contentId: item.parentMetaId, seasonNumber: item.seasonNumber, episodeNumber: item.episodeNumber)
                                }
                            }
                    }
                }
                .padding(.horizontal, gutter).padding(.vertical, dp(8))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }
}

/// CatalogRowSection (components/CatalogRowSection.kt): headlineMedium "Name - Type" and "from <add-on>"
/// labelMedium TextTertiary at the 48dp gutter, 12dp above ContentCards 12dp apart. Landscape items
/// (IPTV channels) use the landscape tile with the logo fitted. Long press = title actions.
struct CatalogRowSection: View {
    let section: HomeCatalogSection
    let cardSize: CGSize
    var onFocus: (MetaPreview) -> Void = { _ in }
    let onOpen: (MetaPreview) -> Void
    var onDetails: ((MetaPreview) -> Void)? = nil
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            VStack(alignment: .leading, spacing: dp(4)) {
                Text(section.title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
                Text("from \(section.addonName)").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
            }
            .padding(.horizontal, dp(48))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: NuvioTokens.Layout.itemGap) {
                    ForEach(section.items, id: \.id) { item in
                        let landscape = item.posterShape == .landscape
                        let size = landscape ? NuvioCardSize.hubLandscape : cardSize
                        NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                        width: size.width, height: size.height, onFocus: { onFocus(item) }) { onOpen(item) }
                            .environment(\.artworkContentMode, landscape ? .fit : .fill)
                            .titleActions(item) { (onDetails ?? onOpen)(item) }
                    }
                }
                .padding(.horizontal, dp(48)).padding(.vertical, dp(8))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }
}

// MARK: - Classic

/// ClassicHomeContent.kt: the 400dp HeroCarousel over a full-page backdrop of the active slide, then
/// Continue Watching and the catalog rows (posters ×1.35), 32dp apart.
struct ClassicHomeView: View {
    let rows: HomeUiState?
    /// Catalog rows and collections in the profile's order (TvHomeCollectionsPolicy).
    let entries: [TvHomeEntry]
    let continueWatching: [TvCwItem]
    let showHero: Bool
    let cwStyle: CwStyle
    let onOpenCw: (TvCwItem) -> Void
    let onOpen: (MetaPreview) -> Void
    let onOpenFolder: (FolderTarget) -> Void
    @Environment(\.nuvio) private var colors
    @State private var active: MetaPreview?
    @State private var heroFocused = true

    private var heroItems: [MetaPreview] { rows.map { Array($0.heroItems) } ?? [] }

    var body: some View {
        ZStack(alignment: .topLeading) {
            colors.background.ignoresSafeArea()
            if showHero, let active {
                HeroBackdrop(url: active.banner ?? active.landscapePoster ?? active.poster)
                    .opacity(heroFocused ? 1 : 0)
                    .animation(NuvioTokens.Motion.overlay, value: heroFocused)
                    .id(active.id)
                    .transition(.opacity)
            }
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: dp(32)) {
                    if showHero && !heroItems.isEmpty {
                        HeroCarousel(items: heroItems, onActive: { active = $0 }, onFocusChange: { heroFocused = $0 }, onOpen: onOpen)
                    }
                    if !continueWatching.isEmpty {
                        ContinueWatchingRow(items: continueWatching, style: cwStyle, size: cwStyle.classicSize, header: .classic, onOpen: onOpenCw)
                    }
                    HomeRowsStates(rows: rows, hasContinueWatching: !continueWatching.isEmpty, hasCollections: entries.contains { $0.collection != nil })
                    if rows != nil {
                        ForEach(entries, id: \.key) { entry in
                            if let section = entry.section {
                                CatalogRowSection(section: section, cardSize: CGSize(width: dp(NuvioCardSize.posterWidthDp * 1.35),
                                                                                      height: dp(NuvioCardSize.posterWidthDp * 1.5 * 1.35)),
                                                  onOpen: onOpen)
                            } else if let collection = entry.collection {
                                CollectionRow(collection: collection, style: .classic, onOpen: onOpenFolder)
                            }
                        }
                    }
                }
                .padding(.top, showHero && !heroItems.isEmpty ? 0 : dp(24)).padding(.bottom, dp(24))
            }
            .scrollClipDisabled()
        }
    }
}

/// HeroCarouselBackdrop(fullPage = true): the slide's backdrop, a bottom scrim from 55% and a left scrim
/// over 66% of the width.
private struct HeroBackdrop: View {
    let url: String?
    @Environment(\.nuvio) private var colors

    var body: some View {
        GeometryReader { geo in
            CachedPosterArtwork(urlString: url, width: geo.size.width, height: geo.size.height, maximumWidth: 1920) { Color.clear }
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .overlay(LinearGradient(stops: [
                    .init(color: .clear, location: 0.55), .init(color: colors.background.opacity(0.32), location: 0.55 + 0.45 * 0.4222),
                    .init(color: colors.background.opacity(0.62), location: 0.55 + 0.45 * 0.7778), .init(color: colors.background.opacity(0.78), location: 1),
                ], startPoint: .top, endPoint: .bottom))
                .overlay(LinearGradient(stops: [
                    .init(color: colors.background.opacity(0.98), location: 0), .init(color: colors.background.opacity(0.88), location: 0.66 * 0.2424),
                    .init(color: colors.background.opacity(0.56), location: 0.66 * 0.5152), .init(color: colors.background.opacity(0.2), location: 0.66 * 0.7879),
                    .init(color: .clear, location: 0.66),
                ], startPoint: .leading, endPoint: .trailing))
        }
        .ignoresSafeArea()
    }
}

/// HeroCarousel.kt: one 400dp focusable; LEFT / RIGHT change slide (crossfade 300 ms), OK opens the
/// title, and it advances every 10 s while unfocused (first advance after 20 s). Text bottom-left at
/// 48dp, 42% wide: logo (100dp tall, ≤220 wide) or headlineLarge title, labelMedium meta, bodyMedium
/// synopsis (4 lines). Dots bottom-centre: active 32×6 in the focus colour, others 12×4 white 30%.
private struct HeroCarousel: View {
    let items: [MetaPreview]
    let onActive: (MetaPreview) -> Void
    let onFocusChange: (Bool) -> Void
    let onOpen: (MetaPreview) -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool
    @State private var index = 0

    var body: some View {
        let item = items[min(index, items.count - 1)]
        Button { onOpen(item) } label: {
            ZStack(alignment: .bottomLeading) {
                Color.clear
                slide(HeroContent(preview: item)).id(item.id).transition(.opacity)
                HStack(spacing: dp(8)) {
                    ForEach(items.indices, id: \.self) { i in
                        RoundedRectangle(cornerRadius: dp(3))
                            .fill(i == index ? colors.focusRing : Color.white.opacity(0.3))
                            .frame(width: i == index ? dp(32) : dp(12), height: i == index ? dp(6) : dp(4))
                    }
                }
                .frame(maxWidth: .infinity).padding(.bottom, dp(16))
            }
            .frame(maxWidth: .infinity).frame(height: dp(400))
            .animation(.easeInOut(duration: 0.3), value: index)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onMoveCommand { direction in
            switch direction {
            case .left where index > 0: index -= 1
            case .left: ContentFocusActivity.requestRail()   // first slide: LEFT opens the drawer
            case .right where index < items.count - 1: index += 1
            default: break
            }
        }
        .onChange(of: focused) { _, f in ContentFocusActivity.shared.leftEdgeOwned = f; onFocusChange(f) }
        .onChange(of: index) { _, i in onActive(items[min(i, items.count - 1)]) }
        // The hero list refreshes after first load (often reordered): the slide redraws items[index],
        // so the backdrop must follow it too, or the text and backdrop show different titles.
        .onChange(of: items.map(\.id)) { _, _ in
            if index >= items.count { index = 0 }
            onActive(items[min(index, items.count - 1)])
        }
        .onAppear { onActive(item) }
        .task(id: focused) {
            guard items.count > 1, !focused else { return }
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            while !Task.isCancelled {
                index = (index + 1) % items.count
                try? await Task.sleep(nanoseconds: 10_000_000_000)
            }
        }
    }

    private func slide(_ content: HeroContent) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            if let logo = content.logo, !logo.isEmpty {
                CachedPosterArtwork(urlString: logo, width: dp(220), height: dp(100), maximumWidth: dp(440)) {
                    Text(content.title).font(NuvioType.headlineLarge).foregroundStyle(colors.textPrimary).lineLimit(2)
                }
                .environment(\.artworkContentMode, .fit)
                .frame(maxWidth: dp(220), maxHeight: dp(100), alignment: .bottomLeading)
            } else {
                Text(content.title).font(NuvioType.headlineLarge).foregroundStyle(colors.textPrimary).lineLimit(2)
            }
            HStack(spacing: dp(8)) {
                ForEach(Array(content.meta.enumerated()), id: \.offset) { i, part in
                    if i > 0 { Circle().fill(colors.textTertiary.opacity(0.78)).frame(width: dp(4), height: dp(4)) }
                    Text(part).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                }
                if let rating = content.rating {
                    if !content.meta.isEmpty { Circle().fill(colors.textTertiary.opacity(0.78)).frame(width: dp(4), height: dp(4)) }
                    ImdbBadge()
                    Text(rating).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                }
            }
            if let description = content.description {
                Text(description).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary).lineLimit(4)
            }
        }
        .frame(width: UIScreen.main.bounds.width * 0.42, alignment: .leading)
        .padding(dp(48))
    }
}

// MARK: - Grid

/// GridHomeContent.kt: the hero, Continue Watching (fixed-size cards), then each catalog as a
/// headlineMedium divider over an adaptive poster grid (48dp start / 24dp end, 12 / 16dp gaps), capped
/// at 3 rows (2 for posters ≤104dp) with a "See All" card that opens the whole catalog.
struct GridHomeView: View {
    let rows: HomeUiState?
    let entries: [TvHomeEntry]
    let continueWatching: [TvCwItem]
    let showHero: Bool
    let cwStyle: CwStyle
    let onOpenCw: (TvCwItem) -> Void
    let onOpen: (MetaPreview) -> Void
    let onOpenFolder: (FolderTarget) -> Void
    @Environment(\.nuvio) private var colors
    @State private var seeAll: HomeCatalogSectionBox?
    @State private var active: MetaPreview?
    @State private var heroFocused = true

    private var heroItems: [MetaPreview] { rows.map { Array($0.heroItems) } ?? [] }

    var body: some View {
        GeometryReader { geo in
            let card = GridCardSize.poster
            let usable = geo.size.width - dp(48) - dp(24)
            let columns = max(1, Int((usable + NuvioTokens.Layout.itemGap) / (card.width + NuvioTokens.Layout.itemGap)))
            let rowsPerSection = NuvioCardSize.posterWidthDp <= 104 ? 2 : 3
            ZStack(alignment: .topLeading) {
                colors.background.ignoresSafeArea()
                if showHero, let active {
                    HeroBackdrop(url: active.banner ?? active.landscapePoster ?? active.poster)
                        .opacity(heroFocused ? 1 : 0).animation(NuvioTokens.Motion.overlay, value: heroFocused)
                }
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(alignment: .leading, spacing: dp(16)) {
                        if showHero && !heroItems.isEmpty {
                            HeroCarousel(items: heroItems, onActive: { active = $0 }, onFocusChange: { heroFocused = $0 }, onOpen: onOpen)
                        }
                        if !continueWatching.isEmpty {
                            ContinueWatchingRow(items: continueWatching, style: cwStyle, size: cwStyle.gridSize, header: .grid, onOpen: onOpenCw)
                        }
                        HomeRowsStates(rows: rows, hasContinueWatching: !continueWatching.isEmpty, hasCollections: entries.contains { $0.collection != nil })
                        if rows != nil {
                            ForEach(entries, id: \.key) { entry in
                                if let section = entry.section {
                                    gridSection(section, columns: columns, maxItems: columns * rowsPerSection, card: card)
                                } else if let collection = entry.collection {
                                    CollectionGridSection(collection: collection, columns: columns, card: card, onOpen: onOpenFolder)
                                }
                            }
                        }
                    }
                    .padding(.top, showHero && !heroItems.isEmpty ? 0 : dp(24)).padding(.bottom, dp(32))
                }
                .scrollClipDisabled()
            }
        }
        .fullScreenCover(item: $seeAll) { box in
            CatalogSeeAllScreen(section: box.section, onOpen: onOpen).environment(\.nuvio, colors)
        }
    }

    @ViewBuilder
    private func gridSection(_ section: HomeCatalogSection, columns: Int, maxItems: Int, card: CGSize) -> some View {
        let items = Array(section.items)
        let hasMore = items.count > maxItems || section.hasMore
        // Trim so "See All" never sits alone on a new row (GridHomeContent.kt:373-412).
        let shown: [MetaPreview] = {
            guard hasMore else { return Array(items.prefix(maxItems)) }
            var capped = Array(items.prefix(maxItems - 1))
            if (capped.count + 1) % columns == 1 && capped.count > 0 { capped.removeLast() }
            return capped
        }()
        VStack(alignment: .leading, spacing: 0) {
            Text(section.title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                .padding(.top, dp(24)).padding(.bottom, dp(12))
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(card.width), spacing: NuvioTokens.Layout.itemGap, alignment: .top), count: columns),
                      alignment: .leading, spacing: dp(16)) {
                ForEach(shown, id: \.id) { item in
                    NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                    width: card.width, height: card.height) { onOpen(item) }
                        .titleActions(item) { onOpen(item) }
                }
                if hasMore { SeeAllGridCard(size: card) { seeAll = HomeCatalogSectionBox(section: section) } }
            }
            .focusSection()
        }
        .padding(.leading, dp(48)).padding(.trailing, dp(24))
    }
}

enum GridCardSize {
    static var poster: CGSize { CGSize(width: dp(NuvioCardSize.posterWidthDp), height: dp(NuvioCardSize.posterWidthDp * 1.5)) }
}

struct HomeCatalogSectionBox: Identifiable {
    let section: HomeCatalogSection
    var id: String { section.key }
}

/// SeeAllGridCard: a poster-sized BackgroundCard with an arrow and "See All" (titleSmall TextSecondary).
private struct SeeAllGridCard: View {
    let size: CGSize
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(spacing: dp(8)) {
                Image("md_chevron_right").renderingMode(.template).resizable().frame(width: dp(32), height: dp(32))
                Text("See All").font(NuvioType.titleSmall)
            }
            .foregroundStyle(colors.textSecondary)
            .frame(width: size.width, height: size.height)
            .background(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous).fill(colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous)
                .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// A catalog's full list (NuvioTV CatalogSeeAllScreen): headlineMedium title, "from <add-on>", and an
/// adaptive poster grid of every item the catalog has returned. Menu goes back.
private struct CatalogSeeAllScreen: View {
    let section: HomeCatalogSection
    let onOpen: (MetaPreview) -> Void
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let card = GridCardSize.poster
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: dp(16)) {
                    VStack(alignment: .leading, spacing: dp(4)) {
                        Text(section.title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                        Text("from \(section.addonName)").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: card.width), spacing: NuvioTokens.Layout.itemGap, alignment: .top)],
                              alignment: .leading, spacing: dp(16)) {
                        ForEach(section.items, id: \.id) { item in
                            NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                            width: card.width, height: card.height) { dismiss(); onOpen(item) }
                                .titleActions(item) { dismiss(); onOpen(item) }
                        }
                    }
                }
                .padding(.horizontal, dp(48)).padding(.top, 60).padding(.bottom, dp(32))
            }
            .scrollClipDisabled()
        }
    }
}

/// Loading shimmer / empty state shared by Classic and Grid (Modern draws its own).
private struct HomeRowsStates: View {
    let rows: HomeUiState?
    let hasContinueWatching: Bool
    var hasCollections = false

    var body: some View {
        if let rows {
            if rows.sections.isEmpty && rows.isLoading {
                ForEach(0..<2, id: \.self) { _ in
                    HStack(spacing: NuvioTokens.Layout.itemGap) {
                        ForEach(0..<7, id: \.self) { _ in NuvioShimmer().frame(width: GridCardSize.poster.width, height: GridCardSize.poster.height) }
                    }
                    .padding(.leading, dp(48))
                }
            } else if rows.sections.isEmpty && !hasContinueWatching && !hasCollections {
                let hint = TvHome.shared.emptyHomeHint()
                NuvioStateMessage(title: StoreCopy.emptyHomeTitle(hint),
                                  message: rows.errorMessage ?? StoreCopy.emptyHomeMessage(hint))
                    .frame(height: dp(200))
            }
        }
    }
}
