package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/**
 * One open game screen's state holder. Resolves each block along the source chain (TheSportsDB via our backend,
 * then ESPN on the device), caching the last payload of every source so a tick that refreshes only the event still
 * re-assembles the blocks that live in the summary.
 *
 * Rules (BLOCK_CONTRACT.md): a miss hides only that block; nothing throws into the UI; the HEADER always comes
 * from the primary source for the game, so a cross-provider mislink cannot change the score shown; a source FAILURE
 * is never treated as "no data" (not remembered, retried next tick), while a source that ANSWERED with nothing is
 * remembered per game (negative cache, keyed by mapping version, expiring) so we do not re-ask every tick.
 */
internal class GameDetailSession(
    private val ref: GameRef,
    private val mappingProvider: () -> SportsMapping,
    private val tsdb: TsdbSource?,
    private val espn: EspnSource?,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val report: (Map<String, Any>) -> Unit = SportsDetailTelemetry::send,
    /** One line per tick (cumulative request counts, never URLs) — the evidence that live ticks hit the summary only. */
    private val debug: (String) -> Unit = { line -> runCatching { debugLog.d { line } } },
    /** Polymarket win chance via our backend; used only when the mapping's `killSwitch.market` is false. */
    private val market: MarketWinChanceSource? = null,
) : GameDetailSource {

    private val lock = Mutex()

    // Last good payloads per source.
    private var tsdbDetail: GameDetail? = null
    private var tsdbAnswer: SourceResult<*>? = null
    private var tsdbDisabled = false
    private val tsdbBlocks = LinkedHashMap<BlockType, GameBlock>()
    private var espnBase: JsonElement? = null          // the scoreboard event, read ONCE
    private var espnEventRoot: JsonElement? = null     // base + live overlay from the summary
    private var espnSummary: JsonElement? = null
    private var espnEventId: String? = ref.espnEventId
    private var espnEventStartMs: Long? = null
    private var tsdbAsOf: Long? = null
    /** ESPN failed recently: skip it until then (the backend may be perfectly healthy). */
    private var espnBackoffUntilMs = 0L
    private var marketCached: WinProbabilityBlock? = null
    private var marketAsOf: Long? = null
    private var marketBackoffUntilMs = 0L
    private var marketFinalFetched = false
    private var backendCalls = 0
    private var summaryCalls = 0
    private var scoreboardCalls = 0
    private var eventCalls = 0
    private var espnAsOf: Long? = null
    /** When (ms, our clock) the cached event / summary were fetched: the overlay lets the FRESHER one win. */
    private var eventAsOfMs = 0L
    private var summaryAsOfMs = 0L

    /** Base event + summary overlay. The summary only overwrites live keys while it is the fresher of the two. */
    private fun rebuildRoot() {
        val b = espnBase ?: return
        espnEventRoot = espnSummary?.let { EspnLiveOverlay.apply(b, it, baseIsFresher = eventAsOfMs > summaryAsOfMs) } ?: b
    }

    // Negative cache + telemetry de-dup.
    private val misses = mutableMapOf<String, Long>()
    private val reported = HashSet<String>()

    override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad = lock.withLock {
        try {
            run(fetchEvent, fetchSummary)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            DetailLoad.Failed(timedOut = false)
        }
    }

    private suspend fun run(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
        val mapping = mappingProvider()
        val version = mapping.version
        val league = mapping.leagueByTsdb(ref.leagueId)
        val profileKey = league?.profile
        val profile = profileKey?.let { mapping.profiles[it] }
        val sport = profileKey ?: "unknown"
        val asm = EspnAssembler(mapping)
        val durations = HashMap<String, Long>()
        var espnEventFailed: SourceResult.Failed? = null
        var espnSummaryFailed: SourceResult.Failed? = null
        val espnOn = espn != null && league?.espn != null && mapping.espnEnabledFor(ref.leagueId)
        val summaryEndpoint = league?.summary != false

        fun missKey(what: String) = "$version|$what"
        fun isMissed(what: String): Boolean = misses[missKey(what)]?.let { nowMs() - it < NEGATIVE_TTL_MS } == true
        fun miss(what: String) { misses[missKey(what)] = nowMs() }
        fun note(block: String, source: String, outcome: String) {
            if (reported.add("$sport|$block|$source|$outcome")) {
                report(SportsDetailTelemetry.properties(sport, block, source, outcome, durations[source] ?: 0L))
            }
        }
        fun outcomeOf(f: SourceResult.Failed) = if (f.timedOut) "timeout" else "failed"

        // 1. Primary: our backend's normalised game-detail (TheSportsDB). Delta rule: the backend is called on the
        //    first load and on the slow (summary) cadence ONLY — a live tick is the ESPN per-game summary alone.
        if (tsdb != null && ref.tsdbEventId != null && !tsdbDisabled && (fetchSummary || tsdbAnswer == null)) {
            val t0 = nowMs()
            backendCalls++
            val r = tsdb.detail(ref.tsdbEventId, ref.leagueId, backendBlocks(mapping, profile))
            durations["tsdb"] = nowMs() - t0
            tsdbAnswer = r
            when (r) {
                is SourceResult.Ok -> {
                    tsdbDetail = r.value
                    tsdbAsOf = nowMs()
                    // Replace per block: a block the backend omitted (a failed sub-call) keeps its last good value.
                    r.value.blocks.forEach { b -> blockTypeOf(b)?.let { tsdbBlocks[it] = b } }
                }
                is SourceResult.Empty -> tsdbDetail = null
                is SourceResult.Disabled -> { tsdbDisabled = true; tsdbDetail = null; tsdbBlocks.clear() }
                is SourceResult.Failed -> {}
            }
        }
        val tsdbPrimary = tsdb != null && ref.tsdbEventId != null && !tsdbDisabled

        // 2. Resolve the ESPN event ONCE (whole-day scoreboard), then keep it. Leagues with no summary endpoint
        //    (F1, UFC, tennis) have nothing else to poll, so they re-read the scoreboard on the SLOW cadence only.
        //    A miss (no hit in the queried scoreboards, pinned id or not) is remembered, so a wrong id cannot
        //    re-fetch whole-day scoreboards every tick.
        val needResolve = espnBase == null && !isMissed("espn|match")
        val needScoreboardRefresh = espnBase != null && !summaryEndpoint && fetchSummary
        var espnCallable = espnOn && nowMs() >= espnBackoffUntilMs
        var justResolved = false
        if (espnCallable && (needResolve || needScoreboardRefresh)) {
            val t0 = nowMs()
            val failure = resolveEspnEvent(mapping, league!!, ::miss) { scoreboardCalls++ }
            durations["espn"] = nowMs() - t0
            espnEventFailed = failure
            if (failure != null) espnBackoffUntilMs = nowMs() + ESPN_BACKOFF_MS
            if (failure == null && espnBase != null) { espnAsOf = nowMs(); eventAsOfMs = nowMs(); justResolved = true; rebuildRoot() }
            when {
                failure != null -> note("match", "espn", outcomeOf(failure))
                espnEventId == null -> note("match", "espn", "empty")
                else -> note("match", "espn", "loaded")
            }
        }

        // 2b. The live event tick: the single-event scoreboard (2-3 KB gzip) replaces the heavy summary as the 15 s signal. Not on
        //     the tick that just resolved the whole-day scoreboard (that answer is already fresh), not for leagues without a summary
        //     endpoint (their shapes differ), and only when the mapping has the template -- otherwise today's behaviour (below).
        val eventTemplate = mapping.espn.eventTemplate
        val eventTickViaSingleEvent = eventTemplate != null && summaryEndpoint
        if (espnCallable && fetchEvent && !justResolved && eventTickViaSingleEvent && espnEventId != null && espnBase != null) {
            val t0 = nowMs()
            eventCalls++
            when (val r = espn!!.event(league!!.espn!!, espnEventId!!)) {
                is SourceResult.Ok -> { espnBase = EspnLiveOverlay.mergeEvent(espnBase!!, r.value); eventAsOfMs = nowMs(); espnAsOf = nowMs(); rebuildRoot() }
                // The answer was "nothing": keep the last good event. Not a failure, not remembered.
                is SourceResult.Empty, is SourceResult.Disabled -> {}
                // A failed tick keeps the last good data, never fails the screen, and counts toward the existing ESPN backoff.
                is SourceResult.Failed -> espnBackoffUntilMs = nowMs() + ESPN_BACKOFF_MS
            }
            durations["espn"] = (durations["espn"] ?: 0L) + (nowMs() - t0)
            espnCallable = nowMs() >= espnBackoffUntilMs
        }

        // 3. Provisional state (to decide whether the summary is worth fetching).
        val baseHdr = if (espnOn && espnEventRoot != null && profileKey != null) asm.header(profileKey, espnEventRoot!!) else null
        val tsdbD = tsdbDetail.takeIf { tsdbPrimary }
        // No backend answer (failed / empty / disabled) is fine when ESPN resolved the event: ESPN is then the primary.
        if (tsdbPrimary && tsdbD == null && baseHdr == null) {
            return when (val a = tsdbAnswer) {
                is SourceResult.Failed -> DetailLoad.Failed(a.timedOut)
                else -> DetailLoad.Empty
            }
        }
        val provisional = tsdbD?.state ?: baseHdr?.state

        // 4. The summary: plays, stats, leaders, lineups, standings. With the single-event tick it rides the slow (60 s) cadence only
        //    (or when never fetched); a mapping without the template keeps the old rule -- the live tick IS the summary.
        val wantsSummary = espnOn && espnEventId != null && espnBase != null && summaryEndpoint && profile != null && provisional != null &&
            (provisional != GameState.UPCOMING || "lineup" in profile.blocks) && provisional != GameState.POSTPONED
        val summaryDue = if (eventTickViaSingleEvent) fetchSummary || espnSummary == null else fetchEvent || fetchSummary || espnSummary == null
        if (wantsSummary && espnCallable && summaryDue && !isMissed("espn|summary")) {
            val t0 = nowMs()
            summaryCalls++
            when (val r = espn!!.summary(league!!.espn!!, espnEventId!!)) {
                is SourceResult.Ok -> { espnSummary = r.value; summaryAsOfMs = nowMs(); rebuildRoot(); espnAsOf = nowMs() }
                is SourceResult.Empty, is SourceResult.Disabled -> miss("espn|summary")
                is SourceResult.Failed -> { espnSummaryFailed = r; espnBackoffUntilMs = nowMs() + ESPN_BACKOFF_MS }
            }
            durations["espn"] = (durations["espn"] ?: 0L) + (nowMs() - t0)
        }

        // 5. Header from the primary source. The ESPN header is computed AFTER the overlay so a live ESPN-primary
        //    game shows the live score.
        val espnHdr = if (espnOn && espnEventRoot != null && profileKey != null) asm.header(profileKey, espnEventRoot!!) else null
        val hdr: EspnHeader
        val primary: String
        if (tsdbD != null) {
            hdr = EspnHeader(tsdbD.header, tsdbD.state, tsdbD.live, tsdbD.startsAt, tsdbD.venue, null, null)
            primary = "tsdb"
            note("header", "tsdb", "loaded")
        } else {
            if (espnEventRoot == null || profileKey == null || espnHdr == null) {
                return if (espnEventFailed != null) DetailLoad.Failed(espnEventFailed.timedOut) else DetailLoad.Empty
            }
            hdr = espnHdr
            primary = "espn"
            note("header", "espn", "loaded")
        }
        // ESPN is fresher than the backend's ~1-2 min cache. When it resolved THIS game (both teams agree by name),
        // score, state and the live line come from ESPN; names, badges and venue stay the primary's. A name
        // mismatch (a bad pin) leaves the primary header untouched and ignores ESPN's score.
        val merged = if (primary == "tsdb" && espnHdr != null) mergeFreshEspn(hdr, espnHdr, mapping.aliases) else null
        val shownHeader = merged?.header ?: hdr.header
        val state = merged?.state ?: hdr.state
        val live: LiveInfo? = merged?.live ?: hdr.live

        // 6. Blocks along the chain.
        val blocks = ArrayList<GameBlock>()
        val sources = LinkedHashMap<String, String>()
        sources["header"] = primary
        // Profiles list the screen's blocks, but some (winProbability) only appear as a path spec: those join at the end.
        val baseOrder = profile?.let { p -> p.blocks + p.specs.keys.filter { it !in p.blocks && BlockType.byId(it) != null } }
            ?: listOf("stats", "timeline", "highlights")
        // The market fills win chance for sports/leagues ESPN has none for (NHL, soccer, college…), so it is asked for on any
        // two-sided game when the mapping switched it on.
        val order = if (market != null && mapping.marketEnabledFor(ref.leagueId) && ref.awayName != null && "winProbability" !in baseOrder) baseOrder + "winProbability" else baseOrder
        for (id in order) {
            val type = BlockType.byId(id) ?: continue
            // Owner decision: no broadcast block. A mapping or backend that still lists/sends one is simply ignored.
            if (type == BlockType.WATCH || type == BlockType.BROADCAST || !visible(type, state)) continue
            for (source in mapping.chainFor(type)) {
                val block: GameBlock? = when (source) {
                    "tsdb" -> if (tsdbD != null) tsdbBlocks[type].also { note(type.id, "tsdb", if (it == null) "empty" else "loaded") } else null
                    "espn" -> if (type == BlockType.WIN_PROBABILITY && state != GameState.LIVE) null
                        else espnBlock(type, asm, profileKey, espnOn, espnSummaryFailed, ::note, ::outcomeOf)?.let { b ->
                            if (type == BlockType.WIN_PROBABILITY && b is WinProbabilityBlock) withMarketExtras(b, mapping, state, fetchSummary, ::isMissed, ::miss, ::note, durations) else b
                        }
                    "market" -> if (type == BlockType.WIN_PROBABILITY) marketBlock(mapping, state, fetchSummary, ::isMissed, ::miss, ::note, durations) else null
                    else -> null
                }
                if (block != null) { blocks += block; sources[type.id] = if (source == "market") "polymarket" else source; break }
            }
        }

        debug("tick fetchEvent=$fetchEvent fetchSummary=$fetchSummary | backend=$backendCalls summary=$summaryCalls event=$eventCalls scoreboard=$scoreboardCalls")
        return DetailLoad.Loaded(
            GameDetail(
                gameId = ref.gameId,
                sport = league?.sport ?: tsdbD?.sport.orEmpty(),
                leagueId = ref.leagueId,
                leagueName = league?.name ?: tsdbD?.leagueName.orEmpty(),
                round = tsdbD?.round,
                venue = hdr.venue ?: tsdbD?.venue,
                startsAt = hdr.startsAt ?: Iso.format(ref.startsAtMs),
                state = state,
                header = shownHeader,
                live = live,
                blocks = blocks,
                sources = sources,
                asOf = buildMap {
                    tsdbAsOf?.let { put("tsdb", it) }
                    espnAsOf?.let { put("espn", it) }
                    marketAsOf?.let { put("polymarket", it) }
                },
            ),
        )
    }

    /** The block ids worth asking the backend for: what the profile shows and the chain lets TheSportsDB supply (never broadcast/watch). */
    private fun backendBlocks(mapping: SportsMapping, profile: ProfileSpec?): Set<String>? {
        val ids = profile?.blocks.orEmpty().mapNotNull { BlockType.byId(it) }
            .filter { it != BlockType.BROADCAST && it != BlockType.WATCH && "tsdb" in mapping.chainFor(it) }.map { it.id }
        return ids.takeIf { it.isNotEmpty() }?.toSet()
    }

    private fun mergeFreshEspn(primary: EspnHeader, espn: EspnHeader, aliases: Map<String, String>): EspnHeader? {
        fun side(p: Side, e: Side) = p.copy(
            score = e.score ?: p.score, short = p.short ?: e.short,
            // Colours and rank ride along under the same "both teams agree by name" guard that protects the score.
            color = e.color ?: p.color, altColor = e.altColor ?: p.altColor, rank = e.rank ?: p.rank,
        )
        val header: Header = when (val p = primary.header) {
            is TeamsHeader -> {
                val e = espn.header as? TeamsHeader ?: return null
                if (!TeamNames.sameTeam(p.home.name, e.home.name, aliases) || !TeamNames.sameTeam(p.away.name, e.away.name, aliases)) return null
                TeamsHeader(side(p.home, e.home), side(p.away, e.away))
            }
            is VersusHeader -> {
                val e = espn.header as? VersusHeader ?: return null
                if (!TeamNames.sameTeam(p.a.name, e.a.name, aliases) || !TeamNames.sameTeam(p.b.name, e.b.name, aliases)) return null
                VersusHeader(side(p.a, e.a), side(p.b, e.b))
            }
            else -> return null
        }
        return primary.copy(header = header, state = espn.state, live = espn.live)
    }

    /** What belongs on screen in each state: live-only blocks stay hidden after the whistle, score blocks before it. */
    private fun visible(type: BlockType, state: GameState): Boolean = when (state) {
        GameState.LIVE -> true
        GameState.FINAL -> type != BlockType.SITUATION && type != BlockType.FIELD   // win chance: only the market's momentum history survives the whistle (see marketBlock)
        GameState.UPCOMING -> type in UPCOMING_BLOCKS
        GameState.POSTPONED -> false
    }

    /** Finds (or matches) the ESPN event on a scoreboard. Returns a Failed when ESPN could not be asked; null otherwise. */
    private suspend fun resolveEspnEvent(mapping: SportsMapping, league: LeagueEntry, miss: (String) -> Unit, counted: () -> Unit): SourceResult.Failed? {
        val path = league.espn ?: return null
        val known = espnEventId ?: mapping.eventOverride(ref.tsdbEventId)
        val anchor = espnEventStartMs ?: ref.startsAtMs
        val windowMs = mapping.match.dateWindowHours * 3_600_000L
        val dates = listOfNotNull(
            Iso.yyyymmdd(anchor),
            Iso.yyyymmdd(anchor - windowMs).takeIf { it != Iso.yyyymmdd(anchor) } ?: Iso.yyyymmdd(anchor + windowMs).takeIf { it != Iso.yyyymmdd(anchor) },
        ).take(if (known != null && espnEventStartMs != null) 1 else 2)
        for (d in dates) {
            counted()
            val sb = when (val r = espn!!.scoreboard(path, d)) {
                is SourceResult.Ok -> r.value
                is SourceResult.Empty, is SourceResult.Disabled -> continue
                is SourceResult.Failed -> return r
            }
            val events = SportsPath.read(sb, mapping.common.event ?: "events[*]")
            fun rawById(id: String) = events.firstOrNull { SportsPath.text(it, mapping.common.id ?: "id") == id }
            if (known != null) {
                rawById(known)?.let { espnEventId = known; espnBase = it; espnEventStartMs = Iso.parseMs(SportsPath.text(it, "date")); return null }
            } else {
                val target = MatchTarget(ref.tsdbEventId ?: ref.gameId, ref.homeName, ref.awayName, ref.startsAtMs)
                val cand = EventMatcher(mapping).match(target, EventMatcher.candidatesFrom(sb, mapping))
                val raw = cand?.let { rawById(it.id) }
                if (cand != null && raw != null) {
                    espnEventId = cand.id; espnBase = raw; espnEventStartMs = cand.startsAtMs
                    return null
                }
            }
        }
        miss("espn|match")
        return null
    }

    /**
     * The market's win-chance response, from OUR backend (Polymarket stays server-side). Fetched on the first tick and then only
     * on the slow (summary) cadence — never a separate poll; a final game fetches once. A miss (404 / disabled) is remembered per
     * game; a failure backs off. Returns the last good response, or null.
     */
    private suspend fun marketData(
        mapping: SportsMapping,
        state: GameState,
        fetchSummary: Boolean,
        isMissed: (String) -> Boolean,
        miss: (String) -> Unit,
        note: (String, String, String) -> Unit,
        durations: MutableMap<String, Long>,
    ): WinProbabilityBlock? {
        val src = market ?: return null
        val away = ref.awayName ?: return null
        if (!mapping.marketEnabledFor(ref.leagueId) || state == GameState.POSTPONED) return null
        val key = "market|winProbability"
        val due = (marketCached == null || fetchSummary) && !(state == GameState.FINAL && marketFinalFetched)
        if (due && !isMissed(key) && nowMs() >= marketBackoffUntilMs) {
            val t0 = nowMs()
            val r = src.winChance(MarketRequest(ref.gameId, ref.leagueId, ref.homeName, away, Iso.format(ref.startsAtMs)), history = true)
            durations["polymarket"] = nowMs() - t0
            when (r) {
                is SourceResult.Ok -> {
                    val w = r.value
                    marketCached = WinProbabilityBlock(w.home, w.source, w.attribution, w.draw, w.away, w.history, w.storylines)
                    marketAsOf = w.asOfMs ?: nowMs()   // the SERVER's time: a stale served value then shows 'Updated Xm ago'
                    if (state == GameState.FINAL) marketFinalFetched = true
                }
                is SourceResult.Empty, is SourceResult.Disabled -> { miss(key); marketCached = null; note("winProbability", "polymarket", "empty"); return null }
                is SourceResult.Failed -> { marketBackoffUntilMs = nowMs() + ESPN_BACKOFF_MS; note("winProbability", "polymarket", if (r.timedOut) "timeout" else "failed") }
            }
        }
        return marketCached?.copy(asOfMs = marketAsOf)
    }

    /** The headline from the market (ESPN has none): a final game shows it only with a real momentum history. */
    private suspend fun marketBlock(
        mapping: SportsMapping, state: GameState, fetchSummary: Boolean,
        isMissed: (String) -> Boolean, miss: (String) -> Unit, note: (String, String, String) -> Unit, durations: MutableMap<String, Long>,
    ): GameBlock? {
        val b = marketData(mapping, state, fetchSummary, isMissed, miss, note, durations) ?: return null
        if (state == GameState.FINAL && b.history.size < 2) return null
        note("winProbability", "polymarket", "loaded")
        return b
    }

    /**
     * ESPN stays the headline when it has one (no source flip-flop), but the market is ALWAYS asked as well — same slow cadence,
     * server-cached — for its storylines and, since ESPN has no series, the chart. The extras are credited separately.
     */
    private suspend fun withMarketExtras(
        headline: WinProbabilityBlock, mapping: SportsMapping, state: GameState, fetchSummary: Boolean,
        isMissed: (String) -> Boolean, miss: (String) -> Unit, note: (String, String, String) -> Unit, durations: MutableMap<String, Long>,
    ): WinProbabilityBlock {
        val m = marketData(mapping, state, fetchSummary, isMissed, miss, note, durations) ?: return headline
        note("winProbability", "polymarket", "loaded")
        return headline.copy(
            history = headline.history.ifEmpty { m.history },
            storylines = m.storylines,
            extrasAttribution = m.attribution ?: "via Polymarket",
            asOfMs = m.asOfMs,
        )
    }

    private fun espnBlock(
        type: BlockType,
        asm: EspnAssembler,
        profileKey: String?,
        espnOn: Boolean,
        summaryFailed: SourceResult.Failed?,
        note: (String, String, String) -> Unit,
        outcomeOf: (SourceResult.Failed) -> String,
    ): GameBlock? {
        val event = espnEventRoot
        if (!espnOn || event == null || profileKey == null) return null
        val needed = asm.rootNeeded(type, profileKey) ?: return null
        if (needed == "summary" && espnSummary == null) {
            if (summaryFailed != null) note(type.id, "espn", outcomeOf(summaryFailed))
            return null
        }
        // ESPN blocks are re-assembled from the cached payloads every tick (no request), so no negative cache here:
        // a timeline that is empty at minute 1 must appear at minute 20.
        val block = asm.assemble(type, profileKey, event, espnSummary)
        note(type.id, "espn", if (block == null) "empty" else "loaded")
        return block
    }

    private companion object {
        val debugLog = Logger.withTag("SportsDetail")
        const val NEGATIVE_TTL_MS = 5L * 60 * 1000
        const val ESPN_BACKOFF_MS = 30_000L
        val UPCOMING_BLOCKS = setOf(BlockType.WIN_PROBABILITY, BlockType.FORM, BlockType.LINEUP, BlockType.RESULTS, BlockType.NEXT, BlockType.STANDINGS, BlockType.STANDINGS_TABLE, BlockType.HIGHLIGHTS)
    }
}

