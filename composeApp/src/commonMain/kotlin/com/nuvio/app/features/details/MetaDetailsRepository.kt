package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamKind
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamResolvedItem
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktRelatedRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktMoreLikeThis
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object MetaDetailsRepository {
    private data class CachedMetaEntry(
        val baseMeta: MetaDetails,
        val metaScreenMeta: MetaDetails? = null,
        val metaScreenSettingsFingerprint: String? = null,
    )

    private val log = Logger.withTag("MetaDetailsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private var activeRequestKey: String? = null
    private val cachedMetaByRequestKey = mutableMapOf<String, CachedMetaEntry>()

    fun load(type: String, id: String) {
        log.d { "load() called — type=$type id=$id" }
        val requestKey = "$type:$id"
        val currentState = _uiState.value
        val mdbListSettings = MdbListSettingsRepository.snapshot()
        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(mdbListSettings)

        cachedMetaByRequestKey[requestKey]?.let { cachedEntry ->
            cachedEntry.metaScreenMeta
                ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
                ?.let { cachedMeta ->
                    _uiState.value = MetaDetailsUiState(meta = cachedMeta.withUnreleasedFilter())
                    activeRequestKey = requestKey
                    return
                }

            val cachedBaseMeta = cachedEntry.baseMeta
            if (!shouldEnrichForMetaScreen(cachedBaseMeta, id, mdbListSettings)) {
                _uiState.value = MetaDetailsUiState(meta = cachedBaseMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
                return
            }

            if (currentState.isLoading && activeRequestKey == requestKey) {
                log.d { "Meta screen enrichment already in flight — type=$type id=$id" }
                return
            }

            activeRequestKey = requestKey
            _uiState.value = MetaDetailsUiState(
                isLoading = true,
                meta = cachedBaseMeta,
            )

            scope.launch {
                val enrichedMeta = withContext(Dispatchers.Default) {
                    enrichForMetaScreen(
                        requestKey = requestKey,
                        meta = cachedBaseMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        settings = mdbListSettings,
                        settingsFingerprint = metaScreenSettingsFingerprint,
                    )
                }
                _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
            }
            return
        }

        if (currentState.meta?.type == type && currentState.meta.id == id && !currentState.isLoading) {
            log.d { "Skipping reload for cached meta — type=$type id=$id" }
            activeRequestKey = requestKey
            return
        }

        if (currentState.isLoading && activeRequestKey == requestKey) {
            log.d { "Request already in flight — type=$type id=$id" }
            return
        }

        activeRequestKey = requestKey
        _uiState.value = MetaDetailsUiState(isLoading = true)

        scope.launch {
            if (XtreamItemRegistry.isXtreamId(id)) {
                loadXtreamMeta(requestKey = requestKey, id = id)
                return@launch
            }
            val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
            val manifests = findMetaManifests(type = type, id = metaLookupId)

            if (manifests.isEmpty()) {
                val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
                if (tmdbMeta != null) {
                    publishLoadedMeta(
                        requestKey = requestKey,
                        meta = tmdbMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        mdbListSettings = mdbListSettings,
                        metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                    )
                    return@launch
                }

                log.w { "No addon provides meta for type=$type id=$id" }
                _uiState.value = MetaDetailsUiState(
                    errorMessage = getString(Res.string.details_no_addon_meta),
                )
                activeRequestKey = null
                return@launch
            }

            for (manifest in manifests) {
                val result = withContext(Dispatchers.Default) {
                    tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false)
                }
                if (result != null) {
                    publishLoadedMeta(
                        requestKey = requestKey,
                        meta = result,
                        fallbackItemId = metaLookupId,
                        fallbackItemType = type,
                        mdbListSettings = mdbListSettings,
                        metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                    )
                    return@launch
                }
            }

            val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
            if (tmdbMeta != null) {
                publishLoadedMeta(
                    requestKey = requestKey,
                    meta = tmdbMeta,
                    fallbackItemId = id,
                    fallbackItemType = type,
                    mdbListSettings = mdbListSettings,
                    metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                )
                return@launch
            }

            _uiState.value = MetaDetailsUiState(
                errorMessage = getString(Res.string.details_load_failed_all_addons),
            )
            activeRequestKey = null
        }
    }

    fun peek(type: String, id: String): MetaDetails? {
        val requestKey = "$type:$id"
        val currentMeta = _uiState.value.meta?.takeIf { it.type == type && it.id == id }
        if (currentMeta != null) return currentMeta

        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(MdbListSettingsRepository.snapshot())
        val cachedEntry = cachedMetaByRequestKey[requestKey] ?: return null
        return cachedEntry.metaScreenMeta
            ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
            ?: cachedEntry.baseMeta
    }

    fun clear() {
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        _uiState.value = MetaDetailsUiState()
    }

    suspend fun fetch(type: String, id: String): MetaDetails? {
        val requestKey = "$type:$id"
        cachedMetaByRequestKey[requestKey]?.let { return it.baseMeta }

        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findMetaManifests(type = type, id = metaLookupId)

        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false)
            }
            if (result != null) {
                cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = result)
                return result
            }
        }

        return tryFetchTmdbFallbackMeta(type = type, id = id)?.also { result ->
            cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = result)
        }
    }

    private const val FETCH_TIMEOUT_MS = 5_000L
    private const val TMDB_ENRICH_TIMEOUT_MS = 5_000L
    private const val MDBLIST_ENRICH_TIMEOUT_MS = 5_000L

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
        includeMdbList: Boolean,
    ): MetaDetails? {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "meta",
            type = type,
            id = id,
        )

        return try {
            TmdbSettingsRepository.ensureLoaded()
            log.d { "Fetching meta from: $url" }
            val payload = httpGetText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            val result = MetaDetailsParser.parse(payload)
            val tmdbEnriched = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                TmdbMetadataService.enrichMeta(
                    meta = result,
                    fallbackItemId = id,
                    settings = TmdbSettingsRepository.snapshot(),
                )
            } ?: result
            val enriched = if (includeMdbList) {
                MdbListSettingsRepository.ensureLoaded()
                withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
                    MdbListMetadataService.enrichMeta(
                        meta = tmdbEnriched,
                        fallbackItemId = id,
                        settings = MdbListSettingsRepository.snapshot(),
                    )
                } ?: tmdbEnriched
            } else {
                tmdbEnriched
            }
            log.d { "Parsed meta: type=${enriched.type}, name=${enriched.name}, videos=${enriched.videos.size}" }
            if (enriched.videos.isNotEmpty()) {
                val first = enriched.videos.first()
                log.d { "First video: id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}" }
            }
            enriched
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.e(e) { "Failed to fetch/parse meta from $url (manifest=${manifest.transportUrl})" }
            null
        }
    }

    private fun findMetaManifests(type: String, id: String): List<AddonManifest> =
        AddonRepository.uiState.value.addons
            .enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "meta" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { id.startsWith(it) })
                }
            }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return itemId

        return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: itemId
    }

    private suspend fun tryFetchTmdbFallbackMeta(type: String, id: String): MetaDetails? =
        withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.fetchStandaloneMeta(
                type = type,
                id = id,
                settings = TmdbSettingsRepository.snapshot(),
            )
        }

    private suspend fun publishLoadedMeta(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        mdbListSettings: com.nuvio.app.features.mdblist.MdbListSettings,
        metaScreenSettingsFingerprint: String,
    ) {
        val cachedEntry = CachedMetaEntry(baseMeta = meta)
        cachedMetaByRequestKey[requestKey] = cachedEntry

        if (!shouldEnrichForMetaScreen(meta, fallbackItemId, mdbListSettings)) {
            _uiState.value = MetaDetailsUiState(meta = meta.withUnreleasedFilter())
            activeRequestKey = requestKey
            return
        }

        _uiState.value = MetaDetailsUiState(
            isLoading = true,
            meta = meta,
        )
        val enrichedMeta = withContext(Dispatchers.Default) {
            enrichForMetaScreen(
                requestKey = requestKey,
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                settings = mdbListSettings,
                settingsFingerprint = metaScreenSettingsFingerprint,
            )
        }
        cachedMetaByRequestKey[requestKey] = cachedEntry.copy(
            metaScreenMeta = enrichedMeta,
            metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
        )
        _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
        activeRequestKey = requestKey
    }

    private suspend fun enrichForMetaScreen(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
        settingsFingerprint: String,
    ): MetaDetails {
        val mdbListEnrichedMeta = withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
            MdbListMetadataService.enrichMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                settings = settings,
            )
        } ?: meta
        val enrichedMeta = applyMoreLikeThisSource(
            meta = mdbListEnrichedMeta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
        )

        cachedMetaByRequestKey[requestKey] = cachedMetaByRequestKey[requestKey]
            ?.copy(
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )
            ?: CachedMetaEntry(
                baseMeta = meta,
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )

        return enrichedMeta
    }

    private suspend fun applyMoreLikeThisSource(
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
    ): MetaDetails {
        TraktSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val traktSettings = TraktSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val shouldUseTrakt = shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = traktSettings.moreLikeThisSource,
        ) && supportsMoreLikeThis(meta, fallbackItemType)

        if (shouldUseTrakt) {
            val items = runCatching {
                TraktRelatedRepository.getRelated(
                    meta = meta,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType,
                )
            }.onFailure { error ->
                log.w { "Failed to load Trakt related titles for ${meta.id}: ${error.message}" }
            }.getOrDefault(emptyList())

            return meta.copy(
                moreLikeThis = items,
                moreLikeThisSource = MoreLikeThisSource.TRAKT.takeIf { items.isNotEmpty() },
            )
        }

        val tmdbSettings = TmdbSettingsRepository.snapshot()
        if (!tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis) {
            return meta.copy(moreLikeThis = emptyList(), moreLikeThisSource = null)
        }

        return meta.copy(
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { meta.moreLikeThis.isNotEmpty() },
        )
    }

    private fun shouldFetchMdbListOnMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean = MdbListMetadataService.shouldFetchForMeta(
        meta = meta,
        fallbackItemId = fallbackItemId,
        settings = settings,
    )

    private fun shouldEnrichForMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean {
        if (shouldFetchMdbListOnMetaScreen(meta, fallbackItemId, settings)) return true
        return shouldApplyMoreLikeThisSource(meta)
    }

    private fun shouldApplyMoreLikeThisSource(meta: MetaDetails): Boolean {
        TraktSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val traktSettings = TraktSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = traktSettings.moreLikeThisSource,
        ) || !tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis || meta.moreLikeThisSource == null && meta.moreLikeThis.isNotEmpty()
    }

    private fun buildMetaScreenSettingsFingerprint(
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): String {
        TraktSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        val providers = settings.enabledProvidersInPriorityOrder().joinToString(",")
        val traktSettings = TraktSettingsRepository.uiState.value
        val traktAuthMode = TraktAuthRepository.uiState.value.mode
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return buildString {
            append("${settings.enabled}:${settings.apiKey.trim()}:$providers")
            append("|more_like=${traktSettings.moreLikeThisSource}:$traktAuthMode")
            append("|tmdb=${tmdbSettings.enabled}:${tmdbSettings.useMoreLikeThis}:${tmdbSettings.hasApiKey}:${tmdbSettings.language}")
        }
    }

    private fun supportsMoreLikeThis(meta: MetaDetails, fallbackItemType: String): Boolean =
        normalizeMoreLikeThisType(meta.type) != null || normalizeMoreLikeThisType(fallbackItemType) != null

    private fun normalizeMoreLikeThisType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> null
        }

    private fun MetaDetails.withUnreleasedFilter(): MetaDetails {
        if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return this
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMoreLikeThis = moreLikeThis.filterReleasedItems(todayIsoDate)
        return copy(
            moreLikeThis = releasedMoreLikeThis,
            moreLikeThisSource = moreLikeThisSource.takeIf { releasedMoreLikeThis.isNotEmpty() },
            collectionItems = collectionItems.filterReleasedItems(todayIsoDate),
        )
    }

   
    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val videosWithStreams = meta.videos.filter { it.streams.isNotEmpty() }
        if (videosWithStreams.isEmpty()) return emptyList()

        val directMatch = videosWithStreams.firstOrNull { it.id == videoId }
        if (directMatch != null) return directMatch.streams

        val parts = videoId.split(":")
        if (parts.size >= 3) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                val episodeMatch = videosWithStreams.firstOrNull { it.season == season && it.episode == episode }
                if (episodeMatch != null) return episodeMatch.streams
            }
        }

        val prefixMatch = videosWithStreams.firstOrNull { it.id.startsWith("$videoId:") }
        if (prefixMatch != null) return prefixMatch.streams

        if (videoId == meta.id && videosWithStreams.size == 1) {
            return videosWithStreams.first().streams
        }

        if (videoId == meta.id && videosWithStreams.isNotEmpty()) {
            return videosWithStreams.flatMap { it.streams }
        }

        return emptyList()
    }

    // --- Xtream IPTV short-circuit (bypasses addons; native detail for VOD/series) ---

    private suspend fun loadXtreamMeta(requestKey: String, id: String) {
        XtreamRepository.ensureLoaded()
        val parsed = XtreamItemRegistry.parseId(id)
        val account = parsed?.let { p -> XtreamRepository.uiState.value.accounts.firstOrNull { it.id == p.accountId } }
        if (parsed == null || account == null) {
            log.w { "Xtream meta short-circuit: no account for id=$id" }
            _uiState.value = MetaDetailsUiState(errorMessage = getString(Res.string.details_no_addon_meta))
            activeRequestKey = null
            return
        }

        val meta = withContext(Dispatchers.Default) {
            when (parsed.kind) {
                XtreamKind.SERIES -> buildXtreamSeriesMeta(id, parsed.accountId, account, parsed.id.toIntOrNull())
                else -> buildXtreamVodMeta(id, parsed.accountId, account, parsed.id.toIntOrNull())
            }
        }
        if (meta == null) {
            _uiState.value = MetaDetailsUiState(errorMessage = getString(Res.string.details_no_addon_meta))
            activeRequestKey = null
            return
        }

        val fingerprint = buildMetaScreenSettingsFingerprint(MdbListSettingsRepository.snapshot())
        cachedMetaByRequestKey[requestKey] = CachedMetaEntry(
            baseMeta = meta,
            metaScreenMeta = meta,
            metaScreenSettingsFingerprint = fingerprint,
        )
        _uiState.value = MetaDetailsUiState(meta = meta.withUnreleasedFilter())
        activeRequestKey = requestKey
    }

    private suspend fun buildXtreamVodMeta(
        id: String,
        accountId: String,
        account: com.nuvio.app.features.iptv.XtreamAccount,
        streamId: Int?,
    ): MetaDetails? {
        if (streamId == null) return null
        val registered = XtreamItemRegistry.get(id)
        val detail = XtreamClient.vodInfo(account, streamId).getOrNull()
        val name = detail?.name ?: registered?.name ?: "Movie"
        val poster = registered?.poster
        // ponytail: prefer the real container_extension from vod_info over a stale registered URL.
        // The browse/list flow registers a ".mp4" URL (the list endpoint omits container_extension);
        // a wrong ".mp4" makes the Download save a mis-named/broken file the local player can't load (B11).
        val streamUrl = detail?.containerExtension?.takeIf { it.isNotBlank() }
            ?.let { XtreamClient.movieStreamUrl(account, streamId, it) }
            ?: registered?.streamUrl
            ?: XtreamClient.movieStreamUrl(account, streamId, "mp4")
        XtreamItemRegistry.register(XtreamResolvedItem(id, accountId, XtreamKind.VOD, name, streamUrl, poster))

        var meta = MetaDetails(
            id = id,
            type = "movie",
            name = name,
            poster = poster,
            description = detail?.plot,
            genres = detail?.genres ?: emptyList(),
            imdbRating = detail?.rating,
            releaseInfo = detail?.releaseDate?.take(4)?.ifBlank { null },
        )
        detail?.tmdbId?.let { meta = enrichXtreamMeta(meta, it) }
        return meta
    }

    /**
     * Rebuilds and re-registers the direct VOD stream item for a persisted `xtream:...:vod:...`
     * id whose in-memory registry entry was lost (e.g. Continue Watching / Library after a fresh
     * launch). Fetches vodInfo so the real container_extension is used instead of the "mp4"
     * default. Returns true once a playable item is registered. Called from the direct-play path
     * (StreamsRepository) so it doesn't have to go through the detail screen first.
     */
    suspend fun ensureXtreamStreamRegistered(id: String): Boolean {
        if (XtreamItemRegistry.get(id) != null) return true
        XtreamRepository.ensureLoaded()
        val parsed = XtreamItemRegistry.parseId(id) ?: return false
        if (parsed.kind != XtreamKind.VOD) return false
        val streamId = parsed.id.toIntOrNull() ?: return false
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return false
        val detail = XtreamClient.vodInfo(account, streamId).getOrNull()
        // ponytail: vodInfo gives the correct container_extension; fall back to mp4 only if absent.
        val streamUrl = XtreamClient.movieStreamUrl(account, streamId, detail?.containerExtension ?: "mp4")
        XtreamItemRegistry.register(
            XtreamResolvedItem(id, parsed.accountId, XtreamKind.VOD, detail?.name ?: "Movie", streamUrl)
        )
        return true
    }

    private suspend fun buildXtreamSeriesMeta(
        id: String,
        accountId: String,
        account: com.nuvio.app.features.iptv.XtreamAccount,
        seriesId: Int?,
    ): MetaDetails? {
        if (seriesId == null) return null
        val registered = XtreamItemRegistry.get(id)
        val detail = XtreamClient.seriesInfo(account, seriesId).getOrNull()
        val videos = detail?.episodes.orEmpty().map { ep ->
            val episodeContentId = XtreamItemRegistry.episodeId(accountId, ep.episodeId)
            val episodeUrl = XtreamClient.episodeStreamUrl(account, ep.episodeId, ep.containerExtension ?: "mp4")
            XtreamItemRegistry.register(
                XtreamResolvedItem(episodeContentId, accountId, XtreamKind.EPISODE, ep.title, episodeUrl)
            )
            MetaVideo(
                id = episodeContentId,
                title = ep.title,
                season = ep.season,
                episode = ep.episodeNum,
                overview = ep.plot,
                thumbnail = ep.still,
                streams = listOf(
                    StreamItem(name = "Direct", title = ep.title, url = episodeUrl, addonName = account.name, addonId = "xtream")
                ),
            )
        }
        val name = detail?.name ?: registered?.name ?: "Series"
        var meta = MetaDetails(
            id = id,
            type = "series",
            name = name,
            poster = registered?.poster ?: detail?.poster,
            description = detail?.plot,
            genres = detail?.genres ?: emptyList(),
            imdbRating = detail?.rating,
            videos = videos,
        )
        detail?.tmdbId?.let { meta = enrichXtreamMeta(meta, it) }
        return meta
    }

    private suspend fun enrichXtreamMeta(meta: MetaDetails, tmdbId: Int): MetaDetails {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return meta
        return runCatching { TmdbMetadataService.enrichMeta(meta, "tmdb:$tmdbId", settings) }
            .onFailure { log.w { "Xtream TMDB enrichment failed for tmdb:$tmdbId: ${it.message}" } }
            .getOrDefault(meta)
    }
}
