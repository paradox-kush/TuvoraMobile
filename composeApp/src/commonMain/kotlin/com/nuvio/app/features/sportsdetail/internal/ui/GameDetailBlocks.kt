package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
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
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import com.nuvio.app.features.sportsdetail.api.WatchUi
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock

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
private fun Badge(url: String?, size: Int = 40) {
    if (url.isNullOrBlank()) Box(Modifier.size(size.dp)) else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp))
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

/** Teams / versus / event header with the live or final status. */
@Composable
internal fun GameHeader(detail: GameDetail, modifier: Modifier = Modifier) {
    NuvioSurfaceCard(modifier = modifier) {
        Text(
            listOfNotNull(detail.leagueName.takeIf { it.isNotBlank() }, detail.round?.let { "Round $it" }, detail.venue).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        when (val h = detail.header) {
            is TeamsHeader -> SidesRow(h.home, h.away, detail)
            is VersusHeader -> SidesRow(h.a, h.b, detail)
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
private fun SidesRow(a: Side, b: Side, detail: GameDetail) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SideColumn(a, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8)) {
            StatusLine(detail)
        }
        SideColumn(b, Modifier.weight(1f))
    }
}

@Composable
private fun SideColumn(s: Side, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Badge(s.badgeUrl, 48)
        Text(s.name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.nuvio.colors.textPrimary)
        s.record?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
        s.score?.let { Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.textPrimary) }
    }
}

@Composable
private fun StatusLine(detail: GameDetail) {
    val live = detail.live
    when (detail.state) {
        GameState.LIVE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LivePill(listOfNotNull(live?.periodLabel, live?.clock).joinToString(" ").ifBlank { "LIVE" }.let { if (it == "LIVE") it else "LIVE $it" })
            live?.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary) }
        }
        GameState.FINAL -> Text(live?.detail?.takeIf { it.isNotBlank() } ?: "Final", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary)
        GameState.POSTPONED -> Text("Postponed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary)
        GameState.UPCOMING -> Text(detail.startsAt.replace('T', ' ').removeSuffix("Z").take(16) + " UTC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
    }
}

@Composable
internal fun SituationCard(situation: SituationBlock?, win: WinProbabilityBlock?, homeName: String, awayName: String) {
    if (situation == null && win == null) return
    NuvioSurfaceCard {
        situation?.let { Text(it.line, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary) }
        if (situation != null && win != null) Spacer(Modifier.height(NuvioTokens.Space.s8))
        win?.let { w ->
            val home = w.homePercent.coerceIn(0, 100)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$awayName ${100 - home}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary)
                Text("$homeName $home%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary)
            }
            Spacer(Modifier.height(NuvioTokens.Space.s4))
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(percent = 50))) {
                if (100 - home > 0) Box(Modifier.weight((100 - home).toFloat()).height(6.dp).background(MaterialTheme.nuvio.colors.borderStrong))
                if (home > 0) Box(Modifier.weight(home.toFloat()).height(6.dp).background(MaterialTheme.nuvio.colors.accent))
            }
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
    BlockCard("Watch on your channels") {
        when (ui) {
            WatchUi.Hidden -> {}
            WatchUi.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(NuvioTokens.Space.s8))
                Text("Checking your channels…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
            }
            is WatchUi.Watching -> Column {
                Text("Your channel playing here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                Text(ui.current.channelName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary)
                ui.switchTo?.let { offer ->
                    Spacer(Modifier.height(NuvioTokens.Space.s8))
                    NuvioPrimaryButton("Switch to ${offer.channelName}", onClick = { onPlay(offer) })
                }
            }
            is WatchUi.Browsing -> Column {
                Text(ui.offer.channelName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary)
                Spacer(Modifier.height(NuvioTokens.Space.s8))
                NuvioPrimaryButton("Play", onClick = { onPlay(ui.offer) })
                TextButton(onClick = onFindChannels) { Text("Find other channels") }
            }
            WatchUi.NoPlaylist -> Column {
                Text("Add an IPTV playlist to find and watch this game on your channels.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                TextButton(onClick = onAddPlaylist) { Text("Add IPTV playlist") }
            }
            WatchUi.NoMatch -> Column {
                Text("None of your channels list this game yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                TextButton(onClick = onFindChannels) { Text("Find channels") }
            }
            WatchUi.LookupFailed -> Column {
                Text("Couldn't check your channels.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
        Text(left, modifier = Modifier.width(64.dp), textAlign = TextAlign.Start, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
        Text(right, modifier = Modifier.width(64.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
    }
}

/** One block's card. Titles follow the design mock; the sport picks "Match events" vs "Latest scoring". */
@Composable
internal fun BlockContent(block: GameBlock, detail: GameDetail) {
    when (block) {
        is LinescoreBlock -> BlockCard("By period") {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(56.dp))
                block.columns.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted) }
            }
            block.rows.forEachIndexed { i, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(block.rowLabels.getOrNull(i).orEmpty(), modifier = Modifier.width(56.dp), maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                    row.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary) }
                }
            }
        }
        is BroadcastBlock -> BlockCard("Broadcast") {
            block.byRegion.filterValues { it.isNotEmpty() }.forEach { (region, channels) ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s2)) {
                    Text(region, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
                    Text(channels.joinToString(", "), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                }
            }
        }
        is StatsBlock -> BlockCard("Team stats") {
            block.rows.forEach { LabeledRow(it.label, it.home + (it.unit ?: ""), it.away + (it.unit ?: "")) }
        }
        is LeadersBlock -> BlockCard("Top performers") {
            block.items.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.athlete, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                    }
                    Text(l.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.nuvio.colors.textPrimary)
                }
            }
        }
        is TimelineBlock -> BlockCard(if (detail.sport.equals("Soccer", ignoreCase = true)) "Match events" else "Latest scoring") {
            block.items.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4)) {
                    Text(listOfNotNull(t.period, t.time.takeIf { it.isNotBlank() }).joinToString(" "), modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                    Text(t.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textPrimary)
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
