package com.tuvora.tvos.screens

import com.nuvio.app.features.details.ImdbEpisodeRatingsRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.details.MoreLikeThisSource
import com.nuvio.app.features.details.PersonDetail
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktCommentReview
import com.nuvio.app.features.trakt.TraktCommentsRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktConnectionMode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.person_role_creator
import nuvio.composeapp.generated.resources.person_role_director
import nuvio.composeapp.generated.resources.person_role_writer
import org.jetbrains.compose.resources.getString

/** One episode's IMDb rating (the shared repository keys them by a Kotlin Pair, which Swift can't read). */
data class TvEpisodeRating(val season: Int, val episode: Int, val rating: Double)

/**
 * The details page's lower sections for Apple TV, over the phone's own data: cast, recommendations,
 * collection, trailers and companies all arrive inside [MetaDetails] from MetaDetailsRepository's single
 * load (TMDB enrichment included), so nothing here re-fetches them. Comments, episode ratings and a
 * person's page are fetched once, on demand, through the same shared repositories the phone uses
 * (each caches), never on a timer.
 */
object TvTitleSections {
    private val isTvType = setOf("series", "show", "tv", "tvshow")

    fun isTvShow(meta: MetaDetails): Boolean = meta.type.trim().lowercase() in isTvType

    /** Episode ratings apply: a series with numbered episodes and the profile's ratings setting on (phone rule). */
    fun showsEpisodeRatings(meta: MetaDetails): Boolean {
        MetaScreenSettingsRepository.ensureLoaded()
        return MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility.showRatings &&
            isTvShow(meta) && meta.videos.any { it.season != null && it.episode != null }
    }

    /** Trakt reviews show when the profile enabled them and Trakt is connected (phone's shouldShowComments). */
    fun showsComments(meta: MetaDetails): Boolean {
        TraktCommentsSettings.ensureLoaded()
        return TraktCommentsSettings.enabled.value &&
            TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED &&
            meta.type.lowercase().let { it == "movie" || it in isTvType }
    }

    /**
     * [episodeRatingsLoaded]: the Ratings tab appears once ratings actually arrived. The ratings
     * service URL is build-time config and is unset in some builds; an always-empty tab is noise.
     */
    fun layout(meta: MetaDetails, episodeRatingsLoaded: Boolean): TvDetailSectionsLayout {
        MetaScreenSettingsRepository.ensureLoaded()
        val disabled = MetaScreenSettingsRepository.uiState.value.items.filterNot { it.enabled }.map { it.key }.toSet()
        return TvDetailSectionsPolicy.layout(
            TvDetailSectionInputs(
                isTvShow = isTvShow(meta),
                castCount = meta.cast.size,
                showEpisodeRatings = episodeRatingsLoaded && showsEpisodeRatings(meta),
                moreLikeThisCount = meta.moreLikeThis.size,
                playableTrailerCount = TvDetailSectionsPolicy.playableTrailers(meta.trailers).size,
                collectionCount = meta.collectionItems.size,
                showComments = showsComments(meta),
                networkCount = meta.networks.size,
                productionCount = meta.productionCompanies.size,
                disabled = disabled,
            ),
        )
    }

    suspend fun cast(meta: MetaDetails): TvCastSplit {
        val roles = TvDetailSectionsPolicy.LeadRoles().with(
            creator = getString(Res.string.person_role_creator),
            director = getString(Res.string.person_role_director),
            writer = getString(Res.string.person_role_writer),
        )
        return TvDetailSectionsPolicy.splitCast(meta.cast, roles)
    }

    fun trailers(meta: MetaDetails): List<MetaTrailer> = TvDetailSectionsPolicy.playableTrailers(meta.trailers)

    /** NuvioTV's "Powered by …" line under More like this (English key; the app localizes it). */
    fun moreLikeThisSourceLabel(meta: MetaDetails): String? = when (meta.moreLikeThisSource) {
        MoreLikeThisSource.TMDB -> "Powered by TMDB"
        MoreLikeThisSource.TRAKT -> "Powered by Trakt"
        MoreLikeThisSource.SIMKL -> "Powered by Simkl"
        null -> null
    }

    /** First page of Trakt reviews (TraktCommentsRepository caches per title). */
    suspend fun comments(meta: MetaDetails): List<TraktCommentReview> =
        TraktCommentsRepository.getCommentsPage(meta, page = 1).items

    /** IMDb episode ratings (ImdbEpisodeRatingsRepository caches); the phone's id resolution. */
    suspend fun episodeRatings(meta: MetaDetails): List<TvEpisodeRating> {
        val imdbId = extractImdbId(meta.id) ?: meta.imdbId
        val tmdbId = extractTmdbId(meta.id)
            ?: TmdbService.ensureTmdbId(meta.id, meta.type, fallbackImdbId = meta.imdbId)?.toIntOrNull()
        if (imdbId == null && tmdbId == null) return emptyList()
        return ImdbEpisodeRatingsRepository.getEpisodeRatings(imdbId = imdbId, tmdbId = tmdbId)
            .filter { (key, _) ->
                MetaScreenSettingsRepository.uiState.value.episodeRatingsVisibility.showRating(
                    com.nuvio.app.features.watched.WatchedRepository.isWatched(meta.id, meta.type, key.first, key.second) ||
                        meta.videos.firstOrNull { it.season == key.first && it.episode == key.second }?.let {
                            com.nuvio.app.features.watchprogress.WatchProgressRepository.progressForVideo(it.id, meta.id, key.first, key.second)?.isEffectivelyCompleted
                        } == true)
            }
            .map { (key, rating) -> TvEpisodeRating(key.first, key.second, rating) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * NuvioTV opens a person page only for a TMDB person; the shared person fetch also needs the
     * profile's TMDB integration on (it returns null otherwise), so without it the card does nothing.
     */
    fun canOpenPerson(person: MetaPerson): Boolean = person.tmdbId != null && TmdbSettingsRepository.snapshot().enabled

    /** A person's page (NuvioTV CastDetailScreen): TMDB bio + credits, cached by the shared service. */
    suspend fun person(tmdbId: Int, preferCrew: Boolean): PersonDetail? =
        TmdbMetadataService.fetchPersonDetail(personId = tmdbId, preferCrewCredits = preferCrew)

    /**
     * Simulator smoke hook only (`-smokeTmdbEnrich`): the TMDB enrichment the phone applies when the
     * profile enables TMDB, computed read-only for one title so the sections can be verified on a
     * profile with TMDB off. Changes no setting and syncs nothing.
     */
    suspend fun smokeTmdbEnriched(meta: MetaDetails): MetaDetails =
        TmdbMetadataService.enrichMeta(meta, fallbackItemId = meta.id, settings = TmdbSettingsRepository.snapshot().copy(enabled = true))

    private fun extractImdbId(value: String?): String? =
        value?.trim()?.split(':', '/', '?', '&')?.firstOrNull { it.startsWith("tt", ignoreCase = true) }?.takeIf { it.length > 2 }

    private fun extractTmdbId(value: String?): Int? =
        value?.trim()?.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')?.substringBefore(':')?.substringBefore('/')?.toIntOrNull()
}
