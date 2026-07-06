package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Maps a namespaced `xtream:{accountId}:{kind}:{id}` content id back to a directly
 * playable stream + display metadata, so Xtream VOD/series/live ride Nuvio's native
 * meta -> streams -> player pipeline with zero addon/debrid involvement. KMP twin of
 * NuvioTV's XtreamItemRegistry.
 *
 * ID SCHEME GOTCHA: accountId is "$baseUrl|$user" and baseUrl carries "://" and an
 * optional ":port" — so the id is riddled with colons. Never naive-split on ':'. Parse
 * by taking the LAST two colon-delimited fields as kind+id and the remainder as accountId.
 */
object XtreamItemRegistry {

    private val _items = MutableStateFlow<Map<String, XtreamResolvedItem>>(emptyMap())

    fun isXtreamId(id: String?): Boolean = id != null && id.startsWith("$PREFIX:")

    fun buildId(accountId: String, kind: String, id: String): String = "$PREFIX:$accountId:$kind:$id"

    /** Prefix shared by every content id of one account — used for playlist-edit id migration. */
    fun accountPrefix(accountId: String): String = "$PREFIX:$accountId:"

    fun vodId(accountId: String, streamId: Int): String = buildId(accountId, XtreamKind.VOD.slug, streamId.toString())
    fun seriesId(accountId: String, seriesId: Int): String = buildId(accountId, XtreamKind.SERIES.slug, seriesId.toString())
    fun liveId(accountId: String, streamId: Int): String = buildId(accountId, XtreamKind.LIVE.slug, streamId.toString())
    fun episodeId(accountId: String, episodeId: String): String = buildId(accountId, XtreamKind.EPISODE.slug, episodeId)

    /**
     * Splits an xtream content id into (accountId, kind, id). The last two ':'-delimited
     * fields are kind+id; everything before is the accountId (which itself contains colons).
     */
    fun parseId(contentId: String): ParsedXtreamId? {
        if (!isXtreamId(contentId)) return null
        val rest = contentId.substring(PREFIX.length + 1)
        val lastColon = rest.lastIndexOf(':')
        if (lastColon <= 0) return null
        val id = rest.substring(lastColon + 1)
        val beforeId = rest.substring(0, lastColon)
        val kindColon = beforeId.lastIndexOf(':')
        if (kindColon <= 0) return null
        val kind = beforeId.substring(kindColon + 1)
        val accountId = beforeId.substring(0, kindColon)
        if (accountId.isBlank() || kind.isBlank() || id.isBlank()) return null
        return ParsedXtreamId(accountId = accountId, kind = XtreamKind.fromSlug(kind) ?: return null, id = id)
    }

    fun register(item: XtreamResolvedItem) {
        _items.update { it + (item.contentId to item) }
    }

    fun registerMovie(accountId: String, movie: XtreamMovie) = register(
        XtreamResolvedItem(vodId(accountId, movie.streamId), accountId, XtreamKind.VOD, movie.name, movie.streamUrl, movie.poster)
    )

    fun registerChannel(accountId: String, channel: XtreamChannel) = register(
        XtreamResolvedItem(liveId(accountId, channel.streamId), accountId, XtreamKind.LIVE, channel.name, channel.streamUrl, channel.logo, streamType = "live")
    )

    fun registerSeries(accountId: String, series: XtreamSeriesItem) = register(
        XtreamResolvedItem(seriesId(accountId, series.seriesId), accountId, XtreamKind.SERIES, series.name, null, series.poster)
    )

    fun get(contentId: String): XtreamResolvedItem? = _items.value[contentId]

    fun isLiveId(contentId: String): Boolean = parseId(contentId)?.kind == XtreamKind.LIVE

    /**
     * Rebuilds a live channel's stream URL straight from its id (accountId + streamId), so a
     * favorited channel plays from the Library after a fresh launch even when the in-memory
     * registry is empty. Returns null if the account is gone or the id isn't a live id.
     *
     * Xtream URLs are rebuildable from creds synchronously. An M3U channel's URL lives only in the
     * content DB (it's an arbitrary line), so this returns null for M3U — use [liveStreamUrlForAsync].
     */
    fun liveStreamUrlFor(contentId: String): String? {
        val parsed = parseId(contentId) ?: return null
        if (parsed.kind != XtreamKind.LIVE) return null
        val streamId = parsed.id.toIntOrNull() ?: return null
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return null
        // M3U (DB-backed) and Stalker (single-use create_link) resolve on the async path.
        if (account.sourceType == SOURCE_TYPE_M3U_URL || account.sourceType == SOURCE_TYPE_STALKER) return null
        return XtreamClient.liveStreamUrl(account, streamId)
    }