internal fun blockTypeOf(b: GameBlock): BlockType? = when (b) {
    is com.nuvio.app.features.sportsdetail.api.SituationBlock -> BlockType.SITUATION
    is com.nuvio.app.features.sportsdetail.api.WatchBlock -> BlockType.WATCH
    is com.nuvio.app.features.sportsdetail.api.BroadcastBlock -> BlockType.BROADCAST
    is com.nuvio.app.features.sportsdetail.api.LinescoreBlock -> BlockType.LINESCORE
    is com.nuvio.app.features.sportsdetail.api.StatsBlock -> BlockType.STATS
    is com.nuvio.app.features.sportsdetail.api.LeadersBlock -> BlockType.LEADERS
    is com.nuvio.app.features.sportsdetail.api.TimelineBlock -> BlockType.TIMELINE
    is com.nuvio.app.features.sportsdetail.api.LineupBlock -> BlockType.LINEUP
    is com.nuvio.app.features.sportsdetail.api.ResultsBlock -> BlockType.RESULTS
    is com.nuvio.app.features.sportsdetail.api.StandingsBlock -> BlockType.STANDINGS
    is com.nuvio.app.features.sportsdetail.api.FormBlock -> BlockType.FORM
    is com.nuvio.app.features.sportsdetail.api.HighlightsBlock -> BlockType.HIGHLIGHTS
    is com.nuvio.app.features.sportsdetail.api.NextBlock -> BlockType.NEXT
    is com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock -> BlockType.WIN_PROBABILITY
    is com.nuvio.app.features.sportsdetail.api.FootballFieldBlock -> BlockType.FIELD
    is com.nuvio.app.features.sportsdetail.api.SoccerStripBlock -> BlockType.SOCCER_STRIP
    is com.nuvio.app.features.sportsdetail.api.PlaysBlock -> BlockType.PLAYS
    is com.nuvio.app.features.sportsdetail.api.StandingsTableBlock -> BlockType.STANDINGS_TABLE
    else -> null
}
