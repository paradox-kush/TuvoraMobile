import SwiftUI
import TuvoraCore

// A profile's collections on Apple TV (the phone's Collections, NuvioTV's "collection" home rows):
// components/CollectionRowSection.kt (FolderCard), GridHomeContent.kt (GridCollectionFolderCard) and
// screens/collection/FolderDetailScreen.kt, translated dp×2. Data: TvCollections (tvosCore facade over
// CollectionRepository / FolderDetailRepository); placement: TvHomeCollectionsPolicy.

/// A folder to open: Identifiable for `.fullScreenCover(item:)`.
struct FolderTarget: Identifiable, Equatable {
    let collectionId: String
    let folderId: String
    var id: String { collectionId + "/" + folderId }
}

/// Which home layout draws the row (header and card metrics differ).
enum CollectionRowStyle { case modern, classic }

/// NuvioTV FolderCard: a BackgroundCard tile in the folder's own shape (poster / 16:9 / square) with its
/// cover image, else the emoji (48sp), else the first two letters (headlineLarge TextSecondary); the
/// title (labelMedium, white, one line) sits inside at the bottom over a dark fade unless hidden.
/// Focus: the 2dp FocusRing plus the tvOS lift.
struct CollectionFolderCard: View {
    let collection: TuvoraCore.Collection
    let folder: CollectionFolder
    let size: CGSize
    var onFocus: () -> Void = {}
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous) }

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .bottom) {
                colors.backgroundCard
                if let cover = TvHomeCollectionsPolicy.shared.coverImage(folder: folder) {
                    CachedPosterArtwork(urlString: cover, width: size.width, height: size.height, maximumWidth: size.width * 2) { placeholder }
                        .frame(width: size.width, height: size.height)
                } else {
                    placeholder
                }
                if !folder.hideTitle {
                    Text(verbatim: folder.title).font(NuvioType.labelMedium).foregroundStyle(.white).lineLimit(1)
                        .multilineTextAlignment(.center)
                        .padding(dp(8))
                        .frame(width: size.width)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom))
                }
            }
            .frame(width: size.width, height: size.height)
            .clipShape(shape)
            .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .hoverEffect(.highlight)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, now in if now { onFocus() } }
        .accessibilityLabel(Text(verbatim: folder.title))
        .accessibilityIdentifier("collection.folder.\(collection.id).\(folder.id)")
    }

    @ViewBuilder private var placeholder: some View {
        let text = TvHomeCollectionsPolicy.shared.placeholder(folder: folder)
        if let emoji = folder.coverEmoji, !emoji.trimmingCharacters(in: .whitespaces).isEmpty {
            Text(verbatim: text).font(.system(size: dp(48))).frame(width: size.width, height: size.height)
        } else {
            Text(verbatim: text).font(NuvioType.headlineLarge).foregroundStyle(colors.textSecondary).frame(width: size.width, height: size.height)
        }
    }
}

/// Tile size for a folder: the layout's portrait card, its landscape card for 16:9, a square of the
/// portrait width for square tiles (NuvioTV FolderCard / ModernHomeModels catalogCardMetrics).
func collectionTileSize(_ folder: CollectionFolder, portrait: CGSize, landscape: CGSize) -> CGSize {
    let ratio = TvHomeCollectionsPolicy.shared.aspectRatio(folder: folder)
    if ratio > 1.01 { return landscape }
    if ratio > 0.99 { return CGSize(width: portrait.width, height: portrait.width) }
    return portrait
}

/// CollectionRowSection.kt: the collection title over a horizontal row of its folders. Modern uses the
/// Modern shelf header (titleMedium SemiBold at the 52dp gutter) and Modern card sizes; Classic uses
/// headlineMedium at 48dp, 12 below, cards 16dp apart.
struct CollectionRow: View {
    let collection: TuvoraCore.Collection
    let style: CollectionRowStyle
    var onFocus: (CollectionFolder) -> Void = { _ in }
    let onOpen: (FolderTarget) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let gutter = style == .modern ? NuvioTokens.Layout.gutter : dp(48)
        VStack(alignment: .leading, spacing: 0) {
            switch style {
            case .modern:
                NuvioShelfHeader(title: collection.title) { EmptyView() }
            case .classic:
                Text(verbatim: collection.title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    .padding(.horizontal, gutter).padding(.bottom, dp(12))
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: style == .modern ? NuvioTokens.Layout.itemGap : dp(16)) {
                    ForEach(collection.folders, id: \.id) { folder in
                        CollectionFolderCard(collection: collection, folder: folder, size: size(for: folder),
                                             onFocus: { onFocus(folder) }) {
                            onOpen(FolderTarget(collectionId: collection.id, folderId: folder.id))
                        }
                    }
                }
                .padding(.horizontal, gutter).padding(.vertical, dp(8))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }

    private func size(for folder: CollectionFolder) -> CGSize {
        switch style {
        case .modern:
            return collectionTileSize(folder, portrait: ModernCardSize.portrait, landscape: ModernCardSize.landscape)
        case .classic:
            let w = dp(NuvioCardSize.posterWidthDp * 1.35)
            return collectionTileSize(folder, portrait: CGSize(width: w, height: w * 1.5), landscape: CGSize(width: w * 16 / 9, height: w))
        }
    }
}

/// GridHomeContent.kt CollectionHeader + GridCollectionFolderCard: a headlineMedium divider over the
/// folders in the poster grid's columns, each tile the column width at its own aspect ratio.
struct CollectionGridSection: View {
    let collection: TuvoraCore.Collection
    let columns: Int
    let card: CGSize
    let onOpen: (FolderTarget) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: collection.title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                .padding(.top, dp(24)).padding(.bottom, dp(12))
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(card.width), spacing: NuvioTokens.Layout.itemGap, alignment: .top), count: columns),
                      alignment: .leading, spacing: dp(16)) {
                ForEach(collection.folders, id: \.id) { folder in
                    let ratio = CGFloat(TvHomeCollectionsPolicy.shared.aspectRatio(folder: folder))
                    CollectionFolderCard(collection: collection, folder: folder, size: CGSize(width: card.width, height: card.width / ratio)) {
                        onOpen(FolderTarget(collectionId: collection.id, folderId: folder.id))
                    }
                }
            }
            .focusSection()
        }
        .padding(.leading, dp(48)).padding(.trailing, dp(24))
    }
}

