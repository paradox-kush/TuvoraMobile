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
            SettingsActionRow(title: source.title ?? source.catalogId ?? "Source", subtitle: source.genre, value: "Remove", showChevron: false) {
                editor.removeCatalogSource(index: Int32(index))
            }
        }
        if StoreCopy.showsContentDiscovery {
            SettingsActionRow(title: "Add catalog") { editor.showCatalogPicker() }
        }
        SettingsActionRow(title: "Add TMDB source") { editor.showTmdbSourcePicker() }
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

private struct CollectionSourceInput: View {
    let isTrakt: Bool
    @State private var input = ""
    @State private var title = ""
    @State private var series = false
    @State private var mode = "list"
    @State private var error: String?
    private var editor: CollectionEditorRepository { CollectionEditorRepository.shared }
    private let modes: [(String, TmdbBuilderMode)] = [("list", .list), ("collection", .collection), ("company", .production), ("network", .network), ("person", .person), ("director", .director)]

    var body: some View {
        NuvioDialog(title: isTrakt ? "Add Trakt list" : "Add TMDB source", width: dp(660)) {
            if !isTrakt {
                ScrollView(.horizontal, showsIndicators: false) { HStack {
                    ForEach(modes, id: \.0) { key, _ in
                        SettingsChoiceChip(label: key.capitalized, selected: mode == key) { mode = key }
                    }
                } }.scrollClipDisabled().focusSection()
            }
            TextField(isTrakt ? "List URL or ID" : "TMDB URL or ID", text: $input)
            TextField("Title", text: $title)
            SettingsToggleRow(title: "TV shows", isOn: series) { series.toggle() }
            if let error { Text(error).font(NuvioType.bodyMedium) }
            SettingsActionRow(title: "Add source", showChevron: false) {
                let media: TmdbCollectionMediaType = series ? .tv : .movie
                if isTrakt {
                    editor.setTraktInput(value: input); editor.setTraktTitleInput(value: title)
                    editor.setTraktMediaBoth(value: false); editor.setTraktMediaType(value: media)
                    editor.addTraktSourceFromInput()
                } else {
                    editor.setTmdbBuilderMode(mode: modes.first(where: { $0.0 == mode })?.1 ?? .list)
                    editor.setTmdbInput(value: input); editor.setTmdbTitleInput(value: title)
                    editor.setTmdbMediaBoth(value: false); editor.setTmdbMediaType(value: media)
                    editor.addTmdbSourceFromInput()
                }
            }
        }
        .task { for await state in editor.uiState { error = isTrakt ? state.traktSearchError : state.tmdbSearchError } }
    }
}
