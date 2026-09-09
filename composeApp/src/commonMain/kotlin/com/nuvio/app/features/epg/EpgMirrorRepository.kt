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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Serializes channels-index ingests so two can never interleave shadow begin/insert/commit and
     *  publish or drop each other's generation. `ensureFresh` already single-flights via [syncMutex];
     *  this additionally guards a direct [ingestChannelsIndexStream]. It does NOT gate guide reads
     *  (those take EpgMirrorDb's own lock), so serializing ingests never blocks the guide. */
    private val indexIngestMutex = Mutex()
    /** Survives any screen: region changes rebuild even though the picker closes immediately. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** Drop a removed playlist's mappings and schedule state (account-removal purge path). */
    suspend fun purgeProvider(providerKey: String) {
        EpgMirrorDb.purgeProvider(providerKey)
        EpgMirrorDb.deleteMeta(mappedGenKey(providerKey))
        EpgMirrorDb.deleteMeta(attemptAtKey(providerKey))
    }

    // --- region selection (the picker) ---------------------------------------------

    /**
     * Regions the viewer chose, or empty for "no preference" (everything — the opt-in default).
     * Stored in the mirror's own meta table: it is EPG cache state, it belongs with the data it
     * filters, and it needs no new per-platform storage.
     */
    suspend fun selectedRegions(): Set<String> =
        EpgMirrorDb.meta(META_REGIONS).orEmpty()
            .split(REGION_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Every region the mirror publishes, for the picker (works offline after one sync). */
    suspend fun availableRegions(): List<EpgRegion> = EpgRegionCatalog.catalogFrom(EpgMirrorDb.sources())

    /**
     * Applies a new selection and rebuilds against it.
     *
     * The index is stored pre-filtered, so a changed selection invalidates it: clear the sync
     * stamps so the next [ensureFresh] re-downloads, and clear every account's mapped-generation
     * so mappings are re-derived against the new index (they were computed against the old one,
     * which is exactly the case the generation key exists to catch).
     */
    suspend fun setSelectedRegions(regions: Set<String>) {
        val normalized = regions.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalized == selectedRegions()) return
        EpgMirrorDb.setMeta(META_REGIONS, normalized.joinToString(REGION_SEPARATOR))
        EpgMirrorDb.setMeta(META_SYNCED_AT, "0")
        EpgMirrorDb.setMeta(META_GENERATION, "")
        for (key in EpgMirrorDb.metaKeysWithPrefix(MAPPED_GEN_PREFIX)) EpgMirrorDb.deleteMeta(key)
        log.i { "epg regions set to ${normalized.ifEmpty { setOf("<all>") }}; index will rebuild" }
        // Rebuild on the repository's own scope, NOT the caller's. The picker dismisses itself
        // as soon as it applies, so a `rememberCoroutineScope` launch is cancelled mid-fetch
        // (ForgottenCoroutineScopeException — observed on the emulator). The stamps above already
        // guarantee the next natural sync rebuilds; this just makes it immediate.
        scope.launch { ensureFresh(force = true) }
    }

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
            // `sourcesAreEmpty` forces the full path once after upgrading to region support:
            // the published source list is only written when the index is (re)built, so without
            // this an install whose generation never changes would show an empty region picker
            // forever.
            if (fresh && !EpgMirrorDb.indexIsEmpty() && !EpgMirrorDb.sourcesAreEmpty()) {
                // Whatever the stored index was built from is what any mapping must agree with.
                mapAccountsIfNeeded(now, force = false, generation = EpgMirrorDb.meta(META_GENERATION).orEmpty())
                return
            }

            val base = storageBase() ?: return
            val manifest = fetchJson<MirrorManifest>("$base/manifest.json") ?: return
            val generation = manifest.generatedAt.orEmpty()
            if (!force && generation.isNotEmpty() && generation == EpgMirrorDb.meta(META_GENERATION) &&
                !EpgMirrorDb.indexIsEmpty() && !EpgMirrorDb.sourcesAreEmpty()
            ) {
                EpgMirrorDb.setMeta(META_SYNCED_AT, now.toString())
                mapAccountsIfNeeded(now, force = false, generation = generation)
                return
            }

            // Stream the channels-index: decode ONE source at a time (the whole ChannelsIndexDoc tree
            // and the whole decompressed body are never materialized), store the selected regions'
            // rows into a bounded shadow, and swap it in only after a fully successful parse — so an
            // oversized/malformed/truncated response leaves the previous generation intact.
            val indexUrl = "$base/${manifest.channelsIndexPath ?: "channels-index.json.gz"}"
            val committed = ingestChannelsIndexStream(selectedRegions()) { onChunk ->
                httpStreamLines(indexUrl, null) { line -> onChunk(line) }
            }
            if (!committed) return

            // The index just changed, so this is the one moment a re-match can produce a new
            // answer — but the policy still admits at most ONE account per sync, so a bump that
            // affects every account spreads over visits instead of stacking a 49k-channel
            // foreground episode (research/tv-epg-mirror-spin.md).
            mapAccountsIfNeeded(now, force, generation)

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
                    // Shadow-table refresh: readers keep the old programme window until the
                    // commit swap — the old up-front clear left the guide EMPTY for the
                    // whole streamed feed download on slow boxes.
                    EpgMirrorDb.beginProgrammesRefresh()
                    val windowStart = now - WINDOW_BACK_MS
                    val windowEnd = now + WINDOW_AHEAD_MS
                    val covered = mutableSetOf<String>()
                    var stored = 0
                    for (slug in chosen) {
                        val want = idsBySlug[slug].orEmpty().minus(covered)
                        if (want.isEmpty()) continue
                        // Feeds download from their ORIGIN (GitHub CDN etc.) — the backend
                        // publishes pointers only, no bytes transit Supabase.
                        val feedUrl = manifest.urlFor(slug) ?: continue
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
                            httpStreamLines(feedUrl, null, null) { line ->
                                parser.feed(line); parser.feed("\n")
                            }
                            parser.finish()
                        }.onFailure { log.w(it) { "feed $slug failed" } }
                        rows.chunked(CHUNK).forEach { EpgMirrorDb.insertProgrammes(it) }
                        stored += rows.size
                        covered += seen
                    }
                    EpgMirrorDb.commitProgrammesRefresh()
                    log.i { "mirror sync: $stored programmes for ${covered.size} channels from $chosen" }
                    EpgTelemetry.ingestFinished(
                        source = EpgTelemetry.Source.MIRROR,
                        outcome = if (stored > 0) EpgTelemetry.Outcome.OK else EpgTelemetry.Outcome.EMPTY,
                        programmes = stored,
                        channels = mappedIds.size,
                        channelsCovered = covered.size,
                        durationMs = TraktPlatformClock.nowEpochMs() - now,
                    )
                    // Same reason as the XMLTV lane: programmes just landed, so any "nothing for
                    // this channel" verdict taken before them is stale and must not hold a tile
                    // empty for the rest of its cooldown.
                    com.nuvio.app.features.iptv.XtreamHubRepository.onGuideDataChanged()
                }
            }

            EpgMirrorDb.setMeta(META_GENERATION, generation)
            EpgMirrorDb.setMeta(META_SYNCED_AT, now.toString())
        } catch (t: Throwable) {
            log.w(t) { "mirror sync failed" }
            EpgTelemetry.ingestFinished(
                source = EpgTelemetry.Source.MIRROR,
                outcome = EpgTelemetry.Outcome.ERROR,
                errorClass = t::class.simpleName,
            )
        } finally {
            syncMutex.unlock()
        }
    }

    /** Meta keys for one account's mapping schedule (cleared by [purgeProvider]). */
    private fun mappedGenKey(accountId: String) = "$MAPPED_GEN_PREFIX$accountId"
    private fun attemptAtKey(accountId: String) = "acct_attempt_ms:$accountId"

    /**
     * Re-match the accounts [EpgRemapPolicy] selects — never-mapped ones (cooldown-gated),
     * at most one aged one, or all under `force`. The expensive parts — the tens-of-MB
     * transient [EpgChannelIndex] and the per-channel match walk — only happen when at least
     * one account is due, which in steady state is one account a week, not every account on
     * every generation bump (that was the Onn TV "background spin",
     * research/tv-epg-mirror-spin.md; mobile shares the design, so it shares the fix).
     *
     * "Mapped" is meta-stamped on a COMPLETED match run, even one with zero hits — keying it
     * on row presence made all-24/7 accounts re-run the episode on every surface visit.
     */
    private suspend fun mapAccountsIfNeeded(nowMs: Long, force: Boolean, generation: String) {
        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        if (accounts.isEmpty()) return
        var agedBudgetLeft = true
        val due = accounts.filter { acc ->
            val mappedGen = EpgMirrorDb.meta(mappedGenKey(acc.id)).orEmpty()
            val attemptedAt = EpgMirrorDb.meta(attemptAtKey(acc.id))?.toLongOrNull() ?: 0L
            val decision = EpgRemapPolicy.decide(nowMs, force, mappedGen, generation, attemptedAt, agedBudgetLeft)
            if (decision == EpgRemapPolicy.Decision.REMATCH && mappedGen.isNotEmpty() && !force) {
                agedBudgetLeft = false
            }
            decision == EpgRemapPolicy.Decision.REMATCH
        }
        if (due.isEmpty()) return

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

        for (acc in due) {
            val matchStartedMs = TraktPlatformClock.nowEpochMs()
            EpgMirrorDb.setMeta(attemptAtKey(acc.id), nowMs.toString())
            val channels = runCatching { XtreamSearchIndex.liveChannelsFor(acc) }.getOrDefault(emptyList())
            // Empty local index (account not ingested yet): attempt stamped, mappedAt not —
            // the cooldown owns the retry.
            if (channels.isEmpty()) continue
            val mappings = channels.mapNotNull { ch ->
                index.match(ch.name, ch.epgChannelId)?.let { hit ->
                    EpgMappingRow(ch.streamId, normalizeChannelId(hit.epgId), hit.tier)
                }
            }
            EpgMirrorDb.replaceMapping(acc.id, mappings)
            // Stamped even at zero hits: the run COMPLETED against this index.
            EpgMirrorDb.setMeta(mappedGenKey(acc.id), generation.ifEmpty { NO_GENERATION })
            log.i { "mapped ${mappings.size}/${channels.size} channels for ${acc.name}" }
            // The match rate is what "my EPG stopped working" almost always turns out to be —
            // it collapses when a provider renumbers its catalog, and we have never been able
            // to see it. No account name or host here: counts only.
            EpgTelemetry.mappingFinished(
                matched = mappings.size,
                channels = channels.size,
                durationMs = TraktPlatformClock.nowEpochMs() - matchStartedMs,
            )
        }
    }

    // --- transport ------------------------------------------------------------------

    private fun storageBase(): String? {
        val url = runCatching { SupabaseProvider.selectedBackend.normalizedSupabaseUrl }.getOrNull()
            ?.trim()?.trimEnd('/')
        if (url.isNullOrBlank()) return null
        return "$url/storage/v1/object/public/epg"
    }


    /** GET + accumulate + parse. httpStreamLines transparently gunzips bare .gz bodies.
     *  Input limit: the DECOMPRESSED accumulation is bounded so a runaway / decompression-bomb /
     *  corrupt response cannot OOM. Over the cap → reject the whole fetch (null); every caller keeps
     *  the previous valid generation (`?: return`), never a partial replacement. This bounds the
     *  bomb/corruption vector; it does NOT reduce the legitimate index's decode transient — bounding
     *  that needs element-streaming into the DB (backend NDJSON), the documented follow-up. */
    private suspend inline fun <reified T> fetchJson(url: String): T? = runCatching {
        val body = boundedJoin(MAX_FETCH_CHARS) { emit ->
            httpStreamLines(url, null, null) { line -> emit(line) }
        }
        if (body == null) {
            log.w { "EPG response exceeded $MAX_FETCH_CHARS chars; rejected, kept prior generation: $url" }
            return@runCatching null
        }
        json.decodeFromString<T>(body)
    }.onFailure { log.w(it) { "fetch failed: $url" } }.getOrNull()

    /**
     * Bounded-memory channels-index ingestion, in two phases so guide reads are never blocked on the
     * network and so a channel's keep/skip decision uses its source's FINAL metadata:
     *
     * PHASE 1 (no EPG DB lock held): [feed] streams the (gunzipped) response; a
     * [ChannelsIndexStreamParser] emits each source's scalars and its channel objects one at a time,
     * and EVERY source's channels are appended to bounded off-DB [EpgIngestStaging] (a temp file),
     * tagged by a STABLE per-source ordinal. Keep is NOT decided here — a late or duplicate
     * slug/label/countries could still change it — so field order is fully independent and no channel
     * is ever selected on a stale value. The whole document, a whole source's channel list, and the
     * whole catalog all stay out of memory (peak = one row + the parser/writer buffers).
     *
     * PHASE 2 (short per-batch lock, NO network): once the metadata is final, [EpgRegionCatalog.slugsFor]
     * decides the kept sources; a plain suspend loop pulls staged rows and promotes only the kept
     * ordinals' rows into the shadow in [INDEX_BATCH] batches, each under a lock RELEASED between
     * batches (a guide read interleaves), then swaps atomically. The previous generation serves until
     * the swap; any failure or cancellation drops the shadow and keeps the prior generation.
     *
     * Caps ([MAX_SOURCES], [MAX_ROWS] total staged, [MAX_SCALAR_CHARS]/[MAX_CHANNEL_CHARS]/[MAX_DEPTH])
     * bound skipped-region and unknown-field input too. Returns true iff a new generation was committed.
     */
    internal suspend fun ingestChannelsIndexStream(
        selection: Set<String>,
        feed: suspend (onChunk: (String) -> Unit) -> Unit,
    ): Boolean = indexIngestMutex.withLock {
        val published = ArrayList<EpgSourceInfo>() // index == source ordinal; holds each source's FINAL metadata
        val staging = EpgIngestStaging()
        try {
            // --- PHASE 1: network → staging, no EPG DB lock ---
            var ordinal = -1
            var slug = ""; var label: String? = null; var countries: String? = null; var channelCount = 0
            var stagedRows = 0
            val handler = object : ChannelsIndexStreamParser.Handler {
                override fun onSourceBegin() {
                    ordinal++
                    if (ordinal >= MAX_SOURCES) throw EpgElementTooLargeException(ordinal)
                    slug = ""; label = null; countries = null; channelCount = 0
                }

                // Last value wins for a repeated key — the final scalars are what onSourceEnd records.
                override fun onSourceScalar(key: String, value: String?) {
                    when (key) {
                        "slug" -> slug = value.orEmpty()
                        "label" -> label = value
                        "countries" -> countries = value
                    }
                }

                override fun onChannel(channelJson: String) {
                    channelCount++
                    val ch = json.decodeFromString<IndexChannelDoc>(channelJson)
                    val id = normalizeChannelId(ch.id)
                    if (id.isEmpty()) return
                    val names = if (ch.names.isEmpty()) listOf(ch.id) else ch.names
                    for (n in names) {
                        if (n.isBlank()) continue
                        if (stagedRows >= MAX_ROWS) throw EpgElementTooLargeException(stagedRows)
                        staging.append(encodeStagedRow(EpgStagedRow(ordinal, id, n)))
                        stagedRows++
                    }
                }

                override fun onSourceEnd() {
                    published.add(EpgSourceInfo(slug, label ?: slug, countries, channelCount))
                }
            }
            val parser = ChannelsIndexStreamParser(MAX_SCALAR_CHARS, MAX_CHANNEL_CHARS, MAX_DEPTH, handler)
            val parsed = runCatching {
                feed { chunk -> parser.accept(chunk) }
                parser.finish() // throws on an absent/truncated array — never publish a partial replacement
                true
            }.getOrElse { t ->
                if (t is CancellationException) throw t // propagate: the finally disposes staging, prior gen stands
                log.w(t) { "channels-index stream rejected; kept prior generation" }
                false
            }
            if (!parsed) return@withLock false // parse failed/truncated → prior generation intact, catalog not trusted

            // --- keep decision from FINAL metadata (field-order independent; duplicate key = last wins) ---
            val keptSlugs = EpgRegionCatalog.slugsFor(selection, published)
            val slugByKeptOrdinal = HashMap<Int, String>()
            published.forEachIndexed { ord, info -> if (info.slug in keptSlugs) slugByKeptOrdinal[ord] = info.slug }

            // --- PHASE 2: staging → shadow, short per-batch lock, no network ---
            staging.openForRead()
            EpgMirrorDb.beginIndexShadow()
            var committed = false
            try {
                val batch = ArrayList<EpgIndexRow>(INDEX_BATCH)
                var promoted = 0
                while (true) {
                    val line = staging.nextLine() ?: break
                    val row = decodeStagedRow(line)
                    val rowSlug = slugByKeptOrdinal[row.o] ?: continue // skipped region — never promoted
                    batch.add(EpgIndexRow(rowSlug, row.i, row.n))
                    promoted++
                    if (batch.size >= INDEX_BATCH) { EpgMirrorDb.insertIndexShadow(batch); batch.clear() }
                }
                if (batch.isNotEmpty()) EpgMirrorDb.insertIndexShadow(batch)
                if (promoted > 0) { EpgMirrorDb.commitIndexShadow(); committed = true } else EpgMirrorDb.dropIndexShadow()
            } catch (t: Throwable) {
                EpgMirrorDb.dropIndexShadow()
                throw t
            }
            EpgMirrorDb.replaceSources(published) // full catalog parsed → publish for the region picker
            return@withLock committed
        } finally {
            staging.dispose()
        }
    }

    // --- wire models ------------------------------------------------------------------

    @Serializable
    private data class MirrorManifest(
        val generatedAt: String? = null,
        val files: List<MirrorFile> = emptyList(),
        val channelsIndexPath: String? = null,
    ) {
        fun urlFor(slug: String): String? = files.firstOrNull { it.slug == slug && it.error == null }?.url
    }

    @Serializable
    private data class MirrorFile(
        val slug: String,
        val url: String? = null,
        val error: String? = null,
    )

    /** One channel of a source — decoded one at a time from the stream (never a whole-source list). */
    @Serializable
    private data class IndexChannelDoc(
        val id: String,
        val names: List<String> = emptyList(),
    )

    /** Manifest fetch cap (small JSON) — a corrupt/runaway manifest is rejected without OOM. The
     *  channels-index no longer goes through fetchJson: it is streamed record-wise (below). */
    private const val MAX_FETCH_CHARS = 4_000_000

    // --- streaming channels-index bounds -------------------------------------------------
    // Working-set bounds (retained memory, independent of catalog size):
    /** One source's metadata string (slug/label/countries) cap — larger ⇒ reject the update. */
    private const val MAX_SCALAR_CHARS = 8_192
    /** One channel object's JSON cap — larger ⇒ reject. A channel is `{"id":..,"names":[..]}`; tiny. */
    private const val MAX_CHANNEL_CHARS = 64 * 1024
    /** Max nesting depth inside a channel object or a skipped field — larger ⇒ reject. */
    private const val MAX_DEPTH = 32
    /** Rows buffered before a flush+clear: the parser callback can't suspend, so a full batch is the
     *  peak retained selected-row memory. Kept modest so working memory stays flat. */
    private const val INDEX_BATCH = 4_000
    /** Max source records before the update is rejected (the mirror publishes ~tens of regions). */
    private const val MAX_SOURCES = 4_096
    // Total-accepted bound (NOT a working-set bound — rows are streamed to disk in INDEX_BATCH flushes):
    /** Max selected index rows accepted in one refresh. The full multi-region index is ~49k channels;
     *  a household stores ~13%. Well above the real ceiling so a valid catalog is never rejected. */
    private const val MAX_ROWS = 400_000

    private const val META_SYNCED_AT = "synced_at"
    private const val META_REGIONS = "selected_regions"
    /** Region names cannot contain it, unlike the comma the backend uses inside `countries`. */
    private const val REGION_SEPARATOR = "\u0001"
    private const val MAPPED_GEN_PREFIX = "acct_mapped_gen:"
    private const val META_GENERATION = "generation"
    /** Stamped when the mirror publishes no generation, so "matched once" is still recorded. */
    private const val NO_GENERATION = "-"
    private const val SYNC_TTL_MS = 12 * 60 * 60 * 1000L
    /** Only download a feed when it covers a meaningful slice of the user's channels. */
    private const val MIN_SLUG_COVER = 25
    private const val MAX_FEEDS = 4
    /** Programme window kept locally: enough for "started earlier" + two days of guide. */
    private const val WINDOW_BACK_MS = 6 * 60 * 60 * 1000L
    private const val WINDOW_AHEAD_MS = 48 * 60 * 60 * 1000L
    private const val CHUNK = 5_000
}
