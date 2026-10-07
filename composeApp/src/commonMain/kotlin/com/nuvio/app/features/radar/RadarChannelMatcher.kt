package com.nuvio.app.features.radar

import com.nuvio.app.core.contracts.SportsReplay
import com.nuvio.app.features.epg.EpgLang
import com.nuvio.app.features.epg.EpgMirrorRepository
import com.nuvio.app.features.epg.EpgNorm
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.isM3u
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamSearchIndex
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Which of MY channels is showing this match?" — the Sports Centre matcher.
 *
 * The CORE is source-agnostic: it scores [CandidateChannel]s (plain data) against a fixture,
 * with an optional per-candidate EPG lookup. Today the single assembly function pulls
 * candidates from Xtream playlists; when the playlist-manager feature lands M3U/Stalker,
 * extend [assembleCandidates] to add their channels (content DB) and route [epgFor] through
 * `epg_programmes` first — the core never changes. (See radar-feature-requirements.md §5.)
 *
 * Layered because real-panel EPG is sparse-to-empty: name matches alone must produce results.
 */
object RadarChannelMatcher {

    data class CandidateChannel(
        val playlistId: String,
        val playlistName: String,
        /** Plays through the app's existing live route (registry-registered). */
        val contentId: String,
        val name: String,
        val logo: String?,
        /** Source-specific EPG handle; for Xtream it's the stream id. */
        val streamId: Int,
        /** The provider's own guide id for this channel — joins provider-EPG hits back. */
        val epgChannelId: String? = null,
        /** Channel offers catch-up (Xtream tv_archive) — enables Replay for past fixtures. */
        val hasArchive: Boolean = false,
        val catalogRevision: String = "",
    )

    /** A provider VOD entry that looks like a recording of the fixture. */
    data class RecordingHit(
        val contentId: String,
        val name: String,
        val poster: String?,
        val playlistName: String,
    )

    /** How a channel earned its place in the sheet (drives the "via EPG"/country chips). */
    enum class MatchVia { NAME, EPG, LISTING }

    data class ChannelMatch(
        val channel: CandidateChannel,
        /** The EPG programme that matched, when the panel has EPG for this channel. */
        val programme: SportsProgramme?,
        val score: Int,
        val via: MatchVia = MatchVia.NAME,
        /** Short language/region tag ("FR", "AR") or the broadcaster country ("France"). */
        val language: String? = null,
        /** Evidence tier: time-aligned guide/listing, possible event identity, or competition only. */
        val confidence: MatchConfidence = MatchConfidence.LEAGUE,
        val evidence: SportsMatchEvidence? = null,
    )

    private const val NAME_POOL_CAP = 200
    private const val EPG_PROBE_CAP = 6
    private const val EPG_CONCURRENCY = 2
    /** Sheet capacity now that EPG/listing tiers surface worldwide airings (was 10). */
    private const val RESULT_CAP = 40
    /** Mirror-EPG hits outrank every name tier; listing hits sit between. */
    private const val MIRROR_BASE_SCORE = 100
    private const val LISTING_SCORE = 80
    private const val PROGRAMME_WINDOW_BACK_MS = 45 * 60 * 1000L
    private const val PROGRAMME_WINDOW_AHEAD_MS = 4 * 60 * 60 * 1000L
    private const val RECORDING_CAP = 6
    private const val INDEX_WAIT_MS = 12_000L

    /** Trailing country words TheSportsDB appends to station names ("M4 Sport HU"). */
    private val STATION_COUNTRY_TAILS = setOf(
        "uk", "us", "usa", "ca", "au", "nz", "fr", "france", "de", "germany", "it", "italy",
        "es", "spain", "pt", "portugal", "nl", "netherlands", "be", "mx", "mexico", "br",
        "brazil", "ar", "argentina", "rs", "serbia", "hu", "hr", "si", "sk", "cz", "pl",
        "ro", "bg", "gr", "tr", "il", "za", "ie", "ireland", "is", "iceland", "no", "norway",
        "se", "sweden", "fi", "finland", "dk", "denmark", "ch", "at", "hd",
    )

    // Channel-name markers of generic sports channels — weak candidates that the EPG stage
    // can confirm even when no league keyword appears in the channel name.
    // Compared against normalize()d names — punctuation is already stripped.
    private val GENERIC_SPORT_MARKERS = listOf(
        "sport", "espn", "bein", "dazn", "eurosport", "supersport", "fox sports",
        "sky sports", "tnt sports", "arena", "setanta", "premier sports",
    )

