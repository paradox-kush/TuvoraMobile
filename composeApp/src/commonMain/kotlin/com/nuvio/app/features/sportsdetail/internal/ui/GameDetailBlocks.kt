package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nuvio.app.features.sportsdetail.internal.ChannelLabelPolicy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import kotlin.time.Clock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.ResultsBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import com.nuvio.app.features.sportsdetail.api.WatchUi
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock

/** Back control that shows on every platform (the shared NuvioBackButton hides itself under iOS native navigation, which this overlay does not have). */
@Composable
internal fun GameBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(NuvioTokens.Space.s40).clip(CircleShape).background(MaterialTheme.nuvio.colors.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.nuvio.colors.textPrimary, modifier = Modifier.size(NuvioTokens.Icon.md))
    }
}

/** Thin renderers: every decision (what shows, what it is called) comes from state/policy, nothing here does I/O. */

@Composable
internal fun BlockCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    NuvioSurfaceCard(modifier = modifier) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        content()
    }
}

@Composable
internal fun LivePill(text: String) {
    val danger = MaterialTheme.nuvio.colors.danger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(danger.copy(alpha = 0.14f))
            .border(1.dp, danger.copy(alpha = 0.45f), RoundedCornerShape(percent = 50))
            .padding(horizontal = NuvioTokens.Space.s8, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(danger))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = danger, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StaleChip(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nuvio.colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.nuvio.colors.surfaceElevated)
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
    )
}

/** The feed's situation line (down & distance, count…); hidden by the caller when a field strip shows the same information. */
@Composable
internal fun SituationLineCard(situation: SituationBlock) {
    NuvioSurfaceCard {
        Text(situation.line, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary)
    }
}

/** The mock's yellow Play button: a play glyph, the action, and the cleaned channel as its second line. */
@Composable
private fun WatchPlayButton(text: WatchButtonText, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.colors.accent).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12)
            .semantics(mergeDescendants = true) { contentDescription = "${text.title}, ${text.subtitle}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.colors.onAccent, modifier = Modifier.size(NuvioTokens.Icon.md))
        Column(Modifier.weight(1f)) {
            Text(text.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tokens.colors.onAccent, maxLines = 1)
            Text(text.subtitle, style = MaterialTheme.typography.labelMedium, color = tokens.colors.onAccent.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun WatchCard(
    ui: WatchUi,
    onPlay: (WatchOffer) -> Unit,
    onPlayChannelId: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onFindChannels: () -> Unit,
    onRetry: () -> Unit,
) {
    if (ui == WatchUi.Hidden) return
    // Browsing / Watching are the mock's bare Play row (no card, no channel-count tile); every other state keeps its card.
    when (ui) {
        is WatchUi.Browsing -> Column {
            WatchPlayButton(WatchRowText.play(ui.offer.channelName)) { onPlay(ui.offer) }
            TextButton(onClick = onFindChannels, contentPadding = PaddingValues(0.dp)) { Text("Find other channels") }
        }
        is WatchUi.Watching -> Column {
            Text("Your channel playing here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
            Text(ChannelLabelPolicy.clean(ui.current.channelName), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary)
            ui.switchTo?.let { offer ->
                Spacer(Modifier.height(NuvioTokens.Space.s8))
                WatchPlayButton(WatchRowText.switchTo(offer.channelName)) { onPlay(offer) }
            }
        }
        else -> BlockCard("Watch on your channels") {
            when (ui) {
                WatchUi.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(NuvioTokens.Space.s8))
                    Text("Checking your channels…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                }
                WatchUi.NoPlaylist -> Column {
                    Text("Add an IPTV playlist to find and watch this game on your channels.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                    Spacer(Modifier.height(NuvioTokens.Space.s8))
                    NuvioPrimaryButton("Add IPTV playlist", onClick = onAddPlaylist)
                }
                WatchUi.NoMatch -> Column {
                    Text("None of your channels list this game yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                    Spacer(Modifier.height(NuvioTokens.Space.s8))
                    NuvioPrimaryButton("Find channels", onClick = onFindChannels)
                }
                WatchUi.LookupFailed -> Column {
                    Text("Couldn't check your channels.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Retry") }
                }
                else -> {}
            }
        }
    }
}

/** One block's card. Titles follow the design mock; the sport picks "Match events" vs "Latest scoring". */
@Composable
internal fun BlockContent(block: GameBlock, detail: GameDetail) {
    when (block) {
        is LinescoreBlock -> BlockCard("By period") {
            val ls = GameDetailUiPolicy.trimUnstartedPeriods(block, detail.state, detail.live?.period)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(56.dp))
                ls.columns.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
            }
            ls.rows.forEachIndexed { i, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(ls.rowLabels.getOrNull(i).orEmpty(), modifier = Modifier.width(56.dp), maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                    row.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary) }
                }
            }
        }
        is StatsBlock -> StatsCard(block, detail)
        is LeadersBlock -> LeadersCard(block, detail)
        is StandingsTableBlock -> StandingsCards(block)
        is com.nuvio.app.features.sportsdetail.api.StandingsBlock -> BlockCard("Standings") {
            block.rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.pos, modifier = Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
                    Text(r.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary)
                    Text(listOfNotNull(r.played, r.gd, r.points).joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary)
                }
            }
        }
        is TimelineBlock -> BlockCard(if (detail.sport.equals("Soccer", ignoreCase = true)) "Match events" else "Latest scoring") {
            block.items.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4)) {
                    Text(listOfNotNull(t.period, t.time.takeIf { it.isNotBlank() }).joinToString(" "), modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                    Text(GameDetailUiPolicy.teamTag(t.side, detail.header)?.let { tag -> "$tag · ${t.text}" } ?: t.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary)
                }
            }
        }
        is LineupBlock -> BlockCard("Lineups") {
            block.sides.forEach { s ->
                Text(listOfNotNull(if (s.side == "home") "Home" else "Away", s.formation).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
                s.players.filter { it.starter }.forEach { p ->
                    Text("${p.number}  ${p.name}  ${p.pos}".trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary)
                }
                Spacer(Modifier.height(NuvioTokens.Space.s8))
            }
        }
        is FormBlock -> BlockCard("Recent form") {
            val h = detail.header
            if (h is TeamsHeader) {
                if (block.home.isNotEmpty()) Text("${h.home.name}  ${block.home.joinToString(" ")}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                if (block.away.isNotEmpty()) Text("${h.away.name}  ${block.away.joinToString(" ")}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
            }
        }
        is ResultsBlock -> BlockCard(if (block.columns.firstOrNull() == "Session") "Classification" else "Card") {
            block.rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4)) {
                    r.forEachIndexed { i, cell ->
                        Text(cell, modifier = Modifier.weight(if (i == r.lastIndex) 1.6f else 1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary)
                    }
                }
            }
        }
        is HighlightsBlock -> {
            val uri = LocalUriHandler.current
            BlockCard("Highlights") {
                Row(Modifier.fillMaxWidth().clickable { if (com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy.isSafeLink(block.url)) runCatching { uri.openUri(block.url) } }, verticalAlignment = Alignment.CenterVertically) {
                    block.thumbnail?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(NuvioTokens.Space.s6))) }
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                    Text("Watch highlights", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.accent)
                }
            }
        }
        else -> {}
    }
}
