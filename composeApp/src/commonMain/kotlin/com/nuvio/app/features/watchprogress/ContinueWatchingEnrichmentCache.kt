package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val released: String? = null,
    val hasAired: Boolean = true,
    val lastWatched: Long,
    val sortTimestamp: Long,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
)

@Serializable
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float? = null,
    val progressKey: String? = null,
)

internal fun CachedInProgressItem.resolvedProgressKey(): String =
    progressKey?.takeIf(String::isNotBlank)
        ?: buildWatchProgressKey(
            contentId = contentId,
            seasonNumber = season,
            episodeNumber = episode,
        )

@Serializable
private data class CachedEnrichmentPayload(
    val nextUp: List<CachedNextUpItem> = emptyList(),
    val inProgress: List<CachedInProgressItem> = emptyList(),
)

internal object ContinueWatchingEnrichmentCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val storageKey = "cw_enrichment_cache"
    // Continue-watching is a bounded UI list. The payload store here is per-app preferences
    // (SharedPreferences / NSUserDefaults / desktop file) — NOT inherently size-bounded, so cap both
    // the producer (what we write) and the consumer (what we decode back), the same 500-record bound
    // the TV file cache uses. This keeps a runaway producer or an old/oversized stored value from
    // materializing an unbounded list on a startup path.
    private const val MAX_RECORDS = 500
    private fun <T> List<T>.capped(): List<T> = if (size > MAX_RECORDS) take(MAX_RECORDS) else this
    private val cacheLock = SynchronizedObject()
    private val lastPayloadHashByScope = mutableMapOf<CacheScope, Int>()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun getNextUpSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedNextUpItem> =
        loadPayload(profileId = profileId, source = source)?.nextUp ?: emptyList()

    fun getInProgressSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedInProgressItem> =
        loadPayload(profileId = profileId, source = source)?.inProgress ?: emptyList()

    fun getSnapshots(
        profileId: Int,
        source: WatchProgressSource,
    ): Pair<List<CachedNextUpItem>, List<CachedInProgressItem>> {
        val payload = loadPayload(profileId = profileId, source = source)
        val nextUp = payload?.nextUp ?: emptyList()
        val inProgress = payload?.inProgress ?: emptyList()
        return nextUp to inProgress
    }

    fun saveSnapshots(
        profileId: Int,
        source: WatchProgressSource,
        generation: Int,
        nextUp: List<CachedNextUpItem>,
        inProgress: List<CachedInProgressItem>,
        force: Boolean = false,
    ): Boolean = synchronized(cacheLock) {
        if (generation != _generation.value) return@synchronized false

        removeLegacyPayload(profileId)
        val payload = CachedEnrichmentPayload(nextUp = nextUp.capped(), inProgress = inProgress.capped())
        val payloadHash = payload.hashCode()
        val scope = CacheScope(profileId = profileId, source = source)
        if (!force && lastPayloadHashByScope[scope] == payloadHash) {
            return@synchronized true
        }

        val encoded = runCatching {
            json.encodeToString(payload)
        }.getOrNull() ?: return@synchronized false
        ContinueWatchingEnrichmentStorage.savePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            encoded,
        )
        lastPayloadHashByScope[scope] = payloadHash
        true
    }

    fun invalidate(
        profileId: Int,
        source: WatchProgressSource,
    ) = synchronized(cacheLock) {
        ContinueWatchingEnrichmentStorage.removePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        )
        removeLegacyPayload(profileId)
        lastPayloadHashByScope.remove(CacheScope(profileId = profileId, source = source))
        advanceGeneration()
    }

    fun clearAll(profileId: Int) = synchronized(cacheLock) {
        WatchProgressSource.entries.forEach { source ->
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            lastPayloadHashByScope.remove(CacheScope(profileId = profileId, source = source))
        }
        removeLegacyPayload(profileId)
        advanceGeneration()
    }

    fun clearLocalState() = synchronized(cacheLock) {
        lastPayloadHashByScope.clear()
        advanceGeneration()
    }

    fun onProfileChanged() = synchronized(cacheLock) {
        advanceGeneration()
    }

    private fun loadPayload(
        profileId: Int,
        source: WatchProgressSource,
    ): CachedEnrichmentPayload? = synchronized(cacheLock) {
        removeLegacyPayload(profileId)
        val scope = CacheScope(profileId = profileId, source = source)
        val raw = ContinueWatchingEnrichmentStorage.loadPayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        ) ?: run {
            lastPayloadHashByScope.remove(scope)
            return@synchronized null
        }
        runCatching {
            json.decodeFromString<CachedEnrichmentPayload>(raw)
        }.getOrNull()?.let { decoded ->
            // Defensive consumer cap: an old/oversized stored value must not yield an unbounded list.
            CachedEnrichmentPayload(nextUp = decoded.nextUp.capped(), inProgress = decoded.inProgress.capped())
        }?.also { payload ->
            lastPayloadHashByScope[scope] = payload.hashCode()
        } ?: run {
            lastPayloadHashByScope.remove(scope)
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            null
        }
    }

    private fun removeLegacyPayload(profileId: Int) {
        ContinueWatchingEnrichmentStorage.removePayload(legacyStorageKey(profileId))
    }

    private fun advanceGeneration() {
        _generation.value += 1
    }

    private data class CacheScope(
        val profileId: Int,
        val source: WatchProgressSource,
    )

    internal fun continueWatchingEnrichmentStorageKey(
        profileId: Int,
        source: WatchProgressSource,
    ): String = ProfileScopedKey.of(
        baseKey = "${storageKey}_${source.name.lowercase()}",
        profileId = profileId,
    )

    internal fun legacyStorageKey(profileId: Int): String =
        ProfileScopedKey.of(storageKey, profileId)
}
