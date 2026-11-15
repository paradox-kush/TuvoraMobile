package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
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
    // Phase B
    data class FieldMove(val fromYard: Int, val toYard: Int, val loss: Boolean, val firstDown: Boolean) : Moment
    data class Touchdown(val side: String) : Moment
    data class PossessionChange(val to: String) : Moment
    data class SoccerMoment(val event: com.nuvio.app.features.sportsdetail.api.SoccerEvent) : Moment
}

internal object GameMomentPolicy {
    /** prev == null, a different game, or a state change into LIVE -> emptyList(): nothing animates when the screen opens. */
    fun diff(prev: GameDetail?, next: GameDetail): List<Moment> {
        if (prev == null || prev.gameId != next.gameId) return emptyList()
        if (prev.state != GameState.LIVE && next.state == GameState.LIVE) return emptyList()
        return buildList {
            addAll(scoreChanges(prev, next))
            touchdown(prev, next)?.let(::add)
            winChance(prev, next)?.let(::add)
            fieldMove(prev, next)?.let(::add)
            soccer(prev, next)?.let(::add)
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

    private fun intScore(s: String?): Int? = s?.trim()?.toIntOrNull()

    /** A score rise of 6 or more for the side that had the ball in the previous snapshot. */
    private fun touchdown(prev: GameDetail, next: GameDetail): Moment? {
        val side = prev.blocks.filterIsInstance<FootballFieldBlock>().firstOrNull()?.possession ?: return null
        val (ph, pa) = scores(prev)
        val (nh, na) = scores(next)
        val rise = if (side == "home") (intScore(nh) ?: return null) - (intScore(ph) ?: return null) else (intScore(na) ?: return null) - (intScore(pa) ?: return null)
        return if (rise >= 6) Moment.Touchdown(side) else null
    }

    /** One FieldMove for any gain/loss with the same team on the ball (a jump over several plays is still one); a flip is a PossessionChange. */
    private fun fieldMove(prev: GameDetail, next: GameDetail): Moment? {
        val a = prev.blocks.filterIsInstance<FootballFieldBlock>().firstOrNull() ?: return null
        val b = next.blocks.filterIsInstance<FootballFieldBlock>().firstOrNull() ?: return null
        val pa = a.possession ?: return null
        val pb = b.possession ?: return null
        if (pa != pb) return Moment.PossessionChange(pb)
        if (a.yardLine == b.yardLine) return null
        val loss = if (pb == "home") b.yardLine < a.yardLine else b.yardLine > a.yardLine
        return Moment.FieldMove(a.yardLine, b.yardLine, loss = loss, firstDown = b.down == 1 && a.down != 1)
    }

    /** A different real last event than before (compared by kind, minute and text); the event disappearing animates nothing. */
    private fun soccer(prev: GameDetail, next: GameDetail): Moment? {
        val n = next.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull()?.lastEvent ?: return null
        val p = prev.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull()?.lastEvent
        return if (p == null || p.kind != n.kind || p.minute != n.minute || p.text != n.text) Moment.SoccerMoment(n) else null
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