    private val STOP_TOKENS = setOf("fc", "cf", "sc", "afc", "rc", "cd", "ac", "de", "the", "club", "los", "las")

    /**
     * Match a fixture against every enabled playlist's channels. [onPartial] fires once with
     * the quick name-based matches so the sheet can render before EPG probes finish.
     */
    suspend fun match(
        fixture: RadarFixture,
        league: RadarLeague?,
        stations: List<RadarTvStation> = emptyList(),
        stationLookup: (suspend () -> List<RadarTvStation>)? = null,
        onPartial: suspend (List<ChannelMatch>) -> Unit = {},
    ): List<ChannelMatch> = withContext(Dispatchers.Default) {
        val stationTask = stationLookup?.let { lookup -> async {
            try { withTimeoutOrNull(4_000L) { lookup() }.orEmpty() }
            catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
        } }
        val generation = matcherGeneration.value
        val keywords = league?.keywords.orEmpty()
        val prepared = SportsEventMatchEngine.prepare(fixture, keywords)
        val homeRegion = SportsBroadcastRegionPolicy.regionOfCountry(fixture.country)
        val candidates = assembleCandidates()
        val named = candidates.mapNotNull { c ->
            val evidence = SportsEventMatchEngine.name(prepared, c.name)
            if (evidence.strength == 0) return@mapNotNull null
            ChannelMatch(c, programme = null,
                score = evidence.strength + SportsBroadcastRegionPolicy.homeRegionBoost(c.name, homeRegion),
                confidence = evidence.confidence, evidence = evidence)
        }.sortedWith(matchOrder).take(NAME_POOL_CAP)
        if (generation != matcherGeneration.value) return@withContext emptyList()
        onPartial(mergeMatches(named))

        // Read the local guides before any provider probes. These queries never fetch a catalog.
        val cached = coroutineScope {
            val mirror = async { mirrorMatches(candidates, prepared) }
            val provider = async { providerEpgMatches(candidates, prepared) }
            mirror.await() + provider.await() + stationMatches(candidates, stations, homeRegion)
        }
        if (generation != matcherGeneration.value) return@withContext emptyList()
        onPartial(mergeMatches(named + cached))
        val local = cached + stationMatches(candidates, stationTask?.await().orEmpty(), homeRegion)
        if (generation != matcherGeneration.value) return@withContext emptyList()
        onPartial(mergeMatches(named + local))
        val resolved = local.filter { it.via == MatchVia.EPG && it.confidence == MatchConfidence.CONFIRMED }
            .map { it.channel.contentId }.toSet()
        // Generic sports channels are probe candidates only. They never pad displayed results.
        val probeCandidates = (named.map { it.channel } + candidates.filter { c ->
            GENERIC_SPORT_MARKERS.any { marker -> normalize(c.name).contains(marker) }
        }).distinctBy { it.contentId }.filterNot { it.contentId in resolved }.take(EPG_PROBE_CAP)
        val probed = if (fixture.startEpochMs == null || resolved.isNotEmpty()) emptyList() else coroutineScope {
            val semaphore = Semaphore(EPG_CONCURRENCY)
            probeCandidates.map { c -> async {
                semaphore.withPermit {
                    val programmes = withTimeoutOrNull(3_000L) { cachedProbe(c) }.orEmpty()
                    bestProgramme(programmes, prepared)?.let { hit ->
                        ChannelMatch(c, programme = hit.first.toSportsProgramme(),
                            score = MIRROR_BASE_SCORE + hit.second.strength / 10,
                            via = MatchVia.EPG, confidence = hit.second.confidence, evidence = hit.second)
                    }
                }
            } }.awaitAll().filterNotNull()
        }
        if (generation != matcherGeneration.value) emptyList() else mergeMatches(named + local + probed)
    }

    private val matchOrder = compareByDescending<ChannelMatch> { SportsEventMatchEngine.rank(it.confidence) }
        .thenByDescending { it.evidence?.strength ?: it.score }
        .thenByDescending { it.score }
        .thenBy { it.channel.contentId }

