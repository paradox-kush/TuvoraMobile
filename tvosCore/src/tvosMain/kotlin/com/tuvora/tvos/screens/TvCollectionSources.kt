package com.tuvora.tvos.screens

import com.nuvio.app.features.collection.*

data class TvFilterGenre(val id: String, val name: String)
object TvCollectionSources {
    suspend fun genres(series: Boolean): List<TvFilterGenre> = TmdbCollectionSourceResolver.genres(
        if (series) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE
    ).map { (id, name) -> TvFilterGenre(id.toString(), name) }.sortedBy { it.name }

    fun catalog(index: Int): AvailableCatalog? {
        val state = CollectionEditorRepository.uiState.value
        val source = state.editingFolder?.resolvedSources?.getOrNull(index)?.addonCatalogSource() ?: return null
        return state.availableCatalogs.findAvailableCatalog(source)
    }
}
