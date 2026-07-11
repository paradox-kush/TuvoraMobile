package com.nuvio.app.features.epg

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpStreamLines
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamSearchIndex
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.iptv.epg.XmltvStreamingParser
import com.nuvio.app.features.iptv.epg.normalizeChannelId
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client of the backend's EPG mirror (`epg` storage bucket, filled by the epg-sync edge
 * function). KMP twin of NuvioTV's core/epg/EpgMirrorRepository: keeps a local canonical
 * EPG the app falls back to when the panel's own EPG is missing, and the channel mappings
 * that power the Sports Centre's EPG-first event matching.
 *
 * Sync flow (12h TTL, single-flight, crash-safe via meta-last): manifest → channels index →
 * map every enabled playlist's live channels ([EpgChannelIndex], transient) → download the
 * programme feeds that cover the user's channels (bounded window, mapped channels only).
 */
internal object EpgMirrorRepository {

    private val log = Logger.withTag("EpgMirror")
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    // --- public queries ---------------------------------------------------------

    /** Mirror now/next for a provider channel, or empty when unmapped/uncovered. */
    suspend fun nowNext(providerKey: String, streamId: Int, nowMs: Long): List<EpgProgrammeRow> {
        val epgId = EpgMirrorDb.mappingFor(providerKey)[streamId] ?: return emptyList()
        return EpgMirrorDb.nowNext(epgId, nowMs)
    }

    /** Mirror now/next as [XtreamProgram]s (what the hub/guide UIs consume). */
    suspend fun nowNextProgrammes(providerKey: String, streamId: Int, nowMs: Long): List<XtreamProgram> =
        nowNext(providerKey, streamId, nowMs).map {
            XtreamProgram(
                title = it.title,
                description = it.desc.orEmpty(),
                startMs = it.startMs,
                endMs = it.endMs,
                nowPlaying = nowMs in it.startMs until it.endMs,
            )
        }

    /** streamId → epgId for one playlist (empty until a sync has mapped it). */
    suspend fun mappingFor(providerKey: String): Map<Int, String> = EpgMirrorDb.mappingFor(providerKey)

    /** Candidate programmes for an event window; callers score them (see RadarChannelMatcher). */
    suspend fun programmesInWindow(tokens: List<String>, fromMs: Long, toMs: Long): List<EpgProgrammeRow> =
        EpgMirrorDb.searchProgrammes(tokens, fromMs, toMs)

    /** Drop a removed playlist's mappings (called from the account-removal purge path). */
    suspend fun purgeProvider(providerKey: String) = EpgMirrorDb.purgeProvider(providerKey)

    // --- sync ---------------------------------------------------------------------

    /**
     * Refresh the mirror if stale (12h) and map any newly-added playlists. Cheap when fresh.
     * Never throws; a failed sync leaves the previous data serving. Fire-and-forget from the
     * surfaces that consume the mirror (Sports tab, IPTV hub).
     */
    suspend fun ensureFresh(force: Boolean = false) {
        if (!syncMutex.tryLock()) return
        try {
            val now = TraktPlatformClock.nowEpochMs()
            val lastSync = EpgMirrorDb.meta(META_SYNCED_AT)?.toLongOrNull() ?: 0L
            val fresh = !force && now - lastSync < SYNC_TTL_MS
            if (fresh && !EpgMirrorDb.indexIsEmpty()) {
                mapMissingAccounts()
                return
            }

            val base = storageBase() ?: return
            val manifest = fetchJson<MirrorManifest>("$base/manifest.json") ?: return
            val generation = manifest.generatedAt.orEmpty()
            if (!force && generation.isNotEmpty() && generation == EpgMirrorDb.meta(META_GENERATION) && !EpgMirrorDb.indexIsEmpty()) {
                EpgMirrorDb.setMeta(META_SYNCED_AT, now.toString())
                mapMissingAccounts()
                return
            }

            val index = fetchJson<ChannelsIndexDoc>("$base/${manifest.channelsIndexPath ?: "channels-index.json.gz"}")
                ?: return
            val rows = ArrayList<EpgIndexRow>(64_000)
            for (src in index.sources) {
                for (ch in src.channels) {
                    val id = normalizeChannelId(ch.id)
                    if (id.isEmpty()) continue
                    if (ch.names.isEmpty()) rows.add(EpgIndexRow(src.slug, id, ch.id))
                    else ch.names.forEach { n -> if (n.isNotBlank()) rows.add(EpgIndexRow(src.slug, id, n)) }
                }
            }
            if (rows.isEmpty()) return
            EpgMirrorDb.replaceIndex(rows)

            mapAccounts(allAccounts = true)

            val mappedIds = EpgMirrorDb.mappedEpgIds()
            if (mappedIds.isNotEmpty()) {
                val idsBySlug = HashMap<String, MutableSet<String>>()
                EpgMirrorDb.forEachIndexRow { r ->
                    if (r.epgId in mappedIds) idsBySlug.getOrPut(r.slug) { mutableSetOf() }.add(r.epgId)
                }
                val chosen = idsBySlug.entries
                    .sortedByDescending { it.value.size }
                    .filter { it.value.size >= MIN_SLUG_COVER }
                    .take(MAX_FEEDS)
                    .map { it.key }
                if (chosen.isNotEmpty()) {
                    EpgMirrorDb.clearProgrammes()
                    val windowStart = now - WINDOW_BACK_MS
                    val windowEnd = now + WINDOW_AHEAD_MS
                    val covered = mutableSetOf<String>()
                    var stored = 0
                    for (slug in chosen) {
                        val want = idsBySlug[slug].orEmpty().minus(covered)
                        if (want.isEmpty()) continue
                        val seen = mutableSetOf<String>()
                        // The parser callback can't suspend, so rows for this feed collect
                        // here (window+mapped-filtered: bounded) and chunk-insert after.
                        val rows = ArrayList<EpgProgrammeRow>(4_096)
                        val parser = XmltvStreamingParser(keepChannelIds = want) { p ->
                            if (p.endMs > windowStart && p.startMs < windowEnd) {
                                val id = normalizeChannelId(p.channelId)
                                rows.add(EpgProgrammeRow(id, p.startMs, p.endMs, p.title, p.desc))
                                seen.add(id)
                            }
                        }
                        runCatching {
                            httpStreamLines(feedUrl(base, manifest, slug), null, null) { line ->
                                parser.feed(line); parser.feed("\n")
                            }
                            parser.finish()
                        }.onFailure { log.w(it) { "feed $slug failed" } }
                        rows.chunked(CHUNK).forEach { EpgMirrorDb.insertProgrammes(it) }
                        stored += rows.size
                        covered += seen
                    }
                    log.i { "mirror sync: $stored programmes for ${covered.size} channels from $chosen" }
                }
            }

            EpgMirrorDb.setMeta(META_GENERATION, generation)
            EpgMirrorDb.setMeta(META_SYNCED_AT, now.toString())
        } catch (t: Throwable) {
            log.w(t) { "mirror sync failed" }
        } finally {
            syncMutex.unlock()
        }
    }

