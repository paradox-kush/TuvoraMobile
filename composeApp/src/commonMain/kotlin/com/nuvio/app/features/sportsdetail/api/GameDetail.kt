package com.nuvio.app.features.sportsdetail.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the game-detail screen consumes (research/sports-mapping/BLOCK_CONTRACT.md, v1). Providers and the
 * remote mapping produce this; the UI never sees a provider shape. Display values are Strings on purpose
 * (providers disagree on number types); the sport profile owns units and labels.
 */
@Serializable
enum class GameState {
    @SerialName("upcoming") UPCOMING,
    @SerialName("live") LIVE,
    @SerialName("final") FINAL,
    @SerialName("postponed") POSTPONED,
}

@Serializable
data class Side(
    val name: String,
    val short: String? = null,
    val badgeUrl: String? = null,
    /** "139", "187/4", "6 3". */
    val score: String? = null,
    val record: String? = null,
    val form: List<String>? = null,
    /** Team colour hex without '#', from the feed (`team.color`); null when absent. Contrast is handled by TeamColor. */
    val color: String? = null,
    val altColor: String? = null,
    /** Poll rank (`curatedRank.current`), null when unranked (ESPN uses 99 for "unranked"). */
    val rank: Int? = null,
)

/** Live status line: clock "2H 49'", "Q2 5:12", "Over 38.2"; periodLabel; detail "Halftime". */
@Serializable
data class LiveInfo(
    val clock: String? = null,
    /** "2nd" for quarters/periods; null where the clock already says it (soccer "45'+2'"). */
    val periodLabel: String? = null,
    val detail: String? = null,
    /** The current period number, when known (used to hide periods that have not started). */
    val period: Int? = null,
)

@Serializable
sealed interface Header

/** Team sports: home/away. */
@Serializable @SerialName("teams")
data class TeamsHeader(val home: Side, val away: Side) : Header

/** Head-to-head individuals (tennis). */
@Serializable @SerialName("versus")
data class VersusHeader(val a: Side, val b: Side) : Header

/** Events with a field rather than two sides (motorsport weekend, fight card). */
@Serializable @SerialName("event")
data class EventHeader(val name: String, val session: String? = null, val venue: String? = null) : Header

@Serializable
data class GameDetail(
    /** Our id: "tsdb:<idEvent>" or "espn:<id>". */
    val gameId: String,
    val sport: String,
    /** TheSportsDB league id. */
    val leagueId: String,
    val leagueName: String,
    val round: String? = null,
    val venue: String? = null,
    /** ISO-8601 UTC; may be empty when the provider has no kickoff time. */
    val startsAt: String = "",
    val state: GameState = GameState.UPCOMING,
    val header: Header,
    val live: LiveInfo? = null,
    /** Ordered by the sport profile; any block may be absent. */
    val blocks: List<GameBlock> = emptyList(),
    /** blockType -> "tsdb" | "espn" | "derived" | "local" (includes "header"). */
    val sources: Map<String, String> = emptyMap(),
    /** Per source, WHEN WE FETCHED it (epoch ms; never a provider timestamp). UI shows "Updated 2 min ago" past ~20 s. */
    val asOf: Map<String, Long> = emptyMap(),
    /** The remote UI switches that are ON for this snapshot (mapping `uiSwitches`); e.g. "chartMarkers". A switch not listed is OFF. */
    val uiSwitches: Set<String> = emptySet(),
)

/** One block of the screen. Every block says which source produced it. */
@Serializable
sealed interface GameBlock {
    val source: String
}

@Serializable @SerialName("situation")
data class SituationBlock(
    val line: String,
    /** Structured parts when the provider gives them (down/distance, runners, count, server, "need X off Y"). */
    val parts: Map<String, String> = emptyMap(),
    override val source: String = "",
) : GameBlock

/** Local block: the IPTV matcher's result. `entry` = "watching" | "browsing" decides Play vs Switch. Built by the UI lane. */
@Serializable @SerialName("watch")
data class WatchBlock(val entry: String, val channelName: String? = null, override val source: String = "local") : GameBlock

@Serializable @SerialName("broadcast")
data class BroadcastBlock(val byRegion: Map<String, List<String>>, override val source: String = "") : GameBlock

/** columns e.g. Q1..Q4,T or 1..9,R,H,E; rows[side][] with row 0 = home (or first competitor). */
@Serializable @SerialName("linescore")
data class LinescoreBlock(
    val columns: List<String>,
    val rows: List<List<String>>,
    val rowLabels: List<String> = emptyList(),
    override val source: String = "",
) : GameBlock

@Serializable
data class StatRow(val label: String, val home: String, val away: String, val unit: String? = null)

