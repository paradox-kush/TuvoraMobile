package com.tuvora.tvos.screens

/** Football field strip: all positions already 0..1 (0 = home goal line, home end zone drawn on the LEFT). Swift only draws. */
data class TvFieldStrip(
    val ballX: Float,
    val firstDownX: Float?,
    /** Shaded drive span, ordered; null without a start or without a known possession. */
    val driveFrom: Float?,
    val driveTo: Float?,
    val driveTintHex: String?,
    val homeEndZoneHex: String?,
    val awayEndZoneHex: String?,
    /** true = the offence heads right (home has the ball), false = left, null = unknown (no arrow). */
    val attackingRight: Boolean?,
    /** "LSU ball" (Swift puts the arrow on the side the offence heads to). */
    val ballLabel: String?,
    /** The feed's own text ("2nd & 5 at LSU 33"); never built by us. */
    val line: String?,
    val redZone: Boolean,
    /** Red zone: the strip border takes the possessing team's colour. */
    val borderHex: String?,
    val homeTimeouts: Int?,
    val awayTimeouts: Int?,
    val driveSummary: String?,
)

enum class TvEventStyle { GOAL, YELLOW, RED, NEUTRAL }
data class TvEventChip(val label: String, val style: TvEventStyle, val team: String?, val minute: String?, val text: String)

data class TvSoccerStrip(
    val chip: TvEventChip?,
    val homePossession: Int?,
    val awayPossession: Int?,
    val homeCorners: Int?,
    val awayCorners: Int?,
    val homeYellow: Int,
    val awayYellow: Int,
    val homeRed: Int,
    val awayRed: Int,
)

data class TvPitchSpot(val x: Float, val y: Float, val number: String, val name: String, val isHome: Boolean)
data class TvPitch(val homeName: String, val awayName: String, val homeFormation: String?, val awayFormation: String?, val homeHex: String?, val awayHex: String?, val spots: List<TvPitchSpot>)

/** A scoring moment on the win-chance chart: x/y in 0..1 chart space, placed on the drawn line by core (no estimates). */
data class TvChartMarker(val key: String, val x: Float, val y: Float, val label: String, val side: String?, val colorHex: String?, val labelAbove: Boolean)
/** A faint period boundary with its label under the chart ("Q2", "P1", "45'"). */
data class TvChartDivider(val x: Float, val label: String)

/** Basketball strip: direct feed values (fouls this period, timeouts left, bonus) and the switched, derived run. Swift only draws. */
data class TvBasketballStrip(
    /** "DAL 8-0 run"; null unless the `basketballRun` switch is on and a run of 8+ exists. */
    val runLabel: String?,
    /** "<Team> in the bonus"; the feed's raw value is never printed. */
    val bonusLabel: String?,
    val homeFouls: Int?, val awayFouls: Int?,
    val homeTimeouts: Int?, val awayTimeouts: Int?,
    /** "home" | "away" | null: a small dot beside that team's name on the scoreboard. */
    val possession: String?,
)

/** Hockey strip. The time and bar exist only when core delivered a power-play clock (switch on and unambiguous). */
data class TvHockeyStrip(
    /** "Power play · DAL" or "Even strength". */
    val chipLabel: String,
    val onPowerPlay: Boolean,
    val powerPlaySide: String?,
    /** "1:12" as of the last update; nothing drains between updates. */
    val timeLeftText: String?,
    /** 0..1 of the penalty that is left, only with [timeLeftText]. */
    val fractionLeft: Float?,
    val homeShots: Int?, val awayShots: Int?,
    /** "1/2 – 0/1". */
    val powerPlayText: String?,
    val homeFaceoffs: Int?, val awayFaceoffs: Int?,
)

/** What the left column can afford. The chart is the first thing to drop, then the last-play line (spec 03 B3). */
data class TvLeftFit(val showChart: Boolean, val showLastPlay: Boolean)

/**
 * Pure budget for the fixed-height left column (design dp on the 540 high canvas, the same units as the SwiftUI layout).
 * Order of sacrifice (spec 03 B3): the win-chance chart first, then the last-play line. Heights are the drawn heights.
 */
object TvLeftColumnFit {
    private const val AVAILABLE = 452          // 540 - top/bottom padding - league bar - "Updated" label
    private const val SCOREBOARD = 84
    private const val SCORER_LINE = 14
    private const val LAST_PLAY = 30
    private const val WIN_BASE = 82
    private const val WIN_LINE = 14
    private const val WIN_CHART = 52
    private const val PLAY_ROW = 56
    private const val DIVIDER_ROW = 14
    private const val GAP = 7

    fun fit(stripDp: Int, scorerLines: Int, hasLine: Boolean, hasChart: Boolean, hasLastPlay: Boolean, hasDividers: Boolean = false): TvLeftFit {
        fun total(chart: Boolean, lastPlay: Boolean): Int {
            var parts = 3                                   // scoreboard, win card, play row
            var h = SCOREBOARD + scorerLines * SCORER_LINE + WIN_BASE + (if (hasLine) WIN_LINE else 0) + PLAY_ROW
            if (stripDp > 0) { h += stripDp; parts++ }
            if (lastPlay) { h += LAST_PLAY; parts++ }
            if (chart) h += WIN_CHART + (if (hasDividers) DIVIDER_ROW else 0)
            return h + GAP * (parts - 1)
        }
        var chart = hasChart
        var last = hasLastPlay
        if (total(chart, last) > AVAILABLE) chart = false
        if (total(chart, last) > AVAILABLE) last = false
        return TvLeftFit(showChart = chart, showLastPlay = last)
    }
}
