package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlin.math.abs

/** [x], [y] in 0..1 chart space; [playId] ties a marker to its play so a marker is recognised across snapshots. */
/** [y] is in the PLOTTED space of [WinChanceSeries]; [reference] is that chart's reference line, which decides the label side. */
internal data class ChartMarker(val x: Float, val y: Float, val label: String, val side: String?, val playId: String? = null, val reference: Float = 0.5f)

/**
 * Scoring moments and period boundaries on the win-chance chart. Rule: a play with no wall clock, or with no series point within
 * the tolerance, gets NO marker - a position is never estimated from the game clock. Markers are matched on the FULL series, then
 * placed on the nearest point of the DRAWN (down-sampled) series, so a marker always sits on the line that is on screen.
 */
internal object ChartMarkers {
    const val MAX_MARKERS = 8
    const val TOLERANCE_MS = 90_000L
    const val SWITCH = "chartMarkers"
    /** Labels closer than this share of the chart width would overlap: the newer one is kept. */
    private const val MIN_GAP_X = 0.05f

    /** The label sits above the line from the chart's reference line (50 % two-way, an even third three-way) and below it under that. */
    fun labelAbove(m: ChartMarker): Boolean = m.y >= m.reference

    /**
     * football TD / FG, hockey G / PPG, soccer G / RC (red card); basketball none. At most [max] (the newest), returned left to right.
     * [plays] are the key events of the game ([PlaysBlock.chartItems]), any order.
     */
    fun markers(history: List<WinPoint>, plays: List<TimelineItem>, sport: String, max: Int = MAX_MARKERS, toleranceMs: Long = TOLERANCE_MS, spec: WinChanceSpec = WinChanceSpec.HOME_TWO_WAY): List<ChartMarker> {
        val filled = WinChanceSeries.withDrawFilled(history, spec)
        val full = filled.sortedBy { it.t }
        val drawn = GameDetailUiPolicy.chartSeries(filled) ?: return emptyList()
        val kept = ArrayList<ChartMarker>()
        for (p in plays.sortedByDescending { it.wallclockMs ?: Long.MIN_VALUE }) {
            if (kept.size >= max) break
            val wall = p.wallclockMs ?: continue
            val label = label(sport, p) ?: continue
            val nearest = full.minBy { abs(it.t - wall) }
            if (abs(nearest.t - wall) > toleranceMs) continue
            val idx = drawn.indices.minBy { abs(drawn[it].t - nearest.t) }
            val x = idx / (drawn.size - 1).toFloat()
            if (kept.any { abs(it.x - x) < MIN_GAP_X }) continue          // overlap: the kept one is newer
            kept += ChartMarker(x, WinChanceSeries.valueOf(drawn[idx], spec), label, p.side, p.id, spec.reference)
        }
        return kept.sortedBy { it.x }
    }

    /**
     * Period starts as (x, label), left to right. Only a real "periodStart" play draws a divider (an end of period n-1 is the start of a break
     * for halftime and quarter breaks, so it is never used as a guess): sports whose feed has no period-start plays draw none. A start with no
     * wall clock, outside the series, or the start of the game is skipped.
     */
    fun dividers(history: List<WinPoint>, plays: List<TimelineItem>, sport: String = ""): List<Pair<Float, String>> {
        val drawn = GameDetailUiPolicy.chartSeries(history) ?: return emptyList()
        val tMin = drawn.first().t
        val tMax = drawn.last().t
        val starts = HashMap<Int, Long>()
        for (p in plays) {
            val w = p.wallclockMs ?: continue
            val n = periodNumber(p.period) ?: continue
            when (p.kind) {
                "periodStart" -> starts.getOrPut(n) { w }
            }
        }
        return starts.keys.filter { it >= 2 }.sorted().mapNotNull { n ->
            val w = starts[n] ?: return@mapNotNull null
            if (w !in tMin..tMax) return@mapNotNull null
            val idx = drawn.indices.minBy { abs(drawn[it].t - w) }
            idx / (drawn.size - 1).toFloat() to boundaryLabel(sport, n)
        }.sortedBy { it.first }
    }

    /** Markers for a screen: none when the `chartMarkers` switch is off, there is no series (ESPN-only win chance) or no key events. */
    fun markersFor(d: GameDetail, max: Int = MAX_MARKERS): List<ChartMarker> {
        if (SWITCH !in d.uiSwitches) return emptyList()
        val w = d.blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull() ?: return emptyList()
        val items = d.blocks.filterIsInstance<PlaysBlock>().firstOrNull()?.chartItems ?: return emptyList()
        return markers(w.history, items, d.sport, max, spec = WinChanceSeries.specOf(w))
    }

    fun dividersFor(d: GameDetail): List<Pair<Float, String>> {
        if (SWITCH !in d.uiSwitches) return emptyList()
        val series = d.blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull()?.history ?: return emptyList()
        val items = d.blocks.filterIsInstance<PlaysBlock>().firstOrNull()?.chartItems ?: return emptyList()
        return dividers(series, items, d.sport)
    }

    private fun label(sport: String, p: TimelineItem): String? {
        val s = sport.lowercase()
        val text = p.text.lowercase()
        return when {
            "football" in s -> if (p.kind == "score") when { "touchdown" in text -> "TD"; "field goal" in text -> "FG"; else -> null } else null
            "hockey" in s -> when (p.kind) { "goal" -> "G"; "ppg" -> "PPG"; else -> null }
            "soccer" in s -> when { p.kind == "goal" -> "G"; p.kind == "card" && "red card" in text -> "RC"; else -> null }
            else -> null
        }
    }

    private fun periodNumber(period: String?): Int? = period?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    private fun boundaryLabel(sport: String, n: Int): String {
        val s = sport.lowercase()
        return when {
            "soccer" in s -> when (n) { 2 -> "45'"; 3 -> "90'"; else -> "ET" }
            "hockey" in s -> if (n <= 3) "P$n" else "OT"
            "basketball" in s || "football" in s -> if (n <= 4) "Q$n" else "OT"
            else -> "P$n"
        }
    }
}
