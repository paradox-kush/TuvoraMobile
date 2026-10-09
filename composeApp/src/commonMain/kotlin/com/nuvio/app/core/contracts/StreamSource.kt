package com.nuvio.app.core.contracts

import com.nuvio.app.features.streams.StreamItem

/** One match source (an enabled IPTV account, a signed-in media server), identified by an opaque id its owner maps back. */
data class StreamSourceGroup(val sourceId: String, val addonName: String)

/**
 * Firewall port for own-source stream resolution (IPTV, media servers), consumed by the shared streams
 * repositories. The fork owns all Xtream/Stalker access; the shared repo orchestrates with neutral
 * results only — StreamItem plus opaque source ids. The account is encoded inside
 * [StreamSourceGroup.sourceId] and resolved back fork-side, so no fork type crosses the firewall.
 * Sources are PLURAL: each registers under its own name in [StreamSourceRegistry] and shared code reads
 * the combined view from [StreamSourceAccess.current] (see [CompositeStreamSourceProvider]); with
 * nothing registered it is not-handled / empty everywhere.
 */
interface StreamSourceProvider {
    /** True when [videoId] is a namespaced IPTV id (VOD/live) that resolves to one direct stream. */
    fun isHandledId(videoId: String?): Boolean

    /** True when [videoId]'s account is a Stalker portal (single-use links → never serve the cache). */
    fun isStalkerSource(videoId: String): Boolean

    /** The registered direct stream item for [videoId], or null on a registry miss. */
    fun directStreamItem(videoId: String): StreamItem?

    /** Every version of [videoId] when its source has several (a media server's MediaSources); one item otherwise. */
    fun directStreamItems(videoId: String): List<StreamItem> = listOfNotNull(directStreamItem(videoId))

    /** A light registry row needs one full fetch before versions and sidecars can be presented. */
    fun needsStreamRegistration(videoId: String): Boolean = directStreamItems(videoId).isEmpty()

    /** One match source per enabled Xtream/Stalker account, for TMDB [type] ("movie"/"series"). */
    fun matchSourceGroups(type: String): List<StreamSourceGroup>

    /** Resolve the streams for a single [matchSourceGroups] entry. */
    suspend fun resolveMatchStreams(
        sourceId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem>

    /** True when [providerAddonId] identifies a match source (a [matchSourceGroups] entry's id). */
    fun isMatchSourceId(providerAddonId: String): Boolean

    /** True when [url] is a deferred (not-yet-minted) IPTV play URL. */
    fun isDeferredUrl(url: String?): Boolean

    /** Mint the real play URL for a deferred [url], or null. [forceMint] bypasses static-cmd reuse. */
    suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String?
}

/**
 * Every registered stream-source provider, in registration order; a duplicate name is refused. Content
 * id namespaces and match-group id prefixes are disjoint per source, so ownership is "first provider
 * that claims the id".
 */
object StreamSourceRegistry {
    private val providers = NamedRegistry<StreamSourceProvider>("StreamSourceProvider")

    fun register(name: String, provider: StreamSourceProvider) = providers.register(name, provider)

    val all: List<StreamSourceProvider> get() = providers.all

    internal fun resetForTest() = providers.resetForTest()
}

/**
 * The plural view of [StreamSourceProvider]s behind the single-provider interface the shared code
 * already speaks. With exactly one provider every answer is that provider's own (behaviour-identical to
 * the old single slot); with none, everything is not-handled / empty / null.
 *  - id-keyed calls ([isHandledId], [isStalkerSource], [directStreamItem]) go to the first provider that
 *    handles the id;
 *  - [matchSourceGroups] concatenates in registration order (each provider's own ordering is kept);
 *  - [resolveMatchStreams] goes to the provider that owns the group id ([isMatchSourceId]);
 *  - [isDeferredUrl] is true if any provider claims the url, and [resolveDeferredUrl] mints through the
 *    claiming provider (null when none claims it - callers always check [isDeferredUrl] first).
 */
class CompositeStreamSourceProvider(
    private val providers: () -> List<StreamSourceProvider>,
) : StreamSourceProvider {
    override fun isHandledId(videoId: String?): Boolean = providers().any { it.isHandledId(videoId) }

    override fun isStalkerSource(videoId: String): Boolean =
        providers().firstOrNull { it.isHandledId(videoId) }?.isStalkerSource(videoId) ?: false

    override fun directStreamItem(videoId: String): StreamItem? =
        providers().firstOrNull { it.isHandledId(videoId) }?.directStreamItem(videoId)

    override fun directStreamItems(videoId: String): List<StreamItem> =
        providers().firstOrNull { it.isHandledId(videoId) }?.directStreamItems(videoId).orEmpty()

    override fun needsStreamRegistration(videoId: String): Boolean =
        providers().firstOrNull { it.isHandledId(videoId) }?.needsStreamRegistration(videoId) ?: true

    override fun matchSourceGroups(type: String): List<StreamSourceGroup> =
        providers().flatMap { it.matchSourceGroups(type) }

    override suspend fun resolveMatchStreams(
        sourceId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> =
        providers().firstOrNull { it.isMatchSourceId(sourceId) }
            ?.resolveMatchStreams(sourceId, type, videoId, season, episode)
            ?: emptyList()

    override fun isMatchSourceId(providerAddonId: String): Boolean =
        providers().any { it.isMatchSourceId(providerAddonId) }

    override fun isDeferredUrl(url: String?): Boolean = providers().any { it.isDeferredUrl(url) }

    override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? =
        providers().firstOrNull { it.isDeferredUrl(url) }?.resolveDeferredUrl(url, forceMint)
}

/** Thin read facade (call sites do not churn): the combined view of [StreamSourceRegistry]. */
object StreamSourceAccess {
    private val composite = CompositeStreamSourceProvider { StreamSourceRegistry.all }

    /** The combined provider - not-handled / empty until a source registers. Stable instance. */
    fun current(): StreamSourceProvider = composite

    fun resetForTest() = StreamSourceRegistry.resetForTest()
}
