package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.internal.BasketballStripPolicy
import com.nuvio.app.features.sportsdetail.internal.FieldGeometry
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.LineupPitchPolicy

/** Builds the sport strips' view-models from the shared blocks. Pure; every position is already 0..1. */
internal object TvStrips {
    const val FIELD_DP = 60
    const val SOCCER_DP = 78
    const val BASKETBALL_DP = 72
    const val HOCKEY_DP = 62

    private fun short(s: Side) = s.short?.takeIf { it.isNotBlank() } ?: s.name

    fun field(d: GameDetail, sides: Pair<Side, Side>?, colors: Pair<String?, String?>): TvFieldStrip? {
        if (d.state != GameState.LIVE) return null            // the strip is live only
        val f = d.blocks.filterIsInstance<FootballFieldBlock>().firstOrNull() ?: return null
        if (f.yardLine !in 0..100) return null
        val poss = f.possession?.takeIf { it == "home" || it == "away" }
        val tint = when (poss) { "home" -> colors.first; "away" -> colors.second; else -> null }
        val span = if (poss != null) FieldGeometry.driveSpan(f.drive?.startYardLine, f.yardLine) else null
        val owner = when (poss) { "home" -> sides?.first; "away" -> sides?.second; else -> null }
        return TvFieldStrip(
            ballX = FieldGeometry.x(f.yardLine),
            firstDownX = FieldGeometry.firstDownX(f.yardLine, f.distance, poss),
            driveFrom = span?.first, driveTo = span?.second, driveTintHex = tint,
            homeEndZoneHex = colors.first, awayEndZoneHex = colors.second,
            attackingRight = poss?.let { FieldGeometry.attackingToward(it) == 1f },
            ballLabel = owner?.let { "${short(it)} ball" },
            line = f.line?.takeIf { it.isNotBlank() },
            redZone = f.redZone,
            borderHex = if (f.redZone) tint else null,
            homeTimeouts = f.homeTimeouts, awayTimeouts = f.awayTimeouts,
            driveSummary = f.drive?.summary?.takeIf { it.isNotBlank() },
        )
    }

    private fun label(kind: String) = when (kind) {
        "goal" -> "GOAL"; "yellow" -> "Yellow card"; "red" -> "Red card"; "sub" -> "Sub"; "corner" -> "Corner"
        "penalty" -> "Penalty"; "var" -> "VAR"; "shot" -> "Shot"; "offside" -> "Offside"; "foul" -> "Foul"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    private fun chip(e: SoccerEvent, d: GameDetail): TvEventChip = TvEventChip(
        label = label(e.kind),
        style = when (e.kind) { "goal" -> TvEventStyle.GOAL; "yellow" -> TvEventStyle.YELLOW; "red" -> TvEventStyle.RED; else -> TvEventStyle.NEUTRAL },
        team = GameDetailUiPolicy.teamTag(e.side, d.header), minute = e.minute, text = e.text,
    )

    fun soccer(d: GameDetail, sides: Pair<Side, Side>?): TvSoccerStrip? {
        val b = d.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull() ?: return null
        return TvSoccerStrip(
            chip = b.lastEvent?.let { chip(it, d) },
            homePossession = b.homePossession, awayPossession = b.homePossession?.let { 100 - it },
            homeCorners = b.homeCorners, awayCorners = b.awayCorners,
            homeYellow = b.homeYellow, awayYellow = b.awayYellow, homeRed = b.homeRed, awayRed = b.awayRed,
        )
    }

    /** Both teams on one pitch (home left, away mirrored on the right); null unless BOTH sides place all eleven. */
    fun pitch(d: GameDetail, colors: Pair<String?, String?>): TvPitch? {
        val sides = d.blocks.filterIsInstance<LineupBlock>().firstOrNull()?.sides ?: return null
        val home = sides.firstOrNull { it.side == "home" } ?: return null
        val away = sides.firstOrNull { it.side == "away" } ?: return null
        val h = LineupPitchPolicy.positions(home.formation, home.players)
        val a = LineupPitchPolicy.positions(away.formation, away.players)
        if (h.isEmpty() || a.isEmpty()) return null
        val spots = h.map { TvPitchSpot(it.x * 0.5f, it.y, it.player.number, it.player.name, isHome = true) } +
            a.map { TvPitchSpot(1f - it.x * 0.5f, it.y, it.player.number, it.player.name, isHome = false) }
        return TvPitch(
            homeName = GameDetailUiPolicy.teamTag("home", d.header).orEmpty(), awayName = GameDetailUiPolicy.teamTag("away", d.header).orEmpty(),
            homeFormation = home.formation, awayFormation = away.formation, homeHex = colors.first, awayHex = colors.second, spots = spots,
        )
    }

    fun basketball(d: GameDetail): TvBasketballStrip? {
        val b = d.blocks.filterIsInstance<BasketballStripBlock>().firstOrNull() ?: return null
        val any = b.homeFouls != null || b.awayFouls != null || b.homeTimeouts != null || b.awayTimeouts != null ||
            b.possession != null || b.run != null || BasketballStripPolicy.inBonus("home", b) || BasketballStripPolicy.inBonus("away", b)
        if (!any) return null
        val homeIn = BasketballStripPolicy.inBonus("home", b)
        val awayIn = BasketballStripPolicy.inBonus("away", b)
        val bonus = when {
            homeIn && awayIn -> "Both teams in the bonus"
            homeIn -> "${GameDetailUiPolicy.teamTag("home", d.header).orEmpty()} in the bonus"
            awayIn -> "${GameDetailUiPolicy.teamTag("away", d.header).orEmpty()} in the bonus"
            else -> null
        }
        return TvBasketballStrip(
            runLabel = b.run?.let { "${GameDetailUiPolicy.teamTag(it.side, d.header).orEmpty()} ${it.points}-0 run" },
            bonusLabel = bonus,
            homeFouls = b.homeFouls, awayFouls = b.awayFouls, homeTimeouts = b.homeTimeouts, awayTimeouts = b.awayTimeouts,
            possession = b.possession?.takeIf { it == "home" || it == "away" },
        )
    }

    /** The bar measures what is left of the penalty: a minor (2:00) unless more than that remains (4:00, then 5:00). */
    private fun penaltyTotal(secondsLeft: Int) = when { secondsLeft > 240 -> 300; secondsLeft > 120 -> 240; else -> 120 }

    fun hockey(d: GameDetail): TvHockeyStrip? {
        val h = d.blocks.filterIsInstance<HockeyStripBlock>().firstOrNull() ?: return null
        val side = h.powerPlaySide?.takeIf { it == "home" || it == "away" }
        val left = if (side != null) h.powerPlaySecondsLeft?.takeIf { it >= 0 } else null
        val pp = if (h.homePowerPlay != null && h.awayPowerPlay != null) "${h.homePowerPlay} – ${h.awayPowerPlay}" else null
        return TvHockeyStrip(
            chipLabel = if (side != null) "Power play · ${GameDetailUiPolicy.teamTag(side, d.header).orEmpty()}" else "Even strength",
            onPowerPlay = side != null, powerPlaySide = side,
            timeLeftText = left?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
            fractionLeft = left?.let { (it.toFloat() / penaltyTotal(it)).coerceIn(0f, 1f) },
            homeShots = h.homeShots, awayShots = h.awayShots, powerPlayText = pp,
            homeFaceoffs = h.homeFaceoffs, awayFaceoffs = h.awayFaceoffs,
        )
    }
}
