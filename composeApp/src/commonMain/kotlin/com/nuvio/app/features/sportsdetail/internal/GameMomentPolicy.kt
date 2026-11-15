package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock

/** One thing that really changed between two snapshots. The UI animates the net change once; it never invents in-between state. */
internal sealed interface Moment {
    data class ScoreChange(val side: String, val to: String) : Moment
    data class WinChanceMove(val fromHome: Int, val toHome: Int) : Moment
    data class NewPlay(val item: TimelineItem) : Moment
}

internal object GameMomentPolicy {
    /** prev == null, a different game, or a state change into LIVE -> emptyList(): nothing animates when the screen opens. */
    fun diff(prev: GameDetail?, next: GameDetail): List<Moment> {
        if (prev == null || prev.gameId != next.gameId) return emptyList()
        if (prev.state != GameState.LIVE && next.state == GameState.LIVE) return emptyList()
        return buildList {
            addAll(scoreChanges(prev, next))
            winChance(prev, next)?.let(::add)
            newPlay(prev, next)?.let(::add)
        }
    }

    private fun scores(d: GameDetail): Pair<String?, String?> = when (val h = d.header) {
        is TeamsHeader -> h.home.score to h.away.score
        is VersusHeader -> h.a.score to h.b.score
        else -> null to null
    }

    private fun scoreChanges(prev: GameDetail, next: GameDetail): List<Moment> {
        val (ph, pa) = scores(prev)
        val (nh, na) = scores(next)
        return buildList {
            if (ph != null && nh != null && ph != nh) add(Moment.ScoreChange("home", nh))
            if (pa != null && na != null && pa != na) add(Moment.ScoreChange("away", na))
        }
    }

    private fun winChance(prev: GameDetail, next: GameDetail): Moment? {
        val a = prev.blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull() ?: return null
        val b = next.blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull() ?: return null
        return if (a.homePercent != b.homePercent) Moment.WinChanceMove(a.homePercent, b.homePercent) else null
    }

    /** The newest play: first of [PlaysBlock] (newest first), last of the scoring [TimelineBlock] (oldest first). */
    internal fun newest(d: GameDetail): TimelineItem? =
        d.blocks.filterIsInstance<PlaysBlock>().firstOrNull()?.items?.firstOrNull()
            ?: d.blocks.filterIsInstance<TimelineBlock>().firstOrNull()?.items?.lastOrNull()

    private fun same(a: TimelineItem, b: TimelineItem): Boolean =
        if (a.id != null && b.id != null) a.id == b.id else a.time == b.time && a.text == b.text && a.period == b.period

    private fun newPlay(prev: GameDetail, next: GameDetail): Moment? {
        val n = newest(next) ?: return null
        val p = newest(prev)
        return if (p == null || !same(p, n)) Moment.NewPlay(n) else null
    }
}
