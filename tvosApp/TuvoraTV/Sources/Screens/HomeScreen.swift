import SwiftUI
import TuvoraCore

/// NuvioTV's Modern home (ui/screens/home/ModernHomeContent.kt, ModernHomeHero.kt, ModernHomeRows.kt,
/// components/ContinueWatchingSection.kt), translated: a hero that follows the focused card over the
/// top of the screen, and the rows in the bottom 52% — Continue Watching first, then the catalogs.
struct HomeScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var rows: HomeUiState?
    @State private var continueWatching: [TvCwItem] = []
    @State private var hero: HeroContent?
    @State private var pendingHero: HeroContent?
    @State private var details: PreviewBox?
    @State private var streamTarget: TvCwTargetBox?
    /// Home rows in the profile's order: catalogs and collections (TvCollections / TvHomeCollectionsPolicy).
    @State private var entries: [TvHomeEntry] = []
    @State private var openFolder: FolderTarget?
    @State private var smokeFolderOpened = false
    /// Settings → Layout (NuvioTV "Show Hero Section" / "Show Continue Watching").
    @AppStorage(NuvioLayoutPrefs.showHeroKey) private var showHero = true
    @AppStorage(NuvioLayoutPrefs.cwEnabledKey) private var cwEnabled = true
    /// Settings → Layout → Home Layout (NuvioTV HomeLayout) and Continue Watching style.
    @AppStorage(NuvioLayoutPrefs.homeLayoutKey) private var homeLayout = "modern"
    @AppStorage(NuvioLayoutPrefs.cwStyleKey) private var cwStyleKey = "card"
    private var cwStyle: CwStyle { CwStyle(rawValue: cwStyleKey) ?? .card }

    /// Simulator smoke hooks: `-smokeHomeLayout <modern|grid|classic>`, `-smokeCwStyle <card|wide|poster>`,
    /// `-smokeShowHero <true|false>`
    /// write the Settings → Layout preferences before the screen reads them. `-smokeCollections` shows
    /// in-memory sample collections (never saved or synced); `-smokeOpenFolder <n>` opens the first folder
    /// of the n-th collection row.
    private static let applySmokePrefs: Void = {
        let args = AppArguments.list
        for (flag, key) in [("-smokeHomeLayout", NuvioLayoutPrefs.homeLayoutKey), ("-smokeCwStyle", NuvioLayoutPrefs.cwStyleKey)] {
            if let i = args.firstIndex(of: flag), i + 1 < args.count, UserDefaults.standard.string(forKey: key) != args[i + 1] {
                UserDefaults.standard.set(args[i + 1], forKey: key)
            }
        }
        if let i = args.firstIndex(of: "-smokeShowHero"), i + 1 < args.count {
            UserDefaults.standard.set(args[i + 1] != "false", forKey: NuvioLayoutPrefs.showHeroKey)
        }
        if args.contains("-smokeCollections") { TvCollections.shared.enableSmokeSample() }
    }()
    init() { _ = Self.applySmokePrefs }

    var body: some View {
        layoutBody
        .task {
            TvHome.shared.start()
            for await next in TvHome.shared.rows { rows = next; ContentFocusActivity.contentChanged(); if hero == nil, let first = next.heroItems.first ?? next.sections.first?.items.first { hero = HeroContent(preview: first) } }
        }
        // Media-server rows: re-pulled when Home appears (never a timer); the contributor's own TTL makes a quick return free.
        .task { TvMediaServers.shared.refreshHomeRows() }
        .task { for await next in TvHome.shared.continueWatching { continueWatching = next; ContentFocusActivity.contentChanged() } }
        .task {
            TvCollections.shared.start()
            for await next in TvCollections.shared.entries { entries = next; ContentFocusActivity.contentChanged(); smokeOpenFolder() }
        }
        .task(id: pendingHero) {
            // Hero changes are debounced 450 ms (ModernHomeHero), so fast scrolling doesn't strobe.
            guard let pending = pendingHero else { return }
            try? await Task.sleep(nanoseconds: 450_000_000)
            if !Task.isCancelled { withAnimation(.easeInOut(duration: 0.48)) { hero = pending } }
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
        .fullScreenCover(item: $streamTarget) { box in
            StreamPickerScreen(meta: box.target.meta, video: box.target.video).environmentObject(playback).environment(\.nuvio, colors)
        }
        .fullScreenCover(item: $openFolder) { target in
            CollectionFolderScreen(target: target).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private func smokeOpenFolder() {
        let args = AppArguments.list
        guard !smokeFolderOpened, let i = args.firstIndex(of: "-smokeOpenFolder"), i + 1 < args.count, let n = Int(args[i + 1]) else { return }
        let collections = entries.compactMap(\.collection)
        guard n < collections.count, let folder = collections[n].folders.first else { return }
        smokeFolderOpened = true
        openFolder = FolderTarget(collectionId: collections[n].id, folderId: folder.id)
    }

    /// NuvioTV HomeScreen.kt: CLASSIC → ClassicHomeRoute, GRID → GridHomeRoute, MODERN → ModernHomeRoute.
    @ViewBuilder
    private var layoutBody: some View {
        switch homeLayout {
        case "classic":
            ClassicHomeView(rows: rows, entries: entries, continueWatching: cwEnabled ? continueWatching : [], showHero: showHero, cwStyle: cwStyle,
                            onOpenCw: open, onOpen: { details = PreviewBox(preview: $0) }, onOpenFolder: { openFolder = $0 })
        case "grid":
            GridHomeView(rows: rows, entries: entries, continueWatching: cwEnabled ? continueWatching : [], showHero: showHero, cwStyle: cwStyle,
                         onOpenCw: open, onOpen: { details = PreviewBox(preview: $0) }, onOpenFolder: { openFolder = $0 })
        default:
            modernBody
        }
    }

    private var modernBody: some View {
        GeometryReader { geo in
            let viewport = showHero ? geo.size.height * 0.52 : geo.size.height - dp(30)
            ZStack(alignment: .topLeading) {
                colors.background.ignoresSafeArea()
                if showHero, let hero {
                    HomeHero(content: hero, width: geo.size.width, height: geo.size.height - viewport + dp(24) + dp(14),
                             textBottomInset: viewport + dp(16))
                        .id(hero.id)
                        .transition(.opacity.animation(.easeInOut(duration: 0.48)))
                }
                VStack(spacing: 0) {
                    Spacer(minLength: geo.size.height - viewport)
                    rowsList.frame(height: viewport)
                }
            }
        }
    }

    private var rowsList: some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: NuvioTokens.Layout.rowGap) {
                if cwEnabled && !continueWatching.isEmpty {
                    ContinueWatchingRow(items: continueWatching, style: cwStyle, size: cwStyle.modernSize, header: .modern,
                                        onFocus: focusCw, onOpen: open)
                }
                if let rows {
                    if rows.sections.isEmpty && rows.isLoading {
                        ForEach(0..<2, id: \.self) { _ in shimmerRow }
                    }
                    ForEach(entries, id: \.key) { entry in
                        if let section = entry.section {
                            CatalogRow(section: section, onFocus: { pendingHero = HeroContent(preview: $0) }) { details = PreviewBox(preview: $0) }
                        } else if let collection = entry.collection {
                            CollectionRow(collection: collection, style: .modern,
                                          onFocus: { pendingHero = HeroContent(collection: collection, folder: $0) }) { openFolder = $0 }
                        }
                    }
                    if entries.isEmpty && !rows.isLoading && continueWatching.isEmpty {
                        let hint = TvHome.shared.emptyHomeHint()
                        NuvioStateMessage(title: StoreCopy.emptyHomeTitle(hint),
                                          message: rows.errorMessage ?? StoreCopy.emptyHomeMessage(hint))
                            .frame(height: dp(200))
                    }
                }
            }
            .padding(.bottom, dp(48))
        }
    }

    private var shimmerRow: some View {
        let size = ModernCardSize.portrait
        return HStack(spacing: NuvioTokens.Layout.itemGap) {
            ForEach(0..<7, id: \.self) { _ in NuvioShimmer().frame(width: size.width, height: size.height) }
        }
        .padding(.leading, NuvioTokens.Layout.gutter)
    }

    /// The hero for a Continue Watching card shows the title's facts, like NuvioTV: type · genre · year,
    /// the time left, IMDb and the synopsis — fetched from the title's details (cached by the repository).
    private func focusCw(_ item: TvCwItem) {
        pendingHero = HeroContent(cw: item, meta: nil)
        Task {
            if let target = try? await TvHome.shared.resolve(item: item), pendingHero?.id == "cw:" + item.videoId {
                pendingHero = HeroContent(cw: item, meta: target.meta)
            }
        }
    }

    private func open(_ item: TvCwItem) {
        Task {
            if let target = try? await TvHome.shared.resolve(item: item) {
                streamTarget = TvCwTargetBox(target: target)
            } else {
                playback.notify("\(item.title) couldn't be opened right now.")
            }
        }
    }
}