@Serializable @SerialName("stats")
data class StatsBlock(val rows: List<StatRow>, override val source: String = "") : GameBlock

@Serializable
data class LeaderItem(val category: String, val athlete: String, val value: String, val side: String? = null)

@Serializable @SerialName("leaders")
data class LeadersBlock(val items: List<LeaderItem>, override val source: String = "") : GameBlock

@Serializable
data class TimelineItem(
    val time: String,
    val period: String? = null,
    /** "home" | "away" | null. */
    val side: String? = null,
    val text: String,
    /** goal, card, sub, score, penalty. */
    val kind: String? = null,
    /** ESPN play id. */
    val id: String? = null,
    /** The play's `wallclock`, epoch ms; null when the feed has none. */
    val wallclockMs: Long? = null,
    /** Running score AFTER this play, "10–21" (home–away), only when the feed gives both. */
    val score: String? = null,
)

@Serializable @SerialName("timeline")
data class TimelineBlock(val items: List<TimelineItem>, override val source: String = "") : GameBlock

/** The full play list for the Plays section; [TimelineBlock] stays the scoring summary. Newest first. */
@Serializable @SerialName("plays")
data class PlaysBlock(val items: List<TimelineItem>, override val source: String = "") : GameBlock

@Serializable
data class StandingsTableRow(val teamId: String?, val name: String, val cells: List<String>, val isGameTeam: Boolean = false)

@Serializable
data class StandingsGroup(val name: String, val columns: List<String>, val rows: List<StandingsTableRow>)

/** Generic standings (any league's stat columns); the old soccer-shaped [StandingsBlock] stays for the backend path. */
@Serializable @SerialName("standingsTable")
data class StandingsTableBlock(val groups: List<StandingsGroup>, override val source: String = "") : GameBlock

@Serializable
data class LineupPlayer(
    val number: String, val name: String, val pos: String, val starter: Boolean,
    /** ESPN `formationPlace` (1 = keeper, then the rows of the formation); null when the feed has none. Drives the pitch layout. */
    val place: Int? = null,
)

@Serializable
data class LineupSide(val side: String, val formation: String? = null, val players: List<LineupPlayer>)

@Serializable @SerialName("lineup")
data class LineupBlock(val sides: List<LineupSide>, override val source: String = "") : GameBlock

/** Motorsport / fighting / golf classification, bouts. */
@Serializable @SerialName("results")
data class ResultsBlock(val columns: List<String>, val rows: List<List<String>>, override val source: String = "") : GameBlock

@Serializable
data class StandingRow(val pos: String, val name: String, val played: String, val points: String, val gd: String? = null, val form: String? = null)

@Serializable @SerialName("standings")
data class StandingsBlock(val rows: List<StandingRow>, override val source: String = "") : GameBlock

/** Last five per side: "W" | "D" | "L". */
@Serializable @SerialName("form")
data class FormBlock(val home: List<String>, val away: List<String>, override val source: String = "") : GameBlock

/** TV shows a QR code, phone/iPad open the app. */
@Serializable @SerialName("highlights")
data class HighlightsBlock(val url: String, val thumbnail: String? = null, override val source: String = "") : GameBlock

@Serializable
data class NextItem(@SerialName("when") val whenText: String, val text: String)

@Serializable @SerialName("next")
data class NextBlock(val items: List<NextItem>, override val source: String = "") : GameBlock

/** A short market storyline ("Goes to overtime" 23%): [yes] is 0..100, [side] "home"|"away" when it is about one team. */
@Serializable
data class Storyline(val key: String, val label: String, val yes: Int, val side: String? = null)

/** One point of the win-chance history (epoch ms). [home] is plotted as given (soccer: home's non-draw share h/(h+a)); [draw] is that point's draw percent when the market has one. */
@Serializable
data class WinPoint(val t: Long, val home: Int, val draw: Int? = null)

/**
 * Home win chance, whole percent 0..100. [source] is "espn" or "polymarket"; [attribution] is the caption ("via Polymarket",
 * "via ESPN"); [drawPercent] for sports with a draw; [history] feeds the momentum line (phone/iPad). No odds, prices or links.
 */
@Serializable @SerialName("winProbability")
data class WinProbabilityBlock(
    val homePercent: Int,
    override val source: String = "",
    val attribution: String? = null,
    val drawPercent: Int? = null,
    /** The away share when the provider gave it (soccer: home + draw + away); null = derive as the remainder. */
    val awayPercent: Int? = null,
    val history: List<WinPoint> = emptyList(),
    /** Up to three market storylines shown under the win chance (phone/iPad; TV at most one line). */
    val storylines: List<Storyline> = emptyList(),
    /** Credit for the chart/storylines when they come from a different source than the headline ("via Polymarket" under an ESPN bar). */
    val extrasAttribution: String? = null,
    /** When the MARKET data of this block was valid (server time): the card shows its own staleness, not the screen's. */
    val asOfMs: Long? = null,
) : GameBlock

