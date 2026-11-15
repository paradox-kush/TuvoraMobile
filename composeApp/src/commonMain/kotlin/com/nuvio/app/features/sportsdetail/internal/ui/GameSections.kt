package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.internal.MotionSpec
import com.nuvio.app.features.sportsdetail.internal.Moment

@Composable
internal fun TeamBadge(url: String?, size: Int = 40) {
    if (url.isNullOrBlank()) Box(Modifier.size(size.dp)) else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp))
}

/** A small filled tag: the play kind ("goal", "penalty"…) in the team's colour. */
@Composable
internal fun KindTag(text: String, color: Color) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(color.copy(alpha = 0.16f)).padding(horizontal = NuvioTokens.Space.s6, vertical = 1.dp),
    )
}

/** Horizontally scrollable tab chips from the policy's sections (the caller hides the row when there is only Overview). */
@Composable
internal fun GameTabRow(sections: List<GameSection>, selected: GameSection, onSelect: (GameSection) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.nuvio.colors.background).horizontalScroll(rememberScrollState()).padding(vertical = NuvioTokens.Space.s6),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        sections.forEach { s -> Chip(GameTabsUi.label(s), selected = s == selected, onClick = { onSelect(s) }) }
    }
}

/** One play row: time, team tag in colour, kind chip, text, running score. */
@Composable
internal fun PlayRow(item: TimelineItem, detail: GameDetail, palette: TeamPalette, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s6), verticalAlignment = Alignment.Top) {
        Text(item.time, modifier = Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                GameDetailUiPolicy.teamTag(item.side, detail.header)?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = paletteFor(item.side, palette)) }
                item.kind?.takeIf { it.isNotBlank() }?.let { KindTag(it, paletteFor(item.side, palette)) }
            }
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary)
        }
        item.score?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.nuvio.colors.textSecondary) }
    }
}

@Composable
internal fun PeriodHeader(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted, modifier = Modifier.padding(top = NuvioTokens.Space.s8, bottom = NuvioTokens.Space.s2))
}

/** Stats: team logos header; per row left value, label, right value, the leader's value in a pill in that team's colour, thin two-part bar. */
@Composable
internal fun StatsCard(block: StatsBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val palette = rememberTeamPalette(detail.header)
    val teams = detail.header as? TeamsHeader
    val reduce = rememberReduceMotion()
    NuvioSurfaceCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(teams?.home?.badgeUrl, 28)
            Spacer(Modifier.weight(1f))
            Text("TEAM STATS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
            Spacer(Modifier.weight(1f))
            TeamBadge(teams?.away?.badgeUrl, 28)
        }
        StatsPanel.rows(block).forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s6)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatValue(r.home, r.leader == StatLeader.HOME, palette.home, Modifier.width(72.dp), TextAlign.Start)
                    Text(r.label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
                    StatValue(r.away, r.leader == StatLeader.AWAY, palette.away, Modifier.width(72.dp), TextAlign.End)
                }
                r.homeFraction?.let { f -> StatBar(f, palette, reduce) }
            }
        }
    }
}

/** Two-part bar. The animated share is read here only, so a stat change recomposes this bar, not the whole card. */
@Composable
private fun StatBar(f: Float, palette: TeamPalette, reduce: Boolean) {
    val share by animateFloatAsState(f, motion(MotionSpec.MOVE_MS, reduce), label = "stat")
    Spacer(Modifier.height(NuvioTokens.Space.s4))
    Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(percent = 50)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (share > 0f) Box(Modifier.weight(share).height(4.dp).background(palette.home))
        if (share < 1f) Box(Modifier.weight(1f - share).height(4.dp).background(palette.away))
    }
}

@Composable
private fun StatValue(text: String, leads: Boolean, color: Color, modifier: Modifier, align: TextAlign) {
    val reduce = rememberReduceMotion()
    val pill by androidx.compose.animation.animateColorAsState(if (leads) color.copy(alpha = 0.28f) else Color.Transparent, motion(MotionSpec.QUICK_MS, reduce), label = "pill")
    Box(modifier, contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (leads) FontWeight.Bold else FontWeight.Normal,
            color = if (leads) MaterialTheme.nuvio.colors.textPrimary else MaterialTheme.nuvio.colors.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(pill).padding(horizontal = NuvioTokens.Space.s8, vertical = 2.dp),
        )
    }
}

/** Standings: one card per group, header from the columns, the game's teams highlighted. */
@Composable
internal fun StandingsCards(block: StandingsTableBlock, modifier: Modifier = Modifier) {
    block.groups.filter { it.rows.isNotEmpty() }.forEach { g ->
        NuvioSurfaceCard(modifier = modifier.padding(bottom = NuvioTokens.Space.s12)) {
            if (g.name.isNotBlank()) {
                Text(g.name.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
                Spacer(Modifier.height(NuvioTokens.Space.s6))
            }
            val cellCount = g.rows.maxOf { it.cells.size }
            val headers = List((cellCount - g.columns.size).coerceAtLeast(0)) { "" } + g.columns
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                headers.forEach { Text(it, modifier = Modifier.width(CELL_W), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
            }
            g.rows.forEach { r ->
                val mod = if (r.isGameTeam) Modifier.background(MaterialTheme.nuvio.colors.surfaceElevated, RoundedCornerShape(NuvioTokens.Space.s6)) else Modifier
                Row(Modifier.fillMaxWidth().then(mod).padding(vertical = NuvioTokens.Space.s6, horizontal = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, fontWeight = if (r.isGameTeam) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                    r.cells.forEach { Text(it, modifier = Modifier.width(CELL_W), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary) }
                }
            }
        }
    }
}

private val CELL_W = 38.dp

/** Leaders with the team's logo on each row (the existing card, restyled). */
@Composable
internal fun LeadersCard(block: LeadersBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val teams = detail.header as? TeamsHeader
    NuvioSurfaceCard(modifier = modifier) {
        Text("TOP PERFORMERS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
        Spacer(Modifier.height(NuvioTokens.Space.s6))
        block.items.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                teams?.let { t -> TeamBadge(if (l.side == "home") t.home.badgeUrl else if (l.side == "away") t.away.badgeUrl else null, 24) }
                Column(Modifier.weight(1f)) {
                    Text(l.athlete, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(GameDetailUiPolicy.teamTag(l.side, detail.header), l.category).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                }
                Text(l.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.nuvio.colors.textPrimary)
            }
        }
    }
}

/** Plays grouped by period (newest first), each row with time, coloured team tag, kind chip, text and running score. */
@Composable
internal fun PlaysCard(block: com.nuvio.app.features.sportsdetail.api.PlaysBlock, detail: GameDetail, modifier: Modifier = Modifier) {
    val palette = rememberTeamPalette(detail.header)
    val batch = LocalMoments.current
    val reduce = rememberReduceMotion()
    val newId = batch.moments.filterIsInstance<Moment.NewPlay>().firstOrNull()?.item
    NuvioSurfaceCard(modifier = modifier) {
        PlaysGrouping.groups(block.items).forEach { g ->
            if (g.label.isNotBlank()) PeriodHeader(g.label)
            g.items.forEach { item ->
                // Only the newly arrived row fades in; no list-wide motion.
                val fresh = newId != null && item.id == newId.id && item.text == newId.text && item.time == newId.time
                PlayRow(item, detail, palette, Modifier.enterOnce(batch.seq, fresh, reduce))
            }
        }
    }
}