struct TvCwTargetBox: Identifiable {
    let target: TvCwTarget
    var id: String { target.video?.id ?? target.meta.id }
}

/// Card sizes from the poster preference (ModernHomeContent.kt:647-668).
enum ModernCardSize {
    static var portrait: CGSize {
        let w = NuvioCardSize.posterWidthDp
        return CGSize(width: dp(w * 0.84 * 1.08), height: dp(w * 1.5 * 0.9072))
    }
    static var landscape: CGSize {
        let width = dp(NuvioCardSize.posterWidthDp * 1.24 * 1.34)
        return CGSize(width: width, height: width / 1.77)
    }
}

// MARK: - Hero

struct HeroContent: Identifiable, Equatable {
    let id: String
    let backdrop: String?
    let logo: String?
    let title: String
    let meta: [String]
    let status: String?
    let rating: String?
    let description: String?

    init(preview: MetaPreview) {
        id = preview.id
        backdrop = preview.banner ?? preview.landscapePoster ?? preview.poster
        logo = preview.logo
        title = preview.name
        meta = [preview.type == "series" ? LK("type_series", "Series") : LK("type_movie", "Movie"), preview.genres.first, preview.releaseInfo].compactMap { $0 }
        status = nil
        rating = preview.imdbRating
        description = preview.description_
    }

