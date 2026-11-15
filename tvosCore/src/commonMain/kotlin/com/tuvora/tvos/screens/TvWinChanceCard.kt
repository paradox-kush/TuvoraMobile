package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy

/**
 * What the Apple TV win-chance card shows, decided in shared Kotlin so Swift only draws it (mirrors Android TV's
 * `TvWinChance`). Whole percents plus text; deliberately NO price, odds, order book or link field: the market value is
 * shown as a probability, credited ("Market-implied · via Polymarket"), never as something to bet on.
 */
data class TvWinChance(
    val homePercent: Int,
    val awayPercent: Int,
    val drawPercent: Int?,
    /** Credit + wording, always present for a market value ("Market-implied · via Polymarket" / "via ESPN"). */
    val caption: String?,
    /** At most ONE extra line: the comeback watch, else the first visible storyline. */
    val line: String?,
    /** The card's own "Updated 5 min ago" (live game, market headline, value older than 2 min). */
    val staleLabel: String?,
)

object TvWinChanceCard {
    fun from(block: WinProbabilityBlock, detail: GameDetail, nowMs: Long): TvWinChance {
        val home = block.homePercent.coerceIn(0, 100)
        val draw = block.drawPercent?.coerceIn(0, 100)?.takeIf { it > 0 }
        val away = (block.awayPercent ?: (100 - home - (draw ?: 0))).coerceIn(0, 100)
        val comeback = GameDetailUiPolicy.comebackWatch(block.history, home, away, detail.state, block.asOfMs)
            ?.let { GameDetailUiPolicy.comebackText(it, detail.header) }
        val line = comeback ?: GameDetailUiPolicy.storylineLines(
            GameDetailUiPolicy.visibleStorylines(block.storylines, detail.state, detail.live, detail.sport), detail.header, max = 1,
        ).firstOrNull()
        // A storyline from another source than the headline (an ESPN bar over Polymarket extras) is credited too.
        val credit = block.extrasAttribution?.takeIf { it.isNotBlank() && line != null && block.source != "polymarket" }
            ?.let { "storyline " + it.replaceFirstChar { c -> c.lowercase() } }
        return TvWinChance(
            homePercent = home, awayPercent = away, drawPercent = draw,
            caption = listOfNotNull(GameDetailUiPolicy.winChanceCaption(block), credit).joinToString(" · ").ifBlank { null },
            line = line,
            staleLabel = GameDetailUiPolicy.winChanceStaleLabel(block, detail.state, nowMs),
        )
    }
}
