package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Storyline
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.NextBlock
import com.nuvio.app.features.sportsdetail.api.ResultsBlock
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StandingsBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.UnknownBlock

/** Pure presentation decisions for the game screen. */
internal object GameDetailUiPolicy {
    const val STALE_AFTER_MS = 20_000L
    const val WIDE_MIN_DP = 768

    /** Wide (iPad / landscape) layout: score-and-schedule blocks on the left, the detail blocks on the right. */
    fun isPrimaryColumn(b: GameBlock): Boolean = when (b) {
        is LinescoreBlock, is BroadcastBlock, is FormBlock, is HighlightsBlock, is SituationBlock, is ResultsBlock,
        is com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock -> true
        else -> false
    }

    /** The screen's own backstop: a Loading past its deadline is shown as Failed(timedOut) whatever the work is doing. */
    fun effective(state: GameDetailUiState, nowMs: Long): GameDetailUiState =
        if (state is GameDetailUiState.Loading && nowMs >= state.deadlineAtMs) GameDetailUiState.Failed(timedOut = true) else state

    /** Links from a provider open only if they are plain web links. */
    fun isSafeLink(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("http://")
    }

    /** The live line under the score: "2nd · 5:12", or the break wording (Halftime / Intermission) instead of a stopped clock. */
    fun liveStatusText(live: LiveInfo?): String {
        val detail = live?.detail?.lowercase().orEmpty()
        return when {
            "halftime" in detail || "half-time" in detail -> "Halftime"
            "intermission" in detail || "end of" in detail || "break" in detail -> "Intermission"
            else -> listOfNotNull(live?.periodLabel, live?.clock?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "LIVE" }
        }
    }

    private val TOTAL_COLUMNS = setOf("T", "R", "H", "E")

    /** While live, periods after the current one have not started: hide their columns (totals stay). */
    fun trimUnstartedPeriods(b: LinescoreBlock, state: GameState, livePeriod: Int?): LinescoreBlock {
        if (state != GameState.LIVE || livePeriod == null || livePeriod < 1) return b
        val tail = b.columns.takeLastWhile { it in TOTAL_COLUMNS }.size
        val periods = b.columns.size - tail
        if (livePeriod >= periods) return b
        val keep = (0 until livePeriod) + (periods until b.columns.size)
        return b.copy(columns = keep.map { b.columns[it] }, rows = b.rows.map { r -> keep.mapNotNull { r.getOrNull(it) } })
    }

    /** "WSH" for a row's side; the full name when the provider gave no short name; null for no side / no teams. */
    fun teamTag(side: String?, header: Header): String? {
        val s = when (header) {
            is TeamsHeader -> if (side == "home") header.home else if (side == "away") header.away else null
            is VersusHeader -> if (side == "home") header.a else if (side == "away") header.b else null
            else -> null
        } ?: return null
        return s.short?.takeIf { it.isNotBlank() } ?: s.name
    }

    private const val CHART_MAX_POINTS = 60

    /** Momentum line: home's normalised share as 0..1, time-ordered, at most 60 points; null when there is no line to draw (< 2 points). */
    fun chartPoints(history: List<WinPoint>, drawPercent: Int? = null): List<Float>? {
        if (history.size < 2) return null
        val sorted = history.sortedBy { it.t }
        val picked = if (sorted.size <= CHART_MAX_POINTS) sorted else (0 until CHART_MAX_POINTS).map { sorted[(it.toLong() * (sorted.size - 1) / (CHART_MAX_POINTS - 1)).toInt()] }
        // Home's share of the non-draw mass (soccer: 30% home with a 25% draw plots at 0.4); series points carry only home.
        val mass = (100 - (drawPercent ?: 0)).coerceAtLeast(1)
        return picked.map { (it.home.coerceIn(0, 100).toFloat() / mass).coerceIn(0f, 1f) }
    }

    /** The trailing team gained [rise] win-chance points within the last 10 minutes of the series. */
    data class Comeback(val side: String, val rise: Int)

