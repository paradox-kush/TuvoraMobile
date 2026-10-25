package com.tuvora.tvos.screens

import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_LETTERBOXD
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_MAL
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_METACRITIC
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TRAKT
import com.nuvio.app.features.mdblist.RottenTomatoesStatus
import com.nuvio.app.features.mdblist.rottenTomatoesStatus
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/** One MDBList rating in the details hero: the logo asset and the formatted score. */
data class TvRatingChip(val source: String, val logo: String, val text: String) {
    val sourceName: String get() = when (source) {
        PROVIDER_TRAKT -> "Trakt"
        PROVIDER_IMDB -> "IMDb"
        PROVIDER_TMDB -> "TMDB"
        PROVIDER_LETTERBOXD -> "Letterboxd"
        PROVIDER_MAL -> "MyAnimeList"
        PROVIDER_TOMATOES -> "Rotten Tomatoes"
        PROVIDER_AUDIENCE -> "Audience Score"
        PROVIDER_METACRITIC -> "Metacritic"
        else -> source
    }
}

/**
 * MDBList ratings on the details hero. MetaDetailsRepository.load already enriches the title with
 * MetaDetails.externalRatings (API key or connected account); this decides what the hero shows.
 * Order and look = NuvioTV MDBListRatingsRow; logos and number formats = the phone's DetailMetaInfo.kt,
 * because the values come from the shared decoder (TMDB/Trakt/Metacritic on 0–100, Letterboxd on 0–5).
 */
object TvRatingsPolicy {
    private val ORDER = listOf(
        PROVIDER_TRAKT, PROVIDER_IMDB, PROVIDER_TMDB, PROVIDER_LETTERBOXD, PROVIDER_MAL,
        PROVIDER_TOMATOES, PROVIDER_AUDIENCE, PROVIDER_METACRITIC,
    )

    fun chips(ratings: List<MetaExternalRating>, mdbListActive: Boolean): List<TvRatingChip> {
        if (!mdbListActive) return emptyList()
        val bySource = ratings.associateBy { it.source }
        return ORDER.mapNotNull { source ->
            val rating = bySource[source] ?: return@mapNotNull null
            TvRatingChip(source = source, logo = logo(rating), text = format(rating))
        }
    }

    /**
     * The plain IMDb line in the meta row. The MDBList settings promise "hide default IMDb line when
     * available", so it hides only when the MDBList row actually shows an IMDb score.
     */
    fun showsImdbLine(imdbRating: String?, ratings: List<MetaExternalRating>, mdbListActive: Boolean): Boolean {
        if (imdbRating.isNullOrBlank()) return false
        return chips(ratings, mdbListActive).none { it.source == PROVIDER_IMDB }
    }

    private fun logo(rating: MetaExternalRating): String = when (rating.rottenTomatoesStatus) {
        RottenTomatoesStatus.FRESH -> "rating_rotten_tomatoes"
        RottenTomatoesStatus.ROTTEN -> "rating_rotten_tomatoes_rotten"
        RottenTomatoesStatus.CERTIFIED_FRESH -> "rating_rotten_tomatoes_certified"
        RottenTomatoesStatus.HOT -> "rating_audience_score"
        RottenTomatoesStatus.STALE -> "rating_audience_stale"
        RottenTomatoesStatus.VERIFIED_HOT -> "rating_audience_verified_hot"
        null -> when (rating.source) {
            PROVIDER_IMDB -> "rating_imdb"
            PROVIDER_TMDB -> "rating_tmdb"
            PROVIDER_TRAKT -> "rating_trakt"
            PROVIDER_LETTERBOXD -> "rating_letterboxd"
            PROVIDER_MAL -> "rating_mal"
            PROVIDER_METACRITIC -> "rating_metacritic"
            else -> "rating_imdb"
        }
    }

    private fun format(rating: MetaExternalRating): String = when (rating.source) {
        PROVIDER_IMDB, PROVIDER_LETTERBOXD, PROVIDER_MAL -> oneDecimal(rating.value)
        PROVIDER_TOMATOES, PROVIDER_AUDIENCE -> "${rating.value.roundToInt()}%"
        else -> rating.value.roundToInt().toString()
    }

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10.0).roundToInt()
        return "${rounded / 10}.${(rounded % 10).absoluteValue}"
    }
}
