package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserDialect
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserUrls
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle

/**
 * The one shape of a media-server stream in a source list - used by the direct lane (`ms:` ids) and the matched
 * lane (`ms-match:` groups): a DEFERRED url (minted at pick time, never holding a token or a playable URL), the
 * sidecar text subtitles, and the per-product request header (Emby authenticates a player request by header;
 * Jellyfin's direct stream needs none - design 5.5).
 */
internal object MediaServerStreamItems {
    fun tokenHeaders(services: MediaServerServices, entry: MediaServerEntry): Map<String, String>? {
        val header = MediaBrowserDialect.of(entry.type).extraTokenHeader ?: return null
        val token = services.credentials.token(entry.serverKey) ?: return null
        return mapOf(header to token)
    }

    fun build(
        entry: MediaServerEntry,
        itemId: String,
        title: String?,
        source: MediaServerItemRegistry.Source?,
        groupId: String,
        headers: Map<String, String>?,
    ): StreamItem = StreamItem(
        name = source?.label ?: "Direct play",
        title = title,
        description = source?.description,
        url = MediaServerIds.deferredUrl(entry.serverKey, itemId, source?.id),
        addonName = entry.name,
        addonId = groupId,
        behaviorHints = StreamBehaviorHints(
            proxyHeaders = headers?.let { StreamProxyHeaders(request = it) },
        ),
        // Sidecar text subtitles ride with the stream; the engines list the container's own tracks by themselves.
        externalSubtitles = source?.let { src ->
            src.subtitles.map { sub ->
                StreamSubtitle(
                    url = MediaBrowserUrls.subtitle(entry.address.orEmpty(), itemId, src.id, sub.index),
                    language = sub.language,
                    name = sub.label,
                    headers = headers,
                )
            }
        }.orEmpty(),
    )
}
