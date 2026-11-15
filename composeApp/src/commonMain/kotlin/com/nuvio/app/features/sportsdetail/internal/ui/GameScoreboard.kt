package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.MotionSpec

/** Scoreboard card: logo, rank, name, record, score (counts + pulses once on a real change); centre LIVE pill + clock. */
@Composable
internal fun GameScoreboard(detail: GameDetail, modifier: Modifier = Modifier) {
    NuvioSurfaceCard(modifier = modifier) {
        Text(
            listOfNotNull(detail.leagueName.takeIf { it.isNotBlank() }, detail.round?.let { "Round $it" }, detail.venue).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        val palette = rememberTeamPalette(detail.header)
        when (val h = detail.header) {
            is TeamsHeader -> SidesRow(h.home, h.away, "home", "away", palette, detail, extras = rememberSideExtras(detail, palette))
            is VersusHeader -> SidesRow(h.a, h.b, "home", "away", palette, detail)
            is EventHeader -> Column {
                Text(h.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.textPrimary)
                h.session?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary) }
                Spacer(Modifier.height(NuvioTokens.Space.s6))
                StatusLine(detail)
            }
        }
    }
}

@Composable
private fun SidesRow(a: Side, b: Side, aKey: String, bKey: String, palette: TeamPalette, detail: GameDetail, extras: SideExtras = SideExtras()) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SideColumn(a, aKey, palette.home, extras.home, extras.possession == aKey, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8)) {
            StatusLine(detail)
        }
        SideColumn(b, bKey, palette.away, extras.away, extras.possession == bKey, Modifier.weight(1f))
    }
}

@Composable
private fun SideColumn(s: Side, key: String, teamColor: Color, extras: (@Composable () -> Unit)?, hasBall: Boolean, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        TeamBadge(s.badgeUrl, 48)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasBall) Box(Modifier.padding(end = NuvioTokens.Space.s4).size(6.dp).clip(CircleShape).background(teamColor))
            rankText(s.rank)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted, modifier = Modifier.padding(end = NuvioTokens.Space.s4))
            }
            Text(s.name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.nuvio.colors.textPrimary, modifier = Modifier.weight(1f, fill = false))
        }
        s.record?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
        Box(Modifier.height(3.dp).width(28.dp).clip(CircleShape).background(teamColor))
        s.score?.let { AnimatedScore(it, key, large = true) }
        extras?.invoke()
    }
}

/**
 * The score text. A real change for this side (a [Moment.ScoreChange] in the current batch) scales 1 -> 1.25 -> 1 and tints
 * the accent over [MotionSpec.PULSE_MS] and counts to the new value over [MotionSpec.COUNT_MS]; everything else is static.
 * Animated values are read here only, so the rest of the screen does not recompose per frame.
 */
@Composable
internal fun AnimatedScore(score: String, side: String, large: Boolean) {
    val batch = LocalMoments.current
    val reduce = rememberReduceMotion()
    val changed = batch.moments.any { it is Moment.ScoreChange && it.side == side }
    val target = score.toIntOrNull()
    val style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleSmall
    val base = MaterialTheme.nuvio.colors.textPrimary
    val accent = MaterialTheme.nuvio.colors.accent
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(batch.seq, changed) {
        if (changed && !reduce) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, motion(MotionSpec.PULSE_MS / 3, false))
            pulse.animateTo(0f, motion(MotionSpec.PULSE_MS * 2 / 3, false))
        }
    }
    val shown = if (target != null) {
        val counted by animateIntAsState(target, animationSpec = motion(MotionSpec.COUNT_MS, reduce = reduce || !changed), label = "score")
        counted.toString()
    } else score
    Text(
        shown,
        style = style,
        fontWeight = FontWeight.Bold,
        color = lerp(base, accent, pulse.value),
        modifier = Modifier.graphicsLayer {
            val s = 1f + 0.25f * pulse.value
            scaleX = s; scaleY = s
        },
    )
}

@Composable
internal fun StatusLine(detail: GameDetail) {
    val live = detail.live
    when (detail.state) {
        GameState.LIVE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LivePill("LIVE")
            Text(GameDetailUiPolicy.liveStatusText(live), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
        }
        GameState.FINAL -> Text(live?.detail?.takeIf { it.isNotBlank() } ?: "Final", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary)
        GameState.POSTPONED -> Text("Postponed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary)
        GameState.UPCOMING -> Text(detail.startsAt.replace('T', ' ').removeSuffix("Z").take(16) + " UTC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
    }
}

/** Shown in the sticky header once the scoreboard card has scrolled off: `HOM 1 – 0 AWY`, clock, compact Play. */
@Composable
internal fun SlimScoreBar(detail: GameDetail, canPlay: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val t = GameScoreBar.of(detail) ?: return
    val teams = detail.header as? TeamsHeader
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.nuvio.colors.background).padding(horizontal = NuvioTokens.Space.s4, vertical = NuvioTokens.Space.s6)
            .semantics { contentDescription = "${t.home} ${t.homeScore}, ${t.away} ${t.awayScore}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        TeamBadge(teams?.home?.badgeUrl, 24)
        Text("${t.home} ${t.homeScore} – ${t.awayScore} ${t.away}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.textPrimary, maxLines = 1)
        TeamBadge(teams?.away?.badgeUrl, 24)
        Text(t.clock, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        if (canPlay) {
            Box(
                Modifier.size(NuvioTokens.Space.s32).clip(CircleShape).background(MaterialTheme.nuvio.colors.accent).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = MaterialTheme.nuvio.colors.onAccent, modifier = Modifier.size(NuvioTokens.Icon.md)) }
        }
    }
}

/** Per-side extras under the score: soccer scorers, football timeout dots. Absent blocks add nothing. */
internal class SideExtras(val home: (@Composable () -> Unit)? = null, val away: (@Composable () -> Unit)? = null, val possession: String? = null)

@Composable
private fun rememberSideExtras(detail: GameDetail, palette: TeamPalette): SideExtras {
    val soccer = detail.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.SoccerStripBlock>().firstOrNull()
    val field = detail.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.FootballFieldBlock>().firstOrNull()
    fun scorers(list: List<String>): (@Composable () -> Unit)? = list.takeIf { it.isNotEmpty() }?.let { l ->
        {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScorerLines.of(l).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    fun dots(left: Int?, c: Color): (@Composable () -> Unit)? = left?.let { { CountDots(it, 3, c, Modifier.padding(top = NuvioTokens.Space.s4)) } }
    val ball = detail.blocks.filterIsInstance<com.nuvio.app.features.sportsdetail.api.BasketballStripBlock>().firstOrNull()?.possession
    return SideExtras(
        possession = ball,
        home = scorers(soccer?.homeScorers.orEmpty()) ?: dots(field?.homeTimeouts, palette.home),
        away = scorers(soccer?.awayScorers.orEmpty()) ?: dots(field?.awayTimeouts, palette.away),
    )
}