    /** Map playlists that have no mapping rows yet (added since the last full sync). */
    private suspend fun mapMissingAccounts() {
        val mapped = EpgMirrorDb.mappedProviderKeys()
        XtreamRepository.ensureLoaded()
        val missing = XtreamRepository.uiState.value.accounts.filter { it.enabled && it.id !in mapped }
        if (missing.isEmpty()) return
        mapAccounts(allAccounts = false, only = missing.map { it.id }.toSet())
    }

    /** Build the transient matcher index from the stored channels-index; persist mappings. */
    private suspend fun mapAccounts(allAccounts: Boolean, only: Set<String> = emptySet()) {
        val pairs = ArrayList<Pair<String, List<String>>>(64_000)
        var lastId = ""
        var names = ArrayList<String>()
        EpgMirrorDb.forEachIndexRow { r ->
            if (r.epgId != lastId) {
                if (lastId.isNotEmpty()) pairs.add(lastId to names)
                lastId = r.epgId
                names = ArrayList(3)
            }
            names.add(r.name)
        }
        if (lastId.isNotEmpty()) pairs.add(lastId to names)
        if (pairs.isEmpty()) return
        val index = EpgChannelIndex.build(pairs)

        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts
            .filter { it.enabled && (allAccounts || it.id in only) }
        for (acc in accounts) {
            val channels = runCatching { XtreamSearchIndex.liveChannelsFor(acc) }.getOrDefault(emptyList())
            if (channels.isEmpty()) continue
            val mappings = channels.mapNotNull { ch ->
                index.match(ch.name, ch.epgChannelId)?.let { hit ->
                    EpgMappingRow(ch.streamId, normalizeChannelId(hit.epgId), hit.tier)
                }
            }
            EpgMirrorDb.replaceMapping(acc.id, mappings)
            log.i { "mapped ${mappings.size}/${channels.size} channels for ${acc.name}" }
        }
    }

    // --- transport ------------------------------------------------------------------

    private fun storageBase(): String? {
        val url = runCatching { SupabaseProvider.selectedBackend.normalizedSupabaseUrl }.getOrNull()
            ?.trim()?.trimEnd('/')
        if (url.isNullOrBlank()) return null
        return "$url/storage/v1/object/public/epg"
    }

    private fun feedUrl(base: String, manifest: MirrorManifest, slug: String): String =
        "$base/${manifest.files.firstOrNull { it.slug == slug && it.error == null }?.path ?: "$slug.xml.gz"}"

    /** GET + accumulate + parse. httpStreamLines transparently gunzips bare .gz bodies. */
    private suspend inline fun <reified T> fetchJson(url: String): T? = runCatching {
        val sb = StringBuilder()
        httpStreamLines(url, null, null) { line -> sb.append(line) }
        json.decodeFromString<T>(sb.toString())
    }.onFailure { log.w(it) { "fetch failed: $url" } }.getOrNull()

    // --- wire models ------------------------------------------------------------------

    @Serializable
    private data class MirrorManifest(
        val generatedAt: String? = null,
        val files: List<MirrorFile> = emptyList(),
        val channelsIndexPath: String? = null,
    )

    @Serializable
    private data class MirrorFile(
        val slug: String,
        val path: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class ChannelsIndexDoc(
        val generatedAt: String? = null,
        val sources: List<IndexSourceDoc> = emptyList(),
    )

    @Serializable
    private data class IndexSourceDoc(
        val slug: String,
        val channels: List<IndexChannelDoc> = emptyList(),
    )

    @Serializable
    private data class IndexChannelDoc(
        val id: String,
        val names: List<String> = emptyList(),
    )

    private const val META_SYNCED_AT = "synced_at"
    private const val META_GENERATION = "generation"
    private const val SYNC_TTL_MS = 12 * 60 * 60 * 1000L
    /** Only download a feed when it covers a meaningful slice of the user's channels. */
    private const val MIN_SLUG_COVER = 25
    private const val MAX_FEEDS = 4
    /** Programme window kept locally: enough for "started earlier" + two days of guide. */
    private const val WINDOW_BACK_MS = 6 * 60 * 60 * 1000L
    private const val WINDOW_AHEAD_MS = 48 * 60 * 60 * 1000L
    private const val CHUNK = 5_000
}