    init(cw: TvCwItem, meta details: MetaDetails?) {
        id = "cw:" + cw.videoId
        backdrop = details?.background ?? cw.background ?? cw.artwork
        logo = details?.logo ?? cw.logo
        title = cw.title
        let kind = cw.seasonNumber == nil ? LK("type_movie", "Movie") : LK("type_series", "Series")
        meta = [kind, details?.genres.first, details?.releaseInfo].compactMap { $0 }
        status = [TvContinueWatching.shared.episodeLabel(item: cw), cwBadge(cw)?.uppercased()]
            .compactMap { $0 }.joined(separator: " · ").nilIfEmpty
        rating = details?.imdbRating
        description = details?.description_
    }
}

/// ModernHomeHero: backdrop 72% wide, top-trailing, pushed 56dp past the edge; left scrim over 45%
/// (1 / .86 / .56 / .16 / 0) and bottom strip from 82%; text block bottom-left, 42% wide.
private struct HomeHero: View {
    let content: HeroContent
    let width: CGFloat
    let height: CGFloat
    let textBottomInset: CGFloat
    @Environment(\.nuvio) private var colors

    var body: some View {
        ZStack(alignment: .topLeading) {
            let backdropWidth = width * 0.72
            CachedPosterArtwork(urlString: content.backdrop, width: backdropWidth, height: height, maximumWidth: 1920) { Color.clear }
                .frame(width: backdropWidth, height: height)
                .clipped()
                .overlay(LinearGradient(stops: [
                    .init(color: colors.background, location: 0), .init(color: colors.background.opacity(0.86), location: 0.22 * 0.45),
                    .init(color: colors.background.opacity(0.56), location: 0.46 * 0.45), .init(color: colors.background.opacity(0.16), location: 0.76 * 0.45),
                    .init(color: .clear, location: 0.45),
                ], startPoint: .leading, endPoint: .trailing))
                .overlay(LinearGradient(stops: [
                    .init(color: .clear, location: 0.82), .init(color: colors.background.opacity(0.25), location: 0.82 + 0.18 * 0.4),
                    .init(color: colors.background.opacity(0.65), location: 0.82 + 0.18 * 0.75), .init(color: colors.background, location: 1),
                ], startPoint: .top, endPoint: .bottom))
                .offset(x: width - backdropWidth + dp(56))

            VStack(alignment: .leading, spacing: dp(8)) {
                Spacer()
                if let logo = content.logo, !logo.isEmpty {
                    CachedPosterArtwork(urlString: logo, width: dp(220), height: dp(100), maximumWidth: dp(440)) { titleText }
                        .environment(\.artworkContentMode, .fit)
                        .frame(maxWidth: dp(220), maxHeight: dp(100), alignment: .bottomLeading)
                } else {
                    titleText
                }
                HStack(spacing: dp(8)) {
                    ForEach(Array(content.meta.enumerated()), id: \.offset) { index, part in
                        if index > 0 { Circle().fill(colors.textSecondary).frame(width: dp(3), height: dp(3)) }
                        Text(part).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                    }
                }
                if content.status != nil || content.rating != nil {
                    HStack(spacing: dp(8)) {
                        if let status = content.status { Text(status).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary) }
                        if let rating = content.rating {
                            if content.status != nil { Circle().fill(colors.textSecondary).frame(width: dp(3), height: dp(3)) }
                            ImdbBadge()
                            Text(ImdbBadge.format(rating)).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        }
                    }
                }
                if let description = content.description {
                    Text(description).font(NuvioType.inter(14 * 0.9, .regular)).foregroundStyle(colors.textPrimary).lineLimit(4)
                }
            }
            .frame(width: width * 0.42, alignment: .leading)
            .padding(.leading, NuvioTokens.Layout.gutter)
            .padding(.bottom, textBottomInset - (UIScreen.main.bounds.height - height))
            .frame(height: height, alignment: .bottomLeading)
        }
        .frame(width: width, height: height, alignment: .topLeading)
    }

    private var titleText: some View {
        Text(content.title).font(NuvioType.inter(28 * 0.92, .semibold)).foregroundStyle(colors.textPrimary).lineLimit(2)
    }
}

