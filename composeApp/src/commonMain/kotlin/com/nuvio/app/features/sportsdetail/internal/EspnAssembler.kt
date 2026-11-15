package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupPlayer
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.FieldDrive
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.ResultsBlock
import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableRow
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

internal data class EspnHeader(
    val header: Header,
    val state: GameState,
    val live: LiveInfo?,
    val startsAt: String?,
    val venue: String?,
    val homeTeamId: String?,
    val awayTeamId: String?,
)

/**
 * Turns an ESPN scoreboard event (+ optional summary) into contract blocks by reading the paths in the mapping
 * (research/sports-mapping/BLOCK_CONTRACT.md). Pure and total: a block whose paths read nothing is `null` (the
 * screen hides just that block), and nothing ever throws. Re-pointing a path in the remote mapping repairs a block
 * with no client release.
 */
internal class EspnAssembler(private val m: SportsMapping) {

    /** Which payload [type] reads for [profileKey]: "scoreboard", "summary", or null if the profile has no spec for it. */
    fun rootNeeded(type: BlockType, profileKey: String): String? {
        val p = m.profiles[profileKey] ?: return null
        return when (type) {
            BlockType.LINEUP -> p.spec(type)?.from ?: if ("lineup" in p.blocks) "summary" else null
            else -> p.spec(type)?.from
        }
    }

    fun header(profileKey: String, event: JsonElement): EspnHeader? = safe {
        val profile = m.profiles[profileKey] ?: return@safe null
        val c = m.common
        val state = stateOf(event) ?: return@safe null
        val home = SportsPath.first(event, c.home)
        val away = SportsPath.first(event, c.away)
        val kind = profile.headerKind ?: when (profileKey) {
            "motorsport", "fighting" -> "event"
            "tennis" -> "versus"
            else -> "teams"
        }
        val header: Header = when (kind) {
            "event" -> {
                val name = SportsPath.text(event, "name") ?: return@safe null
                EventHeader(name, session = if (profile.spec(BlockType.RESULTS)?.str("session") != null) currentSession(event, profile) else null,
                    venue = SportsPath.text(event, c.venue) ?: SportsPath.text(event, "circuit.fullName"))
            }
            "versus" -> {
                val comps = SportsPath.read(event, "competitions[0].competitors[*]")
                    .sortedBy { SportsPath.text(it, "order")?.toIntOrNull() ?: Int.MAX_VALUE }
                if (comps.size < 2) return@safe null
                VersusHeader(individual(comps[0]) ?: return@safe null, individual(comps[1]) ?: return@safe null)
            }
            else -> TeamsHeader(side(home) ?: return@safe null, side(away) ?: return@safe null)
        }
        val period = SportsPath.text(event, c.period)?.toIntOrNull()
        val label = profile.periodLabel
        EspnHeader(
            header = header,
            state = state,
            live = LiveInfo(
                clock = SportsPath.text(event, c.clock)?.takeIf { it.isNotBlank() },
                periodLabel = if (state == GameState.LIVE && period != null && (label == "Q" || label == "P")) ordinal(period, if (label == "Q") 4 else 3) else null,
                detail = SportsPath.text(event, c.detail)?.takeIf { it.isNotBlank() },
                period = if (state == GameState.LIVE) period else null,
            ),
            startsAt = Iso.normalize(SportsPath.text(event, "date")),
            venue = SportsPath.text(event, c.venue),
            homeTeamId = SportsPath.text(home, "team.id"),
            awayTeamId = SportsPath.text(away, "team.id"),
        )
    }

    fun assemble(type: BlockType, profileKey: String, event: JsonElement, summary: JsonElement?): GameBlock? = safe {
        val profile = m.profiles[profileKey] ?: return@safe null
        val spec = profile.spec(type)
        fun root(s: BlockSpec?): JsonElement? = when (s?.from) { "summary" -> summary; "scoreboard" -> event; else -> null }
        when (type) {
            BlockType.LINESCORE -> spec?.let { linescore(it, profile, event, root(it)) }
            BlockType.STATS -> spec?.let { stats(it, event, root(it)) }
            BlockType.LEADERS -> spec?.let { sp ->
                // Per-game summary first (follows the live game); the scoreboard snapshot when the summary has none.
                leaders(sp, event, root(sp)) ?: sp.fallback()?.let { fb -> leaders(fb, event, root(fb)) }
            }
            BlockType.TIMELINE -> spec?.let { timeline(it, profile, event, root(it)) }
            BlockType.PLAYS -> spec?.let { plays(it, profile, event, root(it)) }
            BlockType.FIELD -> spec?.let { field(it, event, root(it)) }
            BlockType.SOCCER_STRIP -> spec?.let { soccerStrip(it, event, summary) }
            BlockType.STANDINGS_TABLE -> spec?.let { standingsTable(it, event, root(it)) }
            BlockType.WIN_PROBABILITY -> spec?.let { winProbability(it, root(it)) }
            BlockType.SITUATION -> spec?.let { situation(it, root(it)) }
            BlockType.RESULTS -> spec?.let { results(it, root(it)) }
            BlockType.FORM -> spec?.let { form(it, event, root(it)) }
            BlockType.LINEUP -> if (spec != null || "lineup" in profile.blocks) lineup(spec, event, summary) else null
            else -> null
        }
    }

