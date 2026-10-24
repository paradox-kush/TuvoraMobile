package com.tuvora.tvos.screens

import com.nuvio.app.features.collection.TmdbCollectionFilters
import kotlinx.datetime.LocalDate

data class TvFilterField(val key: String, val label: String, val value: String, val hint: String)
data class TvFilterEdit(val filters: TmdbCollectionFilters?, val error: String?)

/** Validate a draft before changing shared editor state. Failed edits preserve the previous filters. */
object TvCollectionFilters {
    fun empty(): TmdbCollectionFilters = TmdbCollectionFilters()
    fun fields(f: TmdbCollectionFilters): List<TvFilterField> = listOf(
        TvFilterField("withGenres", "Include genres", f.withGenres.orEmpty(), "Choose genres, or enter TMDB IDs"),
        TvFilterField("withoutGenres", "Exclude genres", f.withoutGenres.orEmpty(), "Choose genres, or enter TMDB IDs"),
        TvFilterField("releaseDateGte", "Released after", f.releaseDateGte.orEmpty(), "YYYY-MM-DD"),
        TvFilterField("releaseDateLte", "Released before", f.releaseDateLte.orEmpty(), "YYYY-MM-DD"),
        TvFilterField("voteAverageGte", "Minimum rating", f.voteAverageGte?.toString().orEmpty(), "0–10"),
        TvFilterField("voteAverageLte", "Maximum rating", f.voteAverageLte?.toString().orEmpty(), "0–10"),
        TvFilterField("voteCountGte", "Minimum votes", f.voteCountGte?.toString().orEmpty(), "0 or more"),
        TvFilterField("year", "Release year", f.year?.toString().orEmpty(), "Four-digit year"),
        TvFilterField("withOriginalLanguage", "Original language", f.withOriginalLanguage.orEmpty(), "Two-letter language code, e.g. en"),
        TvFilterField("withOriginCountry", "Country of origin", f.withOriginCountry.orEmpty(), "Two-letter country code, e.g. US"),
        TvFilterField("watchRegion", "Streaming region", f.watchRegion.orEmpty(), "Two-letter country code, e.g. US"),
        TvFilterField("withWatchProviders", "Include streaming services", f.withWatchProviders.orEmpty(), "TMDB provider IDs; set Streaming region"),
        TvFilterField("withoutWatchProviders", "Exclude streaming services", f.withoutWatchProviders.orEmpty(), "TMDB provider IDs; set Streaming region"),
        TvFilterField("withKeywords", "Include keywords", f.withKeywords.orEmpty(), "TMDB keyword IDs"),
        TvFilterField("withoutKeywords", "Exclude keywords", f.withoutKeywords.orEmpty(), "TMDB keyword IDs"),
        TvFilterField("withCompanies", "Include studios", f.withCompanies.orEmpty(), "TMDB company IDs"),
        TvFilterField("withoutCompanies", "Exclude studios", f.withoutCompanies.orEmpty(), "TMDB company IDs"),
        TvFilterField("withNetworks", "Networks", f.withNetworks.orEmpty(), "TMDB network IDs; TV shows only"),
    )

    fun edit(f: TmdbCollectionFilters, key: String, raw: String): TvFilterEdit = try {
        val v = raw.trim().takeIf { it.isNotEmpty() }
        fun ids(): String? = v?.also { require(Regex("[1-9][0-9]*([,|][1-9][0-9]*)*").matches(it)) { "Use positive IDs separated by commas or |." } }
        fun date(): String? = v?.also { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) && runCatching { LocalDate.parse(it) }.isSuccess) { "Use a valid date in YYYY-MM-DD format." } }
        fun rating(): Double? = v?.let { it.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n in 0.0..10.0 } ?: error("Use a rating from 0 to 10.") }
        fun code(upper: Boolean): String? = v?.also { require(Regex("[a-zA-Z]{2}").matches(it)) { "Use a two-letter code." } }?.let { if (upper) it.uppercase() else it.lowercase() }
        val next = when (key) {
            "withGenres" -> f.copy(withGenres = ids())
            "withoutGenres" -> f.copy(withoutGenres = ids())
            "releaseDateGte" -> f.copy(releaseDateGte = date())
            "releaseDateLte" -> f.copy(releaseDateLte = date())
            "voteAverageGte" -> f.copy(voteAverageGte = rating())
            "voteAverageLte" -> f.copy(voteAverageLte = rating())
            "voteCountGte" -> f.copy(voteCountGte = v?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: error("Use a whole number of votes, 0 or more.") })
            "year" -> f.copy(year = v?.let { it.toIntOrNull()?.takeIf { n -> n in 1000..9999 } ?: error("Use a four-digit year.") })
            "withOriginalLanguage" -> f.copy(withOriginalLanguage = code(false))
            "withOriginCountry" -> f.copy(withOriginCountry = code(true))
            "watchRegion" -> f.copy(watchRegion = code(true))
            "withWatchProviders" -> f.copy(withWatchProviders = ids())
            "withoutWatchProviders" -> f.copy(withoutWatchProviders = ids())
            "withKeywords" -> f.copy(withKeywords = ids())
            "withoutKeywords" -> f.copy(withoutKeywords = ids())
            "withCompanies" -> f.copy(withCompanies = ids())
            "withoutCompanies" -> f.copy(withoutCompanies = ids())
            "withNetworks" -> f.copy(withNetworks = ids())
            else -> error("Unknown filter.")
        }
        require(next.voteAverageGte == null || next.voteAverageLte == null || next.voteAverageGte <= next.voteAverageLte) { "Minimum rating must not exceed maximum rating." }
        require(next.releaseDateGte == null || next.releaseDateLte == null || next.releaseDateGte <= next.releaseDateLte) { "Released after must not be later than Released before." }
        TvFilterEdit(next, null)
    } catch (e: IllegalArgumentException) { TvFilterEdit(null, e.message) }
      catch (e: IllegalStateException) { TvFilterEdit(null, e.message) }
}