    /**
     * Resolves a live channel's URL for either source. For Xtream it's the synchronous rebuild; for
     * M3U it reads the stored line from the content DB (ingesting first if this playlist was never
     * browsed on this device). Used by the cold-launch play path when the registry is empty.
     */
    suspend fun liveStreamUrlForAsync(contentId: String): String? {
        liveStreamUrlFor(contentId)?.let { return it }
        val parsed = parseId(contentId) ?: return null
        if (parsed.kind != XtreamKind.LIVE) return null
        val streamId = parsed.id.toIntOrNull() ?: return null
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return null
        return when (account.sourceType) {
            SOURCE_TYPE_M3U_URL -> {
                M3UClient.ensureIngested(account)
                M3UClient.liveUrlFor(account, streamId)
            }
            SOURCE_TYPE_STALKER -> com.nuvio.app.features.iptv.stalker.StalkerClient.resolveLiveUrl(account, streamId)
            else -> null
        }
    }

    fun accountNameFor(contentId: String): String? {
        val accountId = parseId(contentId)?.accountId ?: return null
        return XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.name
    }

    /**
     * True when [contentId] is an xtream id whose account is no longer configured on this
     * device (playlist edited/removed; entry synced from another device) — it can't resolve
     * meta or streams anymore. Non-xtream ids are never orphaned.
     */
    fun isOrphaned(contentId: String): Boolean {
        val parsed = parseId(contentId) ?: return false
        runCatching { XtreamRepository.ensureLoaded() }
        return XtreamRepository.uiState.value.accounts.none { it.id == parsed.accountId }
    }

    /** The playlist's DNS provider for a content id (drives the P3 live-mpv DoH path). "system" if unknown. */
    fun dnsProviderFor(contentId: String): String {
        val accountId = parseId(contentId)?.accountId ?: return "system"
        return XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.dnsProvider ?: "system"
    }

    /** The single direct StreamItem for a playable id, or null (series containers aren't directly playable). */
    fun streamItemFor(contentId: String): StreamItem? {
        val item = get(contentId) ?: return null
        val accountName = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == item.accountId }?.name
        return item.toStreamItem(accountName ?: "Xtream")
    }

    /** Clears everything — call on profile switch so accounts don't leak across profiles. */
    fun resetForProfile() {
        _items.value = emptyMap()
    }

    private const val PREFIX = "xtream"
}

data class ParsedXtreamId(val accountId: String, val kind: XtreamKind, val id: String)

enum class XtreamKind(val slug: String) {
    LIVE("live"), VOD("vod"), SERIES("series"), EPISODE("episode");

    companion object {
        fun fromSlug(slug: String): XtreamKind? = entries.firstOrNull { it.slug == slug }
    }
}

data class XtreamResolvedItem(
    val contentId: String,
    val accountId: String,
    val kind: XtreamKind,
    val name: String,
    /** Direct playback URL. Null for SERIES containers (you play their episodes, not the series). */
    val streamUrl: String?,
    val poster: String? = null,
    val logo: String? = null,
    /** "live" for channels — drives the libmpv engine override in the player. */
    val streamType: String? = null,
)

fun XtreamResolvedItem.toStreamItem(accountName: String): StreamItem? {
    // Blank == a Stalker placeholder (create_link not yet resolved) -> treat as "no direct item" so the
    // streams flow rebuilds it fresh via ensureXtreamStreamRegistered. A real URL is never blank.
    val url = streamUrl?.takeIf { it.isNotBlank() } ?: return null
    return StreamItem(
        name = "Direct",
        title = name,
        url = url,
        addonName = accountName,
        addonId = "xtream",
        streamType = streamType,
    )
}

fun XtreamResolvedItem.toMetaPreview(): MetaPreview = MetaPreview(
    id = contentId,
    type = if (kind == XtreamKind.SERIES) "series" else "movie",
    name = name,
    poster = poster ?: logo,
    posterShape = if (kind == XtreamKind.LIVE) PosterShape.Landscape else PosterShape.Poster,
)