    /** All blocks the profile lists that this assembler can build, in profile order. */
    fun assembleAll(profileKey: String, event: JsonElement, summary: JsonElement?): List<GameBlock> {
        val profile = m.profiles[profileKey] ?: return emptyList()
        return profile.blocks.mapNotNull { id -> BlockType.byId(id)?.let { assemble(it, profileKey, event, summary) } }
    }

    // ---- header pieces -------------------------------------------------------------------------------------------

    private fun stateOf(event: JsonElement): GameState? {
        val raw = SportsPath.text(event, m.common.state)?.lowercase() ?: return null
        val detail = SportsPath.text(event, m.common.detail)?.lowercase().orEmpty()
        if ("postponed" in detail || "canceled" in detail || "cancelled" in detail) return GameState.POSTPONED
        return when (m.states[raw] ?: raw) {
            "upcoming", "pre" -> GameState.UPCOMING
            "live", "in" -> GameState.LIVE
            "final", "post" -> GameState.FINAL
            else -> null
        }
    }

    private fun side(c: JsonElement?): Side? {
        if (c == null) return null
        val t = m.common.team
        val name = SportsPath.text(c, t.name) ?: return null
        return Side(
            name = name,
            short = SportsPath.text(c, t.short),
            badgeUrl = SportsPath.text(c, t.badge),
            score = t.score?.let { SportsPath.text(c, it) },
            record = SportsPath.text(c, t.record),
            form = SportsPath.text(c, t.form)?.let(::formLetters)?.takeIf { it.isNotEmpty() },
            color = hexColor(SportsPath.text(c, t.color)),
            altColor = hexColor(SportsPath.text(c, t.altColor)),
            rank = SportsPath.text(c, t.rank)?.toIntOrNull()?.takeIf { it in 1..98 },   // ESPN: 99 = unranked
        )
    }

    /** `team.color` as 6 lower-case hex digits, or null when it is anything else. */
    private fun hexColor(raw: String?): String? =
        raw?.trim()?.removePrefix("#")?.takeIf { it.length == 6 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }?.lowercase()

    private fun individual(c: JsonElement): Side? {
        val name = SportsPath.text(c, "athlete.displayName") ?: SportsPath.text(c, m.common.team.name) ?: return null
        return Side(
            name = name,
            short = SportsPath.text(c, "athlete.shortName") ?: SportsPath.text(c, "athlete.lastName"),
            badgeUrl = SportsPath.text(c, "athlete.headshot.href") ?: SportsPath.text(c, "athlete.flag.href"),
            score = SportsPath.text(c, "score"),
            record = SportsPath.text(c, m.common.team.record),
        )
    }

    private fun currentSession(event: JsonElement, profile: ProfileSpec): String? {
        val comps = SportsPath.read(event, profile.spec(BlockType.RESULTS)?.path ?: "competitions[*]")
        val pick = comps.firstOrNull { SportsPath.text(it, "status.type.state") != "post" } ?: comps.lastOrNull()
        return SportsPath.text(pick, profile.spec(BlockType.RESULTS)?.str("session"))
    }

    private fun ordinal(n: Int, regulation: Int): String = when {
        n > regulation -> if (n == regulation + 1) "OT" else "${n - regulation}OT"
        n == 1 -> "1st"; n == 2 -> "2nd"; n == 3 -> "3rd"
        else -> "${n}th"
    }

    private fun formLetters(s: String): List<String> = s.uppercase().filter { it == 'W' || it == 'D' || it == 'L' }.map { it.toString() }

    // ---- shared helpers -------------------------------------------------------------------------------------------

    private class Owner(val el: JsonElement, val side: String?)

    /** "home"/"away" for a team id, using the scoreboard competitors. */
    private fun sideOfTeam(event: JsonElement, teamId: String?): String? {
        if (teamId == null) return null
        val h = SportsPath.text(SportsPath.first(event, m.common.home), "team.id")
        val a = SportsPath.text(SportsPath.first(event, m.common.away), "team.id")
        return when (teamId) { h -> "home"; a -> "away"; else -> null }
    }

