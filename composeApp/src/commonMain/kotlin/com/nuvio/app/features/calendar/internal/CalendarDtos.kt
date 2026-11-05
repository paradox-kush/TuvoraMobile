// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): data/remote/dto/TvMazeDto.kt,
// data/remote/dto/trakt/TraktCalendarDtos.kt and the TMDB DTOs its calendar reads, as kotlinx.serialization.
package com.nuvio.app.features.calendar.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val calendarJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class TvMazeShowDto(
    val id: Int,
    val name: String? = null,
    val status: String? = null,
    val network: TvMazeNetworkDto? = null,
    val webChannel: TvMazeNetworkDto? = null,
    val externals: TvMazeExternalsDto? = null,
    val image: TvMazeImageDto? = null,
    @SerialName("_embedded") val embedded: TvMazeShowEmbeddedDto? = null,
)

@Serializable
data class TvMazeImageDto(val medium: String? = null, val original: String? = null)

@Serializable
data class TvMazeNetworkDto(val id: Int? = null, val name: String? = null)

@Serializable
data class TvMazeExternalsDto(val imdb: String? = null, val thetvdb: Long? = null)

@Serializable
data class TvMazeShowEmbeddedDto(
    @SerialName("nextepisode") val nextEpisode: TvMazeEpisodeDto? = null,
    @SerialName("previousepisode") val previousEpisode: TvMazeEpisodeDto? = null,
    val seasons: List<TvMazeSeasonDto>? = null,
)

@Serializable
data class TvMazeSeasonDto(val id: Int? = null, val number: Int? = null)

@Serializable
data class TvMazeEpisodeDto(
    val id: Int? = null,
    val name: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val type: String? = null,
    val airdate: String? = null,
    val airtime: String? = null,
    val airstamp: String? = null,
)

@Serializable
data class TraktIdsDto(
    val trakt: Long? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
)

@Serializable
data class TraktCalendarShowRefDto(val title: String? = null, val status: String? = null, val ids: TraktIdsDto? = null)

@Serializable
data class TraktCalendarShowDto(
    @SerialName("first_aired") val firstAired: String? = null,
    val episode: TraktCalendarEpisodeDto? = null,
    val show: TraktCalendarShowRefDto? = null,
)

@Serializable
data class TraktCalendarEpisodeDto(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    @SerialName("first_aired") val firstAired: String? = null,
    @SerialName("episode_type") val episodeType: String? = null,
)

@Serializable
data class TmdbFindDto(
    @SerialName("movie_results") val movieResults: List<TmdbIdDto>? = null,
    @SerialName("tv_results") val tvResults: List<TmdbIdDto>? = null,
)

@Serializable
data class TmdbIdDto(val id: Long? = null)

@Serializable
data class TmdbEpisodeToAirDto(
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_type") val episodeType: String? = null,
)

@Serializable
data class TmdbNamedDto(val name: String? = null)

@Serializable
data class TmdbTvDetailsDto(
    val name: String? = null,
    val status: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val networks: List<TmdbNamedDto>? = null,
    @SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeToAirDto? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeToAirDto? = null,
)

@Serializable
data class TmdbSeasonDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    val episodes: List<TmdbEpisodeToAirDto>? = null,
)

@Serializable
data class TmdbReleaseDateDto(val type: Int? = null, @SerialName("release_date") val releaseDate: String? = null)

@Serializable
data class TmdbReleaseCountryDto(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDateDto>? = null,
)

@Serializable
data class TmdbReleaseDatesDto(val results: List<TmdbReleaseCountryDto>? = null)
