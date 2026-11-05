// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): ui/screens/home/HomeComingUp.kt and
// ui/screens/calendar/CalendarReminderUi.kt (Home card + corner note), rebuilt for touch.
package com.nuvio.app.features.calendar.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.contracts.DetailsCalendarBell
import com.nuvio.app.core.contracts.DetailsReminderControl
import com.nuvio.app.core.contracts.HomeCalendarSection
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Home settles first; the Calendar's (throttled, due-only) refresh waits this long after Home is RESUMED. */
private const val HOME_SETTLE_DELAY_MS = 20_000L

internal class CalendarHomeSectionImpl(private val repository: () -> CalendarRepository) : HomeCalendarSection {
    @Composable
    override fun Render(onOpenTitle: (MetaPreview) -> Unit, onOpenCalendar: () -> Unit) {
        val repo = remember { repository() }
        val state by repo.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val batcher = remember { CalendarReminderBatcher() }
        val owner = LocalLifecycleOwner.current

        // Lifecycle-bound, never a bare loop: runs only while Home is RESUMED and stops when it is not. The refresh
        // is throttled and asks only for due titles; the reminder check reads local data only (no network).
        LaunchedEffect(owner, repo) {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                repo.start()
                launch {
                    delay(HOME_SETTLE_DELAY_MS)
                    repo.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
                }
                while (isActive) {
                    val current = repo.state.value
                    val batch = batcher.nextBatch(repo.dueReminders())
                    if (batch.isNotEmpty() && current.settings.cornerMessage) {
                        val names = batch.mapNotNull { it.title.name }.distinct()
                        NuvioToastController.show(
                            message = if (batch.size == 1 && names.size == 1) {
                                getString(Res.string.calendar_reminder_note_one, names.single())
                            } else {
                                getString(Res.string.calendar_reminder_note_many, batch.size)
                            },
                            durationMillis = 5_000L,
                            actionLabel = getString(Res.string.calendar_open),
                            onAction = onOpenCalendar,
                        )
                    }
                    val now = calendarNowMs()
                    val next = calendarNextReminderAtMs(current.titles, current.settings.bellOn, current.seenReminders, now)
                    delay(calendarReminderCheckDelayMs(next, now))
                }
            }
        }

        val nowMs = remember(state) { calendarNowMs() }
        val card = remember(state) { calendarReminderCard(repo.dueReminders()) }
        val comingUp = remember(state, nowMs) {
            if (state.settings.comingUpRow) {
                buildCalendarComingUp(state.titles.filteredBy(state.settings.filters), state.watched, nowMs, state.zone, state.settings.comingUpHiddenKeys)
            } else {
                emptyList()
            }
        }
        val comingUpGames = remember(state, nowMs) {
            if (state.settings.comingUpRow) {
                buildCalendarComingUpGames(state.games, state.settings.filters, nowMs, state.zone)
            } else {
                emptyList()
            }
        }
        val tokens = MaterialTheme.nuvio
        Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap)) {
            card?.let {
                ReminderCard(
                    card = it,
                    onOpen = { record -> onOpenTitle(record.toMetaPreview()) },
                    onOpenCalendar = onOpenCalendar,
                    onDismiss = { scope.launch { repo.markRemindersSeen(it.episodeKeys) } },
                )
            }
            if (comingUp.isNotEmpty() || comingUpGames.isNotEmpty()) {
                ComingUpRow(comingUp, comingUpGames, state.zone, onOpen = { record -> onOpenTitle(record.toMetaPreview()) }, onOpenCalendar = onOpenCalendar)
            }
        }
    }
}

@Composable
private fun ReminderCard(
    card: CalendarReminderCard,
    onOpen: (CalendarTitleRecord) -> Unit,
    onOpenCalendar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = tokens.colors.accent)
            Spacer(Modifier.width(tokens.spacing.controlGap))
            Text(
                stringResource(Res.string.calendar_reminder_card_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
            )
        }
        card.items.forEach { reminder ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(tokens.shapes.compactCard).clickable { onOpen(reminder.title) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = listOfNotNull(reminder.title.name, calendarEntryLine(reminder.entry)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card.more > 0) {
            Text(stringResource(Res.string.calendar_reminder_more, card.more), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap), verticalAlignment = Alignment.CenterVertically) {
            NuvioPrimaryButton(text = stringResource(Res.string.calendar_open), onClick = onOpenCalendar)
            Text(
                text = stringResource(Res.string.calendar_reminder_dismiss),
                modifier = Modifier.clip(tokens.shapes.button).clickable(onClick = onDismiss).padding(10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun ComingUpRow(items: List<CalendarComingUpItem>, games: List<CalendarComingUpGame>, zone: kotlinx.datetime.TimeZone, onOpen: (CalendarTitleRecord) -> Unit, onOpenCalendar: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.screenHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.calendar_coming_up_row),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.calendar_title),
                modifier = Modifier.clip(tokens.shapes.chip).clickable(onClick = onOpenCalendar).padding(8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.accent,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = tokens.spacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.railGap),
        ) {
            items(games, key = { "game:${it.game.id}" }) { entry ->
                val game = entry.game
                Column(
                    modifier = Modifier.width(152.dp).clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard)
                        .clickable(onClick = onOpenCalendar).padding(tokens.spacing.cardPaddingCompact),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = calendarGameStatusText(game) ?: calendarTimeText(calendarTimeAt(game.kickoffMs, zone)),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.whenKind == CalendarComingUpWhen.NEW) tokens.colors.accent else tokens.colors.textSecondary,
                        maxLines = 1,
                    )
                    Text(game.title, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    game.league?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            items(items, key = { it.title.key }) { item ->
                Column(modifier = Modifier.width(112.dp).clickable { onOpen(item.title) }) {
                    AsyncImage(
                        model = item.title.poster,
                        contentDescription = item.title.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(tokens.shapes.poster).background(tokens.colors.surfaceCard),
                    )
                    Text(
                        text = calendarComingUpWhenText(item),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.whenKind == CalendarComingUpWhen.NEW) tokens.colors.accent else tokens.colors.textSecondary,
                        maxLines = 1,
                    )
                    Text(item.title.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The map from the app's content type to what the Calendar can follow; null = it cannot. */
internal fun calendarTitleTypeFor(type: String): CalendarTitleType? = when (type.lowercase()) {
    "series", "tv", "show", "anime" -> CalendarTitleType.SERIES
    "movie" -> CalendarTitleType.MOVIE
    else -> null
}

/** The reminder switch on show / film details (a row in the + menu). Off → on adds the title to the Calendar. */
internal class CalendarDetailsBellImpl(private val repository: () -> CalendarRepository) : DetailsCalendarBell {
    @Composable
    override fun reminder(contentId: String, type: String, name: String?, poster: String?): DetailsReminderControl? {
        val titleType = calendarTitleTypeFor(type) ?: return null
        val ids = remember(contentId) { CalendarIds.fromContentId(contentId) }
        if (ids.isEmpty) return null
        val repo = remember { repository() }
        val state by repo.state.collectAsStateWithLifecycle()
        LaunchedEffect(repo) { repo.start() }
        val on = state.settings.bellOn.any { calendarKeyMatches(it, titleType, ids) }
        val scope = rememberCoroutineScope()
        return DetailsReminderControl(on) { scope.launch { repo.setBell(titleType, ids, !on) } }
    }
}
