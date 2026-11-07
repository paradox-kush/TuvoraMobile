package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
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

    fun isWide(widthDp: Int): Boolean = widthDp >= WIDE_MIN_DP

    /** "Updated 40s ago" once the OLDEST source is older than 20 s; null while fresh. */
    fun staleLabel(asOf: Map<String, Long>, nowMs: Long): String? {
        val oldest = asOf.values.minOrNull() ?: return null
        val ageMs = nowMs - oldest
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
        is BroadcastBlock -> b.byRegion.values.all { it.isEmpty() }
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
