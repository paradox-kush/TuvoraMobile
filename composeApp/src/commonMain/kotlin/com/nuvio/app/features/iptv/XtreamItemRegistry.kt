package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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

    /**
     * Resolved items by content id. A plain mutable map behind a lock, NOT a StateFlow of an
     * immutable map: `map + pair` allocates a whole new map per insert, and [register] is called
     * once per item while a category fills, so a 10k-item category copied ~50M entries and threw
     * away 10k maps — quadratic allocation churn, which is what gets a low-RAM TV
     * lowmemorykilled. Nothing ever collected this as a flow (only [get] reads it), so the
     * StateFlow bought nothing. Same idiom as [XtreamHubRepository]'s category cache, and the TV
     * twin's ConcurrentHashMap.
     */
    private val itemsLock = SynchronizedObject()
    private val items = mutableMapOf<String, XtreamResolvedItem>()

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
        synchronized(itemsLock) { items[item.contentId] = item }
    }

    /** Registers a whole category in one lock acquisition — the browse path's hot loop. */
    fun registerAll(batch: List<XtreamResolvedItem>) {
        if (batch.isEmpty()) return
        synchronized(itemsLock) { for (item in batch) items[item.contentId] = item }
    }

    // Pure builders, so a caller filling a whole category can map first and [registerAll] once
    // instead of taking the lock per item.
    fun resolvedMovie(accountId: String, movie: XtreamMovie) =
        XtreamResolvedItem(vodId(accountId, movie.streamId), accountId, XtreamKind.VOD, movie.name, movie.streamUrl, movie.poster)

    fun resolvedChannel(accountId: String, channel: XtreamChannel) =
        XtreamResolvedItem(liveId(accountId, channel.streamId), accountId, XtreamKind.LIVE, channel.name, channel.streamUrl, channel.logo, streamType = "live")

    fun resolvedSeries(accountId: String, series: XtreamSeriesItem) =
        XtreamResolvedItem(seriesId(accountId, series.seriesId), accountId, XtreamKind.SERIES, series.name, null, series.poster)

    fun registerMovie(accountId: String, movie: XtreamMovie) = register(resolvedMovie(accountId, movie))

    fun registerChannel(accountId: String, channel: XtreamChannel) = register(resolvedChannel(accountId, channel))

    fun registerSeries(accountId: String, series: XtreamSeriesItem) = register(resolvedSeries(accountId, series))

    fun get(contentId: String): XtreamResolvedItem? = synchronized(itemsLock) { items[contentId] }

    /**
     * [get] with a cold-start fallback (item 5): a map miss rebuilds the item from the LOCAL
     * stores — the Xtream browse catalog, the M3U rows, or the Stalker write-through rows — and
     * registers it. This is what lets the in-memory map be a cache instead of the source of
     * truth: a Library/Continue-Watching open after a process death resolves without any
     * network fetch. Returns null only when the account is gone or the item was never stored.
     */
    suspend fun getOrLoad(contentId: String): XtreamResolvedItem? {
        get(contentId)?.let { return it }
        val parsed = parseId(contentId) ?: return null
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return null
        val sid = parsed.id.toIntOrNull()
        val built: XtreamResolvedItem? = when (account.sourceType) {
            SOURCE_TYPE_XTREAM -> {
                if (sid == null) null else when (parsed.kind) {
                    XtreamKind.LIVE -> com.nuvio.app.features.iptv.match.XtreamMatchIndex
                        .itemRow(account.id, com.nuvio.app.features.iptv.match.MatchKind.LIVE, sid)?.let {
                            XtreamResolvedItem(contentId, account.id, XtreamKind.LIVE, it.name,
                                XtreamClient.liveStreamUrl(account, sid), logo = it.poster, streamType = "live")
                        }
                    XtreamKind.VOD -> com.nuvio.app.features.iptv.match.XtreamMatchIndex
                        .itemRow(account.id, com.nuvio.app.features.iptv.match.MatchKind.MOVIE, sid)?.let {
                            XtreamResolvedItem(contentId, account.id, XtreamKind.VOD, it.name,
                                XtreamClient.movieStreamUrl(account, sid, it.ext ?: "mp4"), poster = it.poster)
                        }
                    XtreamKind.SERIES -> com.nuvio.app.features.iptv.match.XtreamMatchIndex
                        .itemRow(account.id, com.nuvio.app.features.iptv.match.MatchKind.SERIES, sid)?.let {
                            XtreamResolvedItem(contentId, account.id, XtreamKind.SERIES, it.name, null, poster = it.poster)
                        }
                    XtreamKind.EPISODE -> null   // episodes resolve via the detail/play seams
                }
            }
            SOURCE_TYPE_STALKER, SOURCE_TYPE_M3U_URL, SOURCE_TYPE_M3U_FILE -> {
                if (sid == null) null else when (parsed.kind) {
                    XtreamKind.LIVE -> com.nuvio.app.features.iptv.content.IptvContentDb.channelRow(account.id, sid)?.let {
                        XtreamResolvedItem(contentId, account.id, XtreamKind.LIVE, it.name, it.url, logo = it.logo, streamType = "live")
                    }
                    XtreamKind.VOD -> com.nuvio.app.features.iptv.content.IptvContentDb.vodRow(account.id, sid)?.let {
                        XtreamResolvedItem(contentId, account.id, XtreamKind.VOD, it.name, it.url, poster = it.logo)
                    }
                    XtreamKind.SERIES -> com.nuvio.app.features.iptv.content.IptvContentDb.seriesRow(account.id, sid)?.let {
                        XtreamResolvedItem(contentId, account.id, XtreamKind.SERIES, it.name, null, poster = it.logo)
                    }
                    XtreamKind.EPISODE -> null
                }
            }
            else -> null
        }
        built?.let { register(it) }
        return built
    }

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
        // Every live id now resolves on the async path: an Xtream id carries the sid the channel had
        // when it was saved, and the panel may have renumbered since (commit 6c622d49). The current
        // sid lives in the catalog, which is a database read — see [liveStreamUrlForAsync]. Callers
        // already fall through to it when this returns null.
        return null
    }

    /**
     * Resolves a live channel's URL for either source. For Xtream it's the synchronous rebuild; for
     * M3U it reads the stored line from the content DB (ingesting first if this playlist was never
     * browsed on this device). Used by the cold-launch play path when the registry is empty.
     *
     * [forceMint] rides the 401-refresh ladder into Stalker's resolver: a static-cmd verdict would
     * rebuild the very URL that just died, so the refresh demands a fresh create_link.
     */
    suspend fun liveStreamUrlForAsync(contentId: String, forceMint: Boolean = false): String? {
        val parsed = parseId(contentId) ?: return null
        if (parsed.kind != XtreamKind.LIVE) return null
        val streamId = parsed.id.toIntOrNull() ?: return null
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return null
        return when (account.sourceType) {
            SOURCE_TYPE_M3U_URL -> {
                M3UClient.ensureIngested(account)
                M3UClient.liveUrlFor(account, streamId)
            }
            SOURCE_TYPE_STALKER -> com.nuvio.app.features.iptv.stalker.StalkerClient.resolveLiveUrl(account, streamId, forceMint)
            else -> {
                // The saved sid is a hint, not the truth: bind the channel's identity to whatever sid
                // the CURRENT catalog gives it. Falls back to the saved sid only when this device has
                // never indexed the playlist (cold launch before the first build) — the old formula.
                val currentSid = com.nuvio.app.features.iptv.match.XtreamMatchIndex.resolveLiveSid(account.id, streamId) ?: streamId
                XtreamClient.liveStreamUrl(account, currentSid)
            }
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
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == item.accountId }
        return item.toStreamItem(
            accountName = account?.name ?: "Xtream",
            userAgent = account?.let { StreamUserAgentPolicy.resolve(it) },
        )
    }

    /** Clears everything — call on profile switch so accounts don't leak across profiles. */
    fun resetForProfile() {
        synchronized(itemsLock) { items.clear() }
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

fun XtreamResolvedItem.toStreamItem(accountName: String, userAgent: String? = null): StreamItem? {
    // Blank == a Stalker placeholder (create_link not yet resolved) -> treat as "no direct item" so the
    // streams flow rebuilds it fresh via ensureXtreamStreamRegistered. A real URL is never blank.
    val url = streamUrl?.takeIf { it.isNotBlank() } ?: return null
    // A per-playlist stream UA rides the Stremio proxyHeaders the player already honors, so an
    // honest IPTV-client UA can get past a provider WAF that 456s the spoofed-browser default.
    val behaviorHints = userAgent
        ?.let { StreamBehaviorHints(proxyHeaders = StreamProxyHeaders(request = mapOf("User-Agent" to it))) }
        ?: StreamBehaviorHints()
    return StreamItem(
        name = "Direct",
        title = name,
        url = url,
        addonName = accountName,
        addonId = "xtream",
        streamType = streamType,
        behaviorHints = behaviorHints,
    )
}

fun XtreamResolvedItem.toMetaPreview(): MetaPreview = MetaPreview(
    id = contentId,
    type = if (kind == XtreamKind.SERIES) "series" else "movie",
    name = name,
    poster = poster ?: logo,
    posterShape = if (kind == XtreamKind.LIVE) PosterShape.Landscape else PosterShape.Poster,
)
