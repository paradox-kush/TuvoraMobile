import SwiftUI
import TuvoraCore

/// A local draft: Back cancels; Apply publishes one validated set to the shared editor.
struct CollectionFiltersScreen: View {
    let series: Bool
    let onApply: (TmdbCollectionFilters) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var filters: TmdbCollectionFilters
    @State private var editing: FilterTarget?
    @State private var advanced = false
    init(filters: TmdbCollectionFilters, series: Bool, onApply: @escaping (TmdbCollectionFilters) -> Void) {
        _filters = State(initialValue: filters); self.series = series; self.onApply = onApply
    }
    var body: some View {
        NuvioDialog(title: "Discovery filters", subtitle: "Empty fields include all results.", width: dp(660)) {
            ForEach(TvCollectionFilters.shared.fields(f: filters).filter { field in
                (advanced || ["withGenres", "withoutGenres", "year", "voteAverageGte", "voteCountGte"].contains(field.key)) && (series || field.key != "withNetworks")
            }, id: \.key) { field in
                SettingsActionRow(title: field.label, value: field.value.isEmpty ? "Any" : field.value) { editing = FilterTarget(field: field) }
                    .accessibilityIdentifier("filters.\(field.key)")
            }
            SettingsActionRow(title: advanced ? "Fewer filters" : "More filters", showChevron: false) { advanced.toggle() }
            SettingsActionRow(title: "Reset filters", showChevron: false) { filters = TvCollectionFilters.shared.empty() }
            SettingsActionRow(title: "Apply filters", showChevron: false) { onApply(filters); dismiss() }
                .accessibilityIdentifier("filters.apply")
            SettingsActionRow(title: "Cancel", showChevron: false) { dismiss() }
        }
        .sheet(item: $editing) { target in
            FilterValueScreen(field: target.field, filters: filters, series: series) { next in filters = next; editing = nil }
        }
    }
}
private struct FilterTarget: Identifiable { let field: TvFilterField; var id: String { field.key } }
private struct FilterValueScreen: View {
    let field: TvFilterField
    let filters: TmdbCollectionFilters
    let series: Bool
    let onSave: (TmdbCollectionFilters) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.nuvio) private var colors
    @State private var value: String
    @State private var error: String?
    @State private var genres: [TvFilterGenre] = []
    @State private var loading = false
    @State private var genreError = false
    init(field: TvFilterField, filters: TmdbCollectionFilters, series: Bool, onSave: @escaping (TmdbCollectionFilters) -> Void) {
        self.field = field; self.filters = filters; self.series = series; self.onSave = onSave
        _value = State(initialValue: field.value)
    }
    private var isGenre: Bool { field.key == "withGenres" || field.key == "withoutGenres" }
    private var selected: Set<String> { Set(value.split(whereSeparator: { $0 == "," || $0 == "|" }).map(String.init)) }
    var body: some View {
        NuvioDialog(title: field.label, subtitle: field.hint, width: dp(660)) {
            TextField(field.label, text: $value).autocorrectionDisabled().accessibilityIdentifier("filters.value")
            if let error { Text(error).font(NuvioType.bodyMedium).foregroundStyle(colors.error).accessibilityIdentifier("filters.error") }
            SettingsActionRow(title: "Save", showChevron: false) { save(value) }.accessibilityIdentifier("filters.save")
            SettingsActionRow(title: "Clear", showChevron: false) { save("") }
            SettingsActionRow(title: "Cancel", showChevron: false) { dismiss() }
            if isGenre {
                if loading { ProgressView() }
                if genreError { SettingsActionRow(title: "Couldn't load genres", value: "Retry") { Task { await loadGenres() } } }
                ForEach(genres, id: \.id) { genre in
                    SettingsToggleRow(title: genre.name, isOn: selected.contains(genre.id)) {
                        var next = selected
                        if next.contains(genre.id) { next.remove(genre.id) } else { next.insert(genre.id) }
                        value = next.sorted().joined(separator: ",")
                    }
                }
            }
        }.task { if isGenre { await loadGenres() } }
    }
    private func save(_ raw: String) {
        let result = TvCollectionFilters.shared.edit(f: filters, key: field.key, raw: raw)
        if let next = result.filters { onSave(next) } else { error = result.error }
    }
    private func loadGenres() async {
        guard !loading else { return }
        loading = true; genreError = false
        defer { loading = false }
        do { genres = try await TvCollectionSources.shared.genres(series: series) }
        catch is CancellationError { }
        catch { genreError = true }
    }
}
