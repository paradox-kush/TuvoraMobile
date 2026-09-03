package com.nuvio.app.features.livetv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.XtreamCatchUp
import com.nuvio.app.features.iptv.XtreamProgram
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_livetv_catchup_channel
import nuvio.composeapp.generated.resources.compose_livetv_guide_back_to_now
import nuvio.composeapp.generated.resources.compose_livetv_guide_earlier
import nuvio.composeapp.generated.resources.compose_livetv_guide_later
import nuvio.composeapp.generated.resources.compose_livetv_no_epg
import org.jetbrains.compose.resources.stringResource

private val CHANNEL_COL_WIDTH = 88.dp
private val ROW_HEIGHT = 64.dp
private val MINUTE_WIDTH = 3.5.dp
private val SLOT_MINUTES = GuideTimeTravel.SLOT_MINUTES

/**
 * How long the guide must be still before it asks the panel for the rows on screen.
 *
 * Matches NuvioTV's D-pad focus debounce (EPG_FOCUS_DEBOUNCE_MS): long enough that a fling's
 * intermediate positions never become requests, short enough that it feels immediate once the
 * finger lifts.
 */
private const val SCROLL_SETTLE_MS = 250L

/**
 * TiviMate-style EPG timeline: a pinned channel column on the left, programme blocks laid out across
 * a shared horizontally-scrolling time axis, and a red now-line. Tapping any channel row switches
 * playback in place. Programme windows load lazily as rows scroll into view.
 *
 * The window can travel BACKWARDS through the provider's archive. It renders one window at a time
 * and moves it rather than laying out the whole archive and scrolling within it — see
 * [GuideTimeTravel] for why the obvious design is the one that OOMs a 1 GB box.
 *
 * A programme cell takes a tap only when it has something to replay ([XtreamCatchUp.actionFor]
 * returning REPLAY or START_OVER). Everything else falls through to the lane's existing tap, which
 * still switches channel — so nothing the viewer already does stops working, and no cell promises
 * playback the provider can't deliver.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveGuideGrid(
    channels: List<LiveGuideChannel>,
    currentContentId: String,
    nowMs: Long,
    windowStartMs: Long,
    catchUpDays: Int,
    programmesOf: (String) -> List<XtreamProgram>?,
    /** A settled window of channels to fetch now/next for, in the order they should resolve. */
    onNeedProgrammes: (List<String>) -> Unit,
    onSelectChannel: (LiveGuideChannel) -> Unit,
    onLongPressChannel: (LiveGuideChannel) -> Unit = {},
    onProgrammeAction: (LiveGuideChannel, XtreamProgram, XtreamCatchUp.ProgrammeAction) -> Unit,
    onTravel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val windowEndMs = GuideTimeTravel.windowEndMs(windowStartMs)
    val totalMinutes = (windowEndMs - windowStartMs) / 60_000L
    val totalWidth = MINUTE_WIDTH * totalMinutes.toInt()
    val timeScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val travelling = GuideTimeTravel.isTravelling(windowStartMs, nowMs)

    // Ask the panel only for the rows the viewer actually STOPPED on.
    //
    // `collectLatest` + `delay` is the settle: every new scroll position cancels the pending ask,
    // so a fling that crosses a thousand rows issues ONE window when it comes to rest instead of
    // one `get_short_epg` per row it flew past. That per-row ask is the 2026-08-17 field report —
    // measured at 412 requests / 390 concurrent connections from eight flings, against panels that
    // commonly sell max_connections=1. Keyed on [windowStartMs] too, so travelling re-asks the
    // visible rows for the window it landed on.
    LaunchedEffect(listState, channels, windowStartMs) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) 0 to -1 else visible.first().index to visible.last().index
        }
            .distinctUntilChanged()
            .collectLatest { (firstVisible, lastVisible) ->
                delay(SCROLL_SETTLE_MS)
                val window = GuideEpgPrefetchPolicy
                    .windowFor(firstVisible = firstVisible, lastVisible = lastVisible, size = channels.size)
                    .mapNotNull { channels.getOrNull(it)?.contentId }
                if (window.isNotEmpty()) onNeedProgrammes(window)
            }
    }
    val canTravelBack = windowStartMs > GuideTimeTravel.earliestAnchorMs(nowMs, catchUpDays)

    // Travelling re-lays the whole lane, so the viewer's horizontal position within the OLD window
    // means nothing in the new one. Snapping to the left edge keeps the newly-revealed hours where
    // the eye already is instead of leaving the lane parked mid-window.
    LaunchedEffect(windowStartMs) { timeScroll.scrollTo(0) }

    Column(modifier = modifier.fillMaxSize()) {
        // Time axis header (day label + travel controls pinned over the channel column, slot ticks scroll).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(colors.surfaceElevated),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.width(CHANNEL_COL_WIDTH).padding(horizontal = NuvioTokens.Space.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                TravelButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    description = stringResource(Res.string.compose_livetv_guide_earlier),
                    enabled = canTravelBack,
                    tint = if (canTravelBack) colors.textSecondary else colors.textMuted,
                    onClick = { onTravel(GuideTimeTravel.back(windowStartMs, nowMs, catchUpDays)) },
                )
                Text(
                    text = liveDayLabel(windowStartMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (travelling) colors.accent else colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TravelButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    description = stringResource(Res.string.compose_livetv_guide_later),
                    enabled = travelling,
                    tint = if (travelling) colors.textSecondary else colors.textMuted,
                    onClick = { onTravel(GuideTimeTravel.forward(windowStartMs, nowMs)) },
                )
            }
            Box(modifier = Modifier.horizontalScroll(timeScroll)) {
                Row(modifier = Modifier.width(totalWidth)) {
                    val slots = (totalMinutes / SLOT_MINUTES).toInt()
                    repeat(slots) { i ->
                        val slotMs = windowStartMs + i.toLong() * SLOT_MINUTES * 60_000L
                        Text(
                            text = liveClockLabel(slotMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            modifier = Modifier.width(MINUTE_WIDTH * SLOT_MINUTES),
                        )
                    }
                }
            }
        }

        // Travelling hides the now-line entirely (it isn't in this window), so say where we are.
        if (travelling) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accent.copy(alpha = 0.12f))
                    .clickable { onTravel(GuideTimeTravel.anchorForNow(nowMs)) }
                    .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            ) {
                Icon(
                    imageVector = Icons.Filled.Replay,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(Res.string.compose_livetv_guide_back_to_now),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // Dedup at the key-consumption point: guideChannels can carry a duplicate contentId
            // (provider listing a channel twice), and a duplicate Compose `key` is a hard crash
            // (message-less SIGABRT on iOS). Keys stay unique regardless of the producer.
            items(channels.distinctBy { it.contentId }, key = { it.contentId }) { channel ->
                GuideRow(
                    channel = channel,
                    isCurrent = channel.contentId == currentContentId,
                    programmes = programmesOf(channel.contentId),
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                    nowMs = nowMs,
                    catchUpDays = channel.catchUpDays,
                    totalWidth = totalWidth,
                    timeScroll = timeScroll,
                    accent = colors.accent,
                    onSecondary = colors.onAccent,
                    nowLineColor = colors.danger,
                    cardColor = colors.surfaceCard,
                    textPrimary = colors.textPrimary,
                    textMuted = colors.textMuted,
                    surface = colors.surface,
                    border = colors.borderSubtle,
                    onClick = { onSelectChannel(channel) },
                    onLongClick = { onLongPressChannel(channel) },
                    onProgrammeAction = { programme, action -> onProgrammeAction(channel, programme, action) },
                )
            }
        }
    }
}