    /** Splits a mapping path after its first `[*]` into (owners path incl. the star, per-owner remainder). */
    private fun splitStar(path: String): Pair<String, String>? {
        val i = path.indexOf("[*]")
        if (i < 0) return null
        return path.substring(0, i + 3) to path.substring(i + 3).trimStart('.')
    }

    private fun ownerSide(event: JsonElement, owner: JsonElement): String? =
        when (SportsPath.text(owner, "homeAway")?.lowercase()) {
            "home" -> "home"
            "away" -> "away"
            else -> sideOfTeam(event, SportsPath.text(owner, "team.id"))
        }

    /** Competitors ordered home, away (by homeAway, else by order). */
    private fun orderedOwners(event: JsonElement, owners: List<JsonElement>): List<JsonElement> =
        owners.sortedBy { o ->
            when (SportsPath.text(o, "homeAway")?.lowercase()) {
                "home" -> 0; "away" -> 1
                else -> 2 + (SportsPath.text(o, "order")?.toIntOrNull() ?: 0)
            }
        }

    private fun cell(e: JsonElement): String? {
        SportsPath.display(e)?.let { return it }
        if (e is JsonObject) {
            val runs = SportsPath.display(e["runs"])
            if (runs != null) {
                val wk = SportsPath.display(e["wickets"])
                return if (wk != null) "$runs/$wk" else runs
            }
            return SportsPath.display(e["displayValue"]) ?: SportsPath.display(e["value"])
        }
        return null
    }

    // ---- linescore -------------------------------------------------------------------------------------------------

    private fun linescore(spec: BlockSpec, profile: ProfileSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        val (ownersPath, rest) = splitStar(spec.path ?: return null) ?: return null
        val owners = orderedOwners(event, SportsPath.read(root, ownersPath))
        if (owners.isEmpty()) return null
        // Read per period ELEMENT, so a missing score stays a "-" in its column instead of shifting the row left.
        val star = rest.lastIndexOf("[*]")
        val periodsPath = if (star >= 0) rest.substring(0, star + 3) else rest
        val leaf = if (star >= 0) rest.substring(star + 3).trimStart('.') else ""
        val perOwner = owners.map { o ->
            if (star < 0) SportsPath.read(o, rest).mapNotNull(::cell)
            else SportsPath.read(o, periodsPath).map { el -> (if (leaf.isEmpty()) cell(el) else SportsPath.read(el, leaf).firstOrNull()?.let(::cell)) ?: "-" }
        }
        val n = perOwner.maxOfOrNull { it.size } ?: 0
        if (n == 0 || perOwner.all { cells -> cells.all { it == "-" } }) return null
        val periodLabel = profile.periodLabel
        val labels = (0 until n).map { i -> periodColumn(profile.key, periodLabel, i) }.toMutableList()
        val extras = spec.strings("extra")
        val rows = perOwner.mapIndexed { oi, cells ->
            val row = (0 until n).map { cells.getOrNull(it) ?: "-" }.toMutableList()
            val o = owners[oi]
            if (extras.isNotEmpty()) {
                row += SportsPath.text(o, m.common.team.score ?: "score") ?: "-"
                extras.forEach { row += SportsPath.text(o, it) ?: "-" }
            } else if (!periodLabel.isNullOrBlank()) {
                row += SportsPath.text(o, m.common.team.score ?: "score") ?: "-"
            }
            row.toList()
        }
        if (extras.isNotEmpty()) { labels += "R"; extras.forEach { labels += it.first().uppercaseChar().toString() } }
        else if (!periodLabel.isNullOrBlank()) labels += "T"
        val rowLabels = owners.map { o -> SportsPath.text(o, m.common.team.short ?: "team.abbreviation") ?: SportsPath.text(o, m.common.team.name) ?: SportsPath.text(o, "athlete.shortName") ?: "" }
        return LinescoreBlock(columns = labels, rows = rows, rowLabels = rowLabels, source = ESPN)
    }

    private fun periodColumn(profileKey: String, label: String?, i: Int): String {
        val regulation = when (label) { "Q" -> 4; "P" -> 3; else -> Int.MAX_VALUE }
        if (i >= regulation) return if (i == regulation) "OT" else "OT${i - regulation + 1}"
        return when {
            label == null -> if (profileKey == "tennis") "S${i + 1}" else "${i + 1}"
            else -> "$label${i + 1}".ifEmpty { "${i + 1}" }
        }
    }

    // ---- stats -------------------------------------------------------------------------------------------------------

