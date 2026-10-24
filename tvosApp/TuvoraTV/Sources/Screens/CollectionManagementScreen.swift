import SwiftUI
import TuvoraCore

/// Uses the shared editor and collection sync rather than introducing a second tvOS data model.
struct CollectionManagementScreen: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @State private var collections: [TuvoraCore.Collection] = []
    @State private var editing: CollectionEditTarget?
    @State private var deleting: TuvoraCore.Collection?

    var body: some View {
        NuvioDialog(title: "Manage Collections", width: dp(660)) {
            SettingsActionRow(title: "Create Collection") { editing = CollectionEditTarget(id: nil) }
                .accessibilityIdentifier("collections.create")
            ForEach(collections, id: \.id) { collection in
                SettingsActionRow(title: collection.title, subtitle: "\(collection.folders.count) folders") {
                    editing = CollectionEditTarget(id: collection.id)
                }
                .accessibilityIdentifier("collections.edit.\(collection.id)")
                .contextMenu {
                    Button("Move up") { if let i = collections.firstIndex(where: { $0.id == collection.id }) { CollectionRepository.shared.moveUp(index: Int32(i)) } }
                    Button("Move down") { if let i = collections.firstIndex(where: { $0.id == collection.id }) { CollectionRepository.shared.moveDown(index: Int32(i)) } }
                    Button("Delete", role: .destructive) { deleting = collection }
                }
            }
            SettingsActionRow(title: "Done", showChevron: false) { dialogs.pop() }
        }
        .task {
            CollectionRepository.shared.initialize()
            for await value in CollectionRepository.shared.collections { collections = value }
        }
        .fullScreenCover(item: $editing) { target in
            CollectionEditorScreen(collectionId: target.collectionId) { editing = nil }
        }
        .confirmationDialog("Delete collection?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            if let collection = deleting {
                Button("Delete", role: .destructive) { CollectionRepository.shared.removeCollection(collectionId: collection.id); deleting = nil }
            }
            Button("Cancel", role: .cancel) { deleting = nil }
        }
    }
}

private struct CollectionEditTarget: Identifiable {
    let collectionId: String?
    let id = UUID()
    init(id: String?) { collectionId = id }
}

