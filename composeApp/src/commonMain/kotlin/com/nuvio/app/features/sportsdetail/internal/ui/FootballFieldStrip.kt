package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.FieldGeometry
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.MotionSpec

/**
 * Field strip under the scoreboard: home end zone left in the home colour, away right; ball, drive span, first-down line.
 * Everything positional comes from [FieldGeometry]; the line of text is the feed's own. Ball and span slide over MOVE_MS only
 * on a real FieldMove moment, otherwise they are set directly (no animation on open or resume).
 */
@Composable
internal fun FootballFieldStrip(f: FootballFieldBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val palette = rememberTeamPalette(detail.header)
    val teams = detail.header as? TeamsHeader
    val reduce = rememberReduceMotion()
    val moved = LocalMoments.current.moments.any { it is Moment.FieldMove || it is Moment.PossessionChange }
    val accent = MaterialTheme.nuvio.colors.accent
    val danger = MaterialTheme.nuvio.colors.danger
    val spec = motion<Float>(MotionSpec.MOVE_MS, reduce = reduce || !moved)
    val ball by animateFloatAsState(FieldGeometry.x(f.yardLine), spec, label = "ball")
    val span = FieldGeometry.driveSpan(f.drive?.startYardLine, f.yardLine)
    val spanA by animateFloatAsState(span?.first ?: 0f, spec, label = "spanA")
    val spanB by animateFloatAsState(span?.second ?: 0f, spec, label = "spanB")
    val firstDown = FieldGeometry.firstDownX(f.yardLine, f.down?.let { f.distance }, f.possession)
    val tdFlash = LocalMoments.current.moments.filterIsInstance<Moment.Touchdown>().firstOrNull()?.side
    val possColor = f.possession?.let { paletteFor(it, palette) }
    val border = if (f.redZone && possColor != null) possColor else MaterialTheme.nuvio.colors.borderSubtle
    val grass = MaterialTheme.nuvio.colors.surfaceElevated
    val lineColor = MaterialTheme.nuvio.colors.borderDefault
    val strong = MaterialTheme.nuvio.colors.borderStrong
    NuvioSurfaceCard(modifier = modifier.border(1.dp, border, RoundedCornerShape(NuvioTokens.Space.s12))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(f.line.orEmpty(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            f.possession?.let { p ->
                val name = teams?.let { if (p == "home") it.home.short ?: it.home.name else it.away.short ?: it.away.name }.orEmpty()
                Text(if (p == "home") "$name ball ▶▶" else "◀◀ $name ball", style = MaterialTheme.typography.labelMedium, color = paletteFor(p, palette))
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val ez = size.width * 10f / 120f
            val inner = size.width - 2 * ez
            fun fx(x: Float) = ez + inner * x
            drawRect(grass, Offset(ez, 0f), Size(inner, size.height))
            drawRect(palette.home.copy(alpha = if (tdFlash == "home") 0.9f else 0.7f), Offset(0f, 0f), Size(ez, size.height))
            drawRect(palette.away.copy(alpha = if (tdFlash == "away") 0.9f else 0.7f), Offset(size.width - ez, 0f), Size(ez, size.height))
            for (i in 1..9) drawLine(if (i == 5) strong else lineColor, Offset(fx(i / 10f), 0f), Offset(fx(i / 10f), size.height), strokeWidth = if (i == 5) 2f else 1f)
            if (possColor != null && span != null) drawRect(possColor.copy(alpha = 0.38f), Offset(fx(spanA), 0f), Size(fx(spanB) - fx(spanA), size.height))
            firstDown?.let { drawLine(accent, Offset(fx(it), 0f), Offset(fx(it), size.height), strokeWidth = 3.dp.toPx()) }
            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(fx(ball), size.height / 2))
        }
        Row(Modifier.fillMaxWidth().padding(top = NuvioTokens.Space.s4), horizontalArrangement = Arrangement.SpaceBetween) {
            val h = teams?.home?.short ?: "H"; val a = teams?.away?.short ?: "A"
            listOf(h, "30", "50", "30", a).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
        }
        f.drive?.summary?.let { Text("Drive: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
    }
}

/** Dots (filled = left) under a team on the scoreboard: football timeouts (3). */
@Composable
internal fun CountDots(left: Int?, total: Int, color: Color, modifier: Modifier = Modifier) {
    if (left == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { i ->
            androidx.compose.foundation.layout.Box(
                Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (i < left) color else color.copy(alpha = 0.2f)),
            )
        }
    }
}
