package com.nuvio.app.features.details

import com.nuvio.app.features.streams.StreamItem

/**
 * Pure decisions behind the "embedded streams" lookup (series episodes whose streams ride on the meta,
 * i.e. IPTV / media-server series).
 *
 * B165: the lookup used to read whatever the details screen had last published, so Continue Watching
 * (which never opens details) found nothing and the picker came up empty. The caller now names the
 * title ([pickMeta]) and, when that title cannot be loaded for a source that has no other way to list
 * streams, the failure is reported as a failure ([failureBlocksStreams]) instead of as "no streams".
 */
internal object EmbeddedStreamLookup {

    /** Streams carried by the episode [videoId] inside [meta]; empty when none match. */
    fun streamsFor(meta: MetaDetails?, videoId: String): List<StreamItem> {
        meta ?: return emptyList()
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

    /**
     * The meta to read: the details screen's [current] one when it is the requested title, else a
     * previously fetched one for [parentMetaId], else [current] (legacy behaviour for callers that
     * do not know the parent).
     */
    fun pickMeta(current: MetaDetails?, cached: Collection<MetaDetails>, parentMetaId: String?): MetaDetails? {
        if (parentMetaId.isNullOrBlank()) return current
        if (current?.id == parentMetaId) return current
        return cached.firstOrNull { it.id == parentMetaId } ?: current
    }

    /**
     * True when failing to load the title's meta must be shown as a failure: the id belongs to a source
     * whose streams exist only inside that meta, so "no meta" would otherwise read as "no streams".
     * Addon-backed titles still list streams from the addons without the meta, so they keep going.
     */
    fun failureBlocksStreams(metaLoaded: Boolean, streamsLiveOnlyInMeta: Boolean): Boolean =
        !metaLoaded && streamsLiveOnlyInMeta
}
