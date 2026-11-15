package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.ScoringRun

/** One play as the run policy needs it, oldest first. */
internal data class RunPlay(val side: String?, val points: Int, val scoring: Boolean)

/**
 * DERIVED (behind the `basketballRun` switch, off by default): the points the last-scoring team has scored since the opponent last
 * scored, shown only from 8 points. Arithmetic on the feed's own scores, but a label of ours.
 */
internal object BasketballRunPolicy {
    const val MIN_RUN_POINTS = 8

    fun run(plays: List<RunPlay>): ScoringRun? {
        var side: String? = null
        var points = 0
        for (p in plays.asReversed()) {
            if (!p.scoring || p.points <= 0) continue
            val s = p.side ?: return null            // a scoring play we cannot attribute makes the run ambiguous
            if (side == null) { side = s; points = p.points } else if (s == side) points += p.points else break
        }
        return side?.takeIf { points >= MIN_RUN_POINTS }?.let { ScoringRun(it, points) }
    }
}
