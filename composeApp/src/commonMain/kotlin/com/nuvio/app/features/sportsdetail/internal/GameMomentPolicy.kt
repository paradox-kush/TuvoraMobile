package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.radar.ScoreSlots
import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
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
    // Phase C
    data class PowerPlay(val side: String?, val started: Boolean) : Moment
    data class FoulCount(val side: String, val to: Int) : Moment
    data class BonusReached(val side: String) : Moment
    data class TimeoutUsed(val side: String) : Moment
    // Phase D
    data class MarkerAdded(val marker: ChartMarker) : Moment
    data class StatChange(val label: String, val leaderChanged: Boolean) : Moment
}

internal object GameMomentPolicy {
    /**
     * prev == null, a different game, or a state change into LIVE -> emptyList(): nothing animates when the screen opens.
     * Same for a change of Hide scores / delay mode (shown, hidden, pending): what is SHOWN changed, not the game — a
     * reveal, or the delay's "–" falling due, must never pulse a goal, speak a score or replay a play.
     */
    fun diff(prev: GameDetail?, next: GameDetail): List<Moment> {
        if (prev == null || prev.gameId != next.gameId) return emptyList()
        if (prev.spoilerRedaction != next.spoilerRedaction) return emptyList()
        if (prev.state != GameState.LIVE && next.state == GameState.LIVE) return emptyList()
        return buildList {
            addAll(scoreChanges(prev, next))
            touchdown(prev, next)?.let(::add)
            winChance(prev, next)?.let(::add)
            fieldMove(prev, next)?.let(::add)
            soccer(prev, next)?.let(::add)
            hockey(prev, next)?.let(::add)
            addAll(basketball(prev, next))
            addAll(markers(prev, next))
            addAll(statChanges(prev, next))
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
        // The delay's "–" on either side is not a score: nothing to compare against.
        if (listOf(ph, pa, nh, na).any { it == ScoreSlots.PLACEHOLDER }) return emptyList()
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
        return Moment.FieldMove(a.yardLine, b.yardLine, loss = loss, firstDown = b.down == 1 && (a.down != 1 || (!loss && a.distance != null && kotlin.math.abs(b.yardLine - a.yardLine) >= a.distance)))
    }

    /** A different real last event than before (compared by kind, minute and text); the event disappearing animates nothing. */
    private fun soccer(prev: GameDetail, next: GameDetail): Moment? {
        val n = next.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull()?.lastEvent ?: return null
        val p = prev.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull()?.lastEvent
        return if (p == null || p.kind != n.kind || p.minute != n.minute || p.text != n.text) Moment.SoccerMoment(n) else null
    }

    /** The power play side changing: a side gained it (started) or it went back to even strength. The bar stepping is not a moment. */
    private fun hockey(prev: GameDetail, next: GameDetail): Moment? {
        val a = prev.blocks.filterIsInstance<HockeyStripBlock>().firstOrNull() ?: return null
        val b = next.blocks.filterIsInstance<HockeyStripBlock>().firstOrNull() ?: return null
        if (a.powerPlaySide == b.powerPlaySide) return null
        return if (b.powerPlaySide != null) Moment.PowerPlay(b.powerPlaySide, started = true) else Moment.PowerPlay(null, started = false)
    }

    /** New team fouls, the bonus switching on, a timeout used. A new period resetting the fouls is not a moment. */
    private fun basketball(prev: GameDetail, next: GameDetail): List<Moment> {
        val a = prev.blocks.filterIsInstance<BasketballStripBlock>().firstOrNull() ?: return emptyList()
        val b = next.blocks.filterIsInstance<BasketballStripBlock>().firstOrNull() ?: return emptyList()
        return buildList {
            for (side in listOf("home", "away")) {
                val pf = if (side == "home") a.homeFouls else a.awayFouls
                val nf = if (side == "home") b.homeFouls else b.awayFouls
                if (pf != null && nf != null && nf > pf) add(Moment.FoulCount(side, nf))
                if (!BasketballStripPolicy.inBonus(side, a) && BasketballStripPolicy.inBonus(side, b)) add(Moment.BonusReached(side))
                val pt = if (side == "home") a.homeTimeouts else a.awayTimeouts
                val nt = if (side == "home") b.homeTimeouts else b.awayTimeouts
                if (pt != null && nt != null && nt < pt) add(Moment.TimeoutUsed(side))
            }
        }
    }

    /** Chart markers that were not there before (matched by play id); none while the chartMarkers switch is off. */
    private fun markers(prev: GameDetail, next: GameDetail): List<Moment> {
        val before = ChartMarkers.markersFor(prev).mapNotNull { it.playId }.toSet()
        return ChartMarkers.markersFor(next).filter { it.playId != null && it.playId !in before }.map { Moment.MarkerAdded(it) }
    }

    private fun number(s: String): Double? = s.trim().removeSuffix("%").toDoubleOrNull()

    private fun leader(label: String, home: String, away: String): String? {
        val h = number(home) ?: return null
        val a = number(away) ?: return null
        if (h == a) return null
        val lower = GameDetailUiPolicy.lowerIsBetter(label)
        return if ((h < a) == lower) "home" else "away"
    }

    /** One per stat row whose value changed (rows that appear are not animated); [Moment.StatChange.leaderChanged] when the leading side flipped. */
    private fun statChanges(prev: GameDetail, next: GameDetail): List<Moment> {
        val a = prev.blocks.filterIsInstance<StatsBlock>().firstOrNull()?.rows?.associateBy { it.label } ?: return emptyList()
        val b = next.blocks.filterIsInstance<StatsBlock>().firstOrNull()?.rows ?: return emptyList()
        return b.mapNotNull { r ->
            val old = a[r.label] ?: return@mapNotNull null
            if (old.home == r.home && old.away == r.away) null
            else Moment.StatChange(r.label, leaderChanged = leader(r.label, old.home, old.away) != leader(r.label, r.home, r.away))
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