    /** Keep source, confidence and programme together; preferences are only a tie-breaker. */
    internal fun mergeMatches(matches: List<ChannelMatch>): List<ChannelMatch> = matches
        .filter { it.evidence?.strength != 0 }
        .sortedWith(matchOrder).distinctBy { it.channel.contentId }.take(RESULT_CAP)

    private val matcherGeneration = kotlinx.atomicfu.atomic(0)

    fun resetForProfile() { matcherGeneration.incrementAndGet() }

    private val probeMutex = Mutex()
    private val probeCache = mutableMapOf<String, Pair<Long, List<XtreamProgram>>>()
    private val probeLocks = List(16) { Mutex() }

    private suspend fun cachedProbe(channel: CandidateChannel): List<XtreamProgram> {
        val key = matcherGeneration.value.toString() + ":" + channel.playlistId + ":" + channel.streamId + ":" + channel.name + ":" + channel.epgChannelId + ":" + channel.catalogRevision
        val lock = probeLocks[(key.hashCode() and Int.MAX_VALUE) % probeLocks.size]
        return lock.withLock probe@{
            val now = RadarTime.nowMs()
            val cached = probeMutex.withLock { probeCache[key] }
            if (cached != null && now >= cached.first && now - cached.first < 5 * 60_000L) return@probe cached.second
            val programmes = try { epgFor(channel) }
                catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
            probeMutex.withLock {
                if (probeCache.size >= 128) {
                    val oldest = probeCache.minByOrNull { it.value.first }?.key
                    if (oldest != null) probeCache.remove(oldest)
                }
                probeCache[key] = now to programmes
            }
            programmes
        }
    }

    /** Stage 3: search the mirrored programme window for the event, join via mappings. */

    /**
     * Tier-1b: the provider's own ingested XMLTV, searched in bulk for the fixture and joined
     * back by the channel's own guide id.
     *
     * Costs one local query per playlist and no network, so unlike the get_short_epg probe it
     * doesn't need a channel-name filter in front of it. That filter is what made a Liga MX
     * fixture unmatchable: a Mexican channel scored 0 on name, was dropped before any guide was
     * consulted, and the sheet fell through to generic "has the word sport in it" hits.
     */
    private suspend fun providerEpgMatches(
        candidates: List<CandidateChannel>,
        prepared: SportsEventMatchEngine.Prepared,
    ): List<ChannelMatch> {
        val startMs = prepared.fixture.startEpochMs ?: return emptyList()
        val tokens = prepared.searchTokens
        if (tokens.isEmpty()) return emptyList()
        return buildList {
            for ((playlistId, chans) in candidates.groupBy { it.playlistId }) {
                val byEpgId = chans.filter { !it.epgChannelId.isNullOrBlank() }.groupBy { it.epgChannelId }
                if (byEpgId.isEmpty()) continue
                val hits = try { IptvContentDb.epgSearch(playlistId, tokens,
                    startMs - PROGRAMME_WINDOW_BACK_MS, startMs + PROGRAMME_WINDOW_AHEAD_MS)
                } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
                for (p in hits) {
                    val scored = SportsEventMatchEngine.programme(prepared, p.title, p.desc.orEmpty(), p.startMs, p.endMs)
                    if (scored.strength == 0) continue
                    for (channel in byEpgId[p.channelId].orEmpty()) add(ChannelMatch(
                        channel, programme = SportsProgramme(p.title, p.desc.orEmpty(), p.startMs, p.endMs),
                        score = MIRROR_BASE_SCORE + scored.strength / 10,
                        via = MatchVia.EPG, confidence = scored.confidence, evidence = scored))
                }
            }
        }
    }

