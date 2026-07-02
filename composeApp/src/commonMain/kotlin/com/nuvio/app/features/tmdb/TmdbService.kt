package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TmdbService {
    private val log = Logger.withTag("TmdbService")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbToTmdbCache = linkedMapOf<String, String>()
    private val tmdbToImdbCache = linkedMapOf<String, String>()
    private val cacheMutex = Mutex()

    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        val apiKey = currentApiKey() ?: return null

        val normalized = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        if (normalized.isBlank()) return null
        if (normalized.all(Char::isDigit)) return normalized
        if (!normalized.startsWith("tt", ignoreCase = true)) return null

        return imdbToTmdb(imdbId = normalized, mediaType = mediaType, apiKey = apiKey)
    }

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val apiKey = currentApiKey() ?: return null

        val cacheKey = "$tmdbId:${normalizeMediaType(mediaType)}"
        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey]?.let { return it }
        }

        val endpoint = when (normalizeMediaType(mediaType)) {
            "tv" -> "tv/$tmdbId/external_ids"
            else -> "movie/$tmdbId/external_ids"
        }
        val body = fetch<TmdbExternalIdsResponse>(endpoint = endpoint, apiKey = apiKey) ?: return null
        val imdbId = body.imdbId?.trim()?.takeIf(String::isNotBlank) ?: return null

        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey] = imdbId
            imdbToTmdbCache["$imdbId:${normalizeMediaType(mediaType)}"] = tmdbId.toString()
        }
        return imdbId
    }

    private suspend fun imdbToTmdb(imdbId: String, mediaType: String, apiKey: String): String? {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$imdbId:$normalizedType"
        cacheMutex.withLock {
            imdbToTmdbCache[cacheKey]?.let { return it }
        }

        val body = fetch<TmdbFindResponse>(
            endpoint = "find/$imdbId",
            apiKey = apiKey,
            query = mapOf("external_source" to "imdb_id"),
        ) ?: return null

        val resultId = when (normalizedType) {
            "movie" -> body.movieResults.firstOrNull()?.id
            "tv" -> body.tvResults.firstOrNull()?.id
            else -> body.movieResults.firstOrNull()?.id ?: body.tvResults.firstOrNull()?.id
        }?.takeIf { it > 0 }?.toString()

        if (resultId != null) {
            cacheMutex.withLock {
                imdbToTmdbCache[cacheKey] = resultId
                tmdbToImdbCache["$resultId:$normalizedType"] = imdbId
            }
        } else {
            log.d { "No TMDB ID found for $imdbId ($normalizedType)" }
        }

        return resultId
    }

    /**
     * Every name TMDB knows for a title (primary + original + alternative_titles +
     * translations, one details call) plus release year. This is what makes cross-language
     * IPTV matching work: a panel's "Planeta Tierra II" or "اتاق های پشتی" matches via the
     * official localized title, not fuzzy string similarity.
     */
    suspend fun titleBundle(tmdbId: Int, mediaType: String): TmdbTitleBundle? {
        val apiKey = currentApiKey() ?: return null
        val type = normalizeMediaType(mediaType)
        val cacheKey = "$tmdbId:$type"
        cacheMutex.withLock { titleBundleCache[cacheKey]?.let { return it } }

        val endpoint = if (type == "tv") "tv/$tmdbId" else "movie/$tmdbId"
        val d = fetch<TmdbDetailsWithTitles>(
            endpoint = endpoint,
            apiKey = apiKey,
            query = mapOf("append_to_response" to "alternative_titles,translations"),
        ) ?: return null

        val primary = (d.title ?: d.name)?.trim()?.takeIf(String::isNotBlank)
        val original = (d.originalTitle ?: d.originalName)?.trim()?.takeIf(String::isNotBlank)
        val alts = LinkedHashSet<String>()
        (d.alternativeTitles?.titles.orEmpty() + d.alternativeTitles?.results.orEmpty())
            .mapNotNullTo(alts) { it.title?.trim()?.takeIf(String::isNotBlank) }
        d.translations?.translations.orEmpty()
            .mapNotNullTo(alts) { (it.data?.title ?: it.data?.name)?.trim()?.takeIf(String::isNotBlank) }
        primary?.let(alts::remove); original?.let(alts::remove)

        val bundle = TmdbTitleBundle(
            primary = primary,
            original = original,
            alternatives = alts.toList(),
            year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull(),
        )
        cacheMutex.withLock {
            if (titleBundleCache.size >= 200) titleBundleCache.remove(titleBundleCache.keys.first())
            titleBundleCache[cacheKey] = bundle
        }
        return bundle
    }

    private val titleBundleCache = linkedMapOf<String, TmdbTitleBundle>()

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(httpGetText(url))
        }.onFailure { error ->
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
        }.getOrNull()
    }

    // user-entered key wins; the build-time default keeps TMDB-dependent features
    // (IPTV matching, id conversion) working on installs that never configured one
    private fun currentApiKey(): String? =
        TmdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank)
            ?: TmdbConfig.DEFAULT_API_KEY.takeIf(String::isNotBlank)

    internal fun normalizeMediaType(mediaType: String): String =
        when (mediaType.trim().lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "show", "tvshow" -> "tv"
            else -> mediaType.trim().lowercase()
        }
}

internal fun buildTmdbUrl(
    endpoint: String,
    apiKey: String,
    query: Map<String, String> = emptyMap(),
): String {
    val params = linkedMapOf("api_key" to apiKey)
    query.forEach { (key, value) ->
        if (value.isNotBlank()) {
            params[key] = value
        }
    }
    return buildString {
        append("https://api.themoviedb.org/3/")
        append(endpoint.removePrefix("/"))
        if (params.isNotEmpty()) {
            append("?")
            append(params.entries.joinToString("&") { (key, value) -> "$key=$value" })
        }
    }
}

data class TmdbTitleBundle(
    val primary: String?,
    val original: String?,
    val alternatives: List<String>,
    val year: Int?,
)

@Serializable
private data class TmdbDetailsWithTitles(
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("alternative_titles") val alternativeTitles: TmdbAltTitles? = null,
    val translations: TmdbTranslations? = null,
)

@Serializable
private data class TmdbAltTitles(
    val titles: List<TmdbAltTitle> = emptyList(),   // movies
    val results: List<TmdbAltTitle> = emptyList(),  // tv
)

@Serializable
private data class TmdbAltTitle(val title: String? = null)

@Serializable
private data class TmdbTranslations(val translations: List<TmdbTranslation> = emptyList())

@Serializable
private data class TmdbTranslation(val data: TmdbTranslationData? = null)

@Serializable
private data class TmdbTranslationData(val title: String? = null, val name: String? = null)

@Serializable
private data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbExternalResult> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbExternalResult> = emptyList(),
)

@Serializable
private data class TmdbExternalResult(
    val id: Int,
)

@Serializable
private data class TmdbExternalIdsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
)
