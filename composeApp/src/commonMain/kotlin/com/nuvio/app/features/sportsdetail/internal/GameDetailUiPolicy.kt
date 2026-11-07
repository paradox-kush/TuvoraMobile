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

    /** Momentum line as given by the server (home's per-point non-draw share): 0..1, time-ordered, at most 60 points; null when < 2 points. */
    fun chartPoints(history: List<WinPoint>): List<Float>? {
        if (history.size < 2) return null
        val sorted = history.sortedBy { it.t }
        val picked = if (sorted.size <= CHART_MAX_POINTS) sorted else (0 until CHART_MAX_POINTS).map { sorted[(it.toLong() * (sorted.size - 1) / (CHART_MAX_POINTS - 1)).toInt()] }
        return picked.map { it.home.coerceIn(0, 100) / 100f }
    }

    /** The trailing team gained [rise] win-chance points across the last 10 minutes of the series. */
    data class Comeback(val side: String, val rise: Int)

    private const val COMEBACK_WINDOW_MS = 10L * 60 * 1000
    private const val COMEBACK_MIN_RISE = 15
    private const val COMEBACK_MIN_POINTS = 3
    private const val SERIES_FRESH_MS = 2L * 60 * 1000

    /**
     * "Comeback watch". Only for a LIVE game, with a series whose last point is within ~2 min of the server's [asOfMs], at least
     * three points inside the last 10 minutes, and the rise measured from the START of that window to its end (a spike that came
     * back down is nothing). Who is "trailing" comes from the headline price ([headlineHome]), not from the series' last point.
     */
    fun comebackWatch(history: List<WinPoint>, headlineHome: Int, state: GameState, asOfMs: Long?): Comeback? {
        if (state != GameState.LIVE || asOfMs == null || history.size < COMEBACK_MIN_POINTS) return null
        val pts = history.sortedBy { it.t }
        val last = pts.last()
        if (asOfMs - last.t > SERIES_FRESH_MS) return null
        val window = pts.filter { it.t >= last.t - COMEBACK_WINDOW_MS }
        if (window.size < COMEBACK_MIN_POINTS) return null
        val start = window.first().home
        val end = last.home
        val (side, rise) = when {
            headlineHome < 50 -> "home" to (end - start)
            headlineHome > 50 -> "away" to (start - end)
            else -> return null
        }
        return if (rise >= COMEBACK_MIN_RISE) Comeback(side, rise) else null
    }

    fun comebackText(c: Comeback, header: Header): String? = teamTag(c.side, header)?.let { "Comeback watch · $it +${c.rise} in 10 min" }

    /** "Goes to overtime · 23%" / "Leading at half-time: DEN · 62%" (a one-sided storyline carries its team tag); at most [max] lines. */
    fun storylineLines(storylines: List<Storyline>, header: Header?, max: Int = 3): List<String> =
        storylines.filter { it.label.isNotBlank() }.take(max).map { s ->
            val label = s.label.trim()
            val tag = header?.let { teamTag(s.side, it) }
            val named = if (tag != null && !label.contains(tag, ignoreCase = true)) "$label: $tag" else label
            "$named · ${s.yes.coerceIn(0, 100)}%"
        }

    private fun halfBoundary(sport: String?): Int? = when (sport?.lowercase()) { "soccer" -> 1; "basketball", "american football" -> 2; else -> null }
    private fun regulationPeriods(sport: String?): Int? = when (sport?.lowercase()) { "basketball", "american football" -> 4; "ice hockey" -> 3; else -> null }

    /** Storylines only before and during the game; first-half ones vanish after half-time, overtime ones once overtime has started. */
    fun visibleStorylines(storylines: List<Storyline>, state: GameState, live: LiveInfo?, sport: String?): List<Storyline> {
        if (state != GameState.UPCOMING && state != GameState.LIVE) return emptyList()
        val period = if (state == GameState.LIVE) live?.period else null
        val regulation = regulationPeriods(sport)
        val inOvertime = state == GameState.LIVE && (live?.periodLabel?.contains("OT", ignoreCase = true) == true || (period != null && regulation != null && period > regulation))
        val half = halfBoundary(sport)
        return storylines.filter { s ->
            val key = s.key.lowercase()
            when {
                "first_half" in key -> period == null || half == null || period <= half
                "overtime" in key -> !inOvertime
                else -> true
            }
        }
    }

    /** The win-chance card's OWN staleness: shown once the market value is older than 2 minutes (a server-cached minute is normal). */
    fun blockStaleLabel(asOfMs: Long?, nowMs: Long, thresholdMs: Long = 120_000L): String? {
        val age = nowMs - (asOfMs ?: return null)
        if (age <= thresholdMs) return null
        val minutes = age / 60_000
        return if (minutes < 60) "Updated $minutes min ago" else "Updated ${minutes / 60} h ago"
    }

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