    private suspend fun mirrorMatches(
        candidates: List<CandidateChannel>,
        prepared: SportsEventMatchEngine.Prepared,
    ): List<ChannelMatch> {
        val startMs = prepared.fixture.startEpochMs ?: return emptyList()
        if (prepared.searchTokens.isEmpty()) return emptyList()
        val hits = try { EpgMirrorRepository.programmesInWindow(prepared.searchTokens,
            startMs - PROGRAMME_WINDOW_BACK_MS, startMs + PROGRAMME_WINDOW_AHEAD_MS)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
        val byGuide = hits.mapNotNull { p ->
            val scored = SportsEventMatchEngine.programme(prepared, p.title, p.desc.orEmpty(), p.startMs, p.endMs)
            if (scored.strength > 0) Triple(p.channelId, p, scored) else null
        }.groupBy { it.first }.mapValues { (_, rows) -> rows.maxBy { SportsEventMatchEngine.rank(it.third.confidence) * 1000 + it.third.strength } }
        if (byGuide.isEmpty()) return emptyList()
        return buildList {
            for ((playlistId, chans) in candidates.groupBy { it.playlistId }) {
                val mapping = try { EpgMirrorRepository.mappingFor(playlistId) }
                    catch (error: CancellationException) { throw error } catch (_: Exception) { emptyMap() }
                for (c in chans) {
                    val epgId = mapping[c.streamId] ?: continue
                    val (_, p, scored) = byGuide[epgId] ?: continue
                    add(ChannelMatch(c, programme = SportsProgramme(p.title, p.desc.orEmpty(), p.startMs, p.endMs),
                        score = MIRROR_BASE_SCORE + scored.strength / 10, via = MatchVia.EPG,
                        language = EpgLang.of(epgId, c.name, p.title), confidence = scored.confidence, evidence = scored))
                }
            }
        }
    }

    private fun stationMatches(
        candidates: List<CandidateChannel>,
        stations: List<RadarTvStation>,
        homeRegion: String?,
    ): List<ChannelMatch> {
        if (stations.isEmpty()) return emptyList()
        val byCore = HashMap<String, MutableList<CandidateChannel>>()
        val bySquash = HashMap<String, MutableList<CandidateChannel>>()
        for (c in candidates) {
            val core = EpgNorm.coreNorm(c.name)
            if (core.isEmpty()) continue
            byCore.getOrPut(core) { mutableListOf() }.add(c)
            bySquash.getOrPut(EpgNorm.squash(core)) { mutableListOf() }.add(c)
        }
        return buildList {
            for (st in stations) {
                val raw = st.channel ?: continue
                val core = EpgNorm.coreNorm(raw)
                if (core.isEmpty()) continue
                // Exact whole-name first, then the country-tail-dropped brand ("NFL Network US" -> "nfl network").
                val brand = dropStationCountryTail(core)
                val found = byCore[core] ?: bySquash[EpgNorm.squash(core)]
                    ?: (if (brand != core) byCore[brand] ?: bySquash[EpgNorm.squash(brand)] else null)
                    ?: continue
                // The station broadcasts to one region; coreNorm strips a channel's country PREFIX, so
                // "USA: ESPN 2" and "NL: ESPN 2" both look like "espn 2" and one country's feed would
                // otherwise confirm every same-brand channel the user owns. Gate on region alignment.
                val stationRegion = SportsBroadcastRegionPolicy.regionOfCountry(st.country)
                    ?: SportsBroadcastRegionPolicy.regionOfChannel(raw)
                val score = LISTING_SCORE + SportsBroadcastRegionPolicy.listingScoreDelta(stationRegion, homeRegion)
                for (c in found) {
                    if (!SportsBroadcastRegionPolicy.listingAccepts(stationRegion, c.name)) continue
                    if (!SportsEventMatchEngine.compatibleStation(c.name, raw)) continue
                    val confidence = SportsBroadcastRegionPolicy.listingConfidence(stationRegion, SportsBroadcastRegionPolicy.regionOfChannel(c.name))
                    // A fixture-specific listing keeps its evidence across regions; unknown feed territory remains possible.
                    add(ChannelMatch(c, programme = null, score = score, via = MatchVia.LISTING, language = st.country, confidence = confidence, evidence = SportsMatchEvidence(SportsEvidenceSource.LISTING, confidence, LISTING_SCORE, listOf("fixture_broadcaster_listing"))))
                }
            }
        }
    }

    /** "bein sports 1 france" -> "bein sports 1" (listing names often carry the country). */
    private fun dropStationCountryTail(core: String): String {
        val toks = core.split(" ")
        return if (toks.size > 1 && toks.last() in STATION_COUNTRY_TAILS) toks.dropLast(1).joinToString(" ") else core
    }

    // --- source assembly (the ONLY source-specific part) ----------------------

    private suspend fun assembleCandidates(): List<CandidateChannel> {
        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        return buildList {
            for (account in accounts) {
                // Ktor and the platform stores are suspend/non-blocking. This runs on Default,
                // the platform-neutral background dispatcher, via match() above.
                val revision = account.hashCode().toString() + ":" + IptvContentDb.ingestMeta(account.id)?.builtAtMs
                val channels = if (account.sourceType.isM3u()) {
                    com.nuvio.app.features.iptv.IptvClient.forAccount(account).liveChannels(account).getOrDefault(emptyList())
                } else XtreamSearchIndex.liveChannelsFor(account)
                for (ch in channels) {
                    add(
                        CandidateChannel(
                            playlistId = account.id,
                            playlistName = account.name,
                            contentId = XtreamItemRegistry.liveId(account.id, ch.streamId),
                            name = ch.name,
                            logo = ch.logo,
                            streamId = ch.streamId,
                            epgChannelId = ch.epgChannelId,
                            hasArchive = ch.hasArchive,
                            catalogRevision = revision,
                        )
                    )
                }
            }
        }
    }

    private suspend fun epgFor(channel: CandidateChannel): List<XtreamProgram> {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == channel.playlistId }
            ?: return emptyList()
        // Route through the source-correct client. Hardcoding XtreamClient here asked player_api
        // for the EPG of a Stalker account — whose baseUrl/username/password are blank — so the
        // call built a junk URL and failed into emptyList(), silently denying Stalker channels
        // this tier even though portals answer get_short_epg. M3U has no per-channel guide and
        // returns empty either way; those channels still reach the mirror tier below.
        return com.nuvio.app.features.iptv.IptvClient.forAccount(account)
            .shortEpg(account, channel.streamId, limit = 8)
            .getOrDefault(emptyList())
    }


