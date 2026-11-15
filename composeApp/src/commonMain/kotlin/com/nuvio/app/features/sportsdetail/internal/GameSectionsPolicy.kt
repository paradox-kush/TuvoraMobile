package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.StandingsBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.UnknownBlock
import com.nuvio.app.features.sportsdetail.api.WatchBlock

/** The tabs (phone) / section chips (TV) of the game screen. */
enum class GameSection { OVERVIEW, PLAYS, STATS, LEADERS, LINEUPS, STANDINGS }

/**
 * Which sections a game has and which blocks fill each. A section exists only when a non-empty block for it is
 * present, so a missing feed hides its tab instead of showing an empty one. OVERVIEW is always first.
 *
 *  PLAYS <- [PlaysBlock], else the scoring [TimelineBlock] · STATS <- [StatsBlock] · LEADERS <- [LeadersBlock] ·
 *  LINEUPS <- [LineupBlock] · STANDINGS <- [StandingsTableBlock], else the soccer-shaped [StandingsBlock] ·
 *  everything else (linescore, form, results, highlights, next, win chance, strips, situation) is OVERVIEW.
 */
internal object GameSectionsPolicy {
    fun sections(d: GameDetail): List<GameSection> =
        listOf(GameSection.OVERVIEW) + listOf(GameSection.PLAYS, GameSection.STATS, GameSection.LEADERS, GameSection.LINEUPS, GameSection.STANDINGS)
            .filter { blocksFor(it, d).isNotEmpty() }

    /** The phone shows the tab row only when there is something to switch between. */
    fun showsTabRow(d: GameDetail): Boolean = sections(d).size >= 2

    fun blocksFor(s: GameSection, d: GameDetail): List<GameBlock> {
        val live = d.blocks.filterNot { GameDetailUiPolicy.isEmpty(it) }
        return when (s) {
            GameSection.PLAYS -> live.filterIsInstance<PlaysBlock>().take(1).ifEmpty { live.filterIsInstance<TimelineBlock>().take(1) }
            GameSection.STATS -> live.filterIsInstance<StatsBlock>().take(1)
            GameSection.LEADERS -> live.filterIsInstance<LeadersBlock>().take(1)
            GameSection.LINEUPS -> live.filterIsInstance<LineupBlock>().take(1)
            GameSection.STANDINGS -> live.filterIsInstance<StandingsTableBlock>().take(1).ifEmpty { live.filterIsInstance<StandingsBlock>().take(1) }
            GameSection.OVERVIEW -> live.filterNot {
                it is PlaysBlock || it is TimelineBlock || it is StatsBlock || it is LeadersBlock || it is LineupBlock ||
                    it is StandingsTableBlock || it is StandingsBlock || it is WatchBlock || it is BroadcastBlock || it is UnknownBlock
            }
        }
    }
}
