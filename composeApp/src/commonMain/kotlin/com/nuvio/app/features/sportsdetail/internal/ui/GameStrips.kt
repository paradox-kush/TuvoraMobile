package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.MotionSpec

/** A pill chip that pops in (QUICK_MS) when [popKey] changes while [pop] is true; otherwise static. */
@Composable
private fun PopChip(slot: String, text: String, bg: Color, fg: Color, popKey: Any?, pop: Boolean, reduce: Boolean) {
    val s = remember { Animatable(1f) }
    val ledger = LocalPlayed.current
    val seq = LocalMoments.current.seq
    LaunchedEffect(popKey, pop) {
        if (pop && !reduce && ledger.claim("chip-$slot", seq)) { s.snapTo(0.6f); s.animateTo(1f, motion(MotionSpec.QUICK_MS, false, enter = true)) } else s.snapTo(1f)
    }
    Text(
        text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.graphicsLayer { scaleX = s.value; scaleY = s.value; alpha = s.value }
            .clip(RoundedCornerShape(percent = 50)).background(bg).padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
    )
}

/** Filled dots (left to right) out of [total]; the dot that just changed pops via [popIndex]. */
@Composable
private fun DotRow(slot: String, filled: Int, total: Int, color: Color, popIndex: Int?, reduce: Boolean) {
    val ledger = LocalPlayed.current
    val seq = LocalMoments.current.seq
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { i ->
            val scale = remember { Animatable(1f) }
            LaunchedEffect(popIndex) {
                if (popIndex == i && !reduce && ledger.claim("dot-$slot-$i", seq)) { scale.snapTo(0.4f); scale.animateTo(1f, motion(MotionSpec.QUICK_MS, false, enter = true)) } else scale.snapTo(1f)
            }
            Box(Modifier.size(8.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clip(CircleShape).background(if (i < filled) color else color.copy(alpha = 0.18f)))
        }
    }
}

/** Basketball strip: run chip + bonus chip, team fouls, timeouts. Direct feed values; the run is shown only when the core provided one. */
@Composable
internal fun BasketballStrip(b: BasketballStripBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val m = BasketballStripModel.of(b, detail.header)
    if (m.isEmpty) return
    val palette = rememberTeamPalette(detail.header)
    val reduce = rememberReduceMotion()
    val moments = LocalMoments.current
    val bonusPop = moments.moments.any { it is Moment.BonusReached }
    val colors = MaterialTheme.nuvio.colors
    NuvioSurfaceCard(modifier = modifier) {
        if (m.runLabel != null || m.bonusLabel != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                m.runLabel?.let { PopChip("run", it, colors.accent, colors.onAccent, moments.seq, false, reduce) }
                Spacer(Modifier.weight(1f))
                m.bonusLabel?.let { PopChip("bonus", it, colors.surfaceElevated, colors.textPrimary, it, bonusPop, reduce) }
            }
            Spacer(Modifier.height(NuvioTokens.Space.s8))
        }
        if (m.homeFouls != null || m.awayFouls != null) {
            val foulPop = moments.moments.filterIsInstance<Moment.FoulCount>()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Team fouls", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.weight(1f))
                m.homeFouls?.let { n -> FoulSide("home", n, palette.home, foulPop.firstOrNull { it.side == "home" }?.to?.minus(1), reduce) }
                Spacer(Modifier.padding(horizontal = NuvioTokens.Space.s8))
                m.awayFouls?.let { n -> FoulSide("away", n, palette.away, foulPop.firstOrNull { it.side == "away" }?.to?.minus(1), reduce) }
            }
        }
        if (m.homeTimeouts != null || m.awayTimeouts != null) {
            Spacer(Modifier.height(NuvioTokens.Space.s6))
            val used = moments.moments.filterIsInstance<Moment.TimeoutUsed>()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Timeouts", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.weight(1f))
                m.homeTimeouts?.let { DotRow("to-home", it, BasketballStripModel.TIMEOUT_DOTS, palette.home, if (used.any { u -> u.side == "home" }) it else null, reduce) }
                Spacer(Modifier.padding(horizontal = NuvioTokens.Space.s8))
                m.awayTimeouts?.let { DotRow("to-away", it, BasketballStripModel.TIMEOUT_DOTS, palette.away, if (used.any { u -> u.side == "away" }) it else null, reduce) }
            }
        }
    }
}

@Composable
private fun FoulSide(slot: String, count: Int, color: Color, popIndex: Int?, reduce: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DotRow("foul-$slot", count.coerceAtMost(BasketballStripModel.FOUL_DOTS), BasketballStripModel.FOUL_DOTS, color, popIndex, reduce)
        BasketballStripModel.foulNumber(count)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = color) }
    }
}

/** Hockey strip: power-play chip (+ time and a stepped bar only when the switched clock is on), shots, power plays, faceoffs. */
@Composable
internal fun HockeyStrip(b: HockeyStripBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val m = HockeyStripModel.of(b, detail.header)
    val reduce = rememberReduceMotion()
    val moments = LocalMoments.current
    val ppMoment = moments.moments.filterIsInstance<Moment.PowerPlay>().firstOrNull()
    val colors = MaterialTheme.nuvio.colors
    NuvioSurfaceCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PopChip("pp", m.chip, if (m.onPowerPlay) colors.accent else colors.surfaceElevated, if (m.onPowerPlay) colors.onAccent else colors.textSecondary, ppMoment, ppMoment?.started == true, reduce)
            m.timeLeft?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.padding(start = NuvioTokens.Space.s8)) }
            Spacer(Modifier.weight(1f))
            m.shotsHA?.let { CountedPair("Shots", it, reduce) }
        }
        m.barFraction?.let { f ->
            // Stepped to the value of the last update; nothing drains between updates (contract rule 2).
            val frac by animateFloatAsState(f, motion(MotionSpec.MOVE_MS, reduce), label = "pp")
            Spacer(Modifier.height(NuvioTokens.Space.s8))
            Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(percent = 50)).background(colors.borderDefault)) {
                if (frac > 0f) Box(Modifier.weight(frac).height(4.dp).background(colors.accent))
                if (frac < 1f) Spacer(Modifier.weight(1f - frac))
            }
        }
        if (m.powerPlays != null || m.faceoffs != null) {
            Spacer(Modifier.height(NuvioTokens.Space.s8))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(m.powerPlays.orEmpty(), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                m.faceoffsHA?.let { CountedPair("Faceoffs", it, reduce) }
            }
        }
    }
}

/** "Shots 20 – 14" where both numbers count up over COUNT_MS when the feed changes them (nothing on first show or recomposition). */
@Composable
private fun CountedPair(label: String, ha: Pair<Int, Int>, reduce: Boolean) {
    val h by animateIntAsState(ha.first, motion(MotionSpec.COUNT_MS, reduce), label = "$label-h")
    val a by animateIntAsState(ha.second, motion(MotionSpec.COUNT_MS, reduce), label = "$label-a")
    Text("$label $h – $a", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
}
