package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.internal.FieldGeometry
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.LineupPitchPolicy

/** Builds the sport strips' view-models from the shared blocks. Pure; every position is already 0..1. */
internal object TvStrips {
    const val FIELD_DP = 60
    const val SOCCER_DP = 78

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
}