extension HeroContent {
    /// ModernHomeModels.buildCollectionFolderItem: the folder's backdrop (else cover, else the
    /// collection's), its title logo, the title (emoji first), and the collection name as the meta line.
    init(collection: TuvoraCore.Collection, folder: CollectionFolder) {
        id = "collection:\(collection.id):\(folder.id)"
        backdrop = TvHomeCollectionsPolicy.shared.heroBackdrop(collection: collection, folder: folder)
        logo = folder.titleLogoUrl
        title = TvHomeCollectionsPolicy.shared.heroTitle(folder: folder)
        meta = [collection.title]
        status = nil
        rating = nil
        description = nil
    }
}

// MARK: - Folder screen

/// FolderDetailScreen.kt: the folder's cover (sized by its tile shape) and headlineMedium title, then
/// - TABBED_GRID: tabs ("All" first when the collection shows it) that select on focus, over a poster grid
///   of the selected list, more pages loading as the grid nears its end;
/// - ROWS (and FOLLOW_LAYOUT, which Apple TV draws as rows): one row per list.
/// Cards open the title's details; Menu goes back to Home.
struct CollectionFolderScreen: View {
    let target: FolderTarget
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var state: FolderDetailUiState?
    @State private var details: PreviewBox?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            colors.background.ignoresSafeArea()
            content
        }
        .task {
            TvCollections.shared.openFolder(collectionId: target.collectionId, folderId: target.folderId)
            for await next in TvCollections.shared.folder {
                // The repository keeps the last folder until this one replaces it: skip a stale frame.
                if next.folder?.id == target.folderId || (next.folder == nil && !next.isLoading) { state = next }
            }
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private var phase: FolderScreenPhase {
        FolderScreenPhase.resolve(
            hasFolder: state?.folder != nil, isLoading: state?.isLoading ?? true, tabCount: state?.tabs.count ?? 0,
            anyTabLoading: state?.tabs.contains { $0.isLoading } ?? false,
            anyItems: state?.tabs.contains { !$0.items.isEmpty } ?? false,
            anyTabError: state?.tabs.contains { $0.error != nil } ?? false)
    }

    @ViewBuilder private var content: some View {
        switch phase {
        case .loading:
            DeadlineLoading(retry: { TvCollections.shared.openFolder(collectionId: target.collectionId, folderId: target.folderId) }) {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        case .notFound:
            NuvioStateMessage(title: LK("collections_folder_not_found", "Folder not found"),
                              message: "This folder is no longer in your collection.",
                              actionTitle: "Back", actionId: "folder.back") { dismiss() }
        case .empty:
            VStack(alignment: .leading, spacing: 0) {
                if let folder = state?.folder { FolderHeader(folder: folder).padding(.horizontal, dp(48)).padding(.top, 60) }
                NuvioStateMessage(title: "No sources in this folder yet",
                                  message: "Add sources to this folder from Settings > Layout > Collections.",
                                  actionTitle: "Back", actionId: "folder.back") { dismiss() }
            }
        case .content:
            if let state, let folder = state.folder {
                if state.viewMode == .tabbedGrid {
                    TabbedFolderGrid(state: state, folder: folder, onOpen: { details = PreviewBox(preview: $0) })
                } else {
                    FolderRows(state: state, folder: folder, onOpen: { details = PreviewBox(preview: $0) })
                }
            }
        }
    }
}

/// FolderHeader: cover 32×48 (poster), 64×36 (landscape) or 48×48 (square), radius 8, else the emoji;
/// then the title in headlineMedium.
private struct FolderHeader: View {
    let folder: CollectionFolder
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: dp(12)) {
            if let cover = TvHomeCollectionsPolicy.shared.coverImage(folder: folder) {
                let ratio = TvHomeCollectionsPolicy.shared.aspectRatio(folder: folder)
                let size = ratio > 1.01 ? CGSize(width: dp(64), height: dp(36)) : ratio > 0.99 ? CGSize(width: dp(48), height: dp(48)) : CGSize(width: dp(32), height: dp(48))
                CachedPosterArtwork(urlString: cover, width: size.width, height: size.height, maximumWidth: size.width * 2) { colors.backgroundCard }
                    .frame(width: size.width, height: size.height)
                    .clipShape(RoundedRectangle(cornerRadius: dp(8), style: .continuous))
            } else if let emoji = folder.coverEmoji, !emoji.isEmpty {
                Text(verbatim: emoji).font(NuvioType.headlineLarge)
            }
            Text(verbatim: folder.title).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                .frame(maxWidth: dp(300), alignment: .leading)
                .accessibilityIdentifier("folder.title")
        }
    }
}

