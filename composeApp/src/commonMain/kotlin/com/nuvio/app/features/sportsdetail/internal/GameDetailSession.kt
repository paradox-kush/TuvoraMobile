package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.VersusHeader
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
    private var espnAsOf: Long? = null

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
            val r = tsdb.detail(ref.tsdbEventId, ref.leagueId, null)
            durations["tsdb"] = nowMs() - t0
            tsdbAnswer = r
            when (r) {
                is SourceResult.Ok -> {
                    tsdbDetail = r.value
                    tsdbAsOf = nowMs()
                    tsdbBlocks.clear()
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
        if (espnOn && (needResolve || needScoreboardRefresh)) {
            val t0 = nowMs()
            val failure = resolveEspnEvent(mapping, league!!, ::miss)
            durations["espn"] = nowMs() - t0
            espnEventFailed = failure
            if (failure == null && espnBase != null) { espnAsOf = nowMs(); espnEventRoot = espnSummary?.let { EspnLiveOverlay.apply(espnBase!!, it) } ?: espnBase }
            when {
                failure != null -> note("match", "espn", outcomeOf(failure))
                espnEventId == null -> note("match", "espn", "empty")
                else -> note("match", "espn", "loaded")
            }
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

        // 4. Live tick = the per-game summary only (carries score + clock + everything the blocks need).
        val wantsSummary = espnOn && espnEventId != null && espnBase != null && summaryEndpoint && profile != null && provisional != null &&
            (provisional != GameState.UPCOMING || "lineup" in profile.blocks) && provisional != GameState.POSTPONED
        if (wantsSummary && (fetchEvent || fetchSummary || espnSummary == null) && !isMissed("espn|summary")) {
            val t0 = nowMs()
            when (val r = espn!!.summary(league!!.espn!!, espnEventId!!)) {
                is SourceResult.Ok -> { espnSummary = r.value; espnEventRoot = EspnLiveOverlay.apply(espnBase!!, r.value); espnAsOf = nowMs() }
                is SourceResult.Empty, is SourceResult.Disabled -> miss("espn|summary")
                is SourceResult.Failed -> espnSummaryFailed = r
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
        val state = hdr.state

        // The live line may come from ESPN (richer clock) only when ESPN agrees on the score — never across a mislink.
        val live: LiveInfo? = if (primary == "tsdb" && espnHdr != null && sameScore(hdr, espnHdr)) espnHdr.live else hdr.live

        // 6. Blocks along the chain.
        val blocks = ArrayList<GameBlock>()
        val sources = LinkedHashMap<String, String>()
        sources["header"] = primary
        // Profiles list the screen's blocks, but some (winProbability) only appear as a path spec: those join at the end.
        val order = profile?.let { p -> p.blocks + p.specs.keys.filter { it !in p.blocks && BlockType.byId(it) != null } }
            ?: listOf("broadcast", "stats", "timeline", "highlights")
        for (id in order) {
            val type = BlockType.byId(id) ?: continue
            if (type == BlockType.WATCH || !visible(type, state)) continue
            for (source in mapping.chainFor(type)) {
                val block: GameBlock? = when (source) {
                    "tsdb" -> if (tsdbD != null) tsdbBlocks[type].also { note(type.id, "tsdb", if (it == null) "empty" else "loaded") } else null
                    "espn" -> espnBlock(type, asm, profileKey, espnOn, espnSummaryFailed, ::note, ::outcomeOf)
                    else -> null
                }
                if (block != null) { blocks += block; sources[type.id] = source; break }
            }
        }

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
                header = hdr.header,
                live = live,
                blocks = blocks,
                sources = sources,
                asOf = buildMap {
                    tsdbAsOf?.let { put("tsdb", it) }
                    espnAsOf?.let { put("espn", it) }
                },
            ),
        )
    }

    private fun sameScore(a: EspnHeader, b: EspnHeader): Boolean = when (val x = a.header) {
        is TeamsHeader -> (b.header as? TeamsHeader)?.let { y -> x.home.score != null && x.home.score == y.home.score && x.away.score == y.away.score } == true
        is VersusHeader -> (b.header as? VersusHeader)?.let { y -> x.a.score != null && x.a.score == y.a.score && x.b.score == y.b.score } == true
        else -> true
    }

    /** What belongs on screen in each state: live-only blocks stay hidden after the whistle, score blocks before it. */
    private fun visible(type: BlockType, state: GameState): Boolean = when (state) {
        GameState.LIVE -> true
        GameState.FINAL -> type != BlockType.SITUATION && type != BlockType.WIN_PROBABILITY
        GameState.UPCOMING -> type in UPCOMING_BLOCKS
        GameState.POSTPONED -> type == BlockType.BROADCAST
    }

    /** Finds (or matches) the ESPN event on a scoreboard. Returns a Failed when ESPN could not be asked; null otherwise. */
    private suspend fun resolveEspnEvent(mapping: SportsMapping, league: LeagueEntry, miss: (String) -> Unit): SourceResult.Failed? {
        val path = league.espn ?: return null
        val known = espnEventId ?: mapping.eventOverrides["tsdb:${ref.tsdbEventId}"]
        val anchor = espnEventStartMs ?: ref.startsAtMs
        val windowMs = mapping.match.dateWindowHours * 3_600_000L
        val dates = listOfNotNull(
            Iso.yyyymmdd(anchor),
            Iso.yyyymmdd(anchor - windowMs).takeIf { it != Iso.yyyymmdd(anchor) } ?: Iso.yyyymmdd(anchor + windowMs).takeIf { it != Iso.yyyymmdd(anchor) },
        ).take(if (known != null && espnEventStartMs != null) 1 else 2)
        for (d in dates) {
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
        const val NEGATIVE_TTL_MS = 5L * 60 * 1000
        val UPCOMING_BLOCKS = setOf(BlockType.BROADCAST, BlockType.FORM, BlockType.LINEUP, BlockType.RESULTS, BlockType.NEXT, BlockType.STANDINGS, BlockType.HIGHLIGHTS)
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
    else -> null
}
