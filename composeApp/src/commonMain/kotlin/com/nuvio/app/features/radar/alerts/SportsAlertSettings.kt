package com.nuvio.app.features.radar.alerts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** "This game: all / off" until [expMs] (kickoff + 24 h), after which it is pruned. */
data class GameAlertOverride(val id: String, val level: AlertLevel, val expMs: Long)

/**
 * The per-profile alert settings, one `alerts` object in radar_prefs (synced like `scores_shown`). Defaults are the
 * owner-approved ones (2026-11-24): on; teams everything; leagues full time only; quiet 22:00–08:00; 6 an hour;
 * 60 s batching; during playback Live TV only. Cap and batch COUNTERS are not here: they are per device, in memory.
 */
data class SportsAlertSettings(
    val enabled: Boolean = true,
    val teamLevel: AlertLevel = AlertLevel.All,
    val leagueLevel: AlertLevel = AlertLevel.FinalOnly,
    val quietOn: Boolean = true,
    /** Device-local minute of day, 0..1439. */
    val quietStartMin: Int = DEFAULT_QUIET_START,
    val quietEndMin: Int = DEFAULT_QUIET_END,
    /** 3 / 6 / 12, or 0 = no limit. */
    val maxPerHour: Int = DEFAULT_PER_HOUR,
    /** 0 = off, 60, 300. */
    val batchSeconds: Int = DEFAULT_BATCH_S,
    val duringPlayback: DuringPlayback = DuringPlayback.LiveTvOnly,
    val mutedLeagues: Set<String> = emptySet(),
    val mutedTeams: Set<String> = emptySet(),
    val games: List<GameAlertOverride> = emptyList(),
) {
    /** This game's own level (All / Off) while it has not expired; null = follow the team / league level. */
    fun gameOverride(eventId: String, nowMs: Long): AlertLevel? =
        games.firstOrNull { it.id == eventId && it.expMs > nowMs }?.level

    /** Sets (or with [level] null clears) one game's override; expired ones are pruned and the newest 50 kept. */
    fun withGame(eventId: String, level: AlertLevel?, expMs: Long, nowMs: Long): SportsAlertSettings {
        val others = games.filter { it.id != eventId }
        val next = if (level == null) others else others + GameAlertOverride(eventId, level, expMs)
        return copy(games = next.takeLast(MAX_GAME_OVERRIDES)).pruned(nowMs)
    }

    fun pruned(nowMs: Long): SportsAlertSettings =
        if (games.all { it.expMs > nowMs }) this else copy(games = games.filter { it.expMs > nowMs })

    fun withLeagueMuted(leagueId: String, muted: Boolean): SportsAlertSettings =
        copy(mutedLeagues = if (muted) mutedLeagues + leagueId else mutedLeagues - leagueId)

    fun withTeamMuted(teamId: String, muted: Boolean): SportsAlertSettings =
        copy(mutedTeams = if (muted) mutedTeams + teamId else mutedTeams - teamId)

    companion object {
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 8 * 60
        const val DEFAULT_PER_HOUR = 6
        const val DEFAULT_BATCH_S = 60
        val ALLOWED_PER_HOUR: List<Int> = listOf(3, 6, 12, 0)
        val ALLOWED_BATCH_S: List<Int> = listOf(0, 60, 300)
        /** Kept small: the backend stores the object only under 8 KB ([MAX_JSON_BYTES] is our margin under it). */
        const val MAX_GAME_OVERRIDES = 50
        const val MAX_MUTED = 100
        /** Longer ids are dropped (TheSportsDB ids are short; a fallback fixture key could be long). */
        const val MAX_ID_LENGTH = 64
        const val MAX_JSON_BYTES = 7_900

        fun nextPerHour(current: Int): Int = ALLOWED_PER_HOUR.next(current, DEFAULT_PER_HOUR)
        fun nextBatch(current: Int): Int = ALLOWED_BATCH_S.next(current, DEFAULT_BATCH_S)

        private fun List<Int>.next(current: Int, default: Int): Int {
            val i = indexOf(current).takeIf { it >= 0 } ?: indexOf(default)
            return this[(i + 1) % size]
        }

        /**
         * Tolerant read: a missing object, a missing key, a wrong type or an unknown value all read as that key's
         * default (like [com.nuvio.app.features.radar.ScoreSpoilerSettings.sanitizeDelay]); never throws, so a bad
         * `alerts` value can never cost the user their follows (the whole local blob decodes in one go).
         */
        fun fromJson(element: JsonElement?): SportsAlertSettings {
            val o = element as? JsonObject ?: return SportsAlertSettings()
            val d = SportsAlertSettings()
            return SportsAlertSettings(
                enabled = o.bool("enabled") ?: d.enabled,
                teamLevel = AlertLevel.fromWire(o.str("team_level")) ?: d.teamLevel,
                leagueLevel = AlertLevel.fromWire(o.str("league_level")) ?: d.leagueLevel,
                quietOn = o.bool("quiet_on") ?: d.quietOn,
                quietStartMin = o.int("quiet_start_min")?.takeIf { it in 0 until MINUTES_PER_DAY } ?: d.quietStartMin,
                quietEndMin = o.int("quiet_end_min")?.takeIf { it in 0 until MINUTES_PER_DAY } ?: d.quietEndMin,
                maxPerHour = o.int("max_per_hour")?.takeIf { it in ALLOWED_PER_HOUR } ?: d.maxPerHour,
                batchSeconds = o.int("batch_s")?.takeIf { it in ALLOWED_BATCH_S } ?: d.batchSeconds,
                duringPlayback = DuringPlayback.fromWire(o.str("during_playback")) ?: d.duringPlayback,
                mutedLeagues = o.ids("muted_leagues"),
                mutedTeams = o.ids("muted_teams"),
                games = (o["games"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val g = entry as? JsonObject ?: return@mapNotNull null
                    val id = g.str("id")?.takeIf { it.length <= MAX_ID_LENGTH } ?: return@mapNotNull null
                    // A game override is only ever All or Off; anything else is dropped, never guessed.
                    val level = AlertLevel.fromWire(g.str("level"))?.takeIf { it == AlertLevel.All || it == AlertLevel.Off }
                        ?: return@mapNotNull null
                    val exp = g.long("exp") ?: return@mapNotNull null
                    GameAlertOverride(id, level, exp)
                }.asReversed().distinctBy { it.id }.asReversed().takeLast(MAX_GAME_OVERRIDES),
            )
        }

        /**
         * Always under [MAX_JSON_BYTES]: past it, the oldest game overrides go first (down to 10), then muted ids from the
         * longer list, then the remaining overrides. The caps keep this to the rare hand-built extreme.
         */
        fun toJson(s: SportsAlertSettings): JsonObject {
            var games = s.games.filter { it.id.length <= MAX_ID_LENGTH }.takeLast(MAX_GAME_OVERRIDES)
            var leagues = s.mutedLeagues.filter { it.length <= MAX_ID_LENGTH }.sorted().take(MAX_MUTED)
            var teams = s.mutedTeams.filter { it.length <= MAX_ID_LENGTH }.sorted().take(MAX_MUTED)
            while (true) {
                val o = encode(s, games, leagues, teams)
                if (o.toString().encodeToByteArray().size <= MAX_JSON_BYTES) return o
                when {
                    games.size > MIN_KEPT_GAMES -> games = games.drop(1)
                    leagues.isNotEmpty() || teams.isNotEmpty() ->
                        if (leagues.size >= teams.size) leagues = leagues.dropLast(1) else teams = teams.dropLast(1)
                    games.isNotEmpty() -> games = games.drop(1)
                    else -> return o
                }
            }
        }

        private const val MIN_KEPT_GAMES = 10

        private fun encode(
            s: SportsAlertSettings,
            games: List<GameAlertOverride>,
            leagues: List<String>,
            teams: List<String>,
        ): JsonObject = buildJsonObject {
            put("enabled", s.enabled)
            put("team_level", s.teamLevel.wire)
            put("league_level", s.leagueLevel.wire)
            put("quiet_on", s.quietOn)
            put("quiet_start_min", s.quietStartMin)
            put("quiet_end_min", s.quietEndMin)
            put("max_per_hour", s.maxPerHour)
            put("batch_s", s.batchSeconds)
            put("during_playback", s.duringPlayback.wire)
            putJsonArray("muted_leagues") { leagues.forEach { add(it) } }
            putJsonArray("muted_teams") { teams.forEach { add(it) } }
            putJsonArray("games") {
                games.forEach { g ->
                    addJsonObject {
                        put("id", g.id)
                        put("level", g.level.wire)
                        put("exp", g.expMs)
                    }
                }
            }
        }

        private const val MINUTES_PER_DAY = 24 * 60
    }
}

/** Stores [SportsAlertSettings] as its `alerts` JSON object, read tolerantly ([SportsAlertSettings.fromJson]). Json only. */
object SportsAlertSettingsSerializer : KSerializer<SportsAlertSettings> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): SportsAlertSettings {
        val json = decoder as? JsonDecoder ?: throw SerializationException("SportsAlertSettings is JSON-only")
        return SportsAlertSettings.fromJson(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: SportsAlertSettings) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("SportsAlertSettings is JSON-only")
        json.encodeJsonElement(SportsAlertSettings.toJson(value))
    }
}

private fun JsonObject.prim(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

private fun JsonObject.str(name: String): String? = prim(name)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.bool(name: String): Boolean? = prim(name)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.int(name: String): Int? = prim(name)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.long(name: String): Long? = prim(name)?.takeIf { !it.isString }?.longOrNull

/** Ids as strings; numbers are accepted (TheSportsDB ids are numeric strings, a hand-edited row might hold numbers). */
private fun JsonObject.ids(name: String): Set<String> =
    (this[name] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { id -> id.isNotEmpty() && id.length <= SportsAlertSettings.MAX_ID_LENGTH } }
        .take(SportsAlertSettings.MAX_MUTED)
        .toSet()