    private fun stats(spec: BlockSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        if (root == null) return null
        val (teamsPath, rest) = splitStar(spec.path ?: return null) ?: return null
        val teams = SportsPath.read(root, teamsPath)
        if (teams.isEmpty()) return null
        val labelPath = spec.str("label") ?: "label"
        val valuePath = spec.str("value") ?: "displayValue"
        val bySide = mutableMapOf<String, Map<String, Pair<String, String>>>() // side -> lc label -> (label, value)
        teams.forEachIndexed { i, t ->
            val side = ownerSide(event, t) ?: if (teams.size == 2) (if (i == 0) "away" else "home") else null
            if (side != null && side !in bySide) {
                val map = LinkedHashMap<String, Pair<String, String>>()
                for (s in SportsPath.read(t, rest)) {
                    val label = SportsPath.text(s, labelPath) ?: continue
                    val value = SportsPath.text(s, valuePath) ?: continue
                    map.getOrPut(label.lowercase()) { label to value }
                }
                bySide[side] = map
            }
        }
        val home = bySide["home"].orEmpty()
        val away = bySide["away"].orEmpty()
        val allow = spec.strings("allow")
        val rows = (if (allow.isEmpty()) (home.keys + away.keys).distinct() else allow.map { it.lowercase() }).mapNotNull { key ->
            val h = home[key]
            val a = away[key]
            if (h == null && a == null) null else StatRow(label = (h ?: a)!!.first, home = h?.second ?: "-", away = a?.second ?: "-")
        }
        return rows.takeIf { it.isNotEmpty() }?.let { StatsBlock(it, ESPN) }
    }

    // ---- leaders -----------------------------------------------------------------------------------------------------

    private fun leaders(spec: BlockSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        if (root == null) return null
        val (ownersPath, rest) = splitStar(spec.path ?: return null) ?: return null
        val categoryPath = spec.str("category") ?: "name"
        val athletePath = spec.str("athlete") ?: "leaders[0].athlete.displayName"
        val valuePath = spec.str("value") ?: "leaders[0].displayValue"
        val items = ArrayList<LeaderItem>()
        for (owner in SportsPath.read(root, ownersPath)) {
            val entries = if (rest.isEmpty()) listOf(owner) else SportsPath.read(owner, rest)
            val ownerSide = if (rest.isEmpty()) null else ownerSide(event, owner)
            for (e in entries) {
                val category = SportsPath.text(e, categoryPath)?.let(::prettify) ?: continue
                val athlete = SportsPath.text(e, athletePath) ?: continue
                val value = SportsPath.text(e, valuePath) ?: continue
                val side = ownerSide
                    ?: sideOfTeam(event, SportsPath.text(e, "leaders[0].team.id") ?: SportsPath.text(e, "leaders[0].athlete.team.id") ?: SportsPath.text(e, "team.id"))
                items += LeaderItem(category, athlete, value, side)
            }
        }
        return items.takeIf { it.isNotEmpty() }?.let { LeadersBlock(it, ESPN) }
    }

    private fun prettify(name: String): String {
        if (name.isBlank()) return name
        val sb = StringBuilder()
        name.forEachIndexed { i, ch ->
            if (i == 0) sb.append(ch.uppercaseChar())
            else { if (ch.isUpperCase()) sb.append(' '); sb.append(ch) }
        }
        return sb.toString()
    }

    // ---- timeline ----------------------------------------------------------------------------------------------------

    private fun timeline(spec: BlockSpec, profile: ProfileSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        if (root == null) return null
        val plays = SportsPath.read(root, spec.path ?: return null)
        // A list that is already only scoring plays ("plays[scoringPlay=true]", "scoringPlays[*]") needs no noise filter.
        val scoringList = spec.path!!.contains("scoringPlay", ignoreCase = true)
        val periodLabel = profile.periodLabel.orEmpty()
        val items = plays.mapNotNull { p ->
            val typeText = SportsPath.text(p, spec.str("type") ?: "type.text")
            val scoring = (p as? JsonObject)?.get("scoringPlay")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
            val kind = kindOf(typeText, scoring || scoringList)
            if (!scoringList && kind == null && !(typeText != null && typeText.lowercase() in MARKERS)) return@mapNotNull null
            val text = SportsPath.text(p, spec.str("text") ?: "text")?.takeIf { it.isNotBlank() }
                ?: SportsPath.text(p, "shortText")?.takeIf { it.isNotBlank() } ?: typeText ?: return@mapNotNull null
            val period = spec.str("period")?.let { SportsPath.text(p, it) }
                ?: SportsPath.text(p, "period.number")?.let { "$periodLabel$it" }
            TimelineItem(
                time = spec.str("time")?.let { SportsPath.text(p, it) }.orEmpty(),
                period = period?.takeIf { it.isNotBlank() },
                side = sideOfTeam(event, SportsPath.text(p, "team.id")),
                text = text,
                kind = kind,
            )
        }
        val last = spec.int("last")
        val cut = if (last != null && last > 0) items.takeLast(last) else items
        return cut.takeIf { it.isNotEmpty() }?.let { TimelineBlock(it, ESPN) }
    }