private struct CollectionEditorScreen: View {
    let collectionId: String?
    let close: () -> Void
    @State private var state: CollectionEditorUiState?
    @State private var error: String?
    @State private var discard = false
    @State private var genreCatalog: CatalogGenreTarget?
    @State private var removingSource: Int?
    @Environment(\.nuvio) private var colors
    private var editor: CollectionEditorRepository { CollectionEditorRepository.shared }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: dp(14)) {
                    if let state {
                        SettingsDetailHeader(title: state.showFolderEditor ? "Edit folder" : (state.isNew ? "Create Collection" : "Edit Collection"), subtitle: "Organize the collections shown on Home")
                        if let error { Text(error).font(NuvioType.bodyMedium).foregroundStyle(colors.error) }
                        if let folder = state.editingFolder, state.showFolderEditor {
                            folderEditor(folder, state: state)
                        } else {
                            collectionEditor(state)
                        }
                    } else { ProgressView() }
                }.padding(.horizontal, 100).padding(.vertical, 60)
            }
        }
        .task {
            editor.initialize(collectionId: collectionId)
            for await value in editor.uiState { state = value }
        }
        .onDisappear { editor.clear() }
        .onExitCommand {
            if state?.showFolderEditor == true { editor.cancelFolderEdit() }
            else { discard = true }
        }
        .confirmationDialog("Discard changes?", isPresented: $discard, titleVisibility: .visible) {
            Button("Discard changes", role: .destructive, action: close)
            Button("Keep editing", role: .cancel) { }
        }
        .confirmationDialog("Remove source?", isPresented: Binding(get: { removingSource != nil }, set: { if !$0 { removingSource = nil } }), titleVisibility: .visible) {
            Button("Remove source", role: .destructive) { if let index = removingSource { editor.removeCatalogSource(index: Int32(index)) }; removingSource = nil }
            Button("Cancel", role: .cancel) { removingSource = nil }
        }
        .sheet(item: $genreCatalog) { target in
            NuvioDialog(title: target.catalog.catalogName) {
                if !target.catalog.genreRequired {
                    SettingsActionRow(title: "All genres") { editor.updateCatalogSourceGenre(index: target.index, genre: nil); genreCatalog = nil }
                }
                ForEach(target.catalog.genreOptions, id: \.self) { genre in
                    SettingsActionRow(title: genre) { editor.updateCatalogSourceGenre(index: target.index, genre: genre); genreCatalog = nil }
                }
            }
        }
        .sheet(isPresented: Binding(get: { state?.showCatalogPicker == true }, set: { if !$0 { editor.hideCatalogPicker() } })) {
            if let state { catalogs(state) }
        }
        .sheet(isPresented: Binding(get: { state?.showTmdbSourcePicker == true }, set: { if !$0 { editor.hideTmdbSourcePicker() } })) {
            CollectionSourceInput(isTrakt: false)
        }
        .sheet(isPresented: Binding(get: { state?.showTraktSourcePicker == true }, set: { if !$0 { editor.hideTraktSourcePicker() } })) {
            CollectionSourceInput(isTrakt: true)
        }
    }

    @ViewBuilder private func collectionEditor(_ state: CollectionEditorUiState) -> some View {
        TextField("Collection title", text: Binding(get: { state.title }, set: { editor.setTitle(title: $0) }))
            .accessibilityIdentifier("collections.title")
        TextField("Backdrop image URL", text: Binding(get: { state.backdropImageUrl }, set: { editor.setBackdropImageUrl(url: $0) }))
        SettingsToggleRow(title: "Pin to top", isOn: state.pinToTop) { editor.setPinToTop(pinToTop: !state.pinToTop) }
        SettingsToggleRow(title: "Show All tab", isOn: state.showAllTab) { editor.setShowAllTab(show: !state.showAllTab) }
        HStack {
            ForEach([(FolderViewMode.tabbedGrid, "Tabbed grid"), (.rows, "Rows"), (.followLayout, "Follow layout")], id: \.1) { mode, title in
                SettingsChoiceChip(label: title, selected: state.viewMode == mode) { editor.setViewMode(viewMode: mode) }
            }
        }.focusSection()
        ForEach(Array(state.folders.enumerated()), id: \.element.id) { index, folder in
            SettingsActionRow(title: folder.title, subtitle: "\(folder.resolvedSources.count) sources") { editor.editFolder(folderId: folder.id) }
                .contextMenu {
                    Button("Move up") { editor.moveFolderUp(index: Int32(index)) }
                    Button("Move down") { editor.moveFolderDown(index: Int32(index)) }
                    Button("Remove folder", role: .destructive) { editor.removeFolder(folderId: folder.id) }
                }
        }
        SettingsActionRow(title: "Add folder") { editor.addFolder(defaultTitle: "New folder") }
            .accessibilityIdentifier("collections.addFolder")
        SettingsActionRow(title: "Save", showChevron: false) {
            if editor.save() { close() } else { error = "Enter a collection title." }
        }.accessibilityIdentifier("collections.save")
        SettingsActionRow(title: "Cancel", showChevron: false) { discard = true }
    }

    @ViewBuilder private func folderEditor(_ folder: CollectionFolder, state: CollectionEditorUiState) -> some View {
        TextField("Folder title", text: Binding(get: { folder.title }, set: { editor.updateFolderTitle(title: $0) }))
        TextField("Cover image URL", text: Binding(get: { folder.coverImageUrl ?? "" }, set: { editor.updateFolderCoverImage(url: $0) }))
        TextField("Cover emoji", text: Binding(get: { folder.coverEmoji ?? "" }, set: { editor.updateFolderCoverEmoji(emoji: $0) }))
        SettingsToggleRow(title: "Hide title", isOn: folder.hideTitle) { editor.updateFolderHideTitle(hide: !folder.hideTitle) }
        HStack {
            ForEach([(PosterShape.poster, "Poster"), (.landscape, "Landscape"), (.square, "Square")], id: \.1) { shape, title in
                SettingsChoiceChip(label: title, selected: folder.posterShape == shape) { editor.updateFolderTileShape(shape: shape) }
            }
        }.focusSection()
        ForEach(Array(folder.resolvedSources.enumerated()), id: \.offset) { index, source in
            SettingsActionRow(title: source.title ?? source.catalogId ?? "Source", subtitle: source.filters.map { TvCollectionFilters.shared.fields(f: $0).filter { !$0.value.isEmpty }.map { "\($0.label): \($0.value)" }.joined(separator: " · ") } ?? source.genre, value: source.isTrakt ? "Edit" : "Options", showChevron: false) {
                if source.isTrakt { editor.editTraktSource(index: Int32(index)) }
                else if let catalog = TvCollectionSources.shared.catalog(index: Int32(index)), !catalog.genreOptions.isEmpty {
                    genreCatalog = CatalogGenreTarget(index: Int32(index), catalog: catalog)
                } else { removingSource = index }
            }.contextMenu {
                if let catalog = TvCollectionSources.shared.catalog(index: Int32(index)), !catalog.genreOptions.isEmpty {
                    Button("Choose genre") { genreCatalog = CatalogGenreTarget(index: Int32(index), catalog: catalog) }
                }
                Button("Remove source", role: .destructive) { removingSource = index }
            }

        }
        if StoreCopy.showsContentDiscovery {
            SettingsActionRow(title: "Add catalog") { editor.showCatalogPicker() }
        }
        SettingsActionRow(title: "Add TMDB source") { editor.showTmdbSourcePicker() }.accessibilityIdentifier("collections.addTmdb")
        if state.traktAvailable {
            SettingsActionRow(title: "Add Trakt list") { editor.showTraktSourcePicker() }
        }
        SettingsActionRow(title: "Save folder", showChevron: false) {
            guard !folder.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { error = "Enter a folder title."; return }
            error = nil; editor.saveFolderEdit()
        }.accessibilityIdentifier("collections.saveFolder")
        SettingsActionRow(title: "Cancel", showChevron: false) { error = nil; editor.cancelFolderEdit() }
    }

    private func catalogs(_ state: CollectionEditorUiState) -> some View {
        NuvioDialog(title: "Add catalog", width: dp(660)) {
            ForEach(Array(state.availableCatalogs.enumerated()), id: \.offset) { _, catalog in
                SettingsActionRow(title: catalog.catalogName, subtitle: "\(catalog.addonName) · \(catalog.type)") {
                    editor.addCatalogSource(catalog: catalog)
                    editor.hideCatalogPicker()
                }
            }
        }
    }
}

