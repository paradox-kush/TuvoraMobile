package com.nuvio.app.features.mediaserver.internal.source

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.MetaSourceProvider
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds.Kind
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * Native metadata for a server's own titles (design 5.4 "Details"): the server's item (+ its episodes for a
 * series) becomes [MetaDetails], enriched from TMDB when the item carries a TMDB id and the user has TMDB on -
 * exactly the Xtream lane's behaviour. A series page's episodes are `MetaVideo`s WITHOUT embedded streams:
 * they resolve through the direct lane (`handlesId` -> registry), so the store-build embedded-stream filter
 * never sees them.
 */
internal class MediaServerMetaSource(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
) : MetaSourceProvider {
    private val log = Logger.withTag("MediaServerMetaSource")

    override fun handlesId(id: String): Boolean = MediaServerIds.isContentId(id)

    override suspend fun buildNativeMeta(id: String): MetaDetails? {
        val parsed = MediaServerIds.parse(id) ?: return null
        if (parsed.kind != Kind.MOVIE && parsed.kind != Kind.SERIES) return null
        val entry = store.entryByServerKey(parsed.serverKey) ?: return null
        val client = services.clientFor(entry) ?: return null
        return try {
            val item = client.item(parsed.itemId, fields = DETAIL_FIELDS) ?: return null
            val episodes = if (parsed.kind == Kind.SERIES) client.episodes(parsed.itemId, fields = EPISODE_FIELDS) else emptyList()
            val versions = if (parsed.kind == Kind.MOVIE) MediaServerVersions.of(client, item) else item.mediaSources
            MediaServerItemMapper.registered(entry, item, versions)?.let { registered ->
                MediaServerItemRegistry.register(registered.copy(sourcesLoaded = true))
                // A search result is a stub: opening it answers with the canonical item under ANOTHER id (recorded). The id the
                // viewer arrived with must keep resolving (Continue Watching, a reload), so register it too - same item.
                if (!item.id.equals(parsed.itemId, ignoreCase = true)) MediaServerItemRegistry.register(registered.copy(contentId = id, sourcesLoaded = true))
            }
            episodes.forEach { ep -> MediaServerItemMapper.registered(entry, ep)?.let(MediaServerItemRegistry::register) }
            val meta = MediaServerItemMapper.details(entry, item, episodes) ?: return null
            val tmdbId = item.providerIds.entries.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            if (tmdbId != null) enrich(meta, tmdbId) else meta
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            null
        } catch (e: MediaServerException) {
            null
        }
    }

    /**
     * Rebuilds the registry record of a persisted `ms:` id whose in-memory entry was lost (Continue Watching /
     * Library after a fresh launch): one item fetch. True once a playable item is registered. Nothing here is
     * single-use (unlike Stalker links), so [forceFresh]/[forceMint] need no special handling beyond skipping the cache.
     */
    override suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean {
        // A row / episode list registers cards WITHOUT media sources (a light fetch); play needs them (labels, sidecar subtitles), so such a record is refetched once.
        val cached = MediaServerItemRegistry.get(id)
        if (!forceFresh && cached != null && (cached.sourcesLoaded || cached.sources.isNotEmpty() || cached.kind == Kind.SERIES)) return true
        val parsed = MediaServerIds.parse(id) ?: return false
        val entry = store.entryByServerKey(parsed.serverKey) ?: return false
        val client = services.clientFor(entry) ?: return false
        return try {
            val item = client.item(parsed.itemId, fields = "Overview,MediaSources") ?: return false
            val registered = MediaServerItemMapper.registered(entry, item, MediaServerVersions.of(client, item)) ?: return false
            // the registry key is the id the caller asked with (its kind is authoritative, not the server type's guess)
            MediaServerItemRegistry.register(registered.copy(contentId = id, sourcesLoaded = true))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            false
        } catch (e: MediaServerException) {
            false
        }
    }

    private suspend fun enrich(meta: MetaDetails, tmdbId: String): MetaDetails {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled) return meta
        return runCatching { TmdbMetadataService.enrichMeta(meta, "tmdb:$tmdbId", settings) }
            .onFailure { log.w { "TMDB enrichment failed for tmdb:$tmdbId: ${it.message}" } }
            .getOrDefault(meta)
    }

    private companion object {
        const val DETAIL_FIELDS = "Overview,Genres,People,ProviderIds,MediaSources,OfficialRating,CommunityRating,PremiereDate,ProductionYear,Status,EndDate,Taglines"
        const val EPISODE_FIELDS = "Overview,PremiereDate"
    }
}