// MARK: - Rows

private struct CatalogRow: View {
    let section: HomeCatalogSection
    let onFocus: (MetaPreview) -> Void
    let onOpen: (MetaPreview) -> Void

    var body: some View {
        let size = ModernCardSize.portrait
        VStack(alignment: .leading, spacing: 0) {
            NuvioShelfHeader(title: section.title) { EmptyView() }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: NuvioTokens.Layout.itemGap) {
                    ForEach(section.items, id: \.id) { item in
                        ExpandingPosterCard(item: item, size: size, onFocus: { onFocus(item) }) { onOpen(item) }
                            .titleActions(item) { onOpen(item) }
                    }
                }
                .padding(.horizontal, NuvioTokens.Layout.gutter).padding(.vertical, dp(8))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }
}

/// ContinueWatchingSection.kt ContinueWatchingCard in NuvioTV's three styles (Settings → Layout):
/// - CARD: landscape artwork, gradient from 45% to Background 95%, badge top-trailing (labelSmall on
///   Background 80%), text inside at the bottom (12dp), a 3dp Secondary bar inset 10dp / 4dp.
/// - WIDE: a BackgroundCard strip — 2:3 poster on the left (height × 2/3), then title + badge, episode,
///   episode title (bodySmall) and, at the bottom, the bar with the badge text under it.
/// - POSTER: poster art with the bar on a Background-70% pill (8dp in), badge only for Next Up; the
///   title (titleSmall × 0.85, 2 lines) and episode sit below the artwork, which carries the ring.
/// 2dp FocusRing, no scale.
struct ContinueWatchingCard: View {
    let item: TvCwItem
    var style: CwStyle = .card
    var size: CGSize = CwStyle.card.modernSize
    let onFocus: () -> Void
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            switch style {
            case .card: cardBody
            case .wide: wideBody
            case .poster: posterBody
            }
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, isFocused in if isFocused { onFocus() } }
    }

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous) }
    private var badge: String? { cwBadge(item) }
    private var episodeLabel: String? { TvContinueWatching.shared.episodeLabel(item: item) }
    private var posterArt: String? { item.poster ?? item.background ?? item.artwork }

    private var cardBody: some View {
        ZStack(alignment: .bottomLeading) {
            colors.backgroundCard
            CachedPosterArtwork(urlString: item.artwork, width: size.width, height: size.height, maximumWidth: size.width * 2) { colors.backgroundCard }
                .frame(width: size.width, height: size.height)
            LinearGradient(stops: [.init(color: .clear, location: 0.45), .init(color: colors.background.opacity(0.7), location: 0.72),
                                   .init(color: colors.background.opacity(0.95), location: 1)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: dp(2)) {
                if let episodeLabel { Text(episodeLabel).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary) }
                Text(item.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                if let episode = item.episodeTitle {
                    Text(episode).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                }
            }
            .padding(dp(12)).padding(.bottom, item.isNextUp ? 0 : dp(6))
            if !item.isNextUp && item.progress > 0 {
                progressBar.padding(.horizontal, dp(10)).padding(.bottom, dp(4))
            }
        }
        .frame(width: size.width, height: size.height)
        .overlay(alignment: .topTrailing) { if let badge { badgeView(badge).padding(dp(8)) } }
        .clipShape(shape)
        .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        .hoverEffect(.highlight)
    }

    private var wideBody: some View {
        let strip = size.height * 2 / 3
        return HStack(spacing: 0) {
            CachedPosterArtwork(urlString: posterArt, width: strip, height: size.height, maximumWidth: strip * 2) { colors.backgroundCard }
                .frame(width: strip, height: size.height).clipped()
            VStack(alignment: .leading, spacing: dp(2)) {
                HStack(alignment: .center, spacing: dp(4)) {
                    Text(item.title).font(NuvioType.titleSmall).foregroundStyle(colors.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                    if item.isNextUp, let badge { badgeView(badge) }
                }
                Text(episodeLabel ?? " ").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                Text(item.episodeTitle ?? " ").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(1)
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: dp(2)) {
                    progressBar
                    Text(badge ?? " ").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
                }
                .opacity(item.isNextUp ? 0 : 1)
            }
            .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(width: size.width, height: size.height)
        .background(colors.backgroundCard)
        .clipShape(shape)
        .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        .hoverEffect(.highlight)
    }

    private var posterBody: some View {
        VStack(alignment: .leading, spacing: dp(2)) {
            ZStack(alignment: .bottomLeading) {
                colors.backgroundCard
                CachedPosterArtwork(urlString: posterArt, width: size.width, height: size.height, maximumWidth: size.width * 2) { colors.backgroundCard }
                    .frame(width: size.width, height: size.height)
                if !item.isNextUp {
                    progressBar
                        .padding(dp(2))
                        .background(Capsule().fill(colors.background.opacity(0.7)))
                        .padding(.horizontal, dp(10)).padding(.bottom, dp(8))
                }
            }
            .frame(width: size.width, height: size.height)
            .overlay(alignment: .topTrailing) { if item.isNextUp, let badge { badgeView(badge).padding(dp(8)) } }
            .clipShape(shape)
            .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .hoverEffect(.highlight)
            HStack(alignment: .top, spacing: dp(4)) {
                Text(item.title).font(NuvioType.inter(14 * 0.85, .medium)).foregroundStyle(colors.textPrimary).lineLimit(2)
                Spacer(minLength: 0)
                if let episodeLabel { Text(episodeLabel).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary) }
            }
            .padding(.horizontal, dp(4)).padding(.top, dp(2))
            .frame(width: size.width, height: dp(30), alignment: .topLeading)
        }
    }

    private var progressBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: dp(1.5)).fill(Color.black.opacity(0.3))
                RoundedRectangle(cornerRadius: dp(1.5)).fill(colors.secondary).frame(width: geo.size.width * CGFloat(item.progress))
            }
        }
        .frame(height: dp(3))
    }

    private func badgeView(_ text: String) -> some View {
        Text(ui: text).font(NuvioType.labelSmall).foregroundStyle(colors.textPrimary)
            .padding(.horizontal, dp(6)).padding(.vertical, dp(3))
            .background(RoundedRectangle(cornerRadius: NuvioTokens.Radius.badge).fill(colors.background.opacity(0.8)))
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}


