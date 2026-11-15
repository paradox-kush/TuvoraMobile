package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.internal.LineupPitchPolicy
import com.nuvio.app.features.sportsdetail.internal.PitchSpot

/** Both XIs on one horizontal pitch (home left, away right) in team colours; the caller falls back to the list when [canDraw] is false. */
internal object PitchLayout {
    fun canDraw(b: LineupBlock): Boolean {
        val home = b.sides.firstOrNull { it.side == "home" } ?: return false
        val away = b.sides.firstOrNull { it.side == "away" } ?: return false
        return LineupPitchPolicy.positions(home.formation, home.players).isNotEmpty() &&
            LineupPitchPolicy.positions(away.formation, away.players).isNotEmpty()
    }
}

@Composable
internal fun PitchLineup(b: LineupBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val palette = rememberTeamPalette(detail.header)
    val home = b.sides.first { it.side == "home" }
    val away = b.sides.first { it.side == "away" }
    val homeSpots = LineupPitchPolicy.positions(home.formation, home.players)
    val awaySpots = LineupPitchPolicy.positions(away.formation, away.players)
    val line = MaterialTheme.nuvio.colors.borderDefault
    val grass = MaterialTheme.nuvio.colors.surfaceElevated
    Column(modifier) {
        Text("${home.formation.orEmpty()}   ·   ${away.formation.orEmpty()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
        Spacer(Modifier.height(NuvioTokens.Space.s6))
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.7f).clip(androidx.compose.foundation.shape.RoundedCornerShape(NuvioTokens.Space.s8)).background(grass)) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.7f)) {
                drawRect(line, style = Stroke(2f))
                drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
                drawCircle(line, radius = size.height * 0.14f, center = Offset(size.width / 2, size.height / 2), style = Stroke(2f))
            }
            val dot = 22.dp
            val placed = mutableListOf<Triple<Float, Float, Pair<Color, String>>>()
            homeSpots.forEach { val fx = it.x / 2f; placed += Triple(fx, it.y, palette.home to it.player.number) }
            awaySpots.forEach { val fx = 1f - it.x / 2f; placed += Triple(fx, it.y, palette.away to it.player.number) }
            placed.forEach { (fx, fy, cn) ->
                androidx.compose.foundation.layout.Box(
                    Modifier.offset(maxWidth * fx - dot / 2, maxHeight * fy - dot / 2).size(dot).clip(CircleShape).background(cn.first),
                    contentAlignment = Alignment.Center,
                ) { Text(cn.second, fontSize = 10.sp, color = Color.White) }
            }
        }
    }
}