/**
 * American football live field strip. [yardLine] is 0..100 yards from the HOME team's goal line, exactly as the feed gives it
 * (verified 2026-11-14/15: "OKST 25" with OKST at home -> 25, "TTU 5" with TTU away -> 95). [possession] "home" | "away" | null
 * (unknown -> no arrow, no drive shading). [line] is the feed's own text ("2nd & 5 at LSU 33"), never built by us.
 */
@Serializable @SerialName("field")
data class FootballFieldBlock(
    val yardLine: Int,
    val possession: String? = null,
    /** null / <= 0 in the feed = between plays. */
    val down: Int? = null,
    val distance: Int? = null,
    val line: String? = null,
    val redZone: Boolean = false,
    val homeTimeouts: Int? = null,
    val awayTimeouts: Int? = null,
    val drive: FieldDrive? = null,
    override val source: String = "",
) : GameBlock

/** [startYardLine] is home-based like the ball; [summary] is the feed's "3 plays, 26 yards, 0:54". */
@Serializable
data class FieldDrive(val startYardLine: Int? = null, val summary: String? = null)

/** kind: goal | yellow | red | sub | corner | penalty | var | shot | offside | foul. [text] is the feed's own words. */
@Serializable
data class SoccerEvent(val kind: String, val side: String? = null, val minute: String? = null, val text: String)

/** Soccer live strip: last real event, possession, corners, cards and scorers, all from the single-event scoreboard (+ summary commentary). */
@Serializable @SerialName("soccerStrip")
data class SoccerStripBlock(
    val lastEvent: SoccerEvent? = null,
    /** Home share, whole percent. */
    val homePossession: Int? = null,
    val homeCorners: Int? = null,
    val awayCorners: Int? = null,
    val homeYellow: Int = 0,
    val awayYellow: Int = 0,
    val homeRed: Int = 0,
    val awayRed: Int = 0,
    /** "Saka 23'", "Palmer 41' (pen)", "Dunk 60' (og)" - in time order, under the team that got the goal. */
    val homeScorers: List<String> = emptyList(),
    val awayScorers: List<String> = emptyList(),
    override val source: String = "",
) : GameBlock

/** A run of unanswered points; [side] "home" | "away". Derived from the plays, so it exists only while the `basketballRun` switch is ON. */
@Serializable
data class ScoringRun(val side: String, val points: Int)

/**
 * Basketball live strip (summary, so it moves on the 60 s tick). Fouls, bonus and timeouts are DIRECT from ESPN's header competitors;
 * [homeBonus]/[awayBonus] carry that competitor's own raw `bonusState` (the team that BENEFITS, see BasketballStripPolicy.inBonus).
 */
@Serializable @SerialName("basketballStrip")
data class BasketballStripBlock(
    /** `fouls.teamFoulsCurrent`: team fouls this period. */
    val homeFouls: Int? = null,
    val awayFouls: Int? = null,
    val homeBonus: String? = null,
    val awayBonus: String? = null,
    /** `timeoutsRemaining`. */
    val homeTimeouts: Int? = null,
    val awayTimeouts: Int? = null,
    /** "home" | "away" | null. */
    val possession: String? = null,
    /** Derived; null unless the `basketballRun` switch is ON and the run is at least 8 points. */
    val run: ScoringRun? = null,
    override val source: String = "",
) : GameBlock

/**
 * Hockey live strip (summary, 60 s). [powerPlaySide] comes from the newest play's own `strength`; [powerPlaySecondsLeft] is DERIVED
 * from penalty plays and exists only while the `hockeyPowerPlayClock` switch is ON and the situation is unambiguous.
 */
@Serializable @SerialName("hockeyStrip")
data class HockeyStripBlock(
    val powerPlaySide: String? = null,
    val powerPlaySecondsLeft: Int? = null,
    val homeShots: Int? = null,
    val awayShots: Int? = null,
    /** "goals/opportunities", e.g. "1/2". */
    val homePowerPlay: String? = null,
    val awayPowerPlay: String? = null,
    val homeFaceoffs: Int? = null,
    val awayFaceoffs: Int? = null,
    override val source: String = "",
) : GameBlock

/** Decoded from a block type this build does not know; dropped by [GameDetailCodec]. */
@Serializable @SerialName("unknown")
data class UnknownBlock(override val source: String = "") : GameBlock
