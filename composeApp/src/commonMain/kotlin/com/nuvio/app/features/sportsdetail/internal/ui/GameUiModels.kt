package com.nuvio.app.features.sportsdetail.internal.ui

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.GameSection

/** Pure view-models for the game screen: every decision a composable would otherwise make lives here and is unit-tested. */

internal enum class StatLeader { HOME, AWAY, NONE }

internal data class StatBarRow(
    val label: String,
    val home: String,
    val away: String,
    /** Home's flex share 0..1; null when either value is not a plain number (e.g. "4/11") or both are zero. */
    val homeFraction: Float?,
    val leader: StatLeader,
)

internal object StatsPanel {
    fun rows(b: StatsBlock): List<StatBarRow> = b.rows.map { r ->
        val h = number(r.home)
        val a = number(r.away)
        val unit = r.unit.orEmpty()
        val bar = if (h != null && a != null && h + a > 0.0) (h / (h + a)).toFloat() else null
        val leader = when {
            h == null || a == null || h == a -> StatLeader.NONE
            GameDetailUiPolicy.lowerIsBetter(r.label) -> if (h < a) StatLeader.HOME else StatLeader.AWAY
            else -> if (h > a) StatLeader.HOME else StatLeader.AWAY
        }
        StatBarRow(r.label, r.home + unit, r.away + unit, bar, leader)
    }

    /** A plain non-negative number ("12", "55.5"); anything with a slash or colon is not a barrable value. */
    private fun number(s: String): Double? = s.trim().removeSuffix("%").replace(",", "").toDoubleOrNull()?.takeIf { it >= 0.0 }
}

internal data class ScoreBarText(val home: String, val away: String, val homeScore: String, val awayScore: String, val clock: String)

internal object GameScoreBar {
    fun of(d: GameDetail): ScoreBarText? {
        val h = d.header as? TeamsHeader ?: return null
        val clock = when (d.state) {
            GameState.LIVE -> GameDetailUiPolicy.liveStatusText(d.live)
            GameState.FINAL -> d.live?.detail?.takeIf { it.isNotBlank() } ?: "Final"
            else -> ""
        }
        return ScoreBarText(
            home = h.home.short?.takeIf { it.isNotBlank() } ?: h.home.name,
            away = h.away.short?.takeIf { it.isNotBlank() } ?: h.away.name,
            homeScore = h.home.score ?: "–",
            awayScore = h.away.score ?: "–",
            clock = clock,
        )
    }
}

/** ESPN marks unranked as 99; the model already maps that to null, this guards any other source. */
internal fun rankText(rank: Int?): String? = rank?.takeIf { it in 1..98 }?.toString()

internal data class PlayGroup(val label: String, val items: List<TimelineItem>)

internal object PlaysGrouping {
    /** Consecutive items sharing a period become one group; order is the feed's (newest first). */
    fun groups(items: List<TimelineItem>): List<PlayGroup> {
        val out = mutableListOf<PlayGroup>()
        items.forEach { item ->
            val label = item.period?.uppercase().orEmpty()
            val last = out.lastOrNull()
            if (last != null && last.label == label) out[out.lastIndex] = last.copy(items = last.items + item) else out += PlayGroup(label, listOf(item))
        }
        return out
    }
}

internal object GameTabsUi {
    fun label(s: GameSection): String = when (s) {
        GameSection.OVERVIEW -> "Overview"
        GameSection.PLAYS -> "Plays"
        GameSection.STATS -> "Stats"
        GameSection.LEADERS -> "Leaders"
        GameSection.LINEUPS -> "Lineups"
        GameSection.STANDINGS -> "Standings"
    }

    /** The user's choice while it still exists, else Overview (a section can vanish between refreshes). */
    fun selected(available: List<GameSection>, current: GameSection): GameSection =
        if (current in available) current else GameSection.OVERVIEW
}

internal data class WinChanceModel(
    val leaderSide: String,
    val leaderName: String,
    val leaderPercent: Int,
    val otherName: String,
    val otherPercent: Int,
    val drawPercent: Int?,
    val homePercent: Int,
    val awayPercent: Int,
) {
    val leaderLabel: String get() = "$leaderName to win"

    /** The provider's series is HOME's share; the card plots the LEADER's, so the line ends where the big number is. */
    fun seriesFor(homeSeries: List<Float>): List<Float> = if (leaderSide == "home") homeSeries else homeSeries.map { 1f - it }

    companion object {
        fun of(w: WinProbabilityBlock, header: Header?): WinChanceModel {
            val home = w.homePercent.coerceIn(0, 100)
            val draw = w.drawPercent?.coerceIn(0, 100)?.takeIf { it > 0 }
            val away = (w.awayPercent ?: (100 - home - (draw ?: 0))).coerceIn(0, 100)
            val (hn, an) = (header as? TeamsHeader)?.let { it.home.name to it.away.name } ?: ("Home" to "Away")
            val homeLeads = home >= away
            return WinChanceModel(
                leaderSide = if (homeLeads) "home" else "away",
                leaderName = if (homeLeads) hn else an,
                leaderPercent = if (homeLeads) home else away,
                otherName = if (homeLeads) an else hn,
                otherPercent = if (homeLeads) away else home,
                drawPercent = draw,
                homePercent = home,
                awayPercent = away,
            )
        }
    }
}

/** The compact Play button of the slim score bar triggers the same primary watch action as the Watch row. */
internal object WatchPrimary {
    fun offer(ui: com.nuvio.app.features.sportsdetail.api.WatchUi): com.nuvio.app.features.sportsdetail.api.WatchOffer? = when (ui) {
        is com.nuvio.app.features.sportsdetail.api.WatchUi.Browsing -> ui.offer
        is com.nuvio.app.features.sportsdetail.api.WatchUi.Watching -> ui.switchTo
        else -> null
    }
}

/** Scorers under a team name: at most [max] lines, then "+N" for the rest. */
internal object ScorerLines {
    fun of(scorers: List<String>, max: Int = 3): List<String> =
        if (scorers.size <= max) scorers else scorers.take(max) + "+${scorers.size - max}"
}

/** Label and tone of the soccer strip's last-event chip. */
internal enum class EventTone { GOAL, YELLOW, RED, NEUTRAL }

internal data class EventChip(val label: String, val tone: EventTone)

internal object SoccerChips {
    fun chip(kind: String): EventChip = when (kind) {
        "goal" -> EventChip("GOAL", EventTone.GOAL)
        "penalty" -> EventChip("PENALTY", EventTone.GOAL)
        "yellow" -> EventChip("YELLOW CARD", EventTone.YELLOW)
        "red" -> EventChip("RED CARD", EventTone.RED)
        "sub" -> EventChip("SUBSTITUTION", EventTone.NEUTRAL)
        "corner" -> EventChip("CORNER", EventTone.NEUTRAL)
        "var" -> EventChip("VAR", EventTone.NEUTRAL)
        "shot" -> EventChip("SHOT", EventTone.NEUTRAL)
        "offside" -> EventChip("OFFSIDE", EventTone.NEUTRAL)
        "foul" -> EventChip("FOUL", EventTone.NEUTRAL)
        else -> EventChip(kind.uppercase(), EventTone.NEUTRAL)
    }

    /** "Cards ■1 – ■2" with reds appended only when > 0, e.g. "■1 ■1red". Returns the per-side count text. */
    fun cardsText(yellow: Int, red: Int): String = if (red > 0) "■$yellow ▮$red" else "■$yellow"

    fun awayPossession(home: Int?): Int? = home?.let { 100 - it }
}