private struct CatalogGenreTarget: Identifiable {
    let index: Int32
    let catalog: AvailableCatalog
    var id: Int32 { index }
}

private struct CollectionSourceInput: View {
    let isTrakt: Bool
    @State private var input = ""
    @State private var title = ""
    @State private var series = false
    @State private var both = false
    @State private var mode = "presets"
    @State private var sort = "popularity.desc"
    @State private var descending = false
    @State private var error: String?
    @State private var filterOpen = false
    @State private var filters = TvCollectionFilters.shared.empty()
    private var editor: CollectionEditorRepository { CollectionEditorRepository.shared }
    private let modes: [(String, TmdbBuilderMode)] = [("presets", .presets), ("discover", .discover), ("list", .list), ("collection", .collection), ("company", .production), ("network", .network), ("person", .person), ("director", .director)]
    private var sorts: [(String, String)] {
        if isTrakt { return [("rank", "List order"), ("added", "Date added"), ("title", "Title"), ("released", "Release date"), ("runtime", "Runtime"), ("popularity", "Popularity"), ("percentage", "Rating"), ("votes", "Votes")] }
        let values = [("popularity.desc", "Popularity"), ("vote_average.desc", "Rating"), ("vote_count.desc", "Votes"), (series ? "first_air_date.desc" : "primary_release_date.desc", "Release date")]
        return ["list", "collection"].contains(mode) ? [("original", "Original order")] + values : values
    }
    var body: some View {
        NuvioDialog(title: isTrakt ? "Add Trakt list" : "Add TMDB source", width: dp(660)) {
            if !isTrakt {
                ScrollView(.horizontal, showsIndicators: false) { HStack {
                    ForEach(modes, id: \.0) { key, _ in
                        SettingsChoiceChip(label: key.capitalized, selected: mode == key) {
                            mode = key
                            series = key == "network" ? true : key == "collection" ? false : series
                            if ["network", "collection", "list"].contains(key) { both = false }
                            sort = ["list", "collection"].contains(key) ? "original" : "popularity.desc"
                        }.accessibilityIdentifier("collectionSource.mode.\(key)")
                    }
                } }.scrollClipDisabled().focusSection()
            }
            if !isTrakt && mode == "presets" {
                ForEach(TmdbCollectionSourceResolver.shared.presets(), id: \.label) { preset in
                    SettingsActionRow(title: preset.label) { editor.addTmdbPreset(source: preset.source) }
                }
            } else {
                if isTrakt || mode != "discover" {
                    TextField(isTrakt ? "List URL or ID" : "TMDB URL or ID", text: $input)
                }
                TextField("Title", text: $title).accessibilityIdentifier("collectionSource.title")
                if isTrakt || !["collection", "network"].contains(mode) {
                    SettingsToggleRow(title: "TV shows", isOn: series) { series.toggle(); both = false; if !sorts.contains(where: { $0.0 == sort }) { sort = sorts.first!.0 } }
                }
                if isTrakt || !["collection", "network", "list"].contains(mode) {
                    SettingsToggleRow(title: "Movies and TV shows", isOn: both) { both.toggle(); if both { series = false }; if !sorts.contains(where: { $0.0 == sort }) { sort = sorts.first!.0 } }
                }
                SettingsActionRow(title: "Sort by", value: sorts.first(where: { $0.0 == sort })?.1 ?? sorts[0].1, showChevron: false) {
                    let index = sorts.firstIndex(where: { $0.0 == sort }) ?? 0
                    sort = sorts[(index + 1) % sorts.count].0
                }.accessibilityIdentifier("collectionSource.sort")
                if isTrakt {
                    SettingsToggleRow(title: "Descending order", isOn: descending) { descending.toggle() }
                } else if mode == "discover" {
                    SettingsActionRow(title: "Discovery filters") { filterOpen = true }.accessibilityIdentifier("collectionSource.filters")
                }
                if let error { Text(error).font(NuvioType.bodyMedium) }
                SettingsActionRow(title: "Add source", showChevron: false) { addSource() }.accessibilityIdentifier("collectionSource.add")
            }
        }
        .sheet(isPresented: $filterOpen) {
            CollectionFiltersScreen(filters: filters, series: series) { filters = $0 }
        }
        .task {
            var first = true
            var generation: Int64 = 0
            for await state in editor.uiState {
                if first {
                    generation = state.sourcePickerCompletionGeneration
                    if isTrakt {
                        input = state.traktInput; title = state.traktTitleInput
                        series = state.traktMediaType == .tv; both = state.traktMediaBoth
                        sort = state.traktSortBy; descending = state.traktSortHow == "desc"
                    } else { filters = state.tmdbFilters }
                    first = false
                } else if generation != state.sourcePickerCompletionGeneration {
                    if isTrakt { editor.hideTraktSourcePicker() } else { editor.hideTmdbSourcePicker() }
                    break
                }
                error = isTrakt ? state.traktSearchError : state.tmdbSearchError
            }
        }
    }
    private func addSource() {
        let media: TmdbCollectionMediaType = series ? .tv : .movie
        if isTrakt {
            editor.setTraktInput(value: input); editor.setTraktTitleInput(value: title)
            editor.setTraktMediaType(value: media); editor.setTraktMediaBoth(value: both)
            editor.setTraktSortBy(value: sort); editor.setTraktSortHow(value: descending ? "desc" : "asc")
            editor.addTraktSourceFromInput()
        } else {
            editor.setTmdbBuilderMode(mode: modes.first(where: { $0.0 == mode })?.1 ?? .discover)
            editor.setTmdbInput(value: input); editor.setTmdbTitleInput(value: title)
            editor.setTmdbMediaType(value: media); editor.setTmdbMediaBoth(value: both)
            editor.setTmdbSortBy(value: sort); editor.updateTmdbFilters { _ in filters }
            editor.addTmdbSourceFromInput()
        }
    }
}
