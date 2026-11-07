package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.MappingStorage
import com.nuvio.app.features.sportsdetail.api.StoredMapping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** The block types a profile can list. [id] is the mapping/contract name. */
internal enum class BlockType(val id: String) {
    SITUATION("situation"), WATCH("watch"), BROADCAST("broadcast"), LINESCORE("linescore"), STATS("stats"),
    LEADERS("leaders"), TIMELINE("timeline"), LINEUP("lineup"), RESULTS("results"), STANDINGS("standings"),
    FORM("form"), HIGHLIGHTS("highlights"), NEXT("next"), WIN_PROBABILITY("winProbability");

    companion object {
        fun byId(id: String): BlockType? = entries.firstOrNull { it.id == id }
    }
}

internal data class KillSwitch(val espn: Boolean, val sports: Set<String>, val leagues: Set<String>)

internal data class EspnSourceConfig(
    val scoreboardTemplate: String,
    val summaryTemplate: String,
    val liveMs: Long,
    val summaryMs: Long,
    val minMs: Long,
    val deadlineMs: Long,
)

internal data class LeagueEntry(
    val tsdb: String,
    val name: String,
    val sport: String,
    val espn: String?,
    val profile: String,
    /** false = this league has no ESPN summary endpoint (F1, UFC, tennis). */
    val summary: Boolean,
)

internal data class MatchConfig(val dateWindowHours: Int, val minTeamsMatching: Int)

internal data class TeamPaths(val name: String, val short: String?, val badge: String?, val score: String?, val record: String?, val form: String?)

internal data class CommonPaths(
    val event: String?, val id: String?, val state: String, val detail: String?, val clock: String?, val period: String?,
    val home: String, val away: String, val team: TeamPaths, val broadcast: String?, val venue: String?,
)