private struct TabbedFolderGrid: View {
    let state: FolderDetailUiState
    let folder: CollectionFolder
    let onOpen: (MetaPreview) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let card = GridCardSize.poster
        let tabs = Array(state.tabs)
        let selected = state.selectedTab
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(16)) {
                HStack(spacing: dp(12)) {
                    FolderHeader(folder: folder)
                    if tabs.count > 1 {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: dp(8)) {
                                ForEach(Array(tabs.enumerated()), id: \.offset) { index, tab in
                                    HubChip(title: tab.isAllTab ? LK("collections_tab_all", "All") : tab.label,
                                            selected: index == Int(state.selectedTabIndex),
                                            onFocus: { TvCollections.shared.selectTab(index: Int32(index)) }) {
                                        TvCollections.shared.selectTab(index: Int32(index))
                                    }
                                    .accessibilityIdentifier("folder.tab.\(index)")
                                }
                            }
                            .padding(.vertical, dp(4))
                        }
                        .scrollClipDisabled()
                        .focusSection()
                    }
                }
                if let tab = selected {
                    if tab.isLoading && tab.items.isEmpty {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: card.width), spacing: NuvioTokens.Layout.itemGap)], alignment: .leading, spacing: dp(16)) {
                            ForEach(0..<12, id: \.self) { _ in NuvioShimmer().frame(width: card.width, height: card.height) }
                        }
                    } else if tab.items.isEmpty {
                        Text(verbatim: tab.error ?? "").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                            .frame(maxWidth: .infinity, minHeight: dp(200))
                    } else {
                        let items = Array(tab.items)
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: card.width), spacing: NuvioTokens.Layout.itemGap, alignment: .top)],
                                  alignment: .leading, spacing: dp(16)) {
                            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                                NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                                width: card.width, height: card.height) { onOpen(item) }
                                    .titleActions(item) { onOpen(item) }
                                    .onAppear { if index >= items.count - 10 { TvCollections.shared.loadMore() } }
                            }
                        }
                        .focusSection()
                    }
                }
            }
            .padding(.horizontal, dp(48)).padding(.top, 60).padding(.bottom, dp(48))
        }
        .scrollClipDisabled()
    }
}

private struct FolderRows: View {
    let state: FolderDetailUiState
    let folder: CollectionFolder
    let onOpen: (MetaPreview) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let card = GridCardSize.poster
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: dp(24)) {
                FolderHeader(folder: folder).padding(.horizontal, dp(48))
                ForEach(Array(state.tabs.filter { !$0.isAllTab }.enumerated()), id: \.offset) { _, tab in
                    VStack(alignment: .leading, spacing: dp(12)) {
                        VStack(alignment: .leading, spacing: dp(4)) {
                            Text(verbatim: tab.label).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                            if !tab.typeLabel.isEmpty {
                                Text(verbatim: tab.typeLabel).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                            }
                        }
                        .padding(.horizontal, dp(48))
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: NuvioTokens.Layout.itemGap) {
                                if tab.isLoading && tab.items.isEmpty {
                                    ForEach(0..<7, id: \.self) { _ in NuvioShimmer().frame(width: card.width, height: card.height) }
                                } else if tab.items.isEmpty {
                                    Text(verbatim: tab.error ?? "").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                                } else {
                                    ForEach(Array(tab.items.enumerated()), id: \.offset) { _, item in
                                        NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                                        width: card.width, height: card.height) { onOpen(item) }
                                            .titleActions(item) { onOpen(item) }
                                    }
                                }
                            }
                            .padding(.horizontal, dp(48)).padding(.vertical, dp(8))
                        }
                        .scrollClipDisabled()
                        .focusSection()
                    }
                }
            }
            .padding(.top, 60).padding(.bottom, dp(48))
        }
        .scrollClipDisabled()
    }
}