    private const val COMEBACK_WINDOW_MS = 10L * 60 * 1000
    private const val COMEBACK_MIN_RISE = 15

    /**
     * "Comeback watch": the team currently trailing (share < 50%) gained ≥ 15 points within the last 10 minutes of the series
     * (anchored at its latest point). Shares are home's non-draw mass, as the chart plots them. Null when there is no such move.
     */
    fun comebackWatch(history: List<WinPoint>, drawPercent: Int?): Comeback? {
        if (history.size < 2) return null
        val pts = history.sortedBy { it.t }
        val mass = (100 - (drawPercent ?: 0)).coerceAtLeast(1).toFloat()
        fun share(p: WinPoint) = p.home.coerceIn(0, 100) / mass * 100f
        val last = pts.last()
        val window = pts.filter { it.t >= last.t - COMEBACK_WINDOW_MS }
        val now = share(last)
        val (side, rise) = when {
            now < 50f -> "home" to now.let { n -> n - window.minOf { share(it) } }
            now > 50f -> "away" to window.maxOf { share(it) }.let { m -> m - now }
            else -> return null
        }
        val points = kotlin.math.round(rise).toInt()
        return if (points >= COMEBACK_MIN_RISE) Comeback(side, points) else null
    }

    fun comebackText(c: Comeback, header: Header): String? = teamTag(c.side, header)?.let { "Comeback watch · $it +${c.rise} in 10 min" }

    /** "Goes to overtime · 23%": blank labels dropped, at most [max] lines (3 on phone/iPad, 1 on TV). */
    fun storylineLines(storylines: List<Storyline>, max: Int = 3): List<String> =
        storylines.filter { it.label.isNotBlank() }.take(max).map { "${it.label.trim()} · ${it.yes.coerceIn(0, 100)}%" }

    /** "via Polymarket" / "via ESPN": the block's attribution, else derived from its source; null when unknown. */
    fun winChanceCaption(b: WinProbabilityBlock): String? = b.attribution?.takeIf { it.isNotBlank() } ?: when (b.source) {
        "espn" -> "via ESPN"
        "polymarket" -> "via Polymarket"
        else -> null
    }

    fun isWide(widthDp: Int): Boolean = widthDp >= WIDE_MIN_DP

    /** "Updated 40s ago" once even the freshest source fetch is older than 20 s; null while fresh. */
    fun staleLabel(asOf: Map<String, Long>, nowMs: Long): String? {
        // The FRESHEST fetch of any source: the chip says how stale the screen really is, not how stale its laggiest part is.
        val newest = asOf.values.maxOrNull() ?: return null
        val ageMs = nowMs - newest
        if (ageMs <= STALE_AFTER_MS) return null
        val s = ageMs / 1000
        return when {
            s < 60 -> "Updated ${s}s ago"
            s < 3600 -> "Updated ${s / 60} min ago"
            else -> "Updated ${s / 3600} h ago"
        }
    }

    /** An empty block hides instead of rendering a heading over nothing. */
    fun isEmpty(b: GameBlock): Boolean = when (b) {
        is StatsBlock -> b.rows.isEmpty()
        is LinescoreBlock -> b.rows.isEmpty() || b.columns.isEmpty()
        is TimelineBlock -> b.items.isEmpty()
        is LeadersBlock -> b.items.isEmpty()
        is BroadcastBlock -> true   // owner decision: the broadcast block is never shown
        is FormBlock -> b.home.isEmpty() && b.away.isEmpty()
        is SituationBlock -> b.line.isBlank()
        is HighlightsBlock -> b.url.isBlank()
        is LineupBlock -> b.sides.all { it.players.isEmpty() }
        is ResultsBlock -> b.rows.isEmpty()
        is StandingsBlock -> b.rows.isEmpty()
        is NextBlock -> b.items.isEmpty()
        is UnknownBlock -> true
        else -> false
    }
}