@Composable
private fun TravelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    channel: LiveGuideChannel,
    isCurrent: Boolean,
    programmes: List<XtreamProgram>?,
    windowStartMs: Long,
    windowEndMs: Long,
    nowMs: Long,
    catchUpDays: Int,
    totalWidth: Dp,
    timeScroll: androidx.compose.foundation.ScrollState,
    accent: Color,
    onSecondary: Color,
    nowLineColor: Color,
    cardColor: Color,
    textPrimary: Color,
    textMuted: Color,
    surface: Color,
    border: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onProgrammeAction: (XtreamProgram, XtreamCatchUp.ProgrammeAction) -> Unit,
) {
    val rowBg = if (isCurrent) accent.copy(alpha = 0.10f) else surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pinned channel cell: icon on top, short channel name below. Whole cell switches channel.
        Column(
            modifier = Modifier
                .width(CHANNEL_COL_WIDTH)
                .height(ROW_HEIGHT)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = NuvioTokens.Space.s4, vertical = NuvioTokens.Space.s6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                    .background(cardColor),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = channel.name.take(3).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = textMuted,
                        maxLines = 1,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                // The CHANNEL-level catch-up signal, the broadcaster convention: a small archive
                // glyph beside the name. Archive channels are a tiny slice of a huge catalog (44
                // of 26,430 on one measured panel), so this must read as a property of the
                // channel — never as a mode the playlist is in.
                if (channel.hasArchive) {
                    Icon(
                        imageVector = Icons.Filled.Replay,
                        contentDescription = stringResource(Res.string.compose_livetv_catchup_channel),
                        tint = accent,
                        modifier = Modifier.size(10.dp),
                    )
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) accent else textPrimary,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Scrolling programme lane (shares the header's scroll state).
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ROW_HEIGHT)
                .clickable(onClick = onClick)
                .horizontalScroll(timeScroll),
        ) {
            Box(modifier = Modifier.width(totalWidth).height(ROW_HEIGHT)) {
                val progs = programmes
                if (progs.isNullOrEmpty()) {
                    ProgrammeBlock(
                        title = stringResource(Res.string.compose_livetv_no_epg),
                        startX = 0.dp,
                        width = totalWidth,
                        isNow = false,
                        accent = accent,
                        onSecondary = onSecondary,
                        cardColor = cardColor,
                        textPrimary = textMuted,
                        textMuted = textMuted,
                        border = border,
                    )
                } else {
                    progs.forEach { prog ->
                        val clampedStart = prog.startMs.coerceAtLeast(windowStartMs)
                        val clampedEnd = prog.endMs.coerceAtMost(windowEndMs)
                        if (clampedEnd <= windowStartMs || clampedStart >= windowEndMs) return@forEach
                        val startMin = (clampedStart - windowStartMs) / 60_000L
                        val widthMin = ((clampedEnd - clampedStart) / 60_000L).coerceAtLeast(1)
                        val isNow = nowMs in prog.startMs until prog.endMs
                        val action = XtreamCatchUp.actionFor(
                            programmeStartMs = prog.startMs,
                            programmeEndMs = prog.endMs,
                            nowMs = nowMs,
                            hasArchive = channel.hasArchive,
                            catchUpDays = catchUpDays,
                            programmeHasArchive = prog.hasArchive,
                        )
                        // PLAY_LIVE and NONE stay inert and let the lane's tap switch channel —
                        // exactly what tapping the lane does today. Only a cell with somewhere
                        // else to go takes the gesture.
                        val replayable = action == XtreamCatchUp.ProgrammeAction.REPLAY ||
                            action == XtreamCatchUp.ProgrammeAction.START_OVER
                        ProgrammeBlock(
                            title = prog.title,
                            startX = MINUTE_WIDTH * startMin.toInt(),
                            width = MINUTE_WIDTH * widthMin.toInt(),
                            isNow = isNow,
                            replayable = replayable,
                            accent = accent,
                            onSecondary = onSecondary,
                            cardColor = cardColor,
                            textPrimary = textPrimary,
                            textMuted = textMuted,
                            border = border,
                            onClick = if (replayable) {
                                { onProgrammeAction(prog, action) }
                            } else {
                                null
                            },
                        )
                    }
                }
                // Per-row now-line — together the rows form one continuous line that scrolls with EPG.
                if (nowMs in windowStartMs..windowEndMs) {
                    val nowMin = (nowMs - windowStartMs) / 60_000L
                    Box(
                        modifier = Modifier
                            .offset(x = MINUTE_WIDTH * nowMin.toInt())
                            .width(2.dp)
                            .height(ROW_HEIGHT)
                            .background(nowLineColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    startX: Dp,
    width: Dp,
    isNow: Boolean,
    accent: Color,
    onSecondary: Color,
    cardColor: Color,
    textPrimary: Color,
    textMuted: Color,
    border: Color,
    replayable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .offset(x = startX)
            .width(width)
            .height(ROW_HEIGHT)
            .padding(1.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(if (isNow) accent else cardColor)
            .then(
                // The affordance is a hairline in the accent, not a fill: a past programme is
                // still past, and colouring it like "now" would fight the now-line.
                if (replayable && !isNow) {
                    Modifier.border(NuvioTokens.Border.thin, accent.copy(alpha = 0.55f), RoundedCornerShape(NuvioTokens.Radius.xs))
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s4),
        ) {
            if (replayable) {
                Icon(
                    imageVector = Icons.Filled.Replay,
                    contentDescription = null,
                    tint = if (isNow) onSecondary else accent,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isNow) onSecondary else textPrimary,
                fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
