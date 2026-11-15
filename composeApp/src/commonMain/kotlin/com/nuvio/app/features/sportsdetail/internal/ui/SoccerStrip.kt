package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.MotionSpec

/** Soccer strip: last real event chip + possession bar, corners and cards. Nothing here is synthesized; the chip text is the feed's. */
@Composable
internal fun SoccerStrip(b: SoccerStripBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val palette = rememberTeamPalette(detail.header)
    val teams = detail.header as? TeamsHeader
    val reduce = rememberReduceMotion()
    val moments = LocalMoments.current
    val popKey = moments.moments.filterIsInstance<Moment.SoccerMoment>().firstOrNull()?.event
    val pop = remember { Animatable(1f) }
    val ledger = LocalPlayed.current
    LaunchedEffect(popKey) {
        if (popKey != null && !reduce && ledger.claim("soccer-chip", moments.seq)) {
            pop.snapTo(0.6f)
            pop.animateTo(1f, motion(MotionSpec.QUICK_MS, false, enter = true))
        } else pop.snapTo(1f)
    }
    NuvioSurfaceCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            b.lastEvent?.let { e ->
                val chip = SoccerChips.chip(e.kind)
                val colors = MaterialTheme.nuvio.colors
                val (bg, fg) = when (chip.tone) {
                    EventTone.GOAL -> colors.accent to colors.onAccent
                    EventTone.YELLOW -> Color(0xFFF2C500) to Color(0xFF1A1400)
                    EventTone.RED -> colors.danger to Color.White
                    EventTone.NEUTRAL -> colors.surfaceElevated to colors.textSecondary
                }
                val team = e.side?.let { GameDetailUiPolicy.teamTag(it, detail.header) }
                Text(
                    listOfNotNull(chip.label, team, e.minute).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
                    modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value }
                        .clip(RoundedCornerShape(percent = 50)).background(bg).padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
                )
            }
            Spacer(Modifier.weight(1f))
            b.homePossession?.let { h ->
                val homeN by animateIntAsState(h, motion(MotionSpec.COUNT_MS, reduce), label = "poss")
                Text("Possession $homeN% – ${100 - homeN}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
            }
        }
        b.homePossession?.let { h ->
            val share by animateFloatAsState(h / 100f, motion(MotionSpec.MOVE_MS, reduce), label = "possbar")
            Spacer(Modifier.height(NuvioTokens.Space.s8))
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(percent = 50))) {
                if (share > 0f) Box(Modifier.weight(share).height(6.dp).background(palette.home))
                if (share < 1f) Box(Modifier.weight(1f - share).height(6.dp).background(palette.away))
            }
        }
        val corners = if (b.homeCorners != null && b.awayCorners != null) "Corners ${b.homeCorners} – ${b.awayCorners}" else null
        val cards = "Cards ${SoccerChips.cardsText(b.homeYellow, b.homeRed)} – ${SoccerChips.cardsText(b.awayYellow, b.awayRed)}"
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(corners.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
            Text(cards, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
        }
        if (teams == null) Spacer(Modifier.height(0.dp))
    }
}
