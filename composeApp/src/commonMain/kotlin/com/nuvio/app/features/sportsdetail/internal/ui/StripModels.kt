package com.nuvio.app.features.sportsdetail.internal.ui

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.internal.BasketballStripPolicy
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy

/** Basketball strip view-model: everything the composable shows, decided here and unit-tested. Hidden pieces are null. */
internal data class BasketballStripModel(
    /** "DAL 8-0 run" (switch `basketballRun` ON and a run present), else null. */
    val runLabel: String?,
    /** "<Team> in the bonus" for the side that is in the bonus (never ESPN's raw value), else null. */
    val bonusLabel: String?,
    val homeFouls: Int?, val awayFouls: Int?,
    val homeTimeouts: Int?, val awayTimeouts: Int?,
    val possession: String?,
) {
    val isEmpty: Boolean get() = runLabel == null && bonusLabel == null && homeFouls == null && awayFouls == null && homeTimeouts == null && awayTimeouts == null

    companion object {
        const val FOUL_DOTS = 5
        const val TIMEOUT_DOTS = 7

        fun of(b: BasketballStripBlock, header: Header?): BasketballStripModel {
            val bonusSide = when {
                BasketballStripPolicy.inBonus("home", b) -> "home"
                BasketballStripPolicy.inBonus("away", b) -> "away"
                else -> null
            }
            return BasketballStripModel(
                runLabel = b.run?.let { r -> GameDetailUiPolicy.teamTag(r.side, header ?: return@let null)?.let { "$it ${r.points}-0 run" } },
                bonusLabel = bonusSide?.let { s -> header?.let { GameDetailUiPolicy.teamTag(s, it) }?.let { "$it in the bonus" } },
                homeFouls = b.homeFouls, awayFouls = b.awayFouls,
                homeTimeouts = b.homeTimeouts, awayTimeouts = b.awayTimeouts,
                possession = b.possession,
            )
        }

        /** Dots for [count] fouls: filled up to [FOUL_DOTS]; the number is appended beside the dots only above that. */
        fun foulNumber(count: Int): String? = count.takeIf { it > FOUL_DOTS }?.toString()
    }
}

internal data class HockeyStripModel(
    /** "Power play · DAL" or "Even strength". */
    val chip: String,
    val onPowerPlay: Boolean,
    /** "1:12 left" only when the (switched) clock is available. */
    val timeLeft: String?,
    /** 0..1 fraction of the penalty left, only with [timeLeft]. */
    val barFraction: Float?,
    val shots: String?,
    val powerPlays: String?,
    val faceoffs: String?,
) {
    companion object {
        fun of(b: HockeyStripBlock, header: Header?): HockeyStripModel {
            val tag = b.powerPlaySide?.let { s -> header?.let { GameDetailUiPolicy.teamTag(s, it) } }
            val left = b.powerPlaySecondsLeft?.takeIf { it > 0 && b.powerPlaySide != null }
            return HockeyStripModel(
                chip = if (b.powerPlaySide != null) listOfNotNull("Power play", tag).joinToString(" · ") else "Even strength",
                onPowerPlay = b.powerPlaySide != null,
                timeLeft = left?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')} left" },
                barFraction = left?.let { (it / (if (it > 120) 240f else 120f)).coerceIn(0f, 1f) },
                shots = pair("Shots", b.homeShots, b.awayShots),
                powerPlays = if (b.homePowerPlay != null && b.awayPowerPlay != null) "Power plays ${b.homePowerPlay} – ${b.awayPowerPlay}" else null,
                faceoffs = pair("Faceoffs", b.homeFaceoffs, b.awayFaceoffs),
            )
        }

        private fun pair(label: String, h: Int?, a: Int?): String? = if (h != null && a != null) "$label $h – $a" else null
    }
}
