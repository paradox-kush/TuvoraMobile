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
import kotlinx.serialization.json.JsonObject
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
    private var tsdbRow: JsonObject? = null
    private var tsdbEventAnswer: SourceResult<*>? = null
    private val tsdbLookups = mutableMapOf<TsdbLookup, JsonObject>()
    private var espnEventRoot: JsonElement? = null
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
        var tsdbFailed: SourceResult.Failed? = null
        var espnEventFailed: SourceResult.Failed? = null
        var espnSummaryFailed: SourceResult.Failed? = null
        val tsdbPrimary = ref.tsdbEventId != null && tsdb != null
        val espnOn = espn != null && league?.espn != null && mapping.espnEnabledFor(ref.leagueId)

        fun missKey(what: String) = "$version|$what"
        fun isMissed(what: String): Boolean = misses[missKey(what)]?.let { nowMs() - it < NEGATIVE_TTL_MS } == true
        fun miss(what: String) { misses[missKey(what)] = nowMs() }
        fun note(block: String, source: String, outcome: String) {
            if (reported.add("$sport|$block|$source|$outcome")) {
                report(SportsDetailTelemetry.properties(sport, block, source, outcome, durations[source] ?: 0L))
            }
        }
        fun outcomeOf(f: SourceResult.Failed) = if (f.timedOut) "timeout" else "failed"

        // 1. Primary event row from TheSportsDB.
        if (tsdbPrimary && (fetchEvent || tsdbEventAnswer == null)) {
            val t0 = nowMs()
            val r = tsdb!!.event(ref.tsdbEventId!!)
            durations["tsdb"] = nowMs() - t0
            tsdbEventAnswer = r
            when (r) {
                is SourceResult.Ok -> { tsdbRow = r.value; tsdbAsOf = nowMs() }
                is SourceResult.Empty -> tsdbRow = null
                is SourceResult.Failed -> tsdbFailed = r
            }
        }

        // 2. The matching ESPN event.
        if (espnOn && (fetchEvent || espnEventRoot == null) && !(espnEventId == null && isMissed("espn|match"))) {
            val t0 = nowMs()
            val failure = resolveEspnEvent(mapping, league!!, ::miss)
            durations["espn"] = nowMs() - t0
            espnEventFailed = failure
            if (failure == null && espnEventRoot != null) espnAsOf = nowMs()
            if (failure == null && espnEventId == null) note("match", "espn", "empty") else if (failure == null) note("match", "espn", "loaded")
            if (failure != null) note("match", "espn", outcomeOf(failure))
        }

        // 3. Header from the primary source.
        val espnHdr = if (espnOn && espnEventRoot != null && profileKey != null) asm.header(profileKey, espnEventRoot!!) else null
        val hdr: EspnHeader
        val primary: String
        if (tsdbPrimary) {
            val row = tsdbRow
            if (row == null) {
                return when (val a = tsdbEventAnswer) {
                    is SourceResult.Failed -> DetailLoad.Failed(a.timedOut)
                    else -> DetailLoad.Empty
                }
            }
            hdr = TsdbAssembler.header(row, profileKey) ?: return DetailLoad.Empty
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

        // 4. ESPN summary, only when something on this screen can use it.
        val wantsSummary = espnOn && espnEventId != null && league?.summary != false && profile != null &&
            (state != GameState.UPCOMING || "lineup" in profile.blocks) && state != GameState.POSTPONED
        if (wantsSummary && (fetchSummary || espnSummary == null) && !isMissed("espn|summary")) {
            val t0 = nowMs()
            when (val r = espn!!.summary(league!!.espn!!, espnEventId!!)) {
                is SourceResult.Ok -> { espnSummary = r.value; espnAsOf = nowMs() }
                is SourceResult.Empty -> miss("espn|summary")
                is SourceResult.Failed -> espnSummaryFailed = r
            }
            durations["espn"] = (durations["espn"] ?: 0L) + (nowMs() - t0)
        }

        // 5. Blocks along the chain.
        val blocks = ArrayList<GameBlock>()
        val sources = LinkedHashMap<String, String>()
        sources["header"] = primary
        val fetchedLookups = HashSet<TsdbLookup>()
        // Profiles list the screen's blocks, but some (winProbability) only appear as a path spec: those join at the end.
        val order = profile?.let { p -> p.blocks + p.specs.keys.filter { it !in p.blocks && BlockType.byId(it) != null } }
            ?: listOf("broadcast", "stats", "timeline", "highlights")
        for (id in order) {
            val type = BlockType.byId(id) ?: continue
            if (type == BlockType.WATCH || !visible(type, state)) continue
            for (source in mapping.chainFor(type)) {
                val block: GameBlock? = when (source) {
                    "tsdb" -> tsdbBlock(type, tsdbPrimary, fetchSummary, fetchedLookups, durations, ::isMissed, ::miss, ::note, ::outcomeOf)
                    "espn" -> espnBlock(type, asm, profileKey, espnOn, espnSummaryFailed, ::note, ::outcomeOf)
                    else -> null
                }
                if (block != null) { blocks += block; sources[type.id] = source; break }
            }
        }

        val row = tsdbRow
        return DetailLoad.Loaded(
            GameDetail(
                gameId = ref.gameId,
                sport = league?.sport ?: row?.let { SportsPath.text(it, "strSport") }.orEmpty(),
                leagueId = ref.leagueId,
                leagueName = league?.name ?: row?.let { SportsPath.text(it, "strLeague") }.orEmpty(),
                round = row?.let(TsdbAssembler::round),
                venue = hdr.venue ?: row?.let { SportsPath.text(it, "strVenue") },
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
                is SourceResult.Empty -> continue
                is SourceResult.Failed -> return r
            }
            val events = SportsPath.read(sb, mapping.common.event ?: "events[*]")
            fun rawById(id: String) = events.firstOrNull { SportsPath.text(it, mapping.common.id ?: "id") == id }
            if (known != null) {
                rawById(known)?.let { espnEventId = known; espnEventRoot = it; espnEventStartMs = Iso.parseMs(SportsPath.text(it, "date")); return null }
            } else {
                val target = MatchTarget(ref.tsdbEventId ?: ref.gameId, ref.homeName, ref.awayName, ref.startsAtMs)
                val cand = EventMatcher(mapping).match(target, EventMatcher.candidatesFrom(sb, mapping))
                val raw = cand?.let { rawById(it.id) }
                if (cand != null && raw != null) {
                    espnEventId = cand.id; espnEventRoot = raw; espnEventStartMs = cand.startsAtMs
                    return null
                }
            }
        }
        if (known == null) miss("espn|match")
        return null
    }

    private suspend fun tsdbBlock(
        type: BlockType,
        tsdbPrimary: Boolean,
        fetchSummary: Boolean,
        fetchedLookups: MutableSet<TsdbLookup>,
        durations: MutableMap<String, Long>,
        isMissed: (String) -> Boolean,
        miss: (String) -> Unit,
        note: (String, String, String) -> Unit,
        outcomeOf: (SourceResult.Failed) -> String,
    ): GameBlock? {
        val row = tsdbRow
        if (!tsdbPrimary || row == null) return null
        val key = "tsdb|${type.id}"
        if (isMissed(key)) return null
        val kind = when (type) {
            BlockType.BROADCAST -> TsdbLookup.TV
            BlockType.STATS -> TsdbLookup.STATS
            BlockType.TIMELINE -> TsdbLookup.TIMELINE
            else -> null
        }
        if (kind != null && (tsdbLookups[kind] == null || (fetchSummary && kind !in fetchedLookups))) {
            fetchedLookups += kind
            val t0 = nowMs()
            val r = tsdb!!.lookup(ref.tsdbEventId!!, kind)
            durations["tsdb"] = (durations["tsdb"] ?: 0L) + (nowMs() - t0)
            when (r) {
                is SourceResult.Ok -> { tsdbLookups[kind] = r.value; tsdbAsOf = nowMs() }
                is SourceResult.Empty -> { miss(key); note(type.id, "tsdb", "empty"); return null }
                is SourceResult.Failed -> { note(type.id, "tsdb", outcomeOf(r)); if (tsdbLookups[kind] == null) return null }
            }
        }
        val block = TsdbAssembler.block(type, row, kind?.let { tsdbLookups[it] })
        if (block == null) { miss(key); note(type.id, "tsdb", "empty") } else note(type.id, "tsdb", "loaded")
        return block
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