    /**
     * Catch-up Replay for a started/finished fixture on an archived channel: the programme bounds
     * to replay, from the matched EPG programme when there is one, else a default window opening
     * 15 minutes before kickoff. Null when the channel has no archive, the fixture hasn't
     * started, or the source can't serve catch-up.
     */
    fun replayFor(match: ChannelMatch, fixture: RadarFixture): SportsReplay? = replayDescriptor(
        match = match,
        fixture = fixture,
        account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == match.channel.playlistId },
        nowMs = RadarTime.nowMs(),
    )

    /** [replayFor]'s pure core, split so the refusal rules stay pinned by tests. */
    internal fun replayDescriptor(
        match: ChannelMatch,
        fixture: RadarFixture,
        account: com.nuvio.app.features.iptv.XtreamAccount?,
        nowMs: Long,
    ): SportsReplay? {
        val start = fixture.startEpochMs ?: return null
        if (!match.channel.hasArchive || start > nowMs) return null
        if (account == null) return null
        // Timeshift/catch-up is an Xtream-only feature: a Stalker portal builds its archive links
        // server-side (none of the walk's dialects apply) and an M3U playlist has no panel to ask.
        if (account.sourceType != com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM) return null
        val programme = match.programme
        val replayStart = programme?.startMs?.takeIf { it > 0 } ?: (start - 15 * 60 * 1000L)
        val durationMin = (((programme?.endMs ?: 0L) - (programme?.startMs ?: 0L)) / 60_000L)
            .toInt().takeIf { it in 30..360 } ?: 165
        return SportsReplay(
            contentId = match.channel.contentId,
            channelName = match.channel.name,
            logo = match.channel.logo,
            programmeTitle = programme?.title ?: "${match.channel.name} · Replay",
            programmeStartMs = replayStart,
            programmeEndMs = replayStart + durationMin * 60_000L,
        )
    }

    /**
     * Provider VOD entries that look like recordings of this fixture ("Spain vs Austria…"),
     * from the SAME SQLite catalog index the TMDB matcher builds — no new fetches beyond
     * its lazy first build. Registered so tapping opens the native detail → play pipeline.
     */
    suspend fun findRecordings(fixture: RadarFixture): List<RecordingHit> {
        val start = fixture.startEpochMs ?: return emptyList()
        if (start > RadarTime.nowMs()) return emptyList()
        val homeTokens = teamTokens(fixture.home)
        val awayTokens = teamTokens(fixture.away)
        val eventTokens = teamTokens(fixture.event)
        val queries = buildList {
            homeTokens.firstOrNull()?.let(::add)
            awayTokens.firstOrNull()?.let(::add)
            if (isEmpty()) eventTokens.take(2).forEach(::add)
        }.distinct()
        if (queries.isEmpty()) return emptyList()

        XtreamRepository.ensureLoaded()
        // TMDB-based recording matching is an Xtream-only path (it needs the TMDB match index, which
        // M3U catalogs don't populate). M3U live channels still participate via assembleCandidates.
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled && it.sourceType != com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL }
        val hits = LinkedHashMap<String, RecordingHit>()
        for (account in accounts) {
            withTimeoutOrNull(INDEX_WAIT_MS) {
                XtreamTmdbResolver.ensureIndexed(account, MatchKind.MOVIE)
            }
            for (q in queries) {
                XtreamMatchIndex.searchByName(account.id, MatchKind.MOVIE, q, 30).forEach { item ->
                    val text = normalize(item.name)
                    if (!SportsRecordingMatchPolicy.accepts(homeTokens, awayTokens, eventTokens) { hits(text, it) }) {
                        return@forEach
                    }
                    val movie = com.nuvio.app.features.iptv.XtreamMovie(
                        streamId = item.sid,
                        name = item.name,
                        poster = item.poster,
                        categoryId = null,
                        rating = null,
                        // Source-correct: Stalker returns "" so the play route resolves a fresh
                        // create_link via resolveMovieUrl; Xtream returns the real URL.
                        streamUrl = com.nuvio.app.features.iptv.IptvClient.forAccount(account)
                            .movieStreamUrl(account, item.sid, item.ext ?: "mp4"),
                        tmdb = item.tmdb,
                        containerExtension = item.ext,
                    )
                    XtreamItemRegistry.registerMovie(account.id, movie)
                    val contentId = XtreamItemRegistry.vodId(account.id, item.sid)
                    hits.getOrPut(contentId) { RecordingHit(contentId, item.name, item.poster, account.name) }
                }
            }
            if (hits.size >= RECORDING_CAP) break
        }
        return hits.values.take(RECORDING_CAP)
    }

    /** Registers the match's channel so the play route can resolve its stream URL. */
    fun ensurePlayable(match: ChannelMatch) {
        if (XtreamItemRegistry.get(match.channel.contentId) != null) return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == match.channel.playlistId } ?: return
        XtreamItemRegistry.register(
            com.nuvio.app.features.iptv.XtreamResolvedItem(
                contentId = match.channel.contentId,
                accountId = account.id,
                kind = com.nuvio.app.features.iptv.XtreamKind.LIVE,
                name = match.channel.name,
                // Route through the source-correct client: Stalker/M3U return "" so the play
                // route falls through to the async create_link resolver; Xtream returns the
                // real URL. Hardcoding XtreamClient here fabricated a bogus URL for Stalker
                // that skipped create_link and failed to load.
                streamUrl = com.nuvio.app.features.iptv.IptvClient.forAccount(account)
                    .liveStreamUrl(account, match.channel.streamId),
                logo = match.channel.logo,
                streamType = "live",
            )
        )
    }

    // --- scoring (pure) --------------------------------------------------------

    private fun bestProgramme(
        programmes: List<XtreamProgram>,
        prepared: SportsEventMatchEngine.Prepared,
    ): Pair<XtreamProgram, SportsMatchEvidence>? = programmes.mapNotNull { p ->
        val evidence = SportsEventMatchEngine.programme(prepared, p.title, p.description, p.startMs, p.endMs)
        if (evidence.strength > 0) p to evidence else null
    }.maxByOrNull { SportsEventMatchEngine.rank(it.second.confidence) * 1000 + it.second.strength }

    private fun XtreamProgram.toSportsProgramme(): SportsProgramme =
        SportsProgramme(title, description, startMs, endMs)

    private fun normalize(s: String?): String = SportsEventMatchEngine.normalize(s)

    /**
     * Short single tokens must match on WORD BOUNDARIES — plain substring makes "epl" hit
     * "replay" and "wc" hit anything — while longer/multi-word keywords keep substring
     * semantics ("premier league" should hit "premier league tv").
     */
    private fun hits(normalizedText: String, keyword: String): Boolean =
        if (keyword.length < 5 && ' ' !in keyword) " $normalizedText ".contains(" $keyword ")
        else normalizedText.contains(keyword)

    private fun teamTokens(team: String?): List<String> =
        normalize(team).split(" ").filter { it.length > 2 && it !in STOP_TOKENS }
}