    // ---- plays (the full list for the Plays section) -----------------------------------------------------------------------

    private fun plays(spec: BlockSpec, profile: ProfileSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        if (root == null) return null
        val raw = SportsPath.read(root, spec.path ?: return null) + (spec.str("pathAlso")?.let { SportsPath.read(root, it) }.orEmpty())
        val allow = spec.strings("allow")
        val items = raw.mapNotNull { p -> playItem(spec, profile, event, p, allow) }.reversed()   // feeds are oldest first
        val cap = spec.extraInt("last")?.takeIf { it > 0 } ?: DEFAULT_PLAYS_CAP
        return items.take(cap).takeIf { it.isNotEmpty() }?.let { PlaysBlock(it, ESPN) }
    }

    private fun playItem(spec: BlockSpec, profile: ProfileSpec, event: JsonElement, p: JsonElement, allow: List<String>): TimelineItem? {
        val typeText = SportsPath.text(p, spec.str("type"))
        if (allow.isNotEmpty() && (typeText == null || allow.none { typeText.startsWith(it, ignoreCase = true) })) return null
        val text = SportsPath.text(p, spec.str("text") ?: "text")?.takeIf { it.isNotBlank() } ?: SportsPath.text(p, "shortText")?.takeIf { it.isNotBlank() } ?: return null
        val scoring = (p as? JsonObject)?.get("scoringPlay")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        val kind = if (SportsPath.text(p, "type.penaltyType") != null) "penalty" else playKind(typeText, scoring)
        val home = SportsPath.text(p, spec.str("homeScore"))
        val away = SportsPath.text(p, spec.str("awayScore"))
        return TimelineItem(
            time = SportsPath.text(p, spec.str("time")).orEmpty(),
            period = playPeriod(profile, SportsPath.text(p, spec.str("period"))),
            side = sideOfTeamRef(event, SportsPath.text(p, spec.str("team"))),
            text = text,
            kind = kind,
            id = SportsPath.text(p, spec.str("id")),
            wallclockMs = Iso.parseMs(SportsPath.text(p, spec.str("wallclock"))),
            score = if (home != null && away != null) "$home\u2013$away" else null,
        )
    }

    /** The tag a play carries: the timeline's goal/card/sub/penalty/score, plus the soccer commentary kinds. */
    private fun playKind(typeText: String?, scoring: Boolean): String? {
        val t = typeText?.lowercase().orEmpty()
        return when {
            "penalty" in t && "scored" in t -> "goal"
            "corner" in t -> "corner"
            "shot" in t -> "shot"
            "offside" in t -> "offside"
            t.startsWith("var") -> "var"
            else -> kindOf(typeText, scoring)
        }
    }

    /** Numeric periods read "Q1" / "P2" (periodLabel) or, for soccer, "1st Half"; a feed display value ("1st Quarter") passes through. */
    private fun playPeriod(profile: ProfileSpec, raw: String?): String? {
        val r = raw?.takeIf { it.isNotBlank() } ?: return null
        val n = r.toIntOrNull() ?: return r
        return when {
            profile.key == "soccer" -> when (n) { 1 -> "1st Half"; 2 -> "2nd Half"; 3 -> "Extra time 1"; 4 -> "Extra time 2"; else -> "Shootout" }
            !profile.periodLabel.isNullOrBlank() -> "${profile.periodLabel}$n"
            else -> r
        }
    }

    /** "home"/"away" for a team id (scoreboard competitors) or, when the feed gives only a name (soccer commentary), for that name. */
    private fun sideOfTeamRef(event: JsonElement, ref: String?): String? {
        if (ref == null) return null
        sideOfTeam(event, ref)?.let { return it }
        val namePath = m.common.team.name
        return when (ref) {
            SportsPath.text(SportsPath.first(event, m.common.home), namePath) -> "home"
            SportsPath.text(SportsPath.first(event, m.common.away), namePath) -> "away"
            else -> null
        }
    }

    // ---- football field strip ------------------------------------------------------------------------------------------------