/// NuvioTV's focused-poster expand (focused_poster_backdrop_expand_enabled, default on, 3 s): a poster
/// held in focus widens to 16:9 and shows the backdrop, the logo or title and a two-line synopsis.
private struct ExpandingPosterCard: View {
    let item: MetaPreview
    let size: CGSize
    let onFocus: () -> Void
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool
    @State private var expanded = false
    @State private var timer: Task<Void, Never>?

    var body: some View {
        let width = expanded ? size.height * 16 / 9 : size.width
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(8)) {
                ZStack(alignment: .bottomLeading) {
                    colors.backgroundCard
                    if expanded {
                        CachedPosterArtwork(urlString: item.banner ?? item.landscapePoster ?? item.poster, width: width, height: size.height, maximumWidth: width * 2) { colors.backgroundCard }
                            .frame(width: width, height: size.height)
                        LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: .black.opacity(0.85), location: 1)], startPoint: .top, endPoint: .bottom)
                        VStack(alignment: .leading, spacing: dp(6)) {
                            if let logo = item.logo, !logo.isEmpty {
                                CachedPosterArtwork(urlString: logo, width: width * 0.5, height: size.height * 0.22, maximumWidth: width) {
                                    Text(item.name).font(NuvioType.titleMediumSemi).foregroundStyle(.white)
                                }
                                .environment(\.artworkContentMode, .fit)
                                .frame(width: width * 0.5, height: size.height * 0.22, alignment: .bottomLeading)
                            } else {
                                Text(item.name).font(NuvioType.titleMediumSemi).foregroundStyle(.white).lineLimit(1)
                            }
                            if let description = item.description_ {
                                Text(description).font(NuvioType.bodySmall).foregroundStyle(.white.opacity(0.85)).lineLimit(2)
                            }
                        }
                        .padding(dp(12))
                        .transition(.opacity)
                    } else {
                        CachedPosterArtwork(urlString: item.poster, width: size.width, height: size.height, maximumWidth: size.width * 2) {
                            Text(item.name).font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                                .multilineTextAlignment(.center).lineLimit(3).padding(.horizontal, dp(12))
                                .frame(width: size.width, height: size.height)
                        }
                        .frame(width: size.width, height: size.height)
                    }
                }
                .frame(width: width, height: size.height)
                .clipShape(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous)
                    .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
                .hoverEffect(.highlight)
                Text(item.name).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    .frame(width: width, alignment: .leading)
            }
            .animation(.easeInOut(duration: 0.35), value: expanded)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, isFocused in
            timer?.cancel()
            if isFocused {
                onFocus()
                timer = Task { try? await Task.sleep(nanoseconds: 3_000_000_000); if !Task.isCancelled { expanded = true } }
            } else {
                expanded = false
            }
        }
    }
}