/** One block's path spec, kept as the raw JSON object so remote mappings may add keys without a client release. */
internal class BlockSpec(val raw: JsonObject) {
    val from: String? get() = str("from")
    val path: String? get() = str("path")
    fun str(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun int(key: String): Int? = (raw[key] as? JsonPrimitive)?.content?.toIntOrNull()
    fun strings(key: String): List<String> = (raw[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
}

internal data class ProfileSpec(
    val key: String,
    val blocks: List<String>,
    val periodLabel: String?,
    val headerKind: String?,
    val specs: Map<String, BlockSpec>,
) {
    fun spec(type: BlockType): BlockSpec? = specs[type.id]
}

internal data class SportsMapping(
    val schemaVersion: Int,
    val version: String,
    val killSwitch: KillSwitch,
    val espn: EspnSourceConfig,
    val states: Map<String, String>,
    val leagues: List<LeagueEntry>,
    val aliases: Map<String, String>,
    val eventOverrides: Map<String, String>,
    val match: MatchConfig,
    val common: CommonPaths,
    val profiles: Map<String, ProfileSpec>,
    /** Optional per-block source order override: block id -> ["tsdb","espn"]. */
    val chain: Map<String, List<String>>,
) {
    fun leagueByTsdb(id: String): LeagueEntry? = leagues.firstOrNull { it.tsdb == id }

    /** Remote kill switches: ESPN as a whole, per sport (matches the league's sport OR its profile), per league. */
    fun espnEnabledFor(leagueTsdbId: String): Boolean {
        if (killSwitch.espn) return false
        val league = leagueByTsdb(leagueTsdbId) ?: return false
        if (league.espn.isNullOrBlank()) return false
        if (leagueTsdbId in killSwitch.leagues) return false
        val off = killSwitch.sports.map { it.lowercase() }
        if (league.sport.lowercase() in off || league.profile.lowercase() in off) return false
        return true
    }

    fun profileFor(leagueTsdbId: String): ProfileSpec? = leagueByTsdb(leagueTsdbId)?.let { profiles[it.profile] }

    /** Source order for [type]: the mapping's override, else the default (TheSportsDB first where it has data). */
    fun chainFor(type: BlockType): List<String> = chain[type.id]?.takeIf { it.isNotEmpty() } ?: when (type) {
        BlockType.BROADCAST, BlockType.STATS, BlockType.TIMELINE -> listOf("tsdb", "espn")
        BlockType.HIGHLIGHTS -> listOf("tsdb")
        else -> listOf("espn")
    }
}

internal object SportsMappingParser {
    sealed interface ParseResult {
        data class Ok(val mapping: SportsMapping) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ParseResult = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return ParseResult.Invalid("not an object")
        ParseResult.Ok(build(root))
    } catch (e: InvalidMapping) {
        ParseResult.Invalid(e.message ?: "invalid")
    } catch (e: Throwable) {
        ParseResult.Invalid("unparseable: ${e::class.simpleName}")
    }

    private class InvalidMapping(message: String) : RuntimeException(message)

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.strSet(): Set<String> = (this as? JsonArray)?.mapNotNull { it.s() }?.toSet().orEmpty()

    private fun build(r: JsonObject): SportsMapping {
        val schema = (r["schemaVersion"] as? JsonPrimitive)?.longOrNull?.toInt() ?: throw InvalidMapping("no schemaVersion")
        val version = r["version"].s().orEmpty()
        val kill = r["killSwitch"].obj()
        val espnSrc = r["sources"].obj()?.get("espn").obj() ?: throw InvalidMapping("no sources.espn")
        val refresh = espnSrc["refresh"].obj()
        fun ms(key: String, def: Long) = (refresh?.get(key) as? JsonPrimitive)?.longOrNull ?: def
        val cm = r["common"].obj() ?: throw InvalidMapping("no common")
        val team = cm["team"].obj() ?: throw InvalidMapping("no common.team")
        val leagues = (r["leagues"] as? JsonArray ?: throw InvalidMapping("no leagues")).mapNotNull { e ->
            val l = e.obj() ?: return@mapNotNull null
            LeagueEntry(
                tsdb = l["tsdb"].s() ?: return@mapNotNull null,
                name = l["name"].s().orEmpty(),
                sport = l["sport"].s().orEmpty(),
                espn = l["espn"].s(),
                profile = l["profile"].s() ?: return@mapNotNull null,
                summary = (l["summary"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        val profiles = r["profiles"].obj()?.mapNotNull { (k, v) ->
            val p = v.obj() ?: return@mapNotNull null
            val specs = p.mapNotNull { (sk, sv) -> sv.obj()?.let { sk to BlockSpec(it) } }.toMap()
            k to ProfileSpec(
                key = k,
                blocks = (p["blocks"] as? JsonArray)?.mapNotNull { it.s() }.orEmpty(),
                periodLabel = p["periodLabel"].s(),
                headerKind = p["headerKind"].s(),
                specs = specs,
            )
        }?.toMap().orEmpty()
        val aliasTeams = r["aliases"].obj()?.get("teams").obj()?.mapNotNull { (k, v) -> v.s()?.let { k to it } }?.toMap().orEmpty()
        val overrides = r["eventOverrides"].obj()?.mapNotNull { (k, v) -> v.s()?.let { k to it } }?.toMap().orEmpty()
        val match = r["match"].obj()
        return SportsMapping(
            schemaVersion = schema,
            version = version,
            killSwitch = KillSwitch(
                espn = (kill?.get("espn") as? JsonPrimitive)?.booleanOrNull ?: false,
                sports = kill?.get("sports").strSet(),
                leagues = kill?.get("leagues").strSet(),
            ),
            espn = EspnSourceConfig(
                scoreboardTemplate = espnSrc["scoreboard"].s() ?: throw InvalidMapping("no espn.scoreboard"),
                summaryTemplate = espnSrc["summary"].s() ?: throw InvalidMapping("no espn.summary"),
                liveMs = ms("liveMs", 15_000L), summaryMs = ms("summaryMs", 60_000L), minMs = ms("minMs", 6_000L),
                deadlineMs = (espnSrc["deadlineMs"] as? JsonPrimitive)?.longOrNull ?: 8_000L,
            ),
            states = r["states"].obj()?.mapNotNull { (k, v) -> v.s()?.let { k to it } }?.toMap().orEmpty(),
            leagues = leagues,
            aliases = aliasTeams,
            eventOverrides = overrides,
            match = MatchConfig(
                dateWindowHours = (match?.get("dateWindowHours") as? JsonPrimitive)?.longOrNull?.toInt() ?: 6,
                minTeamsMatching = (match?.get("minTeamsMatching") as? JsonPrimitive)?.longOrNull?.toInt() ?: 2,
            ),
            common = CommonPaths(
                event = cm["event"].s(), id = cm["id"].s(),
                state = cm["state"].s() ?: throw InvalidMapping("no common.state"),
                detail = cm["detail"].s(), clock = cm["clock"].s(), period = cm["period"].s(),
                home = cm["home"].s() ?: throw InvalidMapping("no common.home"),
                away = cm["away"].s() ?: throw InvalidMapping("no common.away"),
                team = TeamPaths(
                    name = team["name"].s() ?: throw InvalidMapping("no common.team.name"),
                    short = team["short"].s(), badge = team["badge"].s(), score = team["score"].s(),
                    record = team["record"].s(), form = team["form"].s(),
                ),
                broadcast = cm["broadcast"].s(), venue = cm["venue"].s(),
            ),
            profiles = profiles,
            chain = r["chain"].obj()?.mapNotNull { (k, v) -> (v as? JsonArray)?.let { a -> k to a.mapNotNull { it.s() } } }?.toMap().orEmpty(),
        )
    }
}

/**
 * Accepts a mapping only if it is one this build understands AND still reads real data: schemaVersion within
 * what we support, a non-empty structure, and a smoke-parse of a bundled ESPN event (the header must resolve and
 * the smoke profile's linescore must read) so a typo in a remote edit can never blank the screen.
 */
internal object SportsMappingValidator {
    const val SUPPORTED_SCHEMA = 1
    private const val SMOKE_PROFILE = "basketball"

    private val smokeEvent: JsonElement by lazy { Json.parseToJsonElement(SmokeFixture.NBA_EVENT) }

    /** null = OK, else the reason it was rejected. */
    fun validate(m: SportsMapping): String? {
        if (m.schemaVersion < 1 || m.schemaVersion > SUPPORTED_SCHEMA) return "unsupported schemaVersion ${m.schemaVersion}"
        if (m.version.isBlank()) return "no version"
        if (m.profiles.isEmpty()) return "no profiles"
        if (m.leagues.isEmpty()) return "no leagues"
        if (m.leagues.any { it.profile !in m.profiles }) return "league points at a missing profile"
        if (m.espn.scoreboardTemplate.isBlank() || m.espn.summaryTemplate.isBlank()) return "empty espn templates"
        if (m.espn.deadlineMs !in 500L..60_000L) return "espn deadline out of range"
        if (m.espn.liveMs < 1_000L || m.espn.minMs < 1_000L) return "refresh cadence out of range"
        if (SMOKE_PROFILE !in m.profiles) return "smoke profile '$SMOKE_PROFILE' missing"
        val asm = EspnAssembler(m)
        val h = asm.header(SMOKE_PROFILE, smokeEvent)
            ?: return "smoke parse: header did not resolve"
        val teams = h.header as? com.nuvio.app.features.sportsdetail.api.TeamsHeader
            ?: return "smoke parse: header is not teams"
        if (teams.home.name != "Smoke Home" || teams.away.name != "Smoke Away" || teams.home.score != "101" || teams.away.score != "99") {
            return "smoke parse: header read wrong values"
        }
        if (m.profiles.getValue(SMOKE_PROFILE).spec(BlockType.LINESCORE) != null && asm.assemble(BlockType.LINESCORE, SMOKE_PROFILE, smokeEvent, null) == null) {
            return "smoke parse: linescore did not resolve"
        }
        return null
    }
}

/** Always-available, tiny ESPN-shaped event the validator smoke-parses candidate mappings against. */
internal object SmokeFixture {
    const val NBA_EVENT: String = """{"id":"1","date":"2026-11-07T00:00Z","name":"Smoke Away at Smoke Home",
        "status":{"displayClock":"0.0","period":4,"type":{"state":"post","shortDetail":"Final"}},
        "competitions":[{"venue":{"fullName":"Smoke Arena"},"broadcasts":[{"market":"national","names":["SMK"]}],"competitors":[
          {"homeAway":"home","score":"101","records":[{"summary":"1-0"}],"team":{"id":"1","displayName":"Smoke Home","abbreviation":"SMH","logo":"https://x/h.png"},
           "linescores":[{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"26"}]},
          {"homeAway":"away","score":"99","records":[{"summary":"0-1"}],"team":{"id":"2","displayName":"Smoke Away","abbreviation":"SMA","logo":"https://x/a.png"},
           "linescores":[{"displayValue":"24"},{"displayValue":"25"},{"displayValue":"25"},{"displayValue":"25"}]}]}]}"""
}

internal sealed interface AdoptResult {
    data class Adopted(val version: String) : AdoptResult
    data class Rejected(val reason: String) : AdoptResult
}

/**
 * Holds the mapping in force: bundled default, replaced by a persisted/remote one only when it validates. A
 * candidate that fails to parse or validate never displaces the last good one.
 */
internal class SportsMappingStore(
    private val storage: MappingStorage,
    private val bundled: SportsMapping,
) {
    var current: SportsMapping = bundled
        private set

    /** ETag of the mapping in force (null for the bundled one) — sent as If-None-Match. */
    var etag: String? = null
        private set

    init {
        val stored = runCatching { storage.load() }.getOrNull()
        if (stored != null) {
            val parsed = SportsMappingParser.parse(stored.json)
            if (parsed is SportsMappingParser.ParseResult.Ok && SportsMappingValidator.validate(parsed.mapping) == null) {
                current = parsed.mapping
                etag = stored.etag
            }
        }
    }

    fun offer(json: String, etag: String?): AdoptResult {
        val parsed = SportsMappingParser.parse(json)
        val candidate = when (parsed) {
            is SportsMappingParser.ParseResult.Ok -> parsed.mapping
            is SportsMappingParser.ParseResult.Invalid -> return AdoptResult.Rejected(parsed.reason)
        }
        SportsMappingValidator.validate(candidate)?.let { return AdoptResult.Rejected(it) }
        current = candidate
        this.etag = etag
        runCatching { storage.save(StoredMapping(json, etag)) }
        return AdoptResult.Adopted(candidate.version)
    }
}