    /**
     * Reads the live `situation` (single-event scoreboard). Verified on live CFB captures 2026-11-14/15: the single-event endpoint carries the
     * same situation as the whole-day scoreboard, including `possession` (a team id), `downDistanceText`, `yardLine` (from the HOME goal line),
     * `isRedZone` and both timeouts. When `possession` is missing the last play's team stands in, but only while the last play's drive has no
     * `result` (a finished drive may have changed hands); otherwise possession stays null: no arrow, no guess.
     */
    private fun field(spec: BlockSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        val sit = SportsPath.first(root, spec.path ?: return null) as? JsonObject ?: return null
        val yard = SportsPath.text(sit, "yardLine")?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val possessionId = SportsPath.text(sit, "possession")
        val possession = if (possessionId != null) sideOfTeam(event, possessionId)
        else if (SportsPath.text(sit, "lastPlay.drive.result").isNullOrBlank()) sideOfTeam(event, SportsPath.text(sit, "lastPlay.team.id")) else null
        val drive = (spec.str("drive")?.let { SportsPath.first(root, it) } ?: SportsPath.first(sit, "lastPlay.drive"))?.let { d ->
            FieldDrive(
                startYardLine = SportsPath.text(d, "start.yardLine")?.toIntOrNull()?.takeIf { it in 0..100 },
                summary = SportsPath.text(d, "description")?.takeIf { it.isNotBlank() },
            ).takeIf { it.startYardLine != null || it.summary != null }
        }
        return FootballFieldBlock(
            yardLine = yard,
            possession = possession,
            down = SportsPath.text(sit, "down")?.toIntOrNull()?.takeIf { it > 0 },
            distance = SportsPath.text(sit, "distance")?.toIntOrNull()?.takeIf { it > 0 },
            line = (SportsPath.text(sit, "downDistanceText") ?: SportsPath.text(sit, "shortDownDistanceText"))?.takeIf { it.isNotBlank() },
            redZone = SportsPath.text(sit, "isRedZone") == "true",
            homeTimeouts = SportsPath.text(sit, "homeTimeouts")?.toIntOrNull(),
            awayTimeouts = SportsPath.text(sit, "awayTimeouts")?.toIntOrNull(),
            drive = drive,
            source = ESPN,
        )
    }

    // ---- soccer strip ----------------------------------------------------------------------------------------------------------

