package com.tuvora.tvos.screens

import com.nuvio.app.features.collection.TmdbCollectionFilters
import kotlin.test.*

class TvCollectionFiltersTest {
    @Test fun invalidNumbersNeverReplaceExistingValues() {
        val f = TmdbCollectionFilters(voteAverageGte = 7.0)
        for (v in listOf("NaN", "Infinity", "11", "-1", "oops")) assertNull(TvCollectionFilters.edit(f, "voteAverageGte", v).filters)
        assertEquals(7.0, f.voteAverageGte)
    }
    @Test fun acceptsRealDatesAndRejectsImpossibleOrReversedRange() {
        assertNotNull(TvCollectionFilters.edit(TmdbCollectionFilters(), "releaseDateGte", "2024-02-29").filters)
        assertNull(TvCollectionFilters.edit(TmdbCollectionFilters(), "releaseDateGte", "2025-02-29").filters)
        assertNull(TvCollectionFilters.edit(TmdbCollectionFilters(releaseDateLte = "2024-01-01"), "releaseDateGte", "2025-01-01").filters)
    }
    @Test fun clearRemovesOnlySelectedFilter() {
        val f = TmdbCollectionFilters(year = 2024, withGenres = "18", voteCountGte = 100)
        val next = TvCollectionFilters.edit(f, "year", " ").filters!!
        assertNull(next.year); assertEquals("18", next.withGenres); assertEquals(100, next.voteCountGte)
    }
    @Test fun normalizesCodesAndPreservesCompoundIdSemantics() {
        assertEquals("US", TvCollectionFilters.edit(TmdbCollectionFilters(), "watchRegion", " us ").filters?.watchRegion)
        assertEquals("en", TvCollectionFilters.edit(TmdbCollectionFilters(), "withOriginalLanguage", " EN ").filters?.withOriginalLanguage)
        assertEquals("18|35,80", TvCollectionFilters.edit(TmdbCollectionFilters(), "withGenres", "18|35,80").filters?.withGenres)
        for(v in listOf("0", "-1", "18,", "18 OR 35")) assertNull(TvCollectionFilters.edit(TmdbCollectionFilters(), "withGenres", v).filters)
    }
    @Test fun reversedRatingsAndUnknownFieldsFailSafely() {
        assertNull(TvCollectionFilters.edit(TmdbCollectionFilters(voteAverageGte = 8.0), "voteAverageLte", "7").filters)
        assertNotNull(TvCollectionFilters.edit(TmdbCollectionFilters(), "unknown", "x").error)
        assertEquals(18, TvCollectionFilters.fields(TmdbCollectionFilters()).size)
    }
}
