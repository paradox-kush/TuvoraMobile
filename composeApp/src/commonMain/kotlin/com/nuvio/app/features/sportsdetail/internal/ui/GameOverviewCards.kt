package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.sportsdetail.internal.ChartMarker
import com.nuvio.app.features.sportsdetail.internal.ChartMarkers
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.WinChanceSeries
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.MotionSpec
import kotlin.time.Clock

/**
 * Win-chance card (Phase A). Caption beside the title ("Market-implied · via Polymarket"), leader big in team colour, bar,
 * chart without markers. No odds, prices or links.
 */
@Composable
internal fun WinChanceCard(w: WinProbabilityBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val palette = rememberTeamPalette(detail.header)
    val m = WinChanceModel.of(w, detail.header)
    val teams = detail.header as? TeamsHeader
    val moved = LocalMoments.current.moments.any { it is Moment.WinChanceMove }
    val reduce = rememberReduceMotion()
    val leaderColor = paletteFor(m.leaderSide, palette)
    val otherSide = if (m.leaderSide == "home") "away" else "home"
    NuvioSurfaceCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WIN CHANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted, modifier = Modifier.weight(1f))
            GameDetailUiPolicy.winChanceCaption(w)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            teams?.let { TeamBadge(if (m.leaderSide == "home") it.home.badgeUrl else it.away.badgeUrl, 32) }
            Column(Modifier.weight(1f)) {
                CountedPercent(m.leaderPercent, moved, reduce, MaterialTheme.typography.headlineMedium, leaderColor)
                Text(m.leaderLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            m.drawPercent?.let { Text("Draw $it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted) }
            Column(horizontalAlignment = Alignment.End) {
                CountedPercent(m.otherPercent, moved, reduce, MaterialTheme.typography.titleMedium, paletteFor(otherSide, palette))
                Text(m.otherName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        WinBar(m, palette, moved, reduce)
        val spec = WinChanceSeries.specOf(w)
        WinChanceSeries.chart(w.history, spec)?.let { pts ->
            Spacer(Modifier.height(NuvioTokens.Space.s12))
            WinChart(pts, spec.reference, leaderColor, moved, reduce, markers = ChartMarkers.markersFor(detail), dividers = ChartMarkers.dividersFor(detail), palette = palette)
            Text("${m.leaderName} win chance over time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
        }
        val lines = GameDetailUiPolicy.storylineLines(GameDetailUiPolicy.visibleStorylines(w.storylines, detail.state, detail.live, detail.sport), detail.header)
        val comeback = GameDetailUiPolicy.comebackWatch(w.history, w.homePercent, m.awayPercent, detail.state, w.asOfMs)?.let { c -> GameDetailUiPolicy.comebackText(c, detail.header) }
        if (comeback != null || lines.isNotEmpty()) {
            Spacer(Modifier.height(NuvioTokens.Space.s8))
            comeback?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.accent) }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary) }
        }
        if (w.extrasAttribution != null && (w.history.size >= 2 || w.storylines.isNotEmpty())) {
            Text("Chart and storylines ${w.extrasAttribution}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
        }
        // The card's own staleness: a stale market value is not hidden behind a fresh ESPN fetch.
        GameDetailUiPolicy.winChanceStaleLabel(w, detail.state, Clock.System.now().toEpochMilliseconds())?.let { StaleChip(it) }
    }
}

@Composable
private fun CountedPercent(value: Int, animate: Boolean, reduce: Boolean, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val shown by animateIntAsState(value, animationSpec = motion(MotionSpec.COUNT_MS, reduce = reduce || !animate), label = "pct")
    Text("$shown%", style = style, fontWeight = FontWeight.Bold, color = color)
}

/** 8 dp bar in team colours (draw segment neutral). Widths animate over MOVE_MS, read in the draw phase only. */
@Composable
private fun WinBar(m: WinChanceModel, palette: TeamPalette, animate: Boolean, reduce: Boolean) {
    val spec = motion<Float>(MotionSpec.MOVE_MS, reduce = reduce || !animate)
    val home by animateFloatAsState(m.homePercent.toFloat(), spec, label = "home")
    val draw by animateFloatAsState((m.drawPercent ?: 0).toFloat(), spec, label = "draw")
    val away by animateFloatAsState(m.awayPercent.toFloat(), spec, label = "away")
    val neutral = MaterialTheme.nuvio.colors.borderDefault
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val total = (home + draw + away).coerceAtLeast(1f)
        val r = CornerRadius(size.height / 2)
        // home on the left, draw in the middle, away on the right (same order as the scoreboard and every strip)
        var x = 0f
        fun seg(v: Float, c: Color) {
            val wd = size.width * v / total
            if (wd > 0f) drawRoundRect(c, Offset(x, 0f), Size(wd, size.height), r)
            x += wd
        }
        seg(home, palette.home); seg(draw, neutral); seg(away, palette.away)
    }
}

/**
 * Momentum line: faint dashed 50% line, line in [color], end dot, then Phase D markers (hollow dots in the scoring team's colour with a
 * short label) and period dividers. One Canvas; the animated values are read in the draw phase only. The newest segment draws over
 * COUNT_MS on a real move and a newly added marker scales in over QUICK_MS.
 */
@Composable
private fun WinChart(
    points: List<Float>, reference: Float, color: Color, animate: Boolean, reduce: Boolean,
    markers: List<ChartMarker>, dividers: List<Pair<Float, String>>, palette: TeamPalette,
) {
    val mid = MaterialTheme.nuvio.colors.borderStrong
    val faint = MaterialTheme.nuvio.colors.borderSubtle
    val muted = MaterialTheme.nuvio.colors.textMuted
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = muted)
    val progress = remember { Animatable(1f) }
    val ledger = LocalPlayed.current
    val seq = LocalMoments.current.seq
    LaunchedEffect(points.size, points.lastOrNull(), animate, reduce) {
        if (animate && !reduce && ledger.claim("win-chart", seq)) {
            progress.snapTo(0f)
            progress.animateTo(1f, motion(MotionSpec.COUNT_MS, false))
        } else progress.snapTo(1f)
    }
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(markers.size) }
    LaunchedEffect(markers.size) {
        if (markers.size > seen && !reduce) { pop.snapTo(0f); pop.animateTo(1f, motion(MotionSpec.QUICK_MS, false, enter = true)) } else pop.snapTo(1f)
        seen = markers.size
    }
    val hasDividers = dividers.isNotEmpty()
    // The line is built once per series and size (drawWithCache); a frame only draws it plus the newest segment. A new series makes a new modifier.
    val drawer = remember(points, reference, markers, dividers, palette, color, mid, faint, labelStyle, hasDividers) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height - if (hasDividers) 16.dp.toPx() else 0f
            fun px(i: Int) = if (points.size == 1) 0f else w * i / (points.size - 1)
            fun py(v: Float) = h * (1f - v)
            val base = Path()
            base.moveTo(px(0), py(points[0]))
            for (i in 1 until points.lastIndex) base.lineTo(px(i), py(points[i]))
            onDrawBehind {
                dividers.forEach { (fx, label) ->
                    val x = w * fx
                    drawLine(faint, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    val t = measurer.measure(label, labelStyle)
                    drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(0f, w - t.size.width), h + 2.dp.toPx()))
                }
                drawLine(mid, Offset(0f, py(reference)), Offset(w, py(reference)), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                var endX = px(0); var endY = py(points[0])
                drawPath(base, color, style = Stroke(width = 3f))
                if (points.size > 1) {
                    val a = points.lastIndex - 1
                    val p = progress.value
                    endX = px(a) + (px(a + 1) - px(a)) * p
                    endY = py(points[a]) + (py(points[a + 1]) - py(points[a])) * p
                    drawLine(color, Offset(px(a), py(points[a])), Offset(endX, endY), strokeWidth = 3f)
                }
                drawCircle(color, radius = 5f, center = Offset(endX, endY))
                markers.forEachIndexed { i, m ->
                    val cx = w * m.x
                    val cy = py(m.y)
                    val scale = if (i == markers.lastIndex) pop.value else 1f
                    drawCircle(paletteFor(m.side, palette), radius = 4.dp.toPx() * scale, center = Offset(cx, cy), style = Stroke(width = 2f))
                    val t = measurer.measure(m.label, labelStyle)
                    val ty = if (m.y >= m.reference) cy - 6.dp.toPx() - t.size.height else cy + 6.dp.toPx()
                    drawText(t, topLeft = Offset((cx - t.size.width / 2f).coerceIn(0f, w - t.size.width), ty.coerceIn(0f, h - t.size.height)))
                }
            }
        }
    }
    Spacer(Modifier.fillMaxWidth().height(if (hasDividers) 104.dp else 88.dp).then(drawer))
}

/** The newest play (feed's own text): team logo + kind tag + text. Fades/rises in once when a new play arrives. */
@Composable
internal fun LastPlayCard(item: TimelineItem, detail: GameDetail, modifier: Modifier = Modifier) {
    val teams = detail.header as? TeamsHeader
    val batch = LocalMoments.current
    val reduce = rememberReduceMotion()
    val isNew = batch.moments.any { it is Moment.NewPlay }
    val palette = rememberTeamPalette(detail.header)
    NuvioSurfaceCard(modifier = modifier.enterOnce("last-play", isNew, reduce)) {
        Text("LAST PLAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
        Spacer(Modifier.height(NuvioTokens.Space.s6))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            teams?.let { t -> val s = if (item.side == "home") t.home else if (item.side == "away") t.away else null; s?.let { TeamBadge(it.badgeUrl, 24) } }
            item.kind?.takeIf { it.isNotBlank() }?.let { KindTag(it, paletteFor(item.side, palette)) }
            Text(
                listOfNotNull(item.period, item.time.takeIf { it.isNotBlank() }).joinToString(" "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted,
            )
        }
        Spacer(Modifier.height(NuvioTokens.Space.s4))
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
