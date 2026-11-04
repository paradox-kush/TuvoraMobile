package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.features.addons.AddonSourcePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedStreamLink(
    val url: String = "",
    val streamName: String,
    val addonName: String,
    val addonId: String,
    val cachedAtMs: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String> = emptyList(),
    val bingeGroup: String? = null,
    val streamType: String? = null,
    val contentLanguage: String? = null,
    val addonLogo: String? = null,
    val streamDescription: String? = null,
)

internal expect fun epochMs(): Long

object StreamLinkCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun contentKey(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): String {
        val normalizedType = type.lowercase()
        return if (!parentMetaId.isNullOrBlank() && season != null && episode != null) {
            "$normalizedType|${parentMetaId.trim()}|s$season|e$episode|$videoId"
        } else {
            "$normalizedType|$videoId"
        }
    }

    fun save(
        contentKey: String,
        url: String,
        streamName: String,
        addonName: String,
        addonId: String,
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String> = emptyMap(),
        filename: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String> = emptyList(),
        bingeGroup: String? = null,
        streamType: String? = null,
        contentLanguage: String? = null,
        addonLogo: String? = null,
        streamDescription: String? = null,
    ) {
        if (url.isNotBlank() && url.hasLikelyExpiringPlaybackCredentials()) {
            remove(contentKey)
            return
        }
        // An IPTV link is only valid for as long as the provider's catalog stands still, and it
        // does not: a panel can renumber its whole VOD catalog overnight (observed live —
        // "synced movie index: +6249 -6253"), after which the cached stream id is gone and the
        // panel answers the replayed URL with 401. Stalker links are worse still: single-use.
        // Re-resolving costs one catalog lookup the matcher performs anyway, so never cache these.
        if (isIptvAddon(addonId)) {
            remove(contentKey)
            return
        }

        val entry = CachedStreamLink(
            url = url,
            streamName = streamName,
            addonName = addonName,
            addonId = addonId,
            cachedAtMs = epochMs(),
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            filename = filename,
            videoHash = videoHash,
            videoSize = videoSize,
            infoHash = infoHash,
            fileIdx = fileIdx,
            sources = sources,
            bingeGroup = bingeGroup,
            streamType = streamType,
            contentLanguage = contentLanguage,
            addonLogo = addonLogo,
            streamDescription = streamDescription,
        )
        val payload = json.encodeToString(CachedStreamLink.serializer(), entry)
        StreamLinkCacheStorage.saveEntry(hashedKey(contentKey), payload)
    }

    fun remove(contentKey: String) {
        StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
    }

    fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null
        val raw = StreamLinkCacheStorage.loadEntry(hashedKey(contentKey)) ?: return null
        val entry = runCatching {
            json.decodeFromString(CachedStreamLink.serializer(), raw)
        }.getOrNull() ?: run {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        val age = epochMs() - entry.cachedAtMs
        if (entry.cachedAtMs <= 0L || age > maxAgeMs) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        if (entry.url.isNotBlank() && entry.url.hasLikelyExpiringPlaybackCredentials()) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        // Also drop IPTV entries written by an earlier build, so an upgrade doesn't keep replaying
        // a link whose stream id the provider has since renumbered away.
        if (isIptvAddon(entry.addonId)) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        if (entry.url.isBlank() && entry.infoHash.isNullOrBlank()) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        // Store builds never play from add-ons, including a link cached before that rule applied.
        if (!AddonSourcePolicy.cachedLinkUsable(entry.addonId, AppFeaturePolicy.addonStreamSourcesEnabled, ::isIptvAddon)) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        return entry
    }

    /**
     * True for a stream produced by an own source (an IPTV account, a media server) rather than an
     * addon/debrid provider: the direct hybrid lane ("xtream") and the TMDB-matched lane
     * ("xtream-match:<accountId>") for IPTV. Which provider ids are "own" is the registered
     * [OwnSourcePolicy]; this stays as the cache's thin adapter so its call sites do not churn.
     */
    internal fun isIptvAddon(addonId: String?): Boolean = OwnSourcePolicy.isOwnProviderId(addonId)

    /**
     * Patches only the videoHash (and optionally videoSize) on an existing cache
     * entry without touching any other fields or resetting cachedAtMs.
     * Does nothing when no entry exists for [contentKey].
     */
    fun updateVideoHash(contentKey: String, videoHash: String?, videoSize: Long?) {
        val hashed = hashedKey(contentKey)
        val raw = StreamLinkCacheStorage.loadEntry(hashed) ?: return
        val existing = runCatching {
            json.decodeFromString(CachedStreamLink.serializer(), raw)
        }.getOrNull() ?: return
        val updated = existing.copy(
            videoHash = videoHash ?: existing.videoHash,
            videoSize = videoSize ?: existing.videoSize,
        )
        val payload = json.encodeToString(CachedStreamLink.serializer(), updated)
        StreamLinkCacheStorage.saveEntry(hashed, payload)
    }

    private fun hashedKey(contentKey: String): String {
        val hash = contentKey.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "stream_link_$hash"
    }
}