    private fun soccerStrip(spec: BlockSpec, event: JsonElement, summary: JsonElement?): GameBlock? {
        val details = SportsPath.read(event, spec.str("details") ?: return null)
        fun flag(d: JsonElement, key: String) = SportsPath.text(d, key) == "true"
        fun minuteValue(d: JsonElement) = SportsPath.text(d, "clock.value")?.toDoubleOrNull() ?: 0.0
        fun who(d: JsonElement) = SportsPath.text(d, "athletesInvolved[0].shortName") ?: SportsPath.text(d, "athletesInvolved[0].displayName")
        fun surname(n: String) = n.substringAfter(". ", n).trim()

        val goals = details.filter { flag(it, "scoringPlay") }.sortedBy(::minuteValue)
        fun scorers(side: String) = goals.filter { sideOfTeam(event, SportsPath.text(it, "team.id")) == side }.map { g ->
            listOfNotNull(who(g)?.let(::surname), SportsPath.text(g, "clock.displayValue")).joinToString(" ") +
                (if (flag(g, "penaltyKick")) " (pen)" else "") + (if (flag(g, "ownGoal")) " (og)" else "")
        }
        fun cards(side: String, key: String) = details.count { flag(it, key) && sideOfTeam(event, SportsPath.text(it, "team.id")) == side }

        // Team statistics: possession (home share) and corners, read per competitor.
        val (ownersPath, rest) = splitStar(spec.str("stats") ?: "competitions[0].competitors[*].statistics[*]") ?: return null
        val statBase = rest.removeSuffix("[*]")
        val owners = SportsPath.read(event, ownersPath)
        fun stat(side: String, name: String): Double? =
            owners.firstOrNull { ownerSide(event, it) == side }?.let { SportsPath.text(it, "$statBase[name=$name].displayValue")?.toDoubleOrNull() }
        val homePoss = stat("home", "possessionPct")?.roundToInt() ?: stat("away", "possessionPct")?.let { 100 - it.roundToInt() }

        val isLive = stateOf(event) == GameState.LIVE
        val lastEvent = if (!isLive) null else {
            val fromBoard = details.filter { flag(it, "scoringPlay") || flag(it, "yellowCard") || flag(it, "redCard") }
                .withIndex().maxWithOrNull(compareBy({ minuteValue(it.value) }, { it.index }))?.value
            val boardEvent = fromBoard?.let { d ->
                SoccerEvent(
                    kind = if (flag(d, "scoringPlay")) "goal" else if (flag(d, "redCard")) "red" else "yellow",
                    side = sideOfTeam(event, SportsPath.text(d, "team.id")),
                    minute = SportsPath.text(d, "clock.displayValue"),
                    text = SportsPath.text(d, "athletesInvolved[0].displayName") ?: SportsPath.text(d, "type.text").orEmpty(),
                )
            }
            val fresherCommentary = spec.str("commentary")?.let { path ->
                SportsPath.read(summary, path).withIndex().mapNotNull { (i, c) ->
                    val kind = commentaryKind(SportsPath.text(c, "play.type.text")) ?: return@mapNotNull null
                    val t = SportsPath.text(c, "time.value")?.toDoubleOrNull() ?: return@mapNotNull null
                    val text = SportsPath.text(c, "text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Triple(t, i, SoccerEvent(kind, sideOfTeamRef(event, SportsPath.text(c, "play.team.displayName")), SportsPath.text(c, "time.displayValue"), text))
                }.maxWithOrNull(compareBy({ it.first }, { it.second }))
            }
            if (fresherCommentary != null && (fromBoard == null || fresherCommentary.first > minuteValue(fromBoard))) fresherCommentary.third else boardEvent
        }

        val block = SoccerStripBlock(
            lastEvent = lastEvent,
            homePossession = homePoss,
            homeCorners = stat("home", "wonCorners")?.roundToInt(),
            awayCorners = stat("away", "wonCorners")?.roundToInt(),
            homeYellow = cards("home", "yellowCard"), awayYellow = cards("away", "yellowCard"),
            homeRed = cards("home", "redCard"), awayRed = cards("away", "redCard"),
            homeScorers = scorers("home"), awayScorers = scorers("away"),
            source = ESPN,
        )
        val empty = block.lastEvent == null && block.homePossession == null && block.homeCorners == null && block.awayCorners == null &&
            block.homeYellow + block.awayYellow + block.homeRed + block.awayRed == 0 && block.homeScorers.isEmpty() && block.awayScorers.isEmpty()
        return if (empty) null else block
    }

    /** Commentary types that count as a strip event; everything else (fouls, aerials, delays...) is chatter. */
    private fun commentaryKind(type: String?): String? {
        val t = type?.lowercase() ?: return null
        return when {
            t == "goal" || ("penalty" in t && "scored" in t) -> "goal"
            t == "yellow card" -> "yellow"
            t == "red card" -> "red"
            t == "substitution" -> "sub"
            t == "corner awarded" -> "corner"
            t == "shot on target" -> "shot"
            t == "offside" -> "offside"
            t.startsWith("var") -> "var"
            t.startsWith("penalty") -> "penalty"
            else -> null
        }
    }

    // ---- standings table ------------------------------------------------------------------------------------------------------

    private fun standingsTable(spec: BlockSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        if (root == null) return null
        val allow = spec.strings("allow")
        val labels = spec.extraStrings("labels")
        if (allow.isEmpty() || labels.size != allow.size) return null
        val gameTeams = listOfNotNull(
            SportsPath.text(SportsPath.first(event, m.common.home), "team.id"),
            SportsPath.text(SportsPath.first(event, m.common.away), "team.id"),
        )
        val entriesPath = spec.str("entries") ?: "standings.entries[*]"
        val groups = SportsPath.read(root, spec.path ?: return null).mapNotNull { g ->
            val rows = SportsPath.read(g, entriesPath).mapNotNull { e ->
                val name = SportsPath.text(e, spec.str("team") ?: "team") ?: return@mapNotNull null
                val id = SportsPath.text(e, spec.str("teamId") ?: "id")
                StandingsTableRow(
                    teamId = id, name = name,
                    cells = allow.map { stat -> SportsPath.text(SportsPath.read(e, "stats[name=$stat]").firstOrNull(), "displayValue") ?: "\u2013" },
                    isGameTeam = id != null && id in gameTeams,
                )
            }
            if (rows.isEmpty()) null else StandingsGroup(groupName(SportsPath.text(g, spec.str("group"))), labels, rows)
        }
        return groups.takeIf { it.isNotEmpty() }?.let { StandingsTableBlock(it, ESPN) }
    }

    /** "2026-27 Central Standings" -> "Central". */
    private fun groupName(raw: String?): String =
        raw.orEmpty().replace(Regex("""^\d{4}(-\d{2})?\s+"""), "").removeSuffix(" Standings").trim()

    private fun kindOf(typeText: String?, scoring: Boolean): String? {
        val t = typeText?.lowercase().orEmpty()
        return when {
            "no goal" in t || "disallowed" in t -> null
            "penalty" in t && "goal" !in t && "scored" !in t -> "penalty"
            "goal" in t -> "goal"
            "card" in t -> "card"
            "substitution" in t -> "sub"
            scoring -> "score"
            else -> null
        }
    }

    // ---- win probability / situation ----------------------------------------------------------------------------------

    private fun winProbability(spec: BlockSpec, root: JsonElement?): GameBlock? {
        val v = (SportsPath.first(root, spec.path) as? JsonPrimitive)?.doubleOrNull ?: return null
        val pct = if (v <= 1.0) v * 100.0 else v
        return WinProbabilityBlock(pct.roundToInt().coerceIn(0, 100), ESPN, attribution = "via ESPN")
    }

    private fun situation(spec: BlockSpec, root: JsonElement?): GameBlock? {
        val v = SportsPath.first(root, spec.path) ?: return null
        if (v is JsonObject) {
            val parts = v.mapNotNull { (k, e) -> SportsPath.display(e)?.let { k to it } }.toMap()
            if (parts.isEmpty()) return null
            val line = parts["shortDownDistanceText"] ?: parts["downDistanceText"] ?: countLine(parts) ?: return null
            return SituationBlock(line, parts, ESPN)
        }
        val line = SportsPath.display(v)?.takeIf { it.isNotBlank() } ?: return null
        return SituationBlock(line, emptyMap(), ESPN)
    }

    private fun countLine(p: Map<String, String>): String? {
        val b = p["balls"] ?: return null
        val s = p["strikes"] ?: return null
        val outs = p["outs"]?.let { "$it ${if (it == "1") "out" else "outs"}" }
        return if (outs != null) "$b-$s, $outs" else "$b-$s"
    }

    // ---- results --------------------------------------------------------------------------------------------------------

    private fun results(spec: BlockSpec, root: JsonElement?): GameBlock? {
        val comps = SportsPath.read(root, spec.path ?: return null)
        if (comps.isEmpty()) return null
        val sessionPath = spec.str("session")
        fun names(c: JsonElement): List<Pair<JsonElement, String>> =
            SportsPath.read(c, "competitors[*]").sortedBy { SportsPath.text(it, "order")?.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { k ->
                (SportsPath.text(k, "athlete.displayName") ?: SportsPath.text(k, "team.displayName"))?.let { k to it }
            }
        val statePath = spec.str("state") ?: "status.type.state"
        val rows = comps.map { c ->
            val done = SportsPath.text(c, statePath) == "post"
            val status = SportsPath.text(c, "status.type.shortDetail") ?: SportsPath.text(c, "status.type.description") ?: "-"
            val ns = names(c)
            if (sessionPath != null) {
                listOf(SportsPath.text(c, sessionPath) ?: "-", if (done && ns.isNotEmpty()) ns.take(3).joinToString(", ") { it.second } else status)
            } else {
                val winner = ns.firstOrNull { (k, _) -> SportsPath.text(k, "winner") == "true" }?.second
                listOf(
                    SportsPath.text(c, "type.abbreviation") ?: SportsPath.text(c, "type.text") ?: "-",
                    ns.joinToString(" vs ") { it.second }.ifEmpty { "-" },
                    if (done && winner != null) winner else status,
                )
            }
        }
        val columns = if (sessionPath != null) listOf("Session", "Result") else listOf("Bout", "Fighters", "Result")
        return ResultsBlock(columns, rows, ESPN)
    }

    // ---- form / lineup -------------------------------------------------------------------------------------

    private fun form(spec: BlockSpec, event: JsonElement, root: JsonElement?): GameBlock? {
        val (ownersPath, rest) = splitStar(spec.path ?: return null) ?: return null
        var home = emptyList<String>()
        var away = emptyList<String>()
        for (o in SportsPath.read(root, ownersPath)) {
            val letters = SportsPath.text(o, rest)?.let(::formLetters).orEmpty()
            when (ownerSide(event, o)) { "home" -> home = letters; "away" -> away = letters }
        }
        return if (home.isEmpty() && away.isEmpty()) null else FormBlock(home, away, ESPN)
    }

    private fun lineup(spec: BlockSpec?, event: JsonElement, summary: JsonElement?): GameBlock? {
        if (summary == null) return null
        fun p(key: String, def: String) = spec?.str(key) ?: def
        val sides = SportsPath.read(summary, p("path", "rosters[*]")).mapNotNull { r ->
            val side = ownerSide(event, r) ?: return@mapNotNull null
            val players = SportsPath.read(r, p("players", "roster[*]")).mapNotNull { pl ->
                val name = SportsPath.text(pl, p("name", "athlete.displayName")) ?: return@mapNotNull null
                LineupPlayer(
                    number = SportsPath.text(pl, p("number", "jersey")).orEmpty(),
                    name = name,
                    pos = SportsPath.text(pl, p("pos", "position.abbreviation")).orEmpty(),
                    starter = SportsPath.text(pl, p("starter", "starter")) == "true",
                    place = SportsPath.text(pl, p("place", "formationPlace"))?.toIntOrNull()?.takeIf { it > 0 },
                )
            }
            if (players.isEmpty()) null else LineupSide(side, SportsPath.text(r, p("formation", "formation")), players.sortedBy { !it.starter })
        }
        return sides.takeIf { it.isNotEmpty() }?.let { LineupBlock(it, ESPN) }
    }

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }

    companion object {
        const val ESPN = "espn"
        private const val DEFAULT_PLAYS_CAP = 60
        private val MARKERS = setOf("kickoff", "halftime", "full time", "end regular time", "end of regular time", "start 2nd half")
    }
}
